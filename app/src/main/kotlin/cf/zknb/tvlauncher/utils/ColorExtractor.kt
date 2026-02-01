package cf.zknb.tvlauncher.utils

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.sqrt

/**
 * 图标颜色提取工具
 * 兼容 Android 4.2 (API 17+)
 */
object ColorExtractor {

    /**
     * 从壁纸的特定区域提取颜色
     * @param context 上下文
     * @param x 区域中心X坐标（像素）
     * @param y 区域中心Y坐标（像素）
     * @param sampleWidth 采样宽度（像素）
     * @param sampleHeight 采样高度（像素）
     * @return 该区域的主色调
     */
    fun extractColorFromWallpaper(
        context: Context,
        x: Int,
        y: Int,
        sampleWidth: Int = 200,
        sampleHeight: Int = 100
    ): Int {
        try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            val wallpaperDrawable = wallpaperManager.drawable ?: return Color.parseColor("#424242")
            
            val wallpaperBitmap = when (wallpaperDrawable) {
                is BitmapDrawable -> wallpaperDrawable.bitmap
                else -> {
                    val width = wallpaperDrawable.intrinsicWidth
                    val height = wallpaperDrawable.intrinsicHeight
                    if (width <= 0 || height <= 0) return Color.parseColor("#424242")
                    
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    wallpaperDrawable.setBounds(0, 0, canvas.width, canvas.height)
                    wallpaperDrawable.draw(canvas)
                    bitmap
                }
            }
            
            // 计算采样区域
            val left = (x - sampleWidth / 2).coerceIn(0, wallpaperBitmap.width - 1)
            val top = (y - sampleHeight / 2).coerceIn(0, wallpaperBitmap.height - 1)
            val right = (x + sampleWidth / 2).coerceIn(left + 1, wallpaperBitmap.width)
            val bottom = (y + sampleHeight / 2).coerceIn(top + 1, wallpaperBitmap.height)
            
            // 提取该区域的bitmap
            val regionBitmap = Bitmap.createBitmap(
                wallpaperBitmap,
                left,
                top,
                right - left,
                bottom - top
            )
            
            val color = extractDominantColor(regionBitmap)
            
            // 清理临时bitmap
            if (regionBitmap != wallpaperBitmap) {
                regionBitmap.recycle()
            }
            
            return color
        } catch (e: Exception) {
            return Color.parseColor("#424242")
        }
    }

    /**
     * 根据背景色获取适合的前景色（黑色或白色）
     * @param backgroundColor 背景颜色
     * @return 如果背景是亮色返回黑色，如果背景是暗色返回白色
     */
    fun getContrastColor(backgroundColor: Int): Int {
        return if (isDarkColor(backgroundColor)) {
            Color.WHITE
        } else {
            Color.BLACK
        }
    }
    
    /**
     * 根据背景色获取带透明度的对比色
     * @param backgroundColor 背景颜色
     * @param alpha 透明度 (0-255)
     * @return 带透明度的对比色
     */
    fun getContrastColorWithAlpha(backgroundColor: Int, alpha: Int = 255): Int {
        val baseColor = getContrastColor(backgroundColor)
        return Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
    }

    /**
     * 从 Drawable 提取主色调
     */
    fun extractColor(drawable: Drawable?): Int {
        if (drawable == null) {
            return Color.parseColor("#424242") // 默认深灰色
        }

        val bitmap = when (drawable) {
            is BitmapDrawable -> drawable.bitmap
            else -> {
                // 将 Drawable 转换为 Bitmap
                val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 64
                val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 64
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }
        }

        return extractDominantColor(bitmap)
    }

    /**
     * 从 Bitmap 提取主色调
     */
    private fun extractDominantColor(bitmap: Bitmap): Int {
        val scaledBitmap = if (bitmap.width > 64 || bitmap.height > 64) {
            // 缩小图片以提高性能
            Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        } else {
            bitmap
        }

        val colorMap = mutableMapOf<Int, Int>()
        val pixels = IntArray(scaledBitmap.width * scaledBitmap.height)
        scaledBitmap.getPixels(pixels, 0, scaledBitmap.width, 0, 0, 
            scaledBitmap.width, scaledBitmap.height)

        // 统计颜色出现次数（忽略透明和接近白色/黑色的颜色）
        for (pixel in pixels) {
            val alpha = Color.alpha(pixel)
            if (alpha < 128) continue // 忽略透明像素

            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)

            // 忽略接近白色或黑色的颜色
            val brightness = (red + green + blue) / 3
            if (brightness > 240 || brightness < 15) continue

            // 忽略饱和度太低的颜色（灰色）
            val maxChannel = maxOf(red, green, blue)
            val minChannel = minOf(red, green, blue)
            val saturation = if (maxChannel > 0) {
                (maxChannel - minChannel).toFloat() / maxChannel
            } else 0f
            if (saturation < 0.15f) continue

            // 颜色量化，减少颜色种类
            val quantizedColor = quantizeColor(pixel)
            colorMap[quantizedColor] = (colorMap[quantizedColor] ?: 0) + 1
        }

        // 找出出现次数最多的颜色
        val dominantColor = colorMap.maxByOrNull { it.value }?.key

        return if (dominantColor != null) {
            // 调整颜色使其更适合作为背景色
            adjustColorForBackground(dominantColor)
        } else {
            Color.parseColor("#424242") // 默认深灰色
        }
    }

    /**
     * 颜色量化，将相似的颜色归为一类
     */
    private fun quantizeColor(color: Int): Int {
        val step = 32 // 量化步长
        val r = (Color.red(color) / step) * step
        val g = (Color.green(color) / step) * step
        val b = (Color.blue(color) / step) * step
        return Color.rgb(r, g, b)
    }

    /**
     * 调整颜色使其更适合作为背景色
     */
    private fun adjustColorForBackground(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        // 降低亮度，使背景不会太亮
        if (hsv[2] > 0.6f) {
            hsv[2] = 0.5f
        } else if (hsv[2] < 0.3f) {
            hsv[2] = 0.35f
        }

        // 适当降低饱和度，避免过于鲜艳
        if (hsv[1] > 0.8f) {
            hsv[1] = 0.7f
        }

        return Color.HSVToColor(hsv)
    }

    /**
     * 判断颜色是否为深色
     */
    fun isDarkColor(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val brightness = sqrt(
            red * red * 0.299 +
            green * green * 0.587 +
            blue * blue * 0.114
        )
        return brightness < 130
    }
}
