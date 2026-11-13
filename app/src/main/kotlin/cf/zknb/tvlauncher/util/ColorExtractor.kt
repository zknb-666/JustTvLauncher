package cf.zknb.tvlauncher.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * 图标 / 壁纸颜色提取工具
 * 兼容 Android 4.2 (API 17+)
 *
 * 性能说明（针对双核 A9 / Dalvik 的老电视）：
 * - 取色需要把 Drawable 画进 ARGB_8888 Bitmap 再逐像素统计，属于重操作。
 * - 旧实现每次调用都会新建整张壁纸大小的 Bitmap（1920x1080 约 8MB）且从不回收，
 *   在时间 tick、焦点变化、天气刷新时反复触发，直接造成 Dalvik GC stop-the-world 掉帧。
 * - 现在按采样区域做 64px 网格缓存，并回收所有临时 Bitmap。壁纸变化时调用 clearWallpaperCache()。
 */
object ColorExtractor {

    private const val DEFAULT_COLOR = 0xFF424242.toInt()
    private const val GRID = 64

    /** key: 量化后的采样区域，value: 主色调 */
    private val wallpaperColorCache = ConcurrentHashMap<Long, Int>()

    /** 壁纸更换后必须调用，否则会继续复用旧壁纸的颜色 */
    fun clearWallpaperCache() {
        wallpaperColorCache.clear()
    }

    private fun cacheKey(x: Int, y: Int, width: Int, height: Int): Long {
        val gx = (x / GRID).toLong() and 0xFFFFL
        val gy = (y / GRID).toLong() and 0xFFFFL
        val gw = (width / GRID).toLong() and 0xFFFFL
        val gh = (height / GRID).toLong() and 0xFFFFL
        return (gx shl 48) or (gy shl 32) or (gw shl 16) or gh
    }

    /**
     * 从壁纸的特定区域提取颜色（带缓存）。
     * 同一区域在壁纸未变化前只会真正解码一次。
     */
    fun extractColorFromWallpaper(
        context: Context,
        x: Int,
        y: Int,
        sampleWidth: Int = 200,
        sampleHeight: Int = 100
    ): Int {
        val key = cacheKey(x, y, sampleWidth, sampleHeight)
        wallpaperColorCache[key]?.let { return it }

        val result = try {
            val wallpaperDrawable = WallpaperManager.getInstance(context).drawable
                ?: return DEFAULT_COLOR

            var createdFullBitmap: Bitmap? = null
            val wallpaperBitmap = when (wallpaperDrawable) {
                is BitmapDrawable -> wallpaperDrawable.bitmap
                else -> {
                    val width = wallpaperDrawable.intrinsicWidth
                    val height = wallpaperDrawable.intrinsicHeight
                    if (width <= 0 || height <= 0) return DEFAULT_COLOR
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    createdFullBitmap = bitmap
                    val canvas = android.graphics.Canvas(bitmap)
                    wallpaperDrawable.setBounds(0, 0, canvas.width, canvas.height)
                    wallpaperDrawable.draw(canvas)
                    bitmap
                }
            }

            val left = (x - sampleWidth / 2).coerceIn(0, wallpaperBitmap.width - 1)
            val top = (y - sampleHeight / 2).coerceIn(0, wallpaperBitmap.height - 1)
            val right = (x + sampleWidth / 2).coerceIn(left + 1, wallpaperBitmap.width)
            val bottom = (y + sampleHeight / 2).coerceIn(top + 1, wallpaperBitmap.height)

            val regionBitmap = Bitmap.createBitmap(
                wallpaperBitmap, left, top, right - left, bottom - top
            )
            val color = extractDominantColor(regionBitmap)
            regionBitmap.recycle()
            // 只回收自己创建的 Bitmap，绝不回收 WallpaperManager 持有的 bitmap
            createdFullBitmap?.recycle()
            color
        } catch (e: Throwable) {
            DEFAULT_COLOR
        }

        wallpaperColorCache[key] = result
        return result
    }

    fun getContrastColor(backgroundColor: Int): Int =
        if (isDarkColor(backgroundColor)) Color.WHITE else Color.BLACK

    fun getContrastColorWithAlpha(backgroundColor: Int, alpha: Int = 255): Int {
        val baseColor = getContrastColor(backgroundColor)
        return Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
    }

    /**
     * 从 Drawable 提取主色调。
     * 注意：本方法仍会创建临时 Bitmap，调用方应做进程级缓存（见 ShortcutCardPresenter）。
     */
    fun extractColor(drawable: Drawable?): Int {
        if (drawable == null) return DEFAULT_COLOR

        return try {
            val bitmap: Bitmap
            val ownsBitmap: Boolean
            if (drawable is BitmapDrawable) {
                bitmap = drawable.bitmap
                ownsBitmap = false
            } else {
                val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 64
                val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 64
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                ownsBitmap = true
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }

            val color = extractDominantColor(bitmap)
            if (ownsBitmap) bitmap.recycle()
            color
        } catch (e: Throwable) {
            DEFAULT_COLOR
        }
    }

    private fun extractDominantColor(bitmap: Bitmap): Int {
        val scaledBitmap = if (bitmap.width > 64 || bitmap.height > 64) {
            Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        } else {
            bitmap
        }

        return try {
            val pixels = IntArray(scaledBitmap.width * scaledBitmap.height)
            scaledBitmap.getPixels(
                pixels, 0, scaledBitmap.width, 0, 0,
                scaledBitmap.width, scaledBitmap.height
            )

            val colorMap = HashMap<Int, Int>()
            for (pixel in pixels) {
                if (Color.alpha(pixel) < 128) continue
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                val brightness = (red + green + blue) / 3
                if (brightness > 240 || brightness < 15) continue
                val maxChannel = maxOf(red, green, blue)
                val minChannel = minOf(red, green, blue)
                val saturation = if (maxChannel > 0) {
                    (maxChannel - minChannel).toFloat() / maxChannel
                } else 0f
                if (saturation < 0.15f) continue
                val quantizedColor = quantizeColor(pixel)
                colorMap[quantizedColor] = (colorMap[quantizedColor] ?: 0) + 1
            }

            val dominantColor = colorMap.maxByOrNull { it.value }?.key
            if (dominantColor != null) adjustColorForBackground(dominantColor) else DEFAULT_COLOR
        } finally {
            if (scaledBitmap !== bitmap) scaledBitmap.recycle()
        }
    }

    private fun quantizeColor(color: Int): Int {
        val step = 32
        return Color.rgb(
            (Color.red(color) / step) * step,
            (Color.green(color) / step) * step,
            (Color.blue(color) / step) * step
        )
    }

    private fun adjustColorForBackground(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[2] > 0.6f) hsv[2] = 0.5f else if (hsv[2] < 0.3f) hsv[2] = 0.35f
        if (hsv[1] > 0.8f) hsv[1] = 0.7f
        return Color.HSVToColor(hsv)
    }

    fun isDarkColor(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val brightness = sqrt(
            red * red * 0.299 + green * green * 0.587 + blue * blue * 0.114
        )
        return brightness < 130
    }
}
