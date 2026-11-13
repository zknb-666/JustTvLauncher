package cf.zknb.tvlauncher.repository

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import cf.zknb.tvlauncher.model.Shortcut
import java.util.concurrent.ConcurrentHashMap

class ShortcutRepository(private val context: Context) {

    companion object {
        /**
         * 应用图标 / 名称在设备生命周期内几乎不变。
         * 旧实现每次刷新列表都逐个 loadIcon（资源解码 + binder）重新生成 Drawable，
         * 在双核 A9 上会造成明显的 GC 抖动。这里做进程级缓存。
         */
        private val iconCache = ConcurrentHashMap<String, Drawable>()
        private val labelCache = ConcurrentHashMap<String, String>()

        /** 应用安装/更新后调用，让图标缓存失效 */
        fun invalidateCache() {
            iconCache.clear()
            labelCache.clear()
        }
    }

    private val categoryData = CategoryRepository.getInstance(context)
    private val openCountData = OpenCountRepository.getInstance(context)

    fun load(): Set<Shortcut> {
        val all = load(Intent.CATEGORY_LAUNCHER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            all.addAll(load(Intent.CATEGORY_LEANBACK_LAUNCHER))
        }
        return all
    }

    private fun load(category: String): MutableSet<Shortcut> {
        val all = mutableSetOf<Shortcut>()
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(category)
        val activityList = packageManager.queryIntentActivities(intent, 0)
        for (it in activityList) {
            val packageName = it.activityInfo.packageName
            if (packageName == context.packageName) continue

            val label = labelCache[packageName]
                ?: it.activityInfo.loadLabel(packageManager).toString()
                    .also { labelCache[packageName] = it }

            // Android 4.2 (API 17) 没有 loadBanner，这里恒为 null
            val banner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                it.activityInfo.loadBanner(packageManager)
            } else {
                null
            }

            val icon = if (banner == null) {
                iconCache[packageName]
                    ?: it.activityInfo.loadIcon(packageManager)
                        ?.also { iconCache[packageName] = it }
            } else {
                null
            }

            val shortcut = Shortcut(packageName, label, icon, banner)
            shortcut.category = categoryData.query(shortcut.id) ?: categoryData.load(packageName)
            shortcut.openCount = openCountData.query(shortcut.id)
            all.add(shortcut)
        }
        return all
    }

    fun updateOpenCount(shortcut: Shortcut) {
        openCountData.update(shortcut.id, shortcut.openCount)
    }

    fun deleteById(id: String) {
        categoryData.delete(id)
        openCountData.delete(id)
    }
}
