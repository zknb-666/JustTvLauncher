package cf.zknb.tvlauncher.util

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Bing 每日一图管理器
 *
 * 通过 https://bing.biturl.top/ 获取每日壁纸直链，下载后设为系统壁纸。
 *
 * - 网络层统一走 [HttpCompat]（平台 HttpURLConnection + 系统 TLS 1.2 +
 *   HTTPS 失败自动降级 HTTP），不再依赖 OkHttp / BouncyCastle。
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
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

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
            val body = HttpCompat.getString(API_URL, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
                ?: return@withContext null
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
                val stream: InputStream = HttpCompat.openStream(
                    imageUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS
                ) ?: run {
                    Log.e(TAG, "下载壁纸失败或响应流为空: $imageUrl")
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
