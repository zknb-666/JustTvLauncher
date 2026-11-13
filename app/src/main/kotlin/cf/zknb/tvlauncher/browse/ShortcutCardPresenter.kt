package cf.zknb.tvlauncher.browse

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import cf.zknb.tvlauncher.R
import cf.zknb.tvlauncher.databinding.PresenterShortcutCardBinding
import cf.zknb.tvlauncher.model.Shortcut
import cf.zknb.tvlauncher.util.ColorExtractor
import java.util.concurrent.ConcurrentHashMap

class ShortcutCardPresenter : Presenter() {

    companion object {
        /**
         * 卡片主色只取决于应用图标。
         * 旧实现在每次 onBindViewHolder（滚动一格、列表重建、焦点变化都会触发）
         * 都新建 Bitmap + Canvas + 4096 像素统计，是桌面连续 GC_FOR_ALLOC 的主要来源。
         * 这里做进程级缓存，同一个包名只算一次。
         */
        private val colorCache = ConcurrentHashMap<String, Int>()

        fun invalidateColors() {
            colorCache.clear()
        }
    }

    private var width = 320
    private var height = 180
    private var iconSize = 60

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val context = parent.context
        width = context.resources.getDimension(R.dimen.card_width).toInt()
        height = context.resources.getDimension(R.dimen.card_height).toInt()
        iconSize = context.resources.getDimension(R.dimen.card_icon_size).toInt()
        val cardView = LayoutInflater.from(context)
            .inflate(R.layout.presenter_shortcut_card, parent, false)
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val shortcut = item as Shortcut
        val binding = PresenterShortcutCardBinding.bind(viewHolder.view)

        if (shortcut.banner != null) {
            binding.content.setPadding(0, 0, 0, 0)
            binding.content.text = ""
            shortcut.banner.setBounds(0, 0, width, height)
            binding.content.background = shortcut.banner
            binding.root.contentDescription = shortcut.title
        } else if (shortcut.icon != null) {
            val paddingHorizontal = binding.content.context.resources
                .getDimensionPixelSize(R.dimen.card_padding_horizontal)
            binding.content.setPadding(paddingHorizontal, 0, paddingHorizontal, 0)

            val cardColor = colorCache[shortcut.id]
                ?: ColorExtractor.extractColor(shortcut.icon).also { colorCache[shortcut.id] = it }
            binding.content.setBackgroundColor(cardColor)

            binding.content.setTextColor(
                if (ColorExtractor.isDarkColor(cardColor)) Color.WHITE else Color.BLACK
            )
            shortcut.icon.setBounds(0, 0, iconSize, iconSize)
            binding.content.setCompoundDrawables(shortcut.icon, null, null, null)
            binding.content.text = shortcut.title
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val binding = PresenterShortcutCardBinding.bind(viewHolder.view)
        binding.content.setCompoundDrawables(null, null, null, null)
        binding.content.background = null
        binding.content.setBackgroundColor(Color.TRANSPARENT)
    }
}
