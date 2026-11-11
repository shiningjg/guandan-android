package com.example.guandan.domain

import org.junit.Assert.*
import org.junit.Test

class GameEngineTest {

    private val level = 2  // 打 2，红桃2为百搭（测试牌用黑桃避开）

    private fun c(r: Int) = Card(r, Suit.SPADES)

    /** 构造有可控手牌的引擎（不要求每人 27 张）。 */
    private fun engine(hands: List<List<Card>>, leader: Int = 0) =
        GameEngine(level, hands, leader)

    @Test
    fun play_and_pass_flow() {
        // P0 两张：领小牌后三家过 → P0 重新领出
        val e = engine(listOf(listOf(c(10), c(4)), listOf(c(3)), listOf(c(5)), listOf(c(7))), leader = 0)
        assertTrue(e.play(0, listOf(c(4))))
        assertTrue(e.pass(1))
        assertTrue(e.pass(2))
        assertTrue(e.pass(3))                      // 三家过 → P0 重领
        assertEquals(0, e.current)
        assertTrue(e.finishOrder.isEmpty())
    }

    @Test
    fun follow_must_beat_or_pass() {
        val e = engine(
            listOf(listOf(c(9), c(4)), listOf(c(3)), listOf(c(5)), listOf(c(7))),
            leader = 0
        )
        assertTrue(e.play(0, listOf(c(4))))        // P0 领小牌
        assertFalse(e.play(1, listOf(c(3))))       // P1 出 3 不能压 4 → 失败
        assertTrue(e.pass(1))                       // P1 只能过
        assertTrue(e.play(2, listOf(c(5))))         // 5>4 跟牌成功，P2 走完
        assertTrue(e.play(3, listOf(c(7))))         // P2 出完→P3 新轮领出并走完
        assertEquals(0, e.current)                  // P0 新轮领出
        assertTrue(e.play(0, listOf(c(9))))         // P0 领 9 走完 → 本局结束
        assertEquals(Phase.HAND_OVER, e.phase)
        assertEquals(listOf(2, 3, 0, 1), e.finishOrder)
    }

    @Test
    fun hand_result_leveling_double_up() {
        // 双上：P0头游、P2(队友)二游 → 0队 +3
        // 手牌：P0=[10], P1=[3,9], P2=[5], P3=[7,8]
        val e = engine(
            listOf(listOf(c(10)), listOf(c(3), c(9)), listOf(c(5)), listOf(c(7), c(8))),
            leader = 0
        )
        assertTrue(e.play(0, listOf(c(10))))       // P0 走完(头游)，其余三家仍要依次表态
        assertTrue(e.pass(1))                      // 3/9 都压不过 10
        assertTrue(e.pass(2))
        assertTrue(e.pass(3))                      // 三家都过 → 标准 4.8 由 P0 的对家 P2 接风领出
        assertEquals(2, e.current)
        assertTrue(e.play(2, listOf(c(5))))        // P2 领 5 走完(二游)
        assertTrue(e.play(3, listOf(c(7))))        // P3 压 7
        assertTrue(e.play(1, listOf(c(9))))        // P1 压 9
        assertTrue(e.pass(3))                      // P3 剩 8 压不过 9 → 轮回到 P1 重新领出
        assertEquals(1, e.current)
        assertTrue(e.play(1, listOf(c(3))))        // P1 走完(三游) → 剩 P3 末游，本局结束
        assertEquals(Phase.HAND_OVER, e.phase)
        val res = e.result()
        assertEquals(listOf(0, 2, 1, 3), res.finishOrder)
        assertEquals(0, res.winnerTeam)
        assertEquals(3, res.levelUp)               // 队友 P2 为二游 → 双上 +3
        assertEquals(3, res.tributeFrom)
        assertEquals(0, res.tributeTo)
        assertTrue(res.doubleDown)                 // 三游 P1 与末游 P3 同队 → 双下，下一局双贡
    }

