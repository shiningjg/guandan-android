package com.example.guandan.domain

import org.junit.Assert.*
import org.junit.Test

/**
 * v1.0.6 标准规则对齐专项测试。
 *
 * 依据：
 * - T/SQGD XH001—2019《掼蛋运动竞技规范》4.3.7（顺子示例 A2345 / 10JQKA）、
 *   4.8（借风）、5.3.7 / 5.3.8（顺子与同花顺大小）、5.9（通关）；
 * - 国家体育总局棋牌中心《竞技掼蛋扑克竞赛规则》第四条(一)2「A 视作 1」、
 *   第五条(一)「级牌按级牌牌点插带」。
 */
class StandardRuleV106Test {

    private val level = 5   // 打 5，红桃 5 为百搭；2 不是级牌，可入顺
    private fun sp(r: Int) = Card(r, Suit.SPADES)
    private fun he(r: Int) = Card(r, Suit.HEARTS)
    private fun cl(r: Int) = Card(r, Suit.CLUBS)
    private fun di(r: Int) = Card(r, Suit.DIAMONDS)
    private fun bigJ() = Card(RANK_BIG_JOKER, Suit.JOKER)
    private fun wild() = he(level)

    /** 混合花色地构造若干点数，避免被识别成同花顺。 */
    private fun s(vararg ranks: Int): List<Card> {
        val suits = listOf(Suit.SPADES, Suit.HEARTS, Suit.CLUBS, Suit.DIAMONDS)
        return ranks.mapIndexed { i, r -> Card(r, suits[i % 4]) }
    }

    // ---------------- A 作 1 点 ----------------

    @Test
    fun `A2345与10JQKA都合法_顶张分别为5和14`() {
        val low = recognize(s(14, 2, 3, 4, 5), level)!!
        val high = recognize(s(10, 11, 12, 13, 14), level)!!
        assertEquals(PatternType.STRAIGHT, low.type)
        assertEquals(5, low.mainRank)          // A 视作 1，整手按顶张 5 比较
        assertEquals(PatternType.STRAIGHT, high.type)
        assertEquals(14, high.mainRank)
    }

    @Test
    fun `顺子大小_A2345最小_10JQKA最大`() {
        val a2345 = recognize(s(14, 2, 3, 4, 5), level)!!
        val s678910 = recognize(s(6, 7, 8, 9, 10), level)!!
        val tjqka = recognize(s(10, 11, 12, 13, 14), level)!!
        assertTrue("6-7-8-9-10 应能压 A-2-3-4-5", beats(a2345, s678910))
        assertTrue("10-J-Q-K-A 应能压 6-7-8-9-10", beats(s678910, tjqka))
        assertFalse(beats(s678910, a2345))
        assertFalse(beats(tjqka, s678910))
    }

    @Test
    fun `跨A循环不合法`() {
        assertNull("Q-K-A-2-3", recognize(s(12, 13, 14, 2, 3), level))
        assertNull("J-Q-K-A-2", recognize(s(11, 12, 13, 14, 2), level))
        assertNull("K-A-2-3-4", recognize(s(13, 14, 2, 3, 4), level))
    }

    @Test
    fun `连对与钢板同样支持A作1`() {
        val aa2233 = listOf(sp(14), he(14), sp(2), he(2), sp(3), he(3))
        val p = recognize(aa2233, level)!!
        assertEquals(PatternType.DOUBLE_STRAIGHT, p.type)
        assertEquals(3, p.mainRank)
        val qqkkaa = listOf(sp(12), he(12), sp(13), he(13), sp(14), he(14))
        assertEquals(14, recognize(qqkkaa, level)!!.mainRank)

        val aaa222 = listOf(sp(14), he(14), cl(14), sp(2), he(2), cl(2))
        val t = recognize(aaa222, level)!!
        assertEquals(PatternType.TRIPLE_STRAIGHT, t.type)
        assertEquals(2, t.mainRank)
    }

    @Test
    fun `同花顺A2345最小_10JQKA最大`() {
        val low = recognize(listOf(14, 2, 3, 4, 5).map { sp(it) }, level)!!
        val high = recognize(listOf(10, 11, 12, 13, 14).map { sp(it) }, level)!!
        assertEquals(PatternType.FLUSH_STRAIGHT, low.type)
        assertEquals(5, low.mainRank)
        assertEquals(PatternType.FLUSH_STRAIGHT, high.type)
        assertEquals(14, high.mainRank)
        assertTrue(beats(low, high))
    }

