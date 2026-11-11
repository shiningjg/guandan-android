package com.example.guandan.domain

/**
 * 合法出牌枚举。
 *
 * - 领出（target==null）：枚举手牌能组成的所有牌型。
 * - 跟牌（target!=null）：仅返回能压过 target 的牌型。
 *
 * 百搭（红桃级牌）在生成时用于：补炸弹、补三带二的对子、补顺子/连对/钢板/同花顺的缺口。
 * 人类玩家也可在 UI 中手动选择任意合法组合（UI 用 [recognize] 校验）。
 *
 * 连续牌型窗口从 1 开始：1 代表「A 作 1 点」，2..14 为自然点数。
 * 因此 A-2-3-4-5 / AA-22-33 / AAA-222 / 同花 A2345 都会生成，
 * 而 Q-K-A-2-3 这类跨 A 循环没有任何窗口能容纳 → 不生成。
 * 级牌按第五条(一)以自然点数插带（红桃级牌已单独作为百搭）。
 */
object MoveGenerator {

    fun legalPlays(hand: List<Card>, target: CardPattern?, level: Int): List<List<Card>> {
        val result = mutableListOf<List<Card>>()
        val wilds = hand.filter { it.isWild(level) }
        val nonWild = hand.filter { !it.isWild(level) }

        result += singles(hand)
        result += pairs(hand, wilds, level)
        result += triples(hand, wilds, level)
        result += tripleWithPairs(hand, wilds, level)
        result += straights(nonWild, wilds, level)
        result += doubleStraights(nonWild, wilds, level)
        result += tripleStraights(nonWild, wilds, level)
        result += flushStraights(nonWild, wilds, level)
        result += bombs(nonWild, wilds, level)
        if (hand.count { it.isBigJoker } >= 2 && hand.count { it.isSmallJoker } >= 2) {
            result += hand.filter { it.isJoker }   // 天王炸
        }

        val cleaned = result.distinctBy { it.sortedBy { c -> c.rank * 100 + c.suit.ordinal } }
            // 丢弃"同一张物理牌被多次计入"的非法组合（仍允许两副牌中两张同点数同花色的合法牌共存）
            .filter { combo ->
                val ids = java.util.IdentityHashMap<Card, Unit>()
                combo.all { ids.put(it, Unit) == null } && recognize(combo, level) != null
            }
        return if (target == null) cleaned
        else cleaned.filter { recognize(it, level)!!.let { p -> beats(target, p) } }
    }

    // ---------------- 基础类型 ----------------

    private fun singles(hand: List<Card>): List<List<Card>> = hand.map { listOf(it) }

    private fun pairs(hand: List<Card>, wilds: List<Card>, level: Int): List<List<Card>> {
        val out = mutableListOf<List<Card>>()
        val byRank = hand.filter { !it.isJoker }.groupBy { it.rank }
        for ((_, cards) in byRank) if (cards.size >= 2) out += cards.take(2)
        // 4.3.2 对子「包括两张大王和两张小王」；5.3.2 对大王>对小王>对级牌。
        // 一大王+一小王牌点不同，不构成对子（只能作为天王炸的一部分）。
        val big = hand.filter { it.isBigJoker }
        val small = hand.filter { it.isSmallJoker }
        if (big.size >= 2) out += big.take(2)
        if (small.size >= 2) out += small.take(2)
        // 百搭 + 单张（合法但低价值，提示/AI 会把它排到最后）
        for (wc in wilds) for (c in hand.filter { !it.isJoker && !it.isWild(level) }) out += listOf(wc, c)
        return out
    }

    private fun triples(hand: List<Card>, wilds: List<Card>, level: Int): List<List<Card>> {
        val out = mutableListOf<List<Card>>()
        val byRank = hand.filter { !it.isJoker && !it.isWild(level) }.groupBy { it.rank }
        for ((_, cards) in byRank) {
            if (cards.size >= 3) out += cards.take(3)
            else if (cards.size == 2 && wilds.isNotEmpty()) out += cards + wilds.first()
            else if (cards.size == 1 && wilds.size >= 2) out += cards + wilds.take(2)
        }
        return out
    }

