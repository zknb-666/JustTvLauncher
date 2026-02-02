package cf.zknb.tvlauncher.repository

import android.content.Context
import android.util.Log
import cf.zknb.tvlauncher.model.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.conscrypt.Conscrypt
import org.json.JSONObject
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * 天气数据仓库
 *
 * 负责从API获取天气数据，使用OkHttp和Conscrypt支持Android 4.2的TLS连接
 */
class WeatherRepository(private val context: Context) {
    
    companion object {
        private const val TAG = "WeatherRepository"
        private const val API_URL = "https://uapis.cn/api/v1/misc/weather"
        private const val TIMEOUT = 10000L // 10秒
        
        init {
            // 在Android 4.2等老版本上安装Conscrypt作为首选安全提供者
            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
                Log.d(TAG, "Conscrypt provider installed successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to install Conscrypt provider", e)
            }
        }
    }
    
    private val client: OkHttpClient by lazy {
        try {
            val trustManager = createTrustManager()
            
            // 获取Conscrypt的SSLContext
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS", Conscrypt.newProvider())
            sslContext.init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), java.security.SecureRandom())
            
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true } // 信任所有主机名
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create OkHttpClient with Conscrypt, using default", e)
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
     * 根据城市adcode获取天气信息
     *
     * @param adcode 高德地图城市adcode（如：110100 北京）
     * @return Weather对象，失败返回null
     */
    suspend fun getWeather(adcode: String): Weather? = withContext(Dispatchers.IO) {
        try {
            val urlString = "$API_URL?adcode=$adcode"
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
            
            // 检查必要字段是否存�?
            if (!json.has("city") || !json.has("weather")) {
                Log.e(TAG, "Missing required fields in response")
                return null
            }
            
            // 获取天气代码，用于图标映�?
            // 由于API没有返回weatherCode，我们根据weather文本映射
            val weatherText = json.optString("weather", "未知")
            val weatherCode = mapWeatherTextToCode(weatherText)
            
            val weather = Weather(
                city = json.optString("city", "未知"),
                weather = weatherText,
                temperature = "${json.optInt("temperature", 0)}°C",
                weatherCode = weatherCode
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
        return when {
            weatherText.contains("晴") -> "100"
            weatherText.contains("多云") -> "101"
            weatherText.contains("阴") -> "104"
            weatherText.contains("雨") && weatherText.contains("雪") -> "400"
            weatherText.contains("小雨") -> "305"
            weatherText.contains("中雨") -> "306"
            weatherText.contains("大雨") -> "307"
            weatherText.contains("雨") -> "305"
            weatherText.contains("小雪") -> "400"
            weatherText.contains("中雪") -> "401"
            weatherText.contains("大雪") -> "402"
            weatherText.contains("雪") -> "400"
            weatherText.contains("雾") -> "500"
            weatherText.contains("霞") -> "502"
            weatherText.contains("云") -> "103"
            else -> "999"
        }
    }
}
