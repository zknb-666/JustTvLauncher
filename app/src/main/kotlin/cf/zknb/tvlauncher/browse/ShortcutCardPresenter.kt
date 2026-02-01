package cf.zknb.tvlauncher.browse

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import cf.zknb.tvlauncher.R
import cf.zknb.tvlauncher.databinding.PresenterShortcutCardBinding
import cf.zknb.tvlauncher.model.Shortcut
import cf.zknb.tvlauncher.utils.ColorExtractor

class ShortcutCardPresenter : Presenter() {
    private var width = 320
    private var height = 180
    private var iconSize = 60
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val context = parent.context
        width = context.resources.getDimension(R.dimen.card_width).toInt()
        height = context.resources.getDimension(R.dimen.card_height).toInt()
        iconSize = context.resources.getDimension(R.dimen.card_icon_size).toInt()
        val layoutInflater = LayoutInflater.from(context)
        val cardView = layoutInflater.inflate(R.layout.presenter_shortcut_card, parent, false)
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val shortcut = item as Shortcut
        val binding = PresenterShortcutCardBinding.bind(viewHolder.view)
        
        if (shortcut.banner != null) {
            // Banner 卡片不添加动态颜色，使用默认背景
            shortcut.banner.setBounds(0, 0, width, height)
            binding.content.setCompoundDrawables(shortcut.banner, null, null, null)
            binding.content.setBackgroundColor(Color.TRANSPARENT)
            binding.root.contentDescription = shortcut.title
        } else if (shortcut.icon != null) {
            // 从图标提取主色调作为卡片背景色
            val cardColor = ColorExtractor.extractColor(shortcut.icon)
            binding.content.setBackgroundColor(cardColor)
            
            // 根据背景色深浅设置文字颜色
            val textColor = if (ColorExtractor.isDarkColor(cardColor)) {
                Color.WHITE
            } else {
                Color.BLACK
            }
            binding.content.setTextColor(textColor)
            
            shortcut.icon.setBounds(0, 0, iconSize, iconSize)
            binding.content.setCompoundDrawables(shortcut.icon, null, null, null)
            binding.content.text = shortcut.title
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val binding = PresenterShortcutCardBinding.bind(viewHolder.view)
        binding.content.setCompoundDrawables(null, null, null, null)
        binding.content.setBackgroundColor(Color.TRANSPARENT)
    }
}