    @Test
    fun single_down_leveling() {
        // 单下：P1头游、队友 P3 末游 → 1队 +1
        // 手牌：P1=[10], P0=[3,9], P2=[5,8], P3=[7,6,4]
        val e = engine(
            listOf(listOf(c(3), c(9)), listOf(c(10)), listOf(c(5), c(8)), listOf(c(7), c(6), c(4))),
            leader = 1
        )
        assertTrue(e.play(1, listOf(c(10))))       // P1 走完(头游)，其余三家依次表态
        assertTrue(e.pass(2))
        assertTrue(e.pass(3))
        assertTrue(e.pass(0))                      // 三家都过 → P1 的对家 P3 接风领出
        assertEquals(3, e.current)
        assertTrue(e.play(3, listOf(c(4))))        // P3 领 4
        assertTrue(e.play(0, listOf(c(9))))        // P0 压 9
        assertTrue(e.pass(2))                      // P2 的 5/8 压不过 9
        assertTrue(e.pass(3))                      // P3 的 6/7 压不过 9 → 轮回到 P0 领出
        assertEquals(0, e.current)
        assertTrue(e.play(0, listOf(c(3))))        // P0 走完(二游)
        assertTrue(e.play(2, listOf(c(5))))        // P2 压 3 → 出 5
        assertTrue(e.play(3, listOf(c(6))))        // P3 压 5 → 出 6
        assertTrue(e.play(2, listOf(c(8))))        // P2 走完(三游) → 剩 P3 末游
        assertEquals(Phase.HAND_OVER, e.phase)
        val res = e.result()
        assertEquals(listOf(1, 0, 2, 3), res.finishOrder)
        assertEquals(1, res.winnerTeam)
        assertEquals(1, res.levelUp)               // 队友 P3 为末游 → 单下 +1
        assertEquals(3, res.tributeFrom)
        assertEquals(1, res.tributeTo)
        assertFalse(res.doubleDown)                // 三游 P2(0队) 与末游 P3(1队) 不同队 → 单贡
    }

    @Test
    fun two_decks_playing_one_identical_card_removes_only_one() {
        // 两副牌里同点数同花色的牌有两张：equals 相等但是不同实例。
        // 回归「removeAll 按 equals 删除」的 bug：出 1 张不能把另一张一起删掉。
        val a = Card(7, Suit.SPADES)
        val b = Card(7, Suit.SPADES)
        assertEquals(a, b)
        assertFalse(a === b)
        val e = engine(listOf(listOf(a, b, c(9)), listOf(c(3)), listOf(c(5)), listOf(c(7))), leader = 0)

        assertTrue(e.play(0, listOf(a)))

        assertEquals(2, e.handOf(0).size)
        assertTrue("另一张同点同花色的牌必须留在手上", e.handOf(0).any { it === b })
    }

    @Test
    fun two_decks_pair_of_identical_cards_removes_exactly_two() {
        val a = Card(7, Suit.SPADES)
        val b = Card(7, Suit.SPADES)
        val e = engine(listOf(listOf(a, b, c(9)), listOf(c(3)), listOf(c(5)), listOf(c(7))), leader = 0)

        assertTrue(e.play(0, listOf(a, b)))   // 用两副牌各一张组成对 7

        assertEquals(1, e.handOf(0).size)
    }
}

class MoveGeneratorTest {
    private val level = 5

    @Test
    fun generates_basic_types() {
        val hand = listOf(
            Card(7, Suit.SPADES), Card(7, Suit.HEARTS),     // 对 7
            Card(8, Suit.SPADES), Card(8, Suit.CLUBS), Card(8, Suit.DIAMONDS), // 三 8
            Card(3, Suit.SPADES), Card(4, Suit.HEARTS),       // 散牌
            Card(9, Suit.SPADES), Card(10, Suit.SPADES),      // 顺子组件
        )
        val leads = MoveGenerator.legalPlays(hand, null, level)
        assertTrue(leads.isNotEmpty())
        // 应包含一对 7
        assertTrue(leads.any { recognize(it, level)?.type == PatternType.PAIR })
        // 应包含三张 8
        assertTrue(leads.any { recognize(it, level)?.type == PatternType.TRIPLE })
    }

    @Test
    fun following_filters_by_beat() {
        val hand = listOf(
            Card(7, Suit.SPADES), Card(7, Suit.HEARTS),
            Card(9, Suit.SPADES), Card(9, Suit.HEARTS),
            Card(3, Suit.SPADES), Card(4, Suit.SPADES), Card(5, Suit.SPADES), Card(6, Suit.SPADES),
        )
        val targetSingle = recognize(listOf(Card(6, Suit.CLUBS)), level)!!   // 单张 6
        val beats6 = MoveGenerator.legalPlays(hand, targetSingle, level)
        // 能压单张6的：7、9 单张，或炸弹/同花顺
        assertTrue(beats6.any { it.size == 1 && recognize(it, level)!!.mainRank > 6 })
        // 不应出现压不过的牌
        assertTrue(beats6.all {
            val p = recognize(it, level)!!
            beats(targetSingle, p)
        })
    }
}
