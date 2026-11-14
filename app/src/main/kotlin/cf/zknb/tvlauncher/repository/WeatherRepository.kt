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
        private const val PREFS_WEATHER = "cached_weather"
        
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
                    val weather = parseWeatherResponse(responseBody)
                    // cache result asynchronously
                    if (weather != null) {
                        cacheWeather(responseBody, context)
                    }
                    weather
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
     * 将天气文本映射到和风天气标准天气代码（用于图标显示）
     *
     * 关键约束：返回的代码必须与 [QWeatherIconsUtil.iconMap] 使用的“和风天气标准
     * condition code”完全一致，否则会出现“图标与实况天气不符”的问题。
     * 例如标准中小雨=305、中雨=306、大雨=307、阵雨=300、雷阵雨=302；旧的映射把
     * 小雨/中雨/大雨分别写成 300/301/302（标准里对应的是阵雨/强阵雨/雷阵雨），
     * 导致下雨下雪时图标全部错位，看起来“有时候图标错误”。
     */
    private fun mapWeatherTextToCode(weatherText: String): String {
        // 参考和风天气官方图标代码 https://dev.qweather.com/docs/resource/icons/#weather-icons
        // when 分支顺序很重要：更具体/更长的描述必须排在更通用的描述之前，
        // 例如“晴间多云”必须在“多云”之前，“小到中雨”必须在“小雨/中雨”之前。
        return when {
            // —— 风（和风天气图标字体没有专门的风图标，回退到未知）——
            weatherText.contains("风") || weatherText.contains("平静") -> "999"

            // —— 晴 / 云 ——
            weatherText.contains("晴间多云") -> "103"     // 晴间多云（含“晴”，须在“晴”之前）
            weatherText.contains("晴转多云") -> "103"     // 晴转多云（含“晴”，须在“晴”之前）
            weatherText.contains("晴") -> "100"           // 晴
            weatherText.contains("多云") -> "101"         // 多云
            weatherText.contains("少云") -> "102"         // 少云
            weatherText.contains("阴") -> "104"           // 阴

            // —— 雾 / 霾 / 沙尘（具体项须在“雾/霾”通用项之前）——
            weatherText.contains("薄雾") -> "500"
            weatherText.contains("特强浓雾") -> "515"
            weatherText.contains("强浓雾") -> "510"
            weatherText.contains("浓雾") -> "509"
            weatherText.contains("大雾") -> "514"
            weatherText.contains("雾") -> "501"           // 雾
            weatherText.contains("中度霾") -> "511"
            weatherText.contains("重度霾") -> "512"
            weatherText.contains("严重霾") -> "513"
            weatherText.contains("霾") -> "502"           // 霾
            weatherText.contains("扬沙") -> "503"
            weatherText.contains("浮尘") -> "504"
            weatherText.contains("强沙尘暴") -> "508"
            weatherText.contains("沙尘暴") -> "507"

            // —— 雨夹雪类（须在通用雨/雪项之前）——
            weatherText.contains("雨夹雪") -> "404"
            weatherText.contains("雨雪天气") -> "405"
            weatherText.contains("阵雨夹雪") -> "406"
            weatherText.contains("雨") && weatherText.contains("雪") -> "404"

            // —— 雨（具体项须在“小雨/中雨/大雨/阵雨”等之前）——
            weatherText.contains("雷阵雨伴冰雹") -> "304"
            weatherText.contains("强雷阵雨") -> "303"
            weatherText.contains("雷阵雨") -> "302"
            weatherText.contains("强阵雨") -> "301"
            weatherText.contains("阵雨") -> "300"
            weatherText.contains("小到中雨") -> "314"
            weatherText.contains("中到大雨") -> "315"
            weatherText.contains("大到暴雨") -> "316"
            weatherText.contains("小雨") -> "305"
            weatherText.contains("中雨") -> "306"
            weatherText.contains("大雨") -> "307"
            weatherText.contains("特大暴雨") -> "312"
            weatherText.contains("大暴雨") -> "311"
            weatherText.contains("暴雨") -> "310"
            weatherText.contains("极端降雨") -> "308"
            weatherText.contains("毛毛雨") || weatherText.contains("细雨") -> "309"
            weatherText.contains("冻雨") -> "313"
            weatherText.contains("雨") -> "399"           // 其它降雨

            // —— 雪 ——
            weatherText.contains("雷阵雪") -> "407"
            weatherText.contains("小到中雪") -> "408"
            weatherText.contains("中到大雪") -> "409"
            weatherText.contains("大到暴雪") -> "410"
            weatherText.contains("小雪") -> "400"
            weatherText.contains("中雪") -> "401"
            weatherText.contains("大雪") -> "402"
            weatherText.contains("阵雪") -> "407"
            weatherText.contains("暴雪") -> "403"
            weatherText.contains("冰雹") -> "304"         // 冰雹：和风无单独代码，近似雷阵雨伴冰雹
            weatherText.contains("雪") -> "499"           // 其它降雪

            weatherText.contains("霜冻") -> "999"
            weatherText.contains("露") -> "999"
            weatherText.contains("热") -> "900"
            weatherText.contains("冷") -> "901"
            weatherText.contains("未知") -> "999"
            else -> "999"                                // 未知天气
        }
    }

    /**
     * 缓存原始响应到SharedPreferences
     */
    private fun cacheWeather(rawJson: String, context: Context) {
        try {
            val prefs = context.getSharedPreferences("weather_settings", Context.MODE_PRIVATE)
            prefs.edit().putString(PREFS_WEATHER, rawJson).apply()
        } catch (_: Exception) {}
    }

    /**
     * 从SharedPreferences中读取缓存的天气
     */
    fun loadCachedWeather(context: Context): Weather? {
        return try {
            val prefs = context.getSharedPreferences("weather_settings", Context.MODE_PRIVATE)
            val json = prefs.getString(PREFS_WEATHER, null) ?: return null
            parseWeatherResponse(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached weather", e)
            null
        }
    }
}