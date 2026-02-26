package cf.zknb.tvlauncher.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import org.json.JSONObject
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * IP定位仓库
 * 
 * 通过IP地址获取用户所在城市信息
 * 使用BouncyCastle（纯Java实现）支持Android 4.2的TLS
 */
class LocationRepository(private val context: Context) {

    companion object {
        private const val TAG = "LocationRepository"
        // 使用 uapis 天气 API 来做 IP 定位
        // 直接访问 https://uapis.cn/api/v1/misc/weather 即可返回当前请求 IP 的天气数据，
        // 其中包含省市区和 adcode，我们只需要解析定位信息即可，不再尝试获取本机 IP。
        private const val IP_API_URL = "https://uapis.cn/api/v1/misc/weather"
        private const val TIMEOUT = 5000L
        
        // 初始化 BouncyCastle 以支持 Android 4.2+ 的 TLS 1.2（纯Java实现）
        init {
            try {
                // 移除已有的BC provider（如果存在）
                Security.removeProvider("BC")
                Security.removeProvider("BCJSSE")
                
                // 添加BouncyCastle provider
                Security.insertProviderAt(BouncyCastleProvider(), 1)
                Security.insertProviderAt(BouncyCastleJsseProvider(), 2)
                Log.d(TAG, "BouncyCastle TLS providers initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize BouncyCastle", e)
            }
        }
    }
    
    // 创建支持 TLS 1.2 的 OkHttpClient，配置 BouncyCastle SSLContext
    private val okHttpClient by lazy {
        try {
            val trustManager = createTrustManager()
            
            // 获取BouncyCastle的SSLContext（使用BCJSSE provider）
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS", "BCJSSE")
            sslContext.init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), java.security.SecureRandom())
            
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true } // 信任所有主机名
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create OkHttpClient with BouncyCastle, using default", e)
            // 降级到默认配置
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .build()
        }
    }
    
    /**
     * 创建一个信任所有证书的TrustManager（仅用于兼容老版本Android）
     * 注意：生产环境应该使用正确的证书验证
     */
    private fun createTrustManager(): javax.net.ssl.X509TrustManager {
        return object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
            }
            
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
            }
            
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
                return arrayOf()
            }
        }
    }

    /**
     * 通过IP获取当前城市信息
     * @return Pair<城市名称, 省份名称> 或 null
     */
    suspend fun getCityByIp(): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "请求定位/天气API: $IP_API_URL")
            
            val request = Request.Builder()
                .url(IP_API_URL) // 不带参数时会根据请求IP自动返回位置和天气信息
                .header("User-Agent", "Mozilla/5.0")
                .build()
            
            okHttpClient.newCall(request).execute().use { response ->
                val responseCode = response.code()
                Log.d(TAG, "API响应码: $responseCode")
                
                if (response.isSuccessful) {
                    val responseBody = response.body()?.string()
                    Log.d(TAG, "API响应内容: $responseBody")
                    
                    if (!responseBody.isNullOrEmpty()) {
                        try {
                            val json = JSONObject(responseBody)
                            val province = json.optString("province", "")
                            val city = json.optString("city", "")
                            val district = json.optString("district", "")
                            Log.d(TAG, "解析到省市区县: province=$province, city=$city, district=$district")

                            // 优先使用district作为定位结果，如果district为空则使用city
                            val locationName = if (district.isNotEmpty()) district else city
                            if (province.isNotEmpty() && locationName.isNotEmpty()) {
                                return@withContext Pair(locationName, province)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "解析定位API响应失败", e)
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "IP定位失败", e)
            null
        }
    }


    /**
     * 自动定位并获取城市adcode
     * @return Pair<城市名称, adcode> 或 null
     */
    suspend fun autoLocate(): Pair<String, String>? {
        Log.d(TAG, "autoLocate() 开始...")
        // 直接使用同一个天气/定位接口获取adcode，避免再通过本地数据文件解析
        try {
            val request = Request.Builder()
                .url(IP_API_URL)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body()?.string()
                    Log.d(TAG, "autoLocate API 响应: $body")
                    if (!body.isNullOrEmpty()) {
                        val json = JSONObject(body)
                        val province = json.optString("province", "")
                        val city = json.optString("city", "")
                        val district = json.optString("district", "")
                        val adcode = json.optString("adcode", "")
                        val locationName = if (district.isNotEmpty()) district else city
                        if (locationName.isNotEmpty() && adcode.isNotEmpty()) {
                            Log.d(TAG, "autoLocate 解析成功: $locationName ($adcode)")
                            return Pair(locationName, adcode)
                        }
                    }
                } else {
                    Log.w(TAG, "autoLocate HTTP错误: ${response.code()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "autoLocate 异常", e)
        }
        return null
    }
}