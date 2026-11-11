package com.example.guandan.domain

/**
 * 记牌器计数（纯函数，便于单元测试）。
 *
 * 掼蛋用两副牌共 108 张：每个点数 2..14 各 8 张（4 花色 × 2 副），大王 / 小王各 2 张。
 * 与斗地主同口径，显示的是「全场 − 已出牌 − 自己手牌」= **其他三家手里可能还有的张数**，
 * 而不是「牌堆里未发的牌」（掼蛋开局就把 108 张全部发完）。
 *
 * 百搭按**实体牌**统计：红桃级牌打出时仍算它本身的点数，不做「所替代点数」折算，
 * 否则记牌器会与手牌 / 出牌记录对不上，反而误导玩家。
 */
data class CounterCell(
    val rank: Int,
    val label: String,
    val left: Int,
    val isLevel: Boolean,
)

/** 各点数剩余张数，按牌力从大到小（大王 → 小王 → A → … → 2）排好序供 UI 直接渲染。 */
fun counterCells(played: List<Card>, hand: List<Card>, level: Int): List<CounterCell> {
    val remain = Deck.full().groupingBy { it.rank }.eachCount().toMutableMap()
    played.forEach { remain[it.rank] = (remain[it.rank] ?: 0) - 1 }
    hand.forEach { remain[it.rank] = (remain[it.rank] ?: 0) - 1 }
    return (listOf(RANK_BIG_JOKER, RANK_SMALL_JOKER) + (14 downTo 2)).map { r ->
        CounterCell(
            rank = r,
            label = when (r) {
                RANK_BIG_JOKER -> "大王"
                RANK_SMALL_JOKER -> "小王"
                11 -> "J"; 12 -> "Q"; 13 -> "K"; 14 -> "A"
                else -> r.toString()
            },
            left = (remain[r] ?: 0).coerceAtLeast(0),
            isLevel = r == level,
        )
    }
}

/** 红桃级牌（逢人配）剩余张数：全场 2 张减去已出与自己手里的。 */
fun wildRemaining(played: List<Card>, hand: List<Card>, level: Int): Int =
    (2 - played.count { it.isWild(level) } - hand.count { it.isWild(level) }).coerceAtLeast(0)

/** 本局已明牌公开的贡 / 还牌（供记牌器提示「已知牌」）。 */
fun disclosedCards(plan: TributePlan?): List<Card> =
    plan?.exchanges?.flatMap { listOf(it.give, it.back) } ?: emptyList()
