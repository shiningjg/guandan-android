package com.example.guandan.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class GuanDanDomainTest {

    @Test
    fun deck_has_108_cards() {
        val d = Deck.full()
        assertEquals(108, d.size)                                  // 两副 × 54
        assertEquals(54, d.toSet().size)                           // 仅 54 种 (花色,点数) 组合
        assertEquals(8, d.count { it.rank == 2 })                  // 每点：4 花色 × 2 副 = 8
        assertEquals(2, d.count { it.suit == Suit.HEARTS && it.rank == 7 })  // 每 (花色,点) 共 2 张
        assertEquals(2, d.count { it.isSmallJoker })               // 小王 2 张
        assertEquals(2, d.count { it.isBigJoker })                 // 大王 2 张
        // 每种 (花色,点数) 恰好 2 张（两副）
        val maxCopies = d.groupingBy { it }.eachCount().values.maxOrNull()
        assertEquals(2, maxCopies)
    }

    @Test
    fun deal_gives_4x27_no_overlap() {
        val hands = Deck.deal(Random(1))
        assertEquals(4, hands.size)
        hands.forEach { assertEquals(27, it.size) }
        val all = hands.flatten()
        assertEquals(108, all.size)                                // 发满 108 张
        // 每种 (花色,点数) 至多 2 张（不会把同一张重复发两人）
        val maxCopies = all.groupingBy { it }.eachCount().values.maxOrNull()
        assertEquals(2, maxCopies)
        assertEquals(54, all.toSet().size)                         // 恰好覆盖全部 54 种
    }

    @Test
    fun strength_ordering_matches_guandan_rules() {
        val level = 5
        val ace = Card(14, Suit.SPADES)
        val king = Card(13, Suit.SPADES)
        val lvl = Card(level, Suit.HEARTS)   // 打 5，红桃 5 为级牌
        val smallJ = Card(RANK_SMALL_JOKER, Suit.JOKER)
        val bigJ = Card(RANK_BIG_JOKER, Suit.JOKER)

        assertTrue(cardStrength(lvl, level) > cardStrength(ace, level))   // 级牌 > A
        assertTrue(cardStrength(ace, level) > cardStrength(king, level))  // A > K
        assertTrue(cardStrength(smallJ, level) > cardStrength(lvl, level)) // 小王 > 级牌
        assertTrue(cardStrength(bigJ, level) > cardStrength(smallJ, level)) // 大王 > 小王

        // 红桃 5 是逢人配；黑桃 5 不是
        assertTrue(lvl.isWild(level))
        assertFalse(Card(level, Suit.SPADES).isWild(level))
        // 非级牌的花色都不是百搭
        assertFalse(Card(7, Suit.HEARTS).isWild(level))
    }

    @Test
    fun jokers_from_other_suit_not_wild() {
        // 级牌是 5；红桃 5 才算百搭，其它花色 5 不是
        val level = 5
        assertFalse(Card(5, Suit.SPADES).isWild(level))
        assertFalse(Card(5, Suit.CLUBS).isWild(level))
        assertFalse(Card(5, Suit.DIAMONDS).isWild(level))
        assertTrue(Card(5, Suit.HEARTS).isWild(level))
    }

    @Test
    fun sort_for_display_puts_strong_first() {
        val level = 5
        val hand = listOf(
            Card(2, Suit.CLUBS),
            Card(14, Suit.HEARTS),     // A
            Card(5, Suit.HEARTS),      // 级牌（红桃5）
            Card(RANK_SMALL_JOKER, Suit.JOKER),
            Card(RANK_BIG_JOKER, Suit.JOKER),
        )
        val sorted = sortForDisplay(hand, level)
        assertEquals(RANK_BIG_JOKER, sorted[0].rank)
        assertEquals(RANK_SMALL_JOKER, sorted[1].rank)
        assertEquals(5, sorted[2].rank)                 // 级牌排第三（强于 A）
        assertEquals(14, sorted[3].rank)                // A
        assertEquals(2, sorted[4].rank)                 // 2 最弱
    }
}
