package cf.zknb.tvlauncher.util

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import org.json.JSONObject
import java.io.InputStream
import java.security.Security
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Bing 每日一图管理器
 *
 * 通过 https://bing.biturl.top/ 获取每日壁纸直链，下载后设为系统壁纸。
 *
 * - 复用与 WeatherRepository 相同的 OkHttp + BouncyCastle(BCJSSE) TLS 通道，
 *   以兼容 Android 4.x（老电视）的 HTTPS 握手。
 * - 使用 WallpaperManager.setStream() 让系统进程解码大图，避免在大屏低端设备上
 *   解码 1920x1080 Bitmap 导致 OOM。
 * - 用 SharedPreferences 记录“已应用日期”，默认每天只下载并应用一次。
 */
object BingWallpaperManager {

    private const val TAG = "BingWallpaper"
    private const val API_URL =
        "https://bing.biturl.top/?resolution=1920x1080&format=json&index=0&mkt=zh-CN"
    private const val PREFS = "bing_wallpaper"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_DATE = "last_applied_date"
    private const val KEY_LAST_URL = "last_url"
    private const val KEY_LAST_COPYRIGHT = "last_copyright"

    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    private val client: OkHttpClient by lazy { createClient() }

    init {
        // 全局安装 BouncyCastle 安全提供者（纯 Java 实现），与天气模块一致
        try {
            Security.removeProvider("BC")
            Security.removeProvider("BCJSSE")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            Security.insertProviderAt(BouncyCastleJsseProvider(), 2)
            Log.d(TAG, "BouncyCastle providers installed successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to install BouncyCastle providers", e)
        }
    }

    private fun createClient(): OkHttpClient {
        return try {
            val trustManager = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<out java.security.cert.X509Certificate>?,
                    authType: String?
                ) {
                }

                override fun checkServerTrusted(
                    chain: Array<out java.security.cert.X509Certificate>?,
                    authType: String?
                ) {
                }

                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                    arrayOf()
            }
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS", "BCJSSE")
            sslContext.init(
                null,
                arrayOf<javax.net.ssl.TrustManager>(trustManager),
                java.security.SecureRandom()
            )
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create BC OkHttpClient, fallback default", e)
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun todayStr(): String = dateFormat.format(Date())

    /** 是否已开启每日自动应用（默认开启，满足“每天自动应用新壁纸”需求）。 */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * 自动每日检查：开启且当天尚未应用时，拉取 Bing 每日一图并设为系统壁纸。
     * 设计为可频繁调用（如每分钟的 TIME_TICK），内部有日期守卫，当天只真正下载一次。
     */
    suspend fun maybeUpdateDaily(context: Context) {
        val appContext = context.applicationContext
        if (!isEnabled(appContext)) return
        val last = prefs(appContext).getString(KEY_LAST_DATE, "")
        if (last == todayStr()) {
            Log.d(TAG, "今天已应用 Bing 壁纸（$last），跳过")
            return
        }
        fetchAndApply(appContext)
    }

    /**
     * 立即应用今日壁纸（忽略日期守卫与开关状态，供设置页“立即应用”使用）。
     * @return 是否成功设置系统壁纸
     */
    suspend fun applyNow(context: Context): Boolean =
        fetchAndApply(context.applicationContext)

    /**
     * 拉取今日 Bing 图并设为系统壁纸；成功后记录日期与来源。
     * @return 是否成功设置
     */
    private suspend fun fetchAndApply(context: Context): Boolean {
        val image = fetchToday() ?: run {
            Log.w(TAG, "获取 Bing 每日图失败")
            return false
        }
        val ok = applyWallpaper(context, image.url)
        if (ok) {
            prefs(context).edit().apply {
                putString(KEY_LAST_DATE, todayStr())
                putString(KEY_LAST_URL, image.url)
                putString(KEY_LAST_COPYRIGHT, image.copyright)
                apply()
            }
            Log.d(TAG, "已应用 Bing 壁纸：${image.copyright}")
        } else {
            Log.e(TAG, "设置系统壁纸失败")
        }
        return ok
    }

    /** 拉取 Bing 每日一图 JSON，返回图片直链与版权信息。 */
    private suspend fun fetchToday(): BingImage? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_URL)
                .get()
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Bing API HTTP ${response.code()}")
                return@withContext null
            }
            val body = response.body()?.string() ?: return@withContext null
            val json = JSONObject(body)
            val url = json.optString("url", "")
            val copyright = json.optString("copyright", "")
            val startDate = json.optString("start_date", "")
            if (url.isEmpty()) return@withContext null
            BingImage(url, copyright, startDate)
        } catch (e: Exception) {
            Log.e(TAG, "fetchToday error: ${e.message}", e)
            null
        }
    }

    /**
     * 下载图片字节流并设为系统壁纸。使用 setStream 让系统进程解码，避免大图 OOM。
     * @return 是否成功
     */
    private suspend fun applyWallpaper(context: Context, imageUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(imageUrl)
                    .get()
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "下载壁纸 HTTP ${response.code()}")
                    return@withContext false
                }
                val stream: InputStream? = response.body()?.byteStream()
                if (stream == null) {
                    Log.e(TAG, "壁纸响应流为空")
                    return@withContext false
                }
                val wallpaperManager = WallpaperManager.getInstance(context)
                stream.use { wallpaperManager.setStream(it) }
                true
            } catch (e: Exception) {
                Log.e(TAG, "applyWallpaper error: ${e.message}", e)
                false
            }
        }

    private data class BingImage(
        val url: String,
        val copyright: String,
        val startDate: String
    )
}
