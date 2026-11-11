package com.example.guandan.domain

/**
 * 手牌横向滑动选牌的索引换算（纯 Kotlin，不依赖 Compose，便于单测）。
 *
 * 手牌在屏幕上横向等距排布，第 i 张牌的左边界为 `i * slot`，
 * 其中 `slot = 牌宽 + 间距`（间距为负即重叠）。因为后一张牌压在前一张之上，
 * 落在重叠区的手指命中的应是「后一张」，`floor(x / slot)` 正好给出这个索引。
 */
object HandSelection {

    /** 手指横坐标 [xPx]（相对手牌行内容区左边界）落在第几张牌上；越界自动钳到首尾。 */
    fun indexAt(xPx: Float, slotPx: Float, count: Int): Int {
        if (count <= 0) return -1
        if (slotPx <= 0f) return 0
        return (xPx / slotPx).toInt().coerceIn(0, count - 1)
    }

    /** 从起点 [anchor] 滑到 [current] 所覆盖的连续区间（含两端），非法输入返回空区间。 */
    fun indicesBetween(anchor: Int, current: Int, count: Int): IntRange {
        if (count <= 0 || anchor !in 0 until count || current !in 0 until count) return IntRange.EMPTY
        return minOf(anchor, current)..maxOf(anchor, current)
    }
}
