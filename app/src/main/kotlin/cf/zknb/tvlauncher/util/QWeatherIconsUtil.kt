package cf.zknb.tvlauncher.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.graphics.PathParser
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * 和风天气图标 —— 在线 CDN 版（零本地资源）。
 *
 * 原先图标靠打包在 APK 里的 `assets/fonts/qweather-icons.ttf`（约 198 KB）以字符 + TypefaceSpan
 * 的方式渲染。现在按天气代码直接从 jsDelivr CDN 拉取官方 SVG：
 *
 * ```
 * https://fastly.jsdelivr.net/npm/qweather-icons@1.8.0/icons/{code}.svg
 * ```
 *
 * 文件名与天气数据里的 `icon` / `weather_icon` 字段一一对应
 * （见 https://icons.qweather.com/usage/ ，例：305 → 305.svg）。
 * 图标项目采用 MIT / CC BY 4.0 许可，允许在线引用。
 *
 * 渲染方式：Android 4.2（API 17）既没有 VectorDrawable，也无法用 BitmapFactory
 * 解码 SVG。这里复用 **已随 leanback 引入的** `androidx.core.graphics.PathParser`
 * 把 SVG 里的 `<path d="...">` 转成 [android.graphics.Path]，再由
 * [SvgIconDrawable] 按 viewBox 缩放绘制 —— 零新增依赖、矢量无损、可任意染色。
 * CDN 上的图标格式固定：`viewBox="0 0 16 16"`、`fill="currentColor"`、
 * 1~2 个纯填充 path，无 transform / stroke / clip。
 *
 * 缓存：SVG 文本落盘到 `cacheDir/qweather_icons/{code}.svg`，解析结果常驻内存。
 * 每个天气代码通常只联网一次；断网或 CDN 故障时，已见过的图标依然能显示。
 */
object QWeatherIconsUtil {

    private const val TAG = "QWeatherIcons"
    private const val CDN_PATH = "/npm/qweather-icons@1.8.0/icons/"

    /** 国内电视实测均可解析；fastly 延迟最低，其余作为故障转移。 */
    private val CDN_HOSTS = arrayOf(
        "fastly.jsdelivr.net",
        "gcore.jsdelivr.net",
        "cdn.jsdelivr.net"
    )
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 6000
    private const val DEFAULT_VIEWBOX = 16f

    private val pathPattern = Pattern.compile("<path\\b[^>]*?\\sd=\"([^\"]+)\"", Pattern.DOTALL)
    private val viewBoxPattern = Pattern.compile(
        "viewBox=\"\\s*[\\d.-]+\\s+[\\d.-]+\\s+([\\d.-]+)\\s+([\\d.-]+)\\s*\""
    )
    private val codePattern = Regex("\\d{3,4}")

    /** 解析后的矢量图形；天气代码数量有限（通常十几个），无需 LRU 淘汰。 */
    private val memoryCache = ConcurrentHashMap<String, IconShape>()

    /** 供同文件的 [SvgIconDrawable] 使用。 */
    internal class IconShape(val paths: List<Path>, val width: Float, val height: Float)

    /** 只接受 3~4 位数字，避免非法 code 拼出越界 URL 或路径穿越。 */
    private fun normalizeCode(code: String?): String {
        val c = code?.trim().orEmpty()
        return if (codePattern.matches(c)) c else "999"
    }

    private fun cacheFile(context: Context, code: String): File =
        File(File(context.applicationContext.cacheDir, "qweather_icons"), "$code.svg")

    /**
     * 同步加载图标，**必须在 IO 线程调用**。
     * 顺序：内存缓存 → 磁盘缓存 → 依次尝试各 CDN 域名。
     * 全部失败返回 null，调用方应隐藏图标而不是崩溃。
     *
     * 加同步是为了避免冷启动时「缓存天气 + 联网天气」两次 updateWeather 并发
     * 请求同一个 code（两者都会在内存缓存写入前进入下载分支）。
     */
    @Synchronized
    fun loadIcon(context: Context, weatherCode: String?): SvgIconDrawable? {
        val code = normalizeCode(weatherCode)
        val app = context.applicationContext

        memoryCache[code]?.let {
            Log.d(TAG, "图标命中内存缓存: $code")
            return SvgIconDrawable(it)
        }

        val file = cacheFile(app, code)
        var svg = readDisk(file)
        val fromDisk = svg != null
        if (svg == null) {
            svg = fetchFromCdn(code)
            if (svg != null) writeDisk(file, svg)
        }
        if (svg == null) {
            Log.w(TAG, "图标下载失败: $code（已尝试 ${CDN_HOSTS.size} 个 CDN）")
            return null
        }

        val shape = parseSvg(svg)
        if (shape == null) {
            Log.w(TAG, "SVG 解析失败: $code, ${svg.length} 字节")
            // 可能是写了一半的损坏缓存，删掉让下次重新下载
            if (fromDisk) file.delete()
            return null
        }
        memoryCache[code] = shape
        Log.d(TAG, "图标就绪: $code (${shape.paths.size} path, ${if (fromDisk) "disk" else "cdn"})")
        return SvgIconDrawable(shape)
    }

