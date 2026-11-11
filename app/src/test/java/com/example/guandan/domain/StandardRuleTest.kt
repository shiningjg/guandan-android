package com.example.guandan.domain

import org.junit.Assert.*
import org.junit.Test

/**
 * 掼蛋标准规则回归测试。
 *
 * 依据 T/SQGD XH001—2019《掼蛋运动竞技规范》：
 *  - 4.3.3 / 4.3.5 / 4.3.7 / 4.3.8：三连对恰好 3 对、钢板恰好 2 组、顺子恰好 5 张、
 *    同花顺 = 同花色的顺子（也是恰好 5 张）。
 *  - 4.3.9：炸弹 4 张及以上同点牌。
 *  - 5.3.10：四王 > 六张和六张以上炸弹 > 同花顺 > 五张炸弹 > 四张炸弹 > 其它牌型。
 *
 * 核心结论：同花顺张数固定 5 张，不存在「6 张同花顺」；
 * 它能压 4/5 张炸弹，但压不了 6 张及以上炸弹（反被压制）。
 */
class StandardRuleTest {

    private val level = 2   // 打 2，测试牌避开点数 2 与王

    private fun sp(r: Int) = Card(r, Suit.SPADES)
    private fun he(r: Int) = Card(r, Suit.HEARTS)
    private fun cl(r: Int) = Card(r, Suit.CLUBS)
    private fun di(r: Int) = Card(r, Suit.DIAMONDS)

    /** 构造 n 张同点牌（不同实例，模拟两副牌中的重复实体牌）。 */
    private fun bombOf(n: Int, rank: Int = 9): List<Card> {
        val suits = listOf(Suit.SPADES, Suit.HEARTS, Suit.CLUBS, Suit.DIAMONDS)
        return List(n) { Card(rank, suits[it % 4]) }
    }

    private fun flush(from: Int = 3): List<Card> =
        (from..from + 4).map { sp(it) }

    private fun mixedStraight(from: Int = 3, len: Int = 5): List<Card> {
        val suits = listOf(Suit.SPADES, Suit.HEARTS, Suit.CLUBS, Suit.DIAMONDS)
        return List(len) { Card(from + it, suits[it % 4]) }
    }

    // ---------------- 牌型张数固定 ----------------

    @Test
    fun `标准_顺子恰好5张`() {
        assertEquals(PatternType.STRAIGHT, recognize(mixedStraight(3, 5), level)!!.type)
        assertNull("6 张连续单牌不是顺子", recognize(mixedStraight(3, 6), level))
        assertNull("4 张连续单牌不是顺子", recognize(mixedStraight(3, 4), level))
    }

    @Test
    fun `标准_同花顺恰好5张_不存在6张同花顺`() {
        val f = recognize(flush(3), level)!!
        assertEquals(PatternType.FLUSH_STRAIGHT, f.type)
        assertEquals(5, f.size)
        assertNull("6 张同花色连续牌不是合法牌型",
            recognize((3..8).map { sp(it) }, level))
        assertNull("4 张同花色连续牌不是同花顺",
            recognize((3..6).map { sp(it) }, level))
    }

    @Test
    fun `标准_连对恰好3对_钢板恰好2组`() {
        val pairs3 = listOf(sp(3), he(3), sp(4), he(4), sp(5), he(5))
        assertEquals(PatternType.DOUBLE_STRAIGHT, recognize(pairs3, level)!!.type)
        assertNull("4 连对不是合法牌型",
            recognize(pairs3 + listOf(sp(6), he(6)), level))

        val triple2 = listOf(sp(3), he(3), cl(3), sp(4), he(4), cl(4))
        assertEquals(PatternType.TRIPLE_STRAIGHT, recognize(triple2, level)!!.type)
        assertNull("3 组钢板不是合法牌型",
            recognize(triple2 + listOf(sp(5), he(5), cl(5)), level))
    }

    @Test
    fun `标准_炸弹4张及以上均合法`() {
        for (n in 4..8) {
            val b = recognize(bombOf(n), level)!!
            assertEquals(PatternType.BOMB, b.type)
            assertEquals(n, b.size)
        }
    }

    // ---------------- 压制层级 ----------------

    @Test
    fun `标准_完整层级为严格全序`() {
        // 由弱到强：普通牌型 < 4 张炸 < 5 张炸 < 同花顺 < 6 张炸 < 天王炸
        val ordered = listOf(
            recognize(listOf(sp(7)), level)!!,
            recognize(bombOf(4, 3), level)!!,
            recognize(bombOf(5, 3), level)!!,
            recognize(flush(3), level)!!,
            recognize(bombOf(6, 3), level)!!,
            recognize(
                listOf(
                    Card(RANK_BIG_JOKER, Suit.JOKER), Card(RANK_BIG_JOKER, Suit.JOKER),
                    Card(RANK_SMALL_JOKER, Suit.JOKER), Card(RANK_SMALL_JOKER, Suit.JOKER)
                ), level
            )!!,
        )
        for (i in ordered.indices) {
            for (j in i + 1 until ordered.size) {
                assertTrue("层级 $j 应压过层级 $i", beats(ordered[i], ordered[j]))
                assertFalse("层级 $i 不应压过层级 $j", beats(ordered[j], ordered[i]))
            }
        }
    }

