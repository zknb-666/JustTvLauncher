package cf.zknb.tvlauncher.browse

import android.app.Application
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import cf.zknb.tvlauncher.R
import cf.zknb.tvlauncher.model.Shortcut
import cf.zknb.tvlauncher.model.ShortcutGroup
import cf.zknb.tvlauncher.repository.AppPreferencesManager
import cf.zknb.tvlauncher.repository.ShortcutRepository
import cf.zknb.tvlauncher.tvinput.TvSourceController

/**
 * 浏览界面的 ViewModel
 *
 * 性能优化：
 * 1. loadShortcutGroupList() 原本在主线程执行 queryIntentActivities + 逐个 loadIcon（解码
 *    几十个应用图标 Drawable）+ SharedPreferences 读取，是冷启动 3~5 秒和返回桌面卡顿的
 *    主要原因。现在整体放到后台线程，通过 postValue 回主线程。
 * 2. 用单飞标志避免广播/onResume 叠加触发并发重复加载。
 * 3. incrementOpenCount() 不再触发整张列表重建（排序在下次真正刷新时生效），
 *    避免点击应用图标的瞬间把整个 BrowseAdapter 推倒重来。
 */
class BrowseViewModel(application: Application) : AndroidViewModel(application) {
    private val shortcutRepository = ShortcutRepository(application)
    private val prefsManager = AppPreferencesManager.getInstance(application)

    companion object {
        private const val TAG = "BrowseViewModel"

        const val TOOL_TV_INPUT = "tool.tv_input"
        const val TOOL_MEMORY_CLEAN = "tool.memory_clean"
    }

    val browseContent = MutableLiveData<List<ShortcutGroup>>()

    @Volatile
    private var loading = false

    init {
        loadShortcutGroupList()
    }

    fun loadShortcutGroupList() {
        if (loading) return
        loading = true
        Thread({
            try {
                browseContent.postValue(buildShortcutGroups())
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load shortcut groups", t)
            } finally {
                loading = false
            }
        }, "shortcut-loader").start()
    }

    /** 在后台线程构建分组数据（含 PackageManager 查询与图标解码） */
    private fun buildShortcutGroups(): List<ShortcutGroup> {
        val shortcutGroupByCategory = HashMap<String, ShortcutGroup>()
        val hiddenApps = prefsManager.getHiddenApps()
        val favoriteApps = prefsManager.getFavoriteApps()

        val app = getApplication<Application>()
        val titleFavorites = app.getString(R.string.title_favorites)
        val titleApps = app.getString(R.string.title_apps)
        val titleSystem = app.getString(R.string.title_system)
        val titleTools = app.getString(R.string.title_tools)

        shortcutRepository.load().forEach {
            if (hiddenApps.contains(it.id)) return@forEach
            val category = it.category
            val group = shortcutGroupByCategory[category]
            if (group != null) {
                group.add(it)
            } else {
                shortcutGroupByCategory[category] = ShortcutGroup(category, mutableListOf(it))
            }
        }

        if (favoriteApps.isNotEmpty()) {
            val favoriteShortcuts = mutableListOf<Shortcut>()
            shortcutGroupByCategory.values.forEach { group ->
                favoriteShortcuts.addAll(group.shortcutList.filter { favoriteApps.contains(it.id) })
            }
            if (favoriteShortcuts.isNotEmpty()) {
                shortcutGroupByCategory[titleFavorites] =
                    ShortcutGroup(titleFavorites, favoriteShortcuts)
            }
        }

        val toolShortcuts = createToolShortcuts()
        if (toolShortcuts.isNotEmpty()) {
            val existing = shortcutGroupByCategory[titleTools]
            if (existing != null) {
                existing.shortcutList.addAll(0, toolShortcuts)
            } else {
                shortcutGroupByCategory[titleTools] = ShortcutGroup(titleTools, toolShortcuts)
            }
        }

        val orderedList = mutableListOf<ShortcutGroup>()
        shortcutGroupByCategory[titleFavorites]?.let { orderedList.add(it) }
        shortcutGroupByCategory[titleApps]?.let { orderedList.add(it) }
        shortcutGroupByCategory[titleSystem]?.let { orderedList.add(it) }
        shortcutGroupByCategory[titleTools]?.let { orderedList.add(it) }

        orderedList.addAll(
            shortcutGroupByCategory.values
                .filter { it.category !in listOf(titleFavorites, titleApps, titleSystem, titleTools) }
                .sortedByDescending { it.openCount }
        )
        return orderedList
    }

    private fun createToolShortcuts(): MutableList<Shortcut> {
        val context = getApplication<Application>()
        val toolShortcuts = mutableListOf<Shortcut>()
        val titleTools = context.getString(R.string.title_tools)

        if (TvSourceController.isDeviceSupported(context)) {
            val tvInputShortcut = Shortcut(
                TOOL_TV_INPUT,
                context.getString(R.string.tool_tv_input),
                ContextCompat.getDrawable(context, R.drawable.ic_tvinput),
                null
            ).apply { category = titleTools }
            toolShortcuts.add(tvInputShortcut)
        }

        val memoryCleanShortcut = Shortcut(
            TOOL_MEMORY_CLEAN,
            context.getString(R.string.tool_memory_clean),
            ContextCompat.getDrawable(context, R.drawable.ic_cleaner),
            null
        ).apply { category = titleTools }
        toolShortcuts.add(memoryCleanShortcut)

        return toolShortcuts
    }

    /** 只持久化计数，不重建 UI；新的排序在下一次真正刷新时生效 */
    fun incrementOpenCount(shortcut: Shortcut) {
        Log.v(TAG, "${shortcut.id}: ${shortcut.openCount} + 1")
        shortcut.openCount++
        shortcutRepository.updateOpenCount(shortcut)
    }

    fun findPosition(shortcut: Shortcut): Pair<Int, Int> {
        val groups = browseContent.value.orEmpty()
        val x = groups.indexOfFirst { it.category == shortcut.category }
        if (x < 0) return Pair(0, 0)
        val y = groups[x].shortcutList.indexOf(shortcut)
        Log.v(TAG, "${shortcut.id}: ($x, $y)")
        return Pair(x, if (y < 0) 0 else y)
    }

    /**
     * 按 id 查找快捷方式在浏览内容中的位置。
     *
     * 列表每次刷新都会重建 Shortcut 对象，因此不能用对象引用（indexOf）查找，
     * 必须按 id 匹配——返回桌面恢复焦点时用的就是这个方法。
     *
     * @param id 应用包名（即快捷方式 id）
     * @return 位置 Pair（组索引，组内索引），找不到返回 null
     */
    fun findPositionById(id: String): Pair<Int, Int>? {
        val groups = browseContent.value.orEmpty()
        val x = groups.indexOfFirst { g -> g.shortcutList.any { it.id == id } }
        if (x < 0) return null
        val y = groups[x].shortcutList.indexOfFirst { it.id == id }
        Log.v(TAG, "$id: ($x, $y)")
        return Pair(x, if (y < 0) 0 else y)
    }

    fun removePackage(packageName: String) {
        shortcutRepository.deleteById(packageName)
        loadShortcutGroupList()
    }
}
