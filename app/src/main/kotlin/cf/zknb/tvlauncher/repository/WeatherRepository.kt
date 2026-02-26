package cf.zknb.tvlauncher.repository

import android.content.Context
import android.util.Log
import cf.zknb.tvlauncher.model.Weather
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
 * 天气数据仓库
 *
 * 负责从API获取天气数据，使用OkHttp和BouncyCastle支持Android 4.2的TLS连接
 * BouncyCastle是纯Java实现，不依赖native库，适合system/app
 */
class WeatherRepository(private val context: Context) {
    
    companion object {
        private const val TAG = "WeatherRepository"
        private const val API_URL = "https://uapis.cn/api/v1/misc/weather"
        private const val TIMEOUT = 1000L // 1秒
        
        init {
            // 在Android 4.2等老版本上安装BouncyCastle作为安全提供者（纯Java实现）
            try {
                // 移除已有的BC provider（如果存在）
                Security.removeProvider("BC")
                Security.removeProvider("BCJSSE")
                
                // 添加BouncyCastle provider
                Security.insertProviderAt(BouncyCastleProvider(), 1)
                Security.insertProviderAt(BouncyCastleJsseProvider(), 2)
                Log.d(TAG, "BouncyCastle providers installed successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to install BouncyCastle providers", e)
            }
        }
    }
    
    private val client: OkHttpClient by lazy {
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
     * 根据城市adcode获取天气信息；如果adcode为空则直接调用IP定位天气接口
     *
     * @param adcode 高德地图城市adcode（如：110100 北京），可为空
     * @return Weather对象，失败返回null
     */
    suspend fun getWeather(adcode: String? = null): Weather? = withContext(Dispatchers.IO) {
        try {
            val urlString = if (adcode.isNullOrEmpty()) API_URL else "$API_URL?adcode=$adcode"
            Log.d(TAG, "Requesting weather from: $urlString")
            
            val request = Request.Builder()
                .url(urlString)
                .get()
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            
            val response = client.newCall(request).execute()
            val responseCode = response.code()
            Log.d(TAG, "Response code: $responseCode")
            
            if (response.isSuccessful) {
                val responseBody = response.body()?.string()
                Log.d(TAG, "Response: $responseBody")
                
                if (responseBody != null) {
                    parseWeatherResponse(responseBody)
                } else {
                    Log.e(TAG, "Response body is null")
                    null
                }
            } else {
                val errorResponse = response.body()?.string() ?: "No error message"
                Log.e(TAG, "HTTP error code: $responseCode, Error: $errorResponse")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching weather: ${e.message}", e)
            null
        }
    }
    
    /**
     * 解析天气API响应
     * API返回格式：{"province":"北京","city":"北京城区","adcode":"110100","weather":"晴","temperature":0,...}
     */
    private fun parseWeatherResponse(response: String): Weather? {
        return try {
            val json = JSONObject(response)
            Log.d(TAG, "Parsing JSON response")
            
            // 检查必要字段是否存在
            if (!json.has("city") || !json.has("weather")) {
                Log.e(TAG, "Missing required fields in response")
                return null
            }
            
            // 获取天气代码，用于图标映射
            // 由于API没有返回weatherCode，我们根据weather文本映射
            val weatherText = json.optString("weather", "未知")
            val weatherCode = mapWeatherTextToCode(weatherText)
            
            // 优先使用district作为city值，如果district为空则使用city
            val locationName = json.optString("district", "")?.takeIf { it.isNotEmpty() } ?: json.optString("city", "未知")
            
            val weather = Weather(
                city = locationName,
                weather = weatherText,
                temperature = "${json.optInt("temperature", 0)}°C",
                weatherCode = weatherCode,
                adcode = json.optString("adcode", "")
            )
            
            Log.d(TAG, "Weather parsed successfully: ${weather.city} ${weather.weather} ${weather.temperature}")
            weather
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing weather response: ${e.message}", e)
            null
        }
    }
    
    /**
     * 将天气文本映射到天气代码（用于图标显示）
     */
    private fun mapWeatherTextToCode(weatherText: String): String {
        // 参考和风天气官方图标代码 https://dev.qweather.com/docs/resource/icons/#weather-icons
        return when {
            weatherText.contains("晴") -> "100" // 晴
            weatherText.contains("多云") -> "101" // 多云
            weatherText.contains("少云") -> "102" // 少云
            weatherText.contains("晴间多云") -> "103" // 晴间多云
            weatherText.contains("阴") -> "104" // 阴
            weatherText.contains("有风") || weatherText.contains("风") -> "200" // 有风
            weatherText.contains("平静") -> "201"
            weatherText.contains("微风") -> "202"
            weatherText.contains("和风") -> "203"
            weatherText.contains("清风") -> "204"
            weatherText.contains("强风") -> "205"
            weatherText.contains("劲风") -> "206"
            weatherText.contains("疾风") -> "207"
            weatherText.contains("大风") -> "208"
            weatherText.contains("烈风") -> "209"
            weatherText.contains("风暴") -> "210"
            weatherText.contains("狂爆风") -> "211"
            weatherText.contains("飓风") -> "212"
            weatherText.contains("龙卷风") -> "213"
            weatherText.contains("雾") -> "500" // 雾
            weatherText.contains("霾") -> "501" // 霾
            weatherText.contains("扬沙") -> "502"
            weatherText.contains("浮尘") -> "503"
            weatherText.contains("沙尘暴") -> "504"
            weatherText.contains("强沙尘暴") -> "507"
            weatherText.contains("强浓雾") -> "508"
            weatherText.contains("雨") && weatherText.contains("雪") -> "406" // 雨夹雪
            weatherText.contains("小雨") -> "300" // 小雨
            weatherText.contains("中雨") -> "301" // 中雨
            weatherText.contains("大雨") -> "302" // 大雨
            weatherText.contains("阵雨") -> "303" // 阵雨
            weatherText.contains("雷阵雨") -> "304" // 雷阵雨
            weatherText.contains("极端降雨") -> "305"
            weatherText.contains("毛毛雨") || weatherText.contains("细雨") -> "306"
            weatherText.contains("暴雨") -> "307" // 暴雨
            weatherText.contains("大暴雨") -> "308" // 大暴雨
            weatherText.contains("特大暴雨") -> "309" // 特大暴雨
            weatherText.contains("冻雨") -> "310"
            weatherText.contains("小雪") -> "400" // 小雪
            weatherText.contains("中雪") -> "401" // 中雪
            weatherText.contains("大雪") -> "402" // 大雪
            weatherText.contains("阵雪") -> "403" // 阵雪
            weatherText.contains("雷阵雪") -> "404"
            weatherText.contains("暴雪") -> "405" // 暴雪
            weatherText.contains("雨夹雪") -> "406" // 雨夹雪
            weatherText.contains("雪") -> "400" // 默认雪
            weatherText.contains("冰雹") -> "407" // 冰雹
            weatherText.contains("霜冻") -> "503"
            weatherText.contains("露") -> "504"
            weatherText.contains("热") -> "900"
            weatherText.contains("冷") -> "901"
            weatherText.contains("未知") -> "999"
            else -> "999" // 未知天气
        }
    }
}