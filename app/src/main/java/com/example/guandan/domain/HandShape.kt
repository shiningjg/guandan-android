package com.example.guandan.domain

/**
 * 手牌「成型牌组」保护与百搭价值评估，供轮循提示（GameViewModel）与 AI（HeuristicAi）共用。
 *
 * 背景：合法出牌枚举会列出「从一手完整牌组里抽走几张」的组合，例如把 4 张炸弹拆成
 * 单张 / 对子去压牌，或把红桃级牌（逢人配）拿去凑一个 3 点小对子。
 * 这些在规则上都合法，但属于明显的低级失误，提示与电脑都不应主动推荐。
 *
 * **重要边界（规范 5.7）**：红桃级牌是百搭，「可以和任意牌点的花色牌搭配成合理牌型使用」。
 * 因此由百搭撑起来的对子 / 三张 / 炸弹**不是固定的成型牌组**，随时可以拆开，
 * 让百搭去替代别的牌（补顺子、补同花顺、补更大的炸弹）。
 * [breakPenalty] 在统计成型组时把百搭完全排除，正是为了实现这一点：
 * 只有「纯粹由自然牌构成」的对子 / 三张 / 炸弹才受保护。
 * 百搭撑起的牌组仍会由 [wastesWild] 做「不主动推荐」的软排序，但不会被判为拆牌。
 */
object HandShape {

    /**
     * 候选 [cards] 拆散了多少个「已成型」的同点数固定组。数值越大越糟，0 表示没拆。
     *
     * 只统计非王、非百搭的自然牌，按点数分组：
     * - 该点数共 4 张及以上（炸弹），却只用掉 1~3 张 → 权重 3；
     * - 恰好 3 张（三张），却只用掉 1~2 张 → 权重 2；
     * - 恰好 2 张（对子），却只出 1 张 → 权重 1；
     * - 整组端走（如 4 张一起出成炸弹、3 张一起出成三张）→ 不罚。
     *
     * 百搭（红桃级牌）不计入 total / used：它撑起的牌组不算固定组，允许拆用（规范 5.7）。
     */
    fun breakPenalty(hand: List<Card>, cards: List<Card>, level: Int): Int {
        val total = hand.filter { !it.isJoker && !it.isWild(level) }
            .groupingBy { it.rank }.eachCount()
        val used = cards.filter { !it.isJoker && !it.isWild(level) }
            .groupingBy { it.rank }.eachCount()
        var penalty = 0
        for ((rank, u) in used) {
            val t = total[rank] ?: continue
            penalty += when {
                t >= 4 && u < 4 -> 3    // 拆炸弹（含 5 张以上超大炸弹）
                t == 3 && u < 3 -> 2    // 拆三张
                t == 2 && u < 2 -> 1    // 拆对子
                else -> 0
            }
        }
        return penalty
    }

    /**
     * 候选是否把百搭（红桃级牌）浪费在低价值牌型上。
     * 百搭应优先用于炸弹 / 同花顺 / 顺子 / 连对 / 钢板这类高价值组合，
     * 而不是单张、小对子、小三张、三带二里补对子。
     *
     * 例外：百搭凑成的 AA / 对级牌 / 三 A 等本身已是最高档的普通牌（mainRank >= 14），
     * 不算浪费；否则「百搭 + A」这种强对子会被错误地压到最后。
     */
    fun wastesWild(cards: List<Card>, pattern: CardPattern, level: Int): Boolean {
        if (cards.none { it.isWild(level) }) return false
        if (pattern.type in setOf(PatternType.PAIR, PatternType.TRIPLE) && pattern.mainRank >= 14) {
            return false
        }
        return pattern.type in setOf(
            PatternType.SINGLE,
            PatternType.PAIR,
            PatternType.TRIPLE,
            PatternType.TRIPLE_WITH_PAIR,
        )
    }

    /**
     * 三带二里「被带走的那个对子」的牌力强度，用于抑制「三张带大对」这种浪费。
     *
     * 规范 5.3.6 三带二只比较三同张的牌点（示例 KKK22 > QQQ33），带牌对子**不影响大小**，
     * 所以把 AA / 对级牌 / 对 K 当带牌甩出去毫无收益。返回 0 表示没有带牌。
     *
     * 取出现次数最多的点数为主三张（百搭补三张时自然牌只有 2 张，仍是最多），
     * 其余牌里牌力最大的一张即为带牌强度；百搭当带牌会得到 15 的高惩罚。
     */
    fun kickerStrength(cards: List<Card>, pattern: CardPattern, level: Int): Int {
        if (pattern.type != PatternType.TRIPLE_WITH_PAIR) return 0
        val main = cards.groupBy { it.rank }.maxByOrNull { it.value.size }?.key ?: return 0
        return cards.filter { it.rank != main }
            .maxOfOrNull { cardStrength(it, level) } ?: 0
    }
}
