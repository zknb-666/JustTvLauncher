package cf.zknb.tvlauncher.model

/**
 * 应用快捷方式组模型类
 *
 * 表示按分类组织的应用快捷方式组，基于打开次数进行排序
 *
 * @property category 快捷方式组的分类名称
 * @property shortcutList 该组中的快捷方式列表，按打开次数降序排序
 * @property openCount 该组中所有快捷方式的总打开次数，用于组排序
 */
class ShortcutGroup(val category: String, val shortcutList: MutableList<Shortcut>) {
    var openCount = shortcutList.sumOf { it.openCount }
    
    /**
     * 向组中添加快捷方式
     *
     * 更新总打开次数，并根据快捷方式的打开次数将其插入到适当位置
     * 以保持降序排列
     *
     * @param element 要添加到组中的快捷方式
     */
    fun add(element: Shortcut) {
        openCount += element.openCount
        val index = findIndex(element.openCount, 0, shortcutList.size - 1)
        shortcutList.add(index, element)
    }

    /**
     * 根据打开次数查找插入快捷方式的索引
     *
     * 使用递归二分搜索算法高效查找正确的插入位置
     * 以保持按打开次数降序排列
     *
     * @param oc 要插入的快捷方式的打开次数
     * @param leftIndex 搜索范围的左边界
     * @param rightIndex 搜索范围的右边界
     * @return 快捷方式应插入的索引
     */
    private tailrec fun findIndex(oc: Int, leftIndex: Int, rightIndex: Int): Int {
        return when {
            // 如果打开次数大于最左侧元素，插入到左侧
            oc > shortcutList[leftIndex].openCount -> leftIndex
            // 如果打开次数小于最右侧元素，插入到右侧之后
            shortcutList[rightIndex].openCount > oc -> rightIndex + 1
            else -> {
                // 二分搜索查找正确位置
                val middleIndex = (leftIndex + rightIndex) / 2
                when {
                    shortcutList[middleIndex].openCount > oc -> {
                        // 继续在左半部分搜索
                        findIndex(oc, leftIndex, middleIndex)
                    }
                    shortcutList[middleIndex].openCount < oc -> {
                        // 继续在右半部分搜索
                        findIndex(oc, middleIndex + 1, rightIndex)
                    }
                    else -> {
                        // 找到精确匹配，插入到中间
                        middleIndex
                    }
                }
            }
        }
    }
}
