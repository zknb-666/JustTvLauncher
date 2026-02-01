package cf.zknb.tvlauncher.model

import android.graphics.drawable.Drawable

/**
 * 应用快捷方式模型类
 *
 * 表示TV启动器中的应用快捷方式，包含应用的基本信息和使用统计数据
 *
 * @property id 应用的包名，用作唯一标识符
 * @property title 应用的显示名称
 * @property icon 应用的图标
 * @property banner 应用的横幅图片
 * @property category 应用的分类
 * @property openCount 应用的打开次数，用于排序和推荐
 */
class Shortcut(
    val id: String,
    val title: String,
    val icon: Drawable?,
    val banner: Drawable?
) {
    lateinit var category: String
    var openCount: Int = 0
    
    /**
     * 比较两个Shortcut对象是否相等
     *
     * 仅比较应用包名（id），因为它是唯一标识符
     *
     * @param other 要比较的对象
     * @return 如果两个对象表示同一个应用，则返回true，否则返回false
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Shortcut
        if (id != other.id) return false
        return true
    }

    /**
     * 为Shortcut对象生成哈希码
     *
     * 仅使用应用包名（id）生成哈希码，与equals方法保持一致
     *
     * @return Shortcut对象的哈希码
     */
    override fun hashCode(): Int {
        return id.hashCode()
    }
}