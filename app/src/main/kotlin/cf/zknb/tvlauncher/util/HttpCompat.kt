package cf.zknb.tvlauncher.util

import android.os.Build
import android.util.Log
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import org.conscrypt.Conscrypt
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 统一网络层 —— 平台 HttpURLConnection + 高性能 TLS，替代 OkHttp + BouncyCastle(BCJSSE)。
 *
 * TLS 三级策略（越靠前越快、越安全）：
 * 1. Conscrypt：Google 的 BoringSSL native JSSE provider，握手走 native，
 *    比 BouncyCastle 纯 Java TLS 快 2~5 倍；自带完整的现代 cipher suite /
 *    TLS 1.2~1.3 / SNI，能连上系统 OpenSSL 因套件太老而被服务端 reset 的站点。
 *    官方支持 API 9+，覆盖本项目 minSdk 17。
 * 2. 系统 OpenSSL：Conscrypt 加载失败时回退，API 16~19 用 [Tls12SocketFactory]
 *    显式打开 TLS 1.2。
 * 3. 明文 HTTP：HTTPS 彻底不可用时降级，并记住主机 10 分钟，避免反复浪费握手。
 *
 * 其它：
 * - 证书校验：API 21+ 用系统默认 TrustManager；API 16~20 的系统根证书库早已过期
 *   （实测 TCL 901 / Android 4.2.1 报 "Trust anchor ... not found"），沿用旧实现的
 *   宽松校验，仅用于拉取公开的天气 / 壁纸数据，不涉及敏感信息。
 * - 重定向：HttpURLConnection 不会自动从 HTTP 跟随到 HTTPS，这里手动处理 3xx。
 *
 * 注意：[openStream] 返回的流由调用方负责关闭，关闭即释放连接。
 */
object HttpCompat {

    private const val TAG = "HttpCompat"
    private const val USER_AGENT = "Mozilla/5.0"
    private const val HTTPS_PREFIX = "https://"
    private const val FALLBACK_TTL_MS = 10 * 60 * 1000L

    private val tlsSocketFactory: SSLSocketFactory? by lazy { createTlsSocketFactory() }

    /** 记录「最近一次 HTTPS 失败、只能走明文」的主机，值为过期时间戳。 */
    private val tlsFallbackUntil = ConcurrentHashMap<String, Long>()

