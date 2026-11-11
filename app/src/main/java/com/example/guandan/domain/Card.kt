package com.example.guandan.domain

/** 花色；王使用 [Suit.JOKER]（无花色）。 */
enum class Suit { SPADES, HEARTS, CLUBS, DIAMONDS, JOKER }

const val RANK_SMALL_JOKER = 16
const val RANK_BIG_JOKER = 17

/**
 * 掼蛋扑克牌（纯 Kotlin，与 UI 无关，可单元测试）。
 *
 * 点数 rank：2..14（A=14）为常规牌；小王=16，大王=17。
 * 级牌（每局不同，由游戏状态持有）不在此固定——它通过 [cardStrength] / [Card.isWild] 动态参与。
 */
data class Card(val rank: Int, val suit: Suit) : Comparable<Card> {
    val isJoker get() = suit == Suit.JOKER
    val isSmallJoker get() = rank == RANK_SMALL_JOKER
    val isBigJoker get() = rank == RANK_BIG_JOKER

    /** 是否红桃级牌（逢人配/百搭）。level 为当前级牌点数。 */
    fun isWild(level: Int): Boolean = suit == Suit.HEARTS && rank == level

    val display: String
        get() = when {
            isBigJoker -> "大王"
            isSmallJoker -> "小王"
            else -> {
                val r = when (rank) {
                    11 -> "J"; 12 -> "Q"; 13 -> "K"; 14 -> "A"
                    else -> rank.toString()
                }
                val s = when (suit) {
                    Suit.SPADES -> "♠"; Suit.HEARTS -> "♥"
                    Suit.CLUBS -> "♣"; else -> "♦"
                }
                s + r
            }
        }

    override fun compareTo(other: Card): Int = rank.compareTo(other.rank)
}

/**
 * 比较强度（弱→强）：2 < 3 < … < 10 < J < Q < K < A < 级牌 < 小王 < 大王。
 * 关键：级牌（无论本身是几）一律排在 A 之上、小王之下。
 */
fun cardStrength(card: Card, level: Int): Int = when {
    card.isBigJoker -> 17
    card.isSmallJoker -> 16
    card.rank == level -> 15
    else -> card.rank          // 2..14（A=14）
}

/** 手牌展示排序：强牌在前（左）。 */
fun sortForDisplay(cards: List<Card>, level: Int): List<Card> =
    cards.sortedWith(
        compareByDescending<Card> { cardStrength(it, level) }
            .thenByDescending { it.suit.ordinal }
    )

/**
 * 两副牌，共 108 张（每副 54：四花色 2..A + 大小王；两副故各 8 张）。
 * 四人掼蛋，每人 27 张。
 */
object Deck {
    fun full(): List<Card> {
        val cards = mutableListOf<Card>()
        repeat(2) {
            for (suit in listOf(Suit.SPADES, Suit.HEARTS, Suit.CLUBS, Suit.DIAMONDS)) {
                for (rank in 2..14) cards.add(Card(rank, suit))
            }
            cards.add(Card(RANK_SMALL_JOKER, Suit.JOKER))
            cards.add(Card(RANK_BIG_JOKER, Suit.JOKER))
        }
        return cards
    }

    /** 洗牌发牌：默认 4 人各 27 张（4×27=108）。rng 可注入以便测试。 */
    fun deal(rng: java.util.Random, players: Int = 4, perPlayer: Int = 27): List<List<Card>> {
        val all = full().shuffled(rng)
        return List(players) { i ->
            all.subList(i * perPlayer, (i + 1) * perPlayer).sortedDescending()
        }
    }
}