    private fun readDisk(file: File): String? = try {
        if (file.isFile && file.length() > 0) file.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
        Log.w(TAG, "读取图标缓存失败: ${e.message}")
        null
    }

    private fun writeDisk(file: File, svg: String) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(svg, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "写入图标缓存失败: ${e.message}")
        }
    }

    /** 走统一网络层（Conscrypt TLS + 自动跨协议重定向），逐个域名重试。 */
    private fun fetchFromCdn(code: String): String? {
        for (host in CDN_HOSTS) {
            val url = "https://$host$CDN_PATH$code.svg"
            val body = try {
                HttpCompat.getString(url, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.w(TAG, "CDN 请求异常 $host: ${e.message}")
                null
            }
            if (!body.isNullOrEmpty() && body.contains("<svg", ignoreCase = true)) {
                Log.d(TAG, "图标已下载: $code <- $host (${body.length} B)")
                return body
            }
            Log.w(TAG, "CDN 未返回 SVG: $host / $code，尝试下一个")
        }
        return null
    }

    /** 提取 viewBox 尺寸与全部 path 的 d，交给 PathParser 转成 Android Path。 */
    private fun parseSvg(svg: String): IconShape? {
        var w = DEFAULT_VIEWBOX
        var h = DEFAULT_VIEWBOX
        val vb = viewBoxPattern.matcher(svg)
        if (vb.find()) {
            w = vb.group(1)?.toFloatOrNull() ?: DEFAULT_VIEWBOX
            h = vb.group(2)?.toFloatOrNull() ?: DEFAULT_VIEWBOX
        }
        val paths = ArrayList<Path>(2)
        val m = pathPattern.matcher(svg)
        while (m.find()) {
            val d = m.group(1) ?: continue
            try {
                PathParser.createPathFromPathData(d)?.let { paths.add(it) }
            } catch (e: Throwable) {
                // PathParser 对畸形 path 可能抛 RuntimeException / IllegalArgumentException
                Log.w(TAG, "忽略无法解析的 path: ${e.javaClass.simpleName}")
            }
        }
        return if (paths.isEmpty() || w <= 0f || h <= 0f) null
        else IconShape(paths, w, h)
    }

    /** 获取简化的天气描述（保留原有文案逻辑，与图标无关）。 */
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
}

/**
 * 用 Canvas 直接绘制 SVG path 的矢量 Drawable。
 *
 * 相比字体图标：不需要 TTF、颜色可直接跟随壁纸对比色、任意缩放不失真。
 * [setIconColor] 兼容 API 1，避开 ImageView.setColorFilter 在旧平台上的差异。
 *
 * **画质要点**：不能在 draw() 里用 canvas.scale() 放大 16 单位的 path。
 * Android 4.2 的硬件加速 hwui 会先按 path 的局部坐标（16×16）栅格化成低分辨率
 * 纹理再放大，边缘呈明显块状/马赛克（实测 TCL 901 上的云朵轮廓）。
 * 这里改为在 bounds 变化时用 Matrix 把 path 预缩放到**最终像素尺寸**，
 * draw() 时不再缩放 Canvas，抗锯齿按屏幕分辨率生效。
 */
class SvgIconDrawable internal constructor(
    private val shape: QWeatherIconsUtil.IconShape
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private var filter: ColorFilter? = null

    /** 已按像素尺寸缩放、合并好的 path（避免重复计算）。 */
    private var scaledPath: Path? = null
    private var scaledWidth = 0
    private var scaledHeight = 0

    /** 由外部（壁纸对比色计算）设置图标颜色。 */
    fun setIconColor(color: Int) {
        paint.color = color
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuildPath(bounds.width(), bounds.height())
    }

    private fun rebuildPath(w: Int, h: Int) {
        if (w <= 0 || h <= 0) {
            scaledPath = null
            return
        }
        if (w == scaledWidth && h == scaledHeight && scaledPath != null) return
        // SVG 必须等比缩放，不能用 setScale(w / viewBoxW, h / viewBoxH)
        // 分别缩放两个轴；View 的 padding 或非正方形测量一旦出现就会拉扁图标。
        // 和风图标本身为 1:1，这里按短边缩放并在方形 View 内居中，保证始终 1:1。
        val scale = minOf(w / shape.width, h / shape.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                (w - shape.width * scale) / 2f,
                (h - shape.height * scale) / 2f
            )
        }
        val combined = Path()
        for (p in shape.paths) combined.addPath(p, matrix)
        scaledPath = combined
        scaledWidth = w
        scaledHeight = h
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0 || shape.paths.isEmpty()) return
        if (scaledPath == null || b.width() != scaledWidth || b.height() != scaledHeight) {
            rebuildPath(b.width(), b.height())
        }
        val path = scaledPath ?: return
        paint.colorFilter = filter
        val saveCount = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.drawPath(path, paint)
        canvas.restoreToCount(saveCount)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        filter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
