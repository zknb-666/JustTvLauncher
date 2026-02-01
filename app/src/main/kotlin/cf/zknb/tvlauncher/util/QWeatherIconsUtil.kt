package cf.zknb.tvlauncher.util

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.Log

/**
 * 和风天气图标字体工具类
 * 
 * 使用QWeather Icons字体来显示天气图标
 */
object QWeatherIconsUtil {
    private const val TAG = "QWeatherIconsUtil"
    private var typeface: Typeface? = null
    
    /**
     * 天气代码到Unicode字符的映�?
     * 这些是和风天气图标字体中的字符编�?
     */
    private val iconMap = mapOf(
        "100" to '\uF101', // 晴
        "101" to '\uF102', // 多云
        "102" to '\uF103', // 少云
        "103" to '\uF104', // 晴间多云
        "104" to '\uF105', // 阴
        "150" to '\uF106', // 晴（夜间）
        "151" to '\uF107', // 多云（夜间）
        "152" to '\uF108', // 少云（夜间）
        "153" to '\uF109', // 晴间多云（夜间）
        "300" to '\uF10A', // 阵雨
        "301" to '\uF10B', // 强阵雨
        "302" to '\uF10C', // 雷阵雨
        "303" to '\uF10D', // 强雷阵雨
        "304" to '\uF10E', // 雷阵雨伴有冰雹
        "305" to '\uF10F', // 小雨
        "306" to '\uF110', // 中雨
        "307" to '\uF111', // 大雨
        "308" to '\uF112', // 极端降雨
        "309" to '\uF113', // 毛毛雨/细雨
        "310" to '\uF114', // 暴雨
        "311" to '\uF115', // 大暴雨
        "312" to '\uF116', // 特大暴雨
        "313" to '\uF117', // 冻雨
        "314" to '\uF118', // 小到中雨
        "315" to '\uF119', // 中到大雨
        "316" to '\uF11A', // 大到暴雨
        "317" to '\uF11B', // 暴雨到大暴雨
        "318" to '\uF11C', // 大暴雨到特大暴雨
        "350" to '\uF11D', // 阵雨（夜间）
        "351" to '\uF11E', // 强阵雨（夜间）
        "399" to '\uF11F', // 雨
        "400" to '\uF120', // 小雪
        "401" to '\uF121', // 中雪
        "402" to '\uF122', // 大雪
        "403" to '\uF123', // 暴雪
        "404" to '\uF124', // 雨夹雪
        "405" to '\uF125', // 雨雪天气
        "406" to '\uF126', // 阵雨夹雪
        "407" to '\uF127', // 阵雪
        "408" to '\uF128', // 小到中雪
        "409" to '\uF129', // 中到大雪
        "410" to '\uF12A', // 大到暴雪
        "456" to '\uF12B', // 阵雨夹雪（夜间）
        "457" to '\uF12C', // 阵雪（夜间）
        "499" to '\uF12D', // 雪
        "500" to '\uF12E', // 薄雾
        "501" to '\uF12F', // 雾
        "502" to '\uF130', // 霧
        "503" to '\uF131', // 扬沙
        "504" to '\uF132', // 浮尘
        "507" to '\uF133', // 沙尘暴
        "508" to '\uF134', // 强沙尘暴
        "509" to '\uF135', // 浓雾
        "510" to '\uF136', // 强浓雾
        "511" to '\uF137', // 中度霧
        "512" to '\uF138', // 重度霧
        "513" to '\uF139', // 严重霧
        "514" to '\uF13A', // 大雾
        "515" to '\uF13B', // 特强浓雾
        "900" to '\uF13C', // 热
        "901" to '\uF13D', // 冷
        "999" to '\uF13E'  // 未知
    )
    
    /**
     * 初始化字体
     */
    fun init(context: Context) {
        try {
            typeface = Typeface.createFromAsset(context.assets, "fonts/qweather-icons.ttf")
            Log.d(TAG, "QWeather Icons font loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load QWeather Icons font", e)
        }
    }
    
    /**
     * 根据天气代码获取图标字符
     */
    fun getWeatherIcon(weatherCode: String): String {
        val iconChar = iconMap[weatherCode] ?: iconMap["999"]!!
        return iconChar.toString()
    }
    
    /**
     * 创建带有自定义字体的SpannableString
     * 用于在TextView中显示天气图标
     */
    fun createIconSpan(context: Context, weatherCode: String): SpannableString {
        if (typeface == null) {
            init(context)
        }
        
        val iconText = getWeatherIcon(weatherCode)
        val spannable = SpannableString(iconText)
        
        typeface?.let {
            spannable.setSpan(
                CustomTypefaceSpan(it),
                0,
                iconText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        
        return spannable
    }
    
    /**
     * 获取简化的天气描述
     */
    fun getSimpleWeatherText(weather: String): String {
        return when {
            weather.contains("晴") -> "晴"
            weather.contains("云") -> "多云"
            weather.contains("阴") -> "阴"
            weather.contains("雨") -> "雨"
            weather.contains("雪") -> "雪"
            weather.contains("雾") || weather.contains("霧") -> "雾霧"
            else -> weather
        }
    }
    
    /**
     * 自定义TypefaceSpan，用于应用自定义字体
     */
    private class CustomTypefaceSpan(private val typeface: Typeface) : TypefaceSpan("") {
        override fun updateDrawState(ds: android.text.TextPaint) {
            ds.typeface = typeface
        }
        
        override fun updateMeasureState(paint: android.text.TextPaint) {
            paint.typeface = typeface
        }
    }
}
