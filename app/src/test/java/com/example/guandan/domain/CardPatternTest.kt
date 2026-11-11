package com.example.guandan.domain

import org.junit.Assert.*
import org.junit.Test

class CardPatternTest {

    private val level = 5  // 打 5，红桃5为百搭

    private fun c(rank: Int, suit: Suit) = Card(rank, suit)
    private fun sp(r: Int) = c(r, Suit.SPADES)
    private fun he(r: Int) = c(r, Suit.HEARTS)   // 红桃 = 百搭（当 rank==level）
    private fun cl(r: Int) = c(r, Suit.CLUBS)
    private fun di(r: Int) = c(r, Suit.DIAMONDS)
    private fun bigJ() = Card(RANK_BIG_JOKER, Suit.JOKER)
    private fun smallJ() = Card(RANK_SMALL_JOKER, Suit.JOKER)
    private fun wild() = he(level)               // 红桃5 = 百搭

    @Test
    fun single_pair_triple() {
        assertEquals(PatternType.SINGLE, recognize(listOf(sp(7)), level)!!.type)
        assertEquals(PatternType.PAIR, recognize(listOf(sp(7), cl(7)), level)!!.type)
        // 4.3.2「对子：两张牌点相同的牌，包括两张大王和两张小王」：
        // 对大王 / 对小王各自成立，一大王+一小王牌点不同，不是对子。
        assertEquals(PatternType.PAIR, recognize(listOf(bigJ(), bigJ()), level)!!.type)
        assertEquals(PatternType.PAIR, recognize(listOf(smallJ(), smallJ()), level)!!.type)
        assertNull(recognize(listOf(bigJ(), smallJ()), level))
        // 百搭+单张 = 对子
        assertEquals(PatternType.PAIR, recognize(listOf(wild(), sp(9)), level)!!.type)
        assertEquals(PatternType.TRIPLE, recognize(listOf(sp(8), cl(8), di(8)), level)!!.type)
        // 百搭补三张
        assertEquals(PatternType.TRIPLE, recognize(listOf(sp(8), cl(8), wild()), level)!!.type)
    }

    @Test
    fun triple_with_pair() {
        val p = recognize(listOf(sp(8), cl(8), di(8), sp(3), he(3)), level)
        assertEquals(PatternType.TRIPLE_WITH_PAIR, p!!.type)
        assertEquals(cardStrength(sp(8), level), p.mainRank)
        // 百搭作对子的一部分
        val p2 = recognize(listOf(sp(8), cl(8), di(8), sp(3), wild()), level)
        assertEquals(PatternType.TRIPLE_WITH_PAIR, p2!!.type)
        // 333 + 百搭 + 9 = 三带二（百搭与 9 作对子）
        val p3 = recognize(listOf(sp(8), cl(8), di(8), wild(), sp(9)), level)
        assertEquals(PatternType.TRIPLE_WITH_PAIR, p3!!.type)
        // 333 + 2百搭 实为 5 张炸弹（更强），识别优先返回炸弹
        val b3 = recognize(listOf(sp(8), cl(8), di(8), wild(), wild()), level)
        assertEquals(PatternType.BOMB, b3!!.type)
    }

    @Test
    fun straight_and_flush() {
        // 普通顺子：混合花色 3-7
        val s = recognize(listOf(sp(3), he(4), cl(5), di(6), sp(7)), level)
        assertEquals(PatternType.STRAIGHT, s!!.type)
        assertEquals(7, s.mainRank)
        // 百搭补缺口（混合花色，不能成同花）：3 4 5 6 + 百搭 = 3-7 顺子
        val s2 = recognize(listOf(sp(3), he(4), cl(5), di(6), wild()), level)
        assertEquals(PatternType.STRAIGHT, s2!!.type)
        assertEquals(7, s2.mainRank)
        // 同花顺（同花色且连续）—— 比普通顺子更强，识别优先返回同花顺
        val f = recognize(listOf(sp(3), sp(4), sp(5), sp(6), sp(7)), level)
        assertEquals(PatternType.FLUSH_STRAIGHT, f!!.type)
        // 标准第五条(一)：级牌按牌点插带，2 可以入顺（混合花色才是普通顺子）；王不能入顺
        assertEquals(PatternType.STRAIGHT,
            recognize(listOf(sp(2), he(3), cl(4), di(5), sp(6)), level)!!.type)
        assertNull(recognize(listOf(sp(3), he(4), cl(5), di(6), bigJ()), level))
    }

    @Test
    fun double_and_triple_straight() {
        // 连对 33 44 55
        val d = recognize(listOf(sp(3), cl(3), sp(4), cl(4), sp(5), cl(5)), level)
        assertEquals(PatternType.DOUBLE_STRAIGHT, d!!.type)
        assertEquals(5, d.mainRank)
        // 钢板 333 444
        val t = recognize(listOf(sp(3), cl(3), di(3), sp(4), cl(4), di(4)), level)
        assertEquals(PatternType.TRIPLE_STRAIGHT, t!!.type)
        assertEquals(4, t.mainRank)
    }

    @Test
    fun bomb_and_four_kings() {
        // 普通炸弹 4 张同点
        val b = recognize(listOf(sp(9), cl(9), di(9), he(9)), level)
        assertEquals(PatternType.BOMB, b!!.type)
        // 百搭补炸弹：3张9 + 百搭
        val b2 = recognize(listOf(sp(9), cl(9), di(9), wild()), level)
        assertEquals(PatternType.BOMB, b2!!.type)
        // 天王炸
        val k = recognize(listOf(bigJ(), bigJ(), smallJ(), smallJ()), level)
        assertEquals(PatternType.FOUR_KINGS, k!!.type)
    }

    @Test
    fun beats_hierarchy() {
        val singleHi = recognize(listOf(sp(7)), level)!!
        val singleLo = recognize(listOf(sp(6)), level)!!
        assertTrue(beats(singleLo, singleHi))
        assertFalse(beats(singleHi, singleLo))

        val bomb = recognize(listOf(sp(3), cl(3), di(3), he(3)), level)!!
        assertTrue(beats(singleHi, bomb))      // 炸弹压单张
        assertFalse(beats(bomb, singleHi))    // 单张压不了炸弹

        val flush = recognize(listOf(he(3), he(4), he(5), he(6), he(7)), level)!!
        assertTrue(beats(bomb, flush))         // 同花顺压炸弹
        assertFalse(beats(flush, bomb))       // 炸弹压不了同花顺

        val kings = recognize(listOf(bigJ(), bigJ(), smallJ(), smallJ()), level)!!
        assertTrue(beats(flush, kings))        // 天王炸压同花顺
        assertFalse(beats(kings, flush))      // 同花顺压不了天王炸

        // 炸弹大小：张数优先
        val bomb6 = recognize(
            listOf(sp(3), cl(3), di(3), he(3), sp(3), cl(3)), level)!!
        assertTrue(beats(bomb, bomb6))         // 6张炸弹 > 4张炸弹
    }

    @Test
    fun level_card_outranks_ace_in_single() {
        // 级牌（红桃5作为普通级牌出单张）强度 > A，但这里出的是红桃5单张（百搭仍可当级牌）
        val lvlSingle = recognize(listOf(he(level)), level)!!
        val ace = recognize(listOf(sp(14)), level)!!
        assertTrue(beats(ace, lvlSingle))
    }
}