    /** GET 全文，失败返回 null。 */
    fun getString(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String? {
        val conn = connect(url, connectTimeoutMs, readTimeoutMs) ?: return null
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "读取响应失败: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /** GET 字节流，失败返回 null；调用方必须 close()。 */
    fun openStream(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): InputStream? {
        val conn = connect(url, connectTimeoutMs, readTimeoutMs) ?: return null
        return try {
            conn.inputStream
        } catch (e: Exception) {
            Log.e(TAG, "打开响应流失败: ${e.message}")
            conn.disconnect()
            null
        }
    }

    private fun connect(url: String, ct: Int, rt: Int): HttpURLConnection? {
        val host = hostOf(url)
        val isHttps = url.startsWith(HTTPS_PREFIX)
        val now = System.currentTimeMillis()

        // 已知该主机 TLS 不可用：在 TTL 内直接走明文，省掉一次必然失败的握手
        if (isHttps && (tlsFallbackUntil[host] ?: 0L) > now) {
            openOnce(toPlain(url), ct, rt)?.let { return it }
            tlsFallbackUntil.remove(host)
        }

        openOnce(url, ct, rt)?.let {
            tlsFallbackUntil.remove(host)
            return it
        }

        if (!isHttps) return null
        val plain = toPlain(url)
        Log.w(TAG, "HTTPS 不可用，降级明文 HTTP: $plain")
        openOnce(plain, ct, rt)?.let {
            tlsFallbackUntil[host] = System.currentTimeMillis() + FALLBACK_TTL_MS
            return it
        }
        return null
    }

    /** 建立连接并确认响应码为 2xx；任何失败都会断开连接并返回 null。 */
    private fun openOnce(url: String, ct: Int, rt: Int, redirectsLeft: Int = 3): HttpURLConnection? {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Log.w(TAG, "无法创建连接 $url: ${e.message}")
            return null
        }
        return try {
            if (conn is HttpsURLConnection) {
                tlsSocketFactory?.let { conn.sslSocketFactory = it }
                // 老设备根证书库过期，主机名校验同样放宽（仅公开数据）
                if (Build.VERSION.SDK_INT < 21) conn.hostnameVerifier = LENIENT_VERIFIER
            }
            conn.connectTimeout = ct
            conn.readTimeout = rt
            // 自己处理重定向，才能跨 HTTP -> HTTPS 协议
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")

            val code = conn.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrEmpty() || redirectsLeft == 0) {
                    Log.w(TAG, "重定向无法跟随 ($code): $url")
                    return null
                }
                val next = if (location.startsWith("http://") || location.startsWith("https://")) {
                    location
                } else {
                    URL(URL(url), location).toString()
                }
                Log.d(TAG, "跟随重定向 $code -> $next")
                return openOnce(next, ct, rt, redirectsLeft - 1)
            }
            if (code !in 200..299) {
                // 保留服务端错误体（LOCATION_NOT_FOUND / IP_LOCATION_FAILED 等），
                // 便于区分「定位失败」与临时网络故障，避免把错误响应当成空数据。
                val errorBody = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                } catch (_: Exception) {
                    null
                }
                Log.w(TAG, "HTTP $code: $url  错误体=$errorBody")
                conn.disconnect()
                null
            } else {
                conn
            }
        } catch (e: Exception) {
            Log.w(TAG, "请求失败 $url: ${e.javaClass.simpleName} ${e.message}")
            conn.disconnect()
            null
        }
    }

    private fun createTlsSocketFactory(): SSLSocketFactory? {
        createConscryptFactory()?.let {
            Log.i(TAG, "TLS engine = Conscrypt (native BoringSSL)")
            return it
        }
        createPlatformFactory()?.let {
            Log.i(TAG, "TLS engine = 系统 OpenSSL (TLS 1.2 强制启用)")
            return it
        }
        Log.w(TAG, "TLS engine 初始化失败，交由系统默认 SSL 处理")
        return null
    }

    /** 首选：Conscrypt native TLS。.so 加载失败抛的是 Error，因此捕获 Throwable。 */
    private fun createConscryptFactory(): SSLSocketFactory? = try {
        val provider = Conscrypt.newProvider()
        val context = SSLContext.getInstance("TLS", provider)
        context.init(null, trustManagers(), SecureRandom())
        Log.d(TAG, "Conscrypt ${provider.name} ${provider.version} 已就绪")
        // Conscrypt 原生支持 TLS 1.2/1.3 与完整 cipher suite，无需再包装协议白名单
        context.socketFactory
    } catch (t: Throwable) {
        Log.w(TAG, "Conscrypt 不可用，回退系统 TLS: ${t.javaClass.simpleName} ${t.message}")
        null
    }

    /** 二级：系统 OpenSSL，API 16~19 需要显式启用 TLS 1.2。 */
    private fun createPlatformFactory(): SSLSocketFactory? = try {
        val context = SSLContext.getInstance("TLS")
        context.init(null, trustManagers(), SecureRandom())
        val base = context.socketFactory
        if (Build.VERSION.SDK_INT in 16..19) Tls12SocketFactory(base) else base
    } catch (t: Throwable) {
        Log.w(TAG, "系统 TLS 初始化失败: ${t.javaClass.simpleName} ${t.message}")
        null
    }

    private fun trustManagers(): Array<TrustManager>? =
        if (Build.VERSION.SDK_INT < 21) arrayOf<TrustManager>(LenientTrustManager()) else null

    private val LENIENT_VERIFIER = HostnameVerifier { _, _ -> true }

    /** 仅用于 API < 21 的公开数据请求，避免老设备过期根证书库导致全网不可用。 */
    private class LenientTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun toPlain(url: String): String = "http://" + url.substring(HTTPS_PREFIX.length)

    private fun hostOf(url: String): String = try {
        URL(url).host
    } catch (e: Exception) {
        url
    }

    /**
     * 装饰系统 SSLSocketFactory，在 API 16~19 上把 TLS 1.2 / 1.1 加入已启用协议。
     * 只挑选该设备实际支持的协议，避免老 ROM 上抛 IllegalArgumentException。
     */
    private class Tls12SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(): Socket = patch(delegate.createSocket())
        override fun createSocket(host: String, port: Int): Socket =
            patch(delegate.createSocket(host, port))
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            patch(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress, port: Int): Socket =
            patch(delegate.createSocket(host, port))
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            patch(delegate.createSocket(address, port, localAddress, localPort))
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            patch(delegate.createSocket(s, host, port, autoClose))

        private fun patch(socket: Socket): Socket {
            if (socket is SSLSocket) {
                val supported = socket.supportedProtocols.toSet()
                val enabled = arrayOf("TLSv1.2", "TLSv1.1", "TLSv1").filter { it in supported }
                if (enabled.isNotEmpty()) socket.enabledProtocols = enabled.toTypedArray()
            }
            return socket
        }
    }
}