    private fun tripleWithPairs(hand: List<Card>, wilds: List<Card>, level: Int): List<List<Card>> {
        val out = mutableListOf<List<Card>>()
        val normal = hand.filter { !it.isJoker && !it.isWild(level) }
        val byRank = normal.groupBy { it.rank }
        val big = hand.filter { it.isBigJoker }
        val small = hand.filter { it.isSmallJoker }
        // 三张候选（不含百搭在内计数）
        val tripleCandidates = byRank.filter { it.value.size >= 3 }.map { it.value.take(3) }
        for (triple in tripleCandidates) {
            val rest = normal.toMutableList().also { it.removeAll(triple) }
            // 对子候选：其它点数的对子
            val pairRanks = rest.groupBy { it.rank }.filter { it.value.size >= 2 }
            for ((_, pc) in pairRanks) out += triple + pc.take(2)
            // 对子 = 1 张 + 1 百搭
            if (wilds.isNotEmpty()) for (c in rest) out += triple + listOf(c, wilds.first())
            // 带牌对子可以是「对大王 / 对小王」（4.3.2），不能是混合王对
            if (big.size >= 2) out += triple + big.take(2)
            if (small.size >= 2) out += triple + small.take(2)
        }
        return out
    }

    // ---------------- 顺子/连对/钢板/同花顺 ----------------

    /** 与 [CardPattern.kt] 的 seqRank 保持一致：A 在含 1 的窗口里解释为 1。 */
    private fun seqRankForWindow(card: Card, window: IntRange): Int? {
        if (card.isJoker) return null
        if (card.rank == 14 && 1 in window) return 1
        return card.rank.takeIf { it in window }
    }

    /**
     * 从手牌中挑出恰好填满窗口 [start, start+nRanks-1] 的一手牌；
     * 每个点数需要 perRank 张，不够的用百搭补齐。手牌不足则返回 null。
     */
    private fun buildSequence(
        nonWild: List<Card>, wilds: List<Card>,
        start: Int, nRanks: Int, perRank: Int, suit: Suit?,
    ): List<Card>? {
        val window = start..(start + nRanks - 1)
        val byRank = HashMap<Int, MutableList<Card>>()
        for (c in nonWild) {
            if (c.isJoker) continue
            if (suit != null && c.suit != suit) continue
            val v = seqRankForWindow(c, window) ?: continue
            byRank.getOrPut(v) { mutableListOf() }.add(c)
        }
        val plan = mutableListOf<Card>()
        var usedWilds = 0
        for (r in window) {
            val have = byRank[r].orEmpty()
            val take = minOf(perRank, have.size)
            plan.addAll(have.take(take))
            val need = perRank - take
            if (usedWilds + need > wilds.size) return null
            repeat(need) { plan.add(wilds[usedWilds]); usedWilds++ }
        }
        return plan
    }

    private fun straights(nonWild: List<Card>, wilds: List<Card>, level: Int): List<List<Card>> {
        val out = mutableListOf<List<Card>>()
        // 标准 4.3.7：顺子恰好 5 张；窗口 1..5(A2345) ~ 10..14(10JQKA)
        for (start in 1..10) buildSequence(nonWild, wilds, start, 5, 1, null)?.let { out += it }
        return out
    }

    private fun doubleStraights(nonWild: List<Card>, wilds: List<Card>, level: Int): List<List<Card>> {
        val out = mutableListOf<List<Card>>()
        // 标准 4.3.3：三连对恰好 3 对（6 张）；窗口 1..3(AA2233) ~ 12..14(QQKKAA)
        for (start in 1..12) buildSequence(nonWild, wilds, start, 3, 2, null)?.let { out += it }
        return out
    }

    private fun tripleStraights(nonWild: List<Card>, wilds: List<Card>, level: Int): List<List<Card>> {
        val out = mutableListOf<List<Card>>()
        // 标准 4.3.5：钢板恰好 2 组三同张（6 张）；窗口 1..2(AAA222) ~ 13..14(KKKAAA)
        for (start in 1..13) buildSequence(nonWild, wilds, start, 2, 3, null)?.let { out += it }
        return out
    }

    private fun flushStraights(nonWild: List<Card>, wilds: List<Card>, level: Int): List<List<Card>> {
        val out = mutableListOf<List<Card>>()
        // 标准 4.3.8：同花顺 = 同花色的顺子，恰好五张；百搭可代表该花色
        for (suit in listOf(Suit.SPADES, Suit.HEARTS, Suit.CLUBS, Suit.DIAMONDS)) {
            for (start in 1..10) {
                buildSequence(nonWild, wilds, start, 5, 1, suit)?.let { out += it }
            }
        }
        return out
    }

    // ---------------- 炸弹 ----------------

    private fun bombs(nonWild: List<Card>, wilds: List<Card>, level: Int): List<List<Card>> {
        val out = mutableListOf<List<Card>>()
        val byRank = nonWild.filter { !it.isJoker }.groupBy { it.rank }
        for ((_, cards) in byRank) {
            val c = cards.size
            for (k in 4..(c + wilds.size)) {        // 用 0..wilds.size 张百搭补到 k 张
                val wildNeed = k - c
                if (wildNeed in 0..wilds.size) out += cards.take(k - wildNeed) + wilds.take(wildNeed)
            }
        }
        return out
    }
}
