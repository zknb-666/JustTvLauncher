package cf.zknb.tvlauncher.browse

import android.util.Log
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import cf.zknb.tvlauncher.model.ShortcutGroup

class BrowseAdapter(shortcutGroupList: List<ShortcutGroup>) :
    ArrayObjectAdapter(createRowPresenter()) {
    init {
        addShortcutGroupList(shortcutGroupList)
    }

    private fun addShortcutGroupList(shortcutGroupList: List<ShortcutGroup>) {
        val cardPresenter = ShortcutCardPresenter()
        shortcutGroupList.forEach {
            Log.v(TAG, "${it.category}: ${it.openCount}")
            val listRowAdapter = ArrayObjectAdapter(cardPresenter)
            listRowAdapter.addAll(0, it.shortcutList)
            val headerItem = HeaderItem(it.category)
            add(ListRow(headerItem, listRowAdapter))
        }
    }

    companion object {
        private const val TAG = "BrowseAdapter"

        /**
         * 选中反馈统一交给卡片自己的白色边框：
         * - 关闭默认的 ZOOM_FACTOR_MEDIUM 放大，否则放大后的白框会压到相邻卡片；
         * - 关闭阴影与「行选中效果」：Leanback 默认会给非当前选中行的每个卡片
         *   画一层半透明黑色遮罩（applySelectLevelToChild → setOverlayColor），
         *   在亮色壁纸上就是「未激活的行出现黑框」。关闭后 needsOverlay=false，
         *   连 ShadowOverlayContainer 包装层都不再创建，卡片也不再被额外覆盖绘制。
         */
        private fun createRowPresenter(): ListRowPresenter =
            ListRowPresenter(FocusHighlight.ZOOM_FACTOR_NONE).apply {
                shadowEnabled = false
                setSelectEffectEnabled(false)
            }
    }
}