    @Test
    fun `百搭可补出A2345`() {
        val p = recognize(listOf(sp(14), he(2), cl(3), di(4), wild()), level)!!
        assertEquals(PatternType.STRAIGHT, p.type)
        assertEquals(5, p.mainRank)
    }

    @Test
    fun `级牌按自然点数插带_打2时2仍可入顺`() {
        val p = recognize(s(2, 3, 4, 5, 6), 2)!!
        assertEquals(PatternType.STRAIGHT, p.type)
        assertEquals(6, p.mainRank)
        assertNull("王仍不能入顺", recognize(listOf(sp(3), he(4), cl(5), di(6), bigJ()), 2))
    }

    @Test
    fun `枚举器生成两端顺子且不生成跨A循环`() {
        val hand = s(14, 2, 3, 4, 5, 10, 11, 12, 13, 14)
        val plays = MoveGenerator.legalPlays(hand, null, level)
        fun hasStraight(top: Int) = plays.any { c ->
            recognize(c, level)?.let { it.type == PatternType.STRAIGHT && it.mainRank == top } == true
        }
        assertTrue("应能枚举 A-2-3-4-5", hasStraight(5))
        assertTrue("应能枚举 10-J-Q-K-A", hasStraight(14))

        val cross = MoveGenerator.legalPlays(s(12, 13, 14, 2, 3), null, level)
        assertTrue("不应枚举跨 A 循环的顺子",
            cross.none { c -> c.size == 5 && recognize(c, level)?.type == PatternType.STRAIGHT })
    }

    // ---------------- 借风（标准 4.8） ----------------

    @Test
    fun `接风_走完者已出完且三家都过_由对家领出`() {
        val e = GameEngine(
            level,
            listOf(listOf(sp(10)), listOf(sp(3)), listOf(sp(5)), listOf(sp(7))),
            leader = 0,
        )
        assertTrue(e.play(0, listOf(sp(10))))       // P0 走完
        assertTrue(e.pass(1))
        assertTrue(e.pass(2))
        assertTrue(e.pass(3))                        // 三家都过
        assertEquals("应由 P0 的对家 P2 接风", 2, e.current)
        assertNull("新一轮可出任意牌型", e.lastPlay)
    }

    @Test
    fun `最后出牌者仍在场时由本人重新领出`() {
        val e = GameEngine(
            level,
            listOf(listOf(sp(3), sp(9)), listOf(sp(4)), listOf(sp(5)), listOf(sp(7))),
            leader = 0,
        )
        assertTrue(e.play(0, listOf(sp(3))))        // P0 领出，未走完
        assertTrue(e.pass(1))
        assertTrue(e.pass(2))
        assertTrue(e.pass(3))
        assertEquals(0, e.current)
        assertNull(e.lastPlay)
    }

    // ---------------- 提示 / AI 的成型牌组保护 ----------------

    @Test
    fun `拆牌惩罚_炸弹三张对子`() {
        val hand = listOf(sp(8), he(8), cl(8), di(8), sp(3), he(3), sp(9), he(9), cl(9), sp(4))
        assertEquals("整组端走 4 张 8 不算拆", 0,
            HandShape.breakPenalty(hand, listOf(sp(8), he(8), cl(8), di(8)), level))
        assertTrue(HandShape.breakPenalty(hand, listOf(sp(8)), level) >= 3)
        assertTrue(HandShape.breakPenalty(hand, listOf(sp(8), he(8)), level) >= 3)
        assertEquals(2, HandShape.breakPenalty(hand, listOf(sp(9), he(9)), level))
        assertEquals(1, HandShape.breakPenalty(hand, listOf(sp(3)), level))
    }

    @Test
    fun `百搭浪费判定_小对子算浪费_炸弹和顺子不算`() {
        val smallPair = listOf(wild(), sp(3))
        assertTrue(HandShape.wastesWild(smallPair, recognize(smallPair, level)!!, level))
        val bomb = listOf(sp(9), he(9), cl(9), wild())
        assertFalse(HandShape.wastesWild(bomb, recognize(bomb, level)!!, level))
        val straight = listOf(sp(3), he(4), cl(5), di(6), wild())
        assertFalse(HandShape.wastesWild(straight, recognize(straight, level)!!, level))
    }
}
