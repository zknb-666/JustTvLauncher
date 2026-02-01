package cf.zknb.tvlauncher.browse

import android.app.Application
import android.graphics.drawable.ColorDrawable
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
 * 浏览界面的ViewModel
 *
 * 管理TV启动器主浏览界面的数据和业务逻辑
 * 包括加载快捷方式组、处理应用启动和更新使用统计数据
 *
 * @param application 应用上下文
 */
class BrowseViewModel(application: Application) : AndroidViewModel(application) {
    private val shortcutRepository = ShortcutRepository(application)
    private val prefsManager = AppPreferencesManager.getInstance(application)
    
    companion object {
        private const val TAG = "BrowseViewModel"
        
        // 工具卡片ID
        const val TOOL_TV_INPUT = "tool.tv_input"
        const val TOOL_MEMORY_CLEAN = "tool.memory_clean"
    }
    
    /**
     * 包含快捷方式组列表的LiveData，按分类组织并按打开次数排序
     */
    val browseContent = MutableLiveData<List<ShortcutGroup>>()

    init {
        loadShortcutGroupList()
    }

    /**
     * 从仓库加载快捷方式组并按分类组织
     *
     * 按分类对快捷方式进行分组，过滤隐藏的应用
     * 并按指定顺序排列：收藏、应用、系统应用、工具
     */
    fun loadShortcutGroupList() {
        val shortcutGroupByCategory = HashMap<String, ShortcutGroup>()
        val hiddenApps = prefsManager.getHiddenApps()
        val favoriteApps = prefsManager.getFavoriteApps()
        
        val titleFavorites = getApplication<Application>().getString(R.string.title_favorites)
        val titleApps = getApplication<Application>().getString(R.string.title_apps)
        val titleSystem = getApplication<Application>().getString(R.string.title_system)
        val titleTools = getApplication<Application>().getString(R.string.title_tools)
        
        // 遍历所有快捷方式并按分类分组，过滤掉隐藏的应用
        shortcutRepository.load().forEach {
            // 跳过隐藏的应用
            if (hiddenApps.contains(it.id)) {
                return@forEach
            }
            
            val category = it.category
            if (shortcutGroupByCategory.containsKey(category)) {
                // 将快捷方式添加到现有组
                shortcutGroupByCategory[category]!!.add(it)
            } else {
                // 为此分类创建新组
                val shortcutGroup = ShortcutGroup(category, mutableListOf(it))
                shortcutGroupByCategory[category] = shortcutGroup
            }
        }
        
        // 如果有收藏的应用，创建收藏分类
        val favoriteShortcuts = mutableListOf<Shortcut>()
        if (favoriteApps.isNotEmpty()) {
            // 从各分类中提取收藏的应用
            shortcutGroupByCategory.values.forEach { group ->
                val favorites = group.shortcutList.filter { favoriteApps.contains(it.id) }
                favoriteShortcuts.addAll(favorites)
            }
            
            // 如果找到收藏的应用，创建收藏组
            if (favoriteShortcuts.isNotEmpty()) {
                val favoriteGroup = ShortcutGroup(titleFavorites, favoriteShortcuts)
                shortcutGroupByCategory[titleFavorites] = favoriteGroup
            }
        }
        
        // 在工具组中添加工具卡片（TV信号源等）
        val toolShortcuts = createToolShortcuts()
        if (toolShortcuts.isNotEmpty()) {
            if (shortcutGroupByCategory.containsKey(titleTools)) {
                // 将工具卡片添加到现有工具组的开头
                shortcutGroupByCategory[titleTools]!!.shortcutList.addAll(0, toolShortcuts)
            } else {
                // 创建新的工具组
                val toolGroup = ShortcutGroup(titleTools, toolShortcuts)
                shortcutGroupByCategory[titleTools] = toolGroup
            }
        }
        
        // 按指定顺序排列分类：收藏、应用、系统应用、工具
        val orderedList = mutableListOf<ShortcutGroup>()
        
        // 1. 收藏（如果有）
        shortcutGroupByCategory[titleFavorites]?.let { orderedList.add(it) }
        
        // 2. 应用（如果有）
        shortcutGroupByCategory[titleApps]?.let { orderedList.add(it) }
        
        // 3. 系统应用（总是有）
        shortcutGroupByCategory[titleSystem]?.let { orderedList.add(it) }
        
        // 4. 工具（如果有）
        shortcutGroupByCategory[titleTools]?.let { orderedList.add(it) }
        
        // 添加其他未明确指定的分类（按打开次数排序）
        val remainingGroups = shortcutGroupByCategory.values
            .filter { it.category !in listOf(titleFavorites, titleApps, titleSystem, titleTools) }
            .sortedByDescending { it.openCount }
        orderedList.addAll(remainingGroups)
        
        browseContent.postValue(orderedList)
    }
    
    /**
     * 创建工具快捷方式列表
     */
    private fun createToolShortcuts(): MutableList<Shortcut> {
        val context = getApplication<Application>()
        val toolShortcuts = mutableListOf<Shortcut>()
        val titleTools = context.getString(R.string.title_tools)
        
        // TV信号源工具卡片 - 仅在设备支持时添加
        if (TvSourceController.isDeviceSupported(context)) {
            val tvInputIcon = ContextCompat.getDrawable(context, R.drawable.ic_tvinput)
            val tvInputShortcut = Shortcut(
                TOOL_TV_INPUT,
                context.getString(R.string.tool_tv_input),
                tvInputIcon,
                null
            ).apply {
                category = titleTools
            }
            toolShortcuts.add(tvInputShortcut)
        }
        
        // 内存清理工具卡片
        val memoryCleanIcon = ContextCompat.getDrawable(context, R.drawable.ic_cleaner)
        val memoryCleanShortcut = Shortcut(
            TOOL_MEMORY_CLEAN,
            context.getString(R.string.tool_memory_clean),
            memoryCleanIcon,
            null
        ).apply {
            category = titleTools
        }
        toolShortcuts.add(memoryCleanShortcut)
        
        return toolShortcuts
    }

    /**
     * 增加快捷方式的打开次数并更新仓库
     *
     * @param shortcut 要增加打开次数的快捷方式
     */
    fun incrementOpenCount(shortcut: Shortcut) {
        Log.v(TAG, "${shortcut.id}: ${shortcut.openCount} + 1")
        shortcut.openCount++
        shortcutRepository.updateOpenCount(shortcut)
        loadShortcutGroupList() // 刷新快捷方式组以反映更新的打开次数
    }

    /**
     * 查找快捷方式在浏览内容中的位置
     *
     * @param shortcut 要查找的快捷方式
     * @return 表示位置的Pair（组索引，组内快捷方式索引）
     */
    fun findPosition(shortcut: Shortcut): Pair<Int, Int> {
        val shortcutGroupList = browseContent.value!!
        val x = shortcutGroupList.indexOfFirst { it.category == shortcut.category }
        val y = shortcutGroupList[x].shortcutList.indexOf(shortcut)
        Log.v(TAG, "${shortcut.id}: ($x, $y)")
        return Pair(x, y)
    }

    /**
     * 从仓库中移除包并刷新快捷方式组
     *
     * @param packageName 要移除的应用包名
     */
    fun removePackage(packageName: String) {
        shortcutRepository.deleteById(packageName)
        loadShortcutGroupList() // 刷新快捷方式组以反映移除操作
    }
}