    @Test
    fun `标准_5张同花顺可压4张和5张炸弹`() {
        val flush = recognize(flush(3), level)!!
        val b4 = recognize(bombOf(4), level)!!
        val b5 = recognize(bombOf(5), level)!!
        assertTrue("5 张同花顺压 4 张炸弹", beats(b4, flush))
        assertTrue("5 张同花顺压 5 张炸弹", beats(b5, flush))
        assertFalse("4 张炸弹压不了同花顺", beats(flush, b4))
        assertFalse("5 张炸弹压不了同花顺", beats(flush, b5))
    }

    @Test
    fun `标准_同花顺压不了6张及以上炸弹_反被压制`() {
        val flush = recognize(flush(3), level)!!
        for (n in 6..9) {
            val bn = recognize(bombOf(n), level)!!
            assertEquals(PatternType.BOMB, bn.type)
            assertTrue("$n 张炸弹应能压同花顺", beats(flush, bn))
            assertFalse("5 张同花顺压不了 $n 张炸弹", beats(bn, flush))
        }
    }

    @Test
    fun `标准_天王炸压一切且不可被压`() {
        val kings = recognize(
            listOf(
                Card(RANK_BIG_JOKER, Suit.JOKER), Card(RANK_BIG_JOKER, Suit.JOKER),
                Card(RANK_SMALL_JOKER, Suit.JOKER), Card(RANK_SMALL_JOKER, Suit.JOKER)
            ), level
        )!!
        for (cand in listOf(
            recognize(listOf(sp(7)), level)!!,
            recognize(bombOf(4), level)!!,
            recognize(bombOf(5), level)!!,
            recognize(flush(3), level)!!,
            recognize(bombOf(8), level)!!,
        )) {
            assertTrue(beats(cand, kings))
            assertFalse(beats(kings, cand))
        }
    }

    @Test
    fun `标准_同花顺之间只比最大牌点数_不比张数`() {
        val f7 = recognize(flush(3), level)!!   // 3-7
        val f8 = recognize(flush(4), level)!!   // 4-8
        assertEquals(f7.size, f8.size)
        assertTrue("4-8 同花顺应压过 3-7", beats(f7, f8))
        assertFalse(beats(f8, f7))
    }

    @Test
    fun `标准_同张数炸弹比点数`() {
        val b9 = recognize(bombOf(4, 9), level)!!
        val bK = recognize(bombOf(4, 13), level)!!
        assertTrue(beats(b9, bK))
        assertFalse(beats(bK, b9))
    }

    // ---------------- 出牌枚举与引擎端到端 ----------------

    @Test
    fun `枚举器不生成非标准张数的顺子类`() {
        val hand = (3..9).map { sp(it) } + (3..9).map { he(it) }
        val leads = MoveGenerator.legalPlays(hand, null, level)
        assertTrue(leads.isNotEmpty())
        for (combo in leads) {
            val p = recognize(combo, level)!!
            when (p.type) {
                PatternType.STRAIGHT, PatternType.FLUSH_STRAIGHT ->
                    assertEquals("${p.type} 必须恰好 5 张", 5, p.size)
                PatternType.DOUBLE_STRAIGHT, PatternType.TRIPLE_STRAIGHT ->
                    assertEquals("${p.type} 必须恰好 6 张", 6, p.size)
                else -> Unit
            }
        }
    }

    @Test
    fun `引擎_同花顺压6张炸弹被拒绝_6张炸弹压同花顺成功`() {
        val b6 = bombOf(6)
        val myFlush = flush(3)
        val small = listOf(Card(4, Suit.CLUBS))

        // 场景 A：6 张炸弹领出，同花顺跟牌应被拒绝
        val e1 = GameEngine(level, listOf(b6 + small, myFlush + small, small, small), leader = 0)
        assertTrue(e1.play(0, b6))
        assertFalse("5 张同花顺不能压 6 张炸弹", e1.play(1, myFlush))

        // 场景 B：同花顺领出，6 张炸弹跟牌应成功
        val e2 = GameEngine(level, listOf(myFlush + small, b6 + small, small, small), leader = 0)
        assertTrue(e2.play(0, myFlush))
        assertTrue("6 张炸弹应能压同花顺", e2.play(1, b6))
    }
}
