package cf.zknb.tvlauncher.repository

import android.content.Context
import android.util.Log
import cf.zknb.tvlauncher.model.Weather
import cf.zknb.tvlauncher.util.HttpCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 天气数据仓库
 *
 * 网络层统一走 [HttpCompat]（HttpURLConnection + Conscrypt native TLS）。
 * 这里严格区分两种语义：
 * - [locateByIp]：自动定位，只认真实网络结果，失败返回 null；
 * - [getWeather]：桌面展示用，失败 / 占位时先回退省级 adcode，再回退本地缓存。
 *
 * 依据 uapis.cn「查询天气」接口文档：不传 city / adcode 时按客户端 IP 定位，
 * adcode 优先级最高；weather 不是固定枚举，weather_icon 才是图标代码；
 * adcode 与部分字段可能为空。
 */
class WeatherRepository(private val context: Context) {

    companion object {
        private const val TAG = "WeatherRepository"
        private const val API_URL = "https://uapis.cn/api/v1/misc/weather"
        // 老设备首次 TLS 握手 + 可能的 HTTP 降级需要留一点余量
        private const val TIMEOUT_MS = 3000
        private const val PREFS_WEATHER = "cached_weather"
    }
    
    /** 一次「真实联网拿到有效实况」的结果，raw 用于写入缓存。 */
    private data class FreshWeather(val raw: String, val weather: Weather)

    /**
     * 自动定位：不带任何参数请求天气接口，由服务端按客户端 IP 定位（见 API 文档）。
     *
     * 与 [getWeather] 的关键区别：**不回退缓存**。请求失败、非 2xx 或占位数据一律
     * 返回 null，调用方才能区分「真的定位到了」与「只是读到上一次别的城市的天气」。
     */
    suspend fun locateByIp(): Weather? = withContext(Dispatchers.IO) {
        try {
            val fresh = requestFresh(API_URL)
            if (fresh == null) {
                Log.w(TAG, "IP 定位未拿到有效实况")
                return@withContext null
            }
            cacheWeather(fresh.raw, context)
            fresh.weather
        } catch (e: Exception) {
            Log.e(TAG, "IP 定位异常: ${e.message}", e)
            null
        }
    }

    /**
     * 按 adcode 查询天气；adcode 为空时按 IP 定位。
     *
     * 实测部分市 / 区级 adcode 会返回整包占位数据（北京 110100 / 110101 / 110117
     * 为「未知 0°C」），而同省的省级 adcode（110000）有正常实况。因此占位时自动
     * 用省级 adcode 再查一次，避免桌面长期显示别的城市的天气。
     *
     * 仅「有效实况」才写缓存；两级都失败时回退到上一次有效天气。
     */
    suspend fun getWeather(adcode: String? = null): Weather? = withContext(Dispatchers.IO) {
        try {
            val primaryUrl = if (adcode.isNullOrEmpty()) API_URL else "$API_URL?adcode=$adcode"
            val primary = requestFresh(primaryUrl)
            val result = if (primary == null && !adcode.isNullOrEmpty()) {
                provinceFallbackUrl(adcode)?.let { fallback ->
                    Log.d(TAG, "市/区级无实况，回退省级: $fallback")
                    requestFresh(fallback)
                }
            } else {
                primary
            }

            if (result != null) {
                cacheWeather(result.raw, context)
                return@withContext result.weather
            }
            Log.w(TAG, "无有效实况，保留上一次有效天气")
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching weather: ${e.message}", e)
        }
        loadCachedWeather(context)
    }

    /** 由 6 位市 / 区级 adcode 推导省级（110101 -> 110000）；已是省级则返回 null。 */
    private fun provinceFallbackUrl(adcode: String): String? {
        if (adcode.length != 6 || !adcode.all { it.isDigit() } || adcode.endsWith("0000")) return null
        return "$API_URL?adcode=${adcode.take(2)}0000"
    }

    /** 发一次请求；仅 2xx 且解析为有效实况才返回，错误响应 / 占位数据一律 null。 */
    private fun requestFresh(url: String): FreshWeather? {
        Log.d(TAG, "Requesting weather from: $url")
        val body = HttpCompat.getString(url, TIMEOUT_MS, TIMEOUT_MS) ?: return null
        Log.d(TAG, "Response: ${body.take(300)}")
        val weather = parseWeatherResponse(body) ?: return null
        return FreshWeather(body, weather)
    }
    
    /**
     * 解析天气 API 响应。
     *
     * 文档要点：weather 是天气现象文本、**不是固定枚举**；weather_icon 才是图标代码
     * （直接对应 CDN 上的 `{code}.svg` 文件名）；adcode 与部分字段可能为空。
     * 因此优先使用 weather_icon，文本映射只作兜底。
     */
    private fun parseWeatherResponse(response: String): Weather? {
        return try {
            val json = JSONObject(response)
            // 400 / 404 / 503 等错误响应没有 city / weather 字段
            if (!json.has("city") || !json.has("weather")) {
                Log.w(TAG, "响应缺少天气字段: ${response.take(160)}")
                return null
            }

            val weatherText = json.optString("weather", "")
            // 占位数据的稳定特征是 weather="未知"（实测北京 1101xx）。
            // 不再依赖 report_time / humidity：文档说明这些可选字段可能合法缺失，
            // 旧判定 (report_time 为空 && humidity==0) 有误杀正常实况的风险。
            if (weatherText.isEmpty() || weatherText == "未知") {
                Log.w(TAG, "收到占位天气数据（adcode=${json.optString("adcode", "")}），忽略")
                return null
            }

            val icon = json.optString("weather_icon", "")
            val weatherCode = icon.takeIf { it.isNotEmpty() } ?: mapWeatherTextToCode(weatherText)
            // 文档：区县名在「自动按 IP 定位时更常见」，省级响应可能没有 district
            val locationName = json.optString("district", "").takeIf { it.isNotEmpty() }
                ?: json.optString("city", "未知")
            // 文档示例 18.3 / 实测 Tokyo 11.5：用 Double，避免 optInt 截掉小数
            val temp = json.optDouble("temperature", Double.NaN)
            val temperature = when {
                temp.isNaN() -> "0°C"
                temp % 1.0 == 0.0 -> "${temp.toInt()}°C"
                else -> "$temp°C"
            }

            val weather = Weather(
                city = locationName,
                weather = weatherText,
                temperature = temperature,
                weatherCode = weatherCode,
                adcode = json.optString("adcode", "")
            )

            Log.d(TAG, "Weather parsed successfully: ${weather.city} ${weather.weather} ${weather.temperature} (icon=$weatherCode)")
            weather
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing weather response: ${e.message}", e)
            null
        }
    }
    
    /**
     * 将天气文本映射到和风天气标准天气代码（用于图标显示）
     *
     * 关键约束：返回的代码必须是和风天气标准 condition code —— 也就是
     * `https://icons.qweather.com/usage/` 所述、CDN 上 `{code}.svg` 的文件名；
     * 否则会出现「图标与实况天气不符」。例如标准中小雨=305、中雨=306、大雨=307、阵雨=300、雷阵雨=302；旧的映射把
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