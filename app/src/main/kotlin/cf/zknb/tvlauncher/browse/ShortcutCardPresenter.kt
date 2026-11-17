package cf.zknb.tvlauncher.browse

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
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

    private var width = 0
    private var height = 0
    private var iconSize = 0
    private var paddingHorizontal = 0
    private var paddingVertical = 0

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val context = parent.context
        val res = context.resources
        width = res.getDimensionPixelSize(R.dimen.card_width)
        height = res.getDimensionPixelSize(R.dimen.card_height)
        iconSize = res.getDimensionPixelSize(R.dimen.card_icon_size)
        paddingHorizontal = res.getDimensionPixelSize(R.dimen.card_padding_horizontal)
        paddingVertical = res.getDimensionPixelSize(R.dimen.card_padding_vertical)

        val cardView = LayoutInflater.from(context)
            .inflate(R.layout.presenter_shortcut_card, parent, false)
        val binding = PresenterShortcutCardBinding.bind(cardView)

        // Leanback 的 ItemBridgeAdapter 会把 Presenter 里设置的焦点监听链式保留
        // （见其 onCreateViewHolder 中的 mChainedListener），所以这里可以安全地用它
        // 驱动选中白框：焦点变化只改 View 可见性，不新建 Drawable、不触发重排，
        // 遥控器连续移动时没有额外分配。
        cardView.setOnFocusChangeListener { _, hasFocus ->
            binding.focusBorder.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val shortcut = item as? Shortcut ?: return
        val view = viewHolder.view
        val binding = PresenterShortcutCardBinding.bind(view)

        // ViewHolder 被复用时焦点可能已经在它身上，此时 OnFocusChangeListener 不会再次回调，
        // 因此每次绑定都按当前焦点同步一次白框状态。
        binding.focusBorder.visibility = if (view.isFocused) View.VISIBLE else View.INVISIBLE

        when {
            shortcut.banner != null -> {
                // 横幅卡片：图片铺满内容区，不显示图标和文字
                binding.content.setPadding(0, 0, 0, 0)
                binding.content.text = ""
                binding.content.setCompoundDrawables(null, null, null, null)
                shortcut.banner.setBounds(0, 0, width, height)
                binding.content.background = shortcut.banner
            }

            shortcut.icon != null -> {
                // 普通应用：图标在左、名称在右，整体垂直居中；背景色取自图标主色
                binding.content.setPadding(
                    paddingHorizontal, paddingVertical,
                    paddingHorizontal, paddingVertical
                )
                val cardColor = colorCache[shortcut.id]
                    ?: ColorExtractor.extractColor(shortcut.icon)
                        .also { colorCache[shortcut.id] = it }
                binding.content.setBackgroundColor(cardColor)
                binding.content.setTextColor(
                    if (ColorExtractor.isDarkColor(cardColor)) Color.WHITE else Color.BLACK
                )
                shortcut.icon.setBounds(0, 0, iconSize, iconSize)
                // Leanback 横排样式：图标在左、名称在右，整体垂直居中
                binding.content.setCompoundDrawables(shortcut.icon, null, null, null)
                binding.content.text = shortcut.title
            }

            else -> {
                // 既没有横幅也没有图标（异常应用）：给一个中性底色，避免复用时残留上一张卡片
                binding.content.setPadding(
                    paddingHorizontal, paddingVertical,
                    paddingHorizontal, paddingVertical
                )
                binding.content.setCompoundDrawables(null, null, null, null)
                binding.content.setBackgroundColor(Color.argb(120, 235, 235, 235))
                binding.content.setTextColor(Color.BLACK)
                binding.content.text = shortcut.title
            }
        }
        view.contentDescription = shortcut.title
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val binding = PresenterShortcutCardBinding.bind(viewHolder.view)
        binding.focusBorder.visibility = View.INVISIBLE
        binding.content.setCompoundDrawables(null, null, null, null)
        binding.content.background = null
        binding.content.text = ""
        viewHolder.view.contentDescription = null
    }
}
