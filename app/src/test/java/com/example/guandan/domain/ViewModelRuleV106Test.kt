package com.example.guandan.domain

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Field

/**
 * v1.0.6 跨局规则专项测试：打 A 通关 / 三次不过退 2、进贡与抗贡细则、提示不拆牌与不浪费百搭。
 *
 * ViewModel 的 level / engine / 结算函数都是私有的，JVM 单测里用反射构造受控局面，
 * 避免靠随机发牌去「碰」出打 A 或双下的局面。
 */
class ViewModelRuleV106Test {

    private fun f(any: Any, name: String): Field =
        any.javaClass.getDeclaredField(name).also { it.isAccessible = true }

    private fun set(any: Any, name: String, value: Any?) = f(any, name).set(any, value)
    private fun setInt(any: Any, name: String, value: Int) = f(any, name).setInt(any, value)

    private fun sp(r: Int) = Card(r, Suit.SPADES)
    private fun he(r: Int) = Card(r, Suit.HEARTS)
    private fun cl(r: Int) = Card(r, Suit.CLUBS)
    private fun di(r: Int) = Card(r, Suit.DIAMONDS)
    private fun bigJ() = Card(RANK_BIG_JOKER, Suit.JOKER)

    /** 造一个已结算（HAND_OVER）的引擎，名次由 order 指定。 */
    private fun finishedEngine(level: Int, order: List<Int>): GameEngine {
        val e = GameEngine(level, List(4) { emptyList<Card>() }, leader = 0)
        e.finishOrder.addAll(order)
        set(e, "phase", Phase.HAND_OVER)
        return e
    }

    private fun vmAt(level: Int, order: List<Int>): GameViewModel {
        val vm = GameViewModel()
        setInt(vm, "level", level)
        set(vm, "engine", finishedEngine(level, order))
        return vm
    }

    private fun callOnHandOver(vm: GameViewModel) {
        val m = GameViewModel::class.java.getDeclaredMethod("onHandOver")
        m.isAccessible = true
        m.invoke(vm)
    }

    private fun hr(order: List<Int>, doubleDown: Boolean = false, levelUp: Int = 2, winner: Int = 0) =
        HandResult(order, winner, levelUp, order[3], order[0], doubleDown)

    // ---------------- 打 A（5.9 + 赛事规程） ----------------

    @Test
    fun `打A_队友二游夺冠`() {
        val vm = vmAt(14, listOf(0, 2, 1, 3))
        callOnHandOver(vm)
        assertEquals(0, vm.uiState.matchWinner)
        assertTrue(vm.uiState.message.contains("夺冠"))
    }

    @Test
    fun `打A_队友三游也夺冠`() {
        val vm = vmAt(14, listOf(0, 1, 2, 3))
        callOnHandOver(vm)
        assertEquals(0, vm.uiState.matchWinner)
    }

    @Test
    fun `打A_队友末游不算过A`() {
        val vm = vmAt(14, listOf(0, 1, 3, 2))
        callOnHandOver(vm)
        assertNull(vm.uiState.matchWinner)
        assertEquals(14, vm.level)
        assertEquals("A", vm.uiState.levelText)
        assertTrue(vm.uiState.message.contains("打 A 未过"))
    }

    @Test
    fun `打A_三次不过退回2`() {
        val vm = vmAt(14, listOf(0, 1, 3, 2))
        repeat(3) { i ->
            set(vm, "handSettled", false)
            set(vm, "engine", finishedEngine(14, listOf(0, 1, 3, 2)))
            callOnHandOver(vm)
            if (i < 2) {
                assertEquals("A", vm.uiState.levelText)
                assertNull(vm.uiState.matchWinner)
            }
        }
        assertEquals(2, vm.level)
        assertEquals("2", vm.uiState.levelText)
        assertTrue(vm.uiState.message.contains("退回打 2"))
    }

    @Test
    fun `常规升级钳制到A_不能跳过A`() {
        val vm = vmAt(13, listOf(0, 2, 1, 3))   // 13 + 3 = 16，应钳制到 14
        callOnHandOver(vm)
        assertEquals(14, vm.level)
        assertEquals("A", vm.uiState.levelText)
        assertNull(vm.uiState.matchWinner)
    }

    // ---------------- 进贡细则（4.9 / 5.5 / 5.6） ----------------

    @Test
    fun `单贡_贡最大并排除红心级牌_还牌不超过10_由下游领出`() {
        val level = 2
        val hands = listOf(
            listOf(sp(3), sp(9), sp(14)),                  // P0 头游（收贡）
            listOf(sp(4)),
            listOf(sp(5)),
            listOf(he(2), sp(14), bigJ(), sp(9)),         // P3 末游（进贡）
        )
        val plan = planTribute(hr(listOf(0, 1, 2, 3)), hands, level)
        assertFalse(plan.antiTribute)
        assertFalse(plan.doubleDown)
        assertEquals(1, plan.exchanges.size)
        val x = plan.exchanges[0]
        assertEquals(3, x.giver)
        assertEquals(0, x.receiver)
        assertTrue("红桃级牌应被排除，大王为最大单张", x.give.isBigJoker)
        assertTrue("还贡牌点不得超过 10", x.back.rank <= 10)
        assertEquals(3, x.back.rank)                      // ≤10 中还最小的一张
        assertEquals(3, plan.nextLeader)                  // 5.5「并由下游出牌」
    }

    @Test
    fun `双贡_头游拿大贡_二游拿小贡_各自还牌`() {
        val level = 5
        val hands = listOf(
            listOf(sp(3), sp(10), sp(14)),                // P0 头游
            listOf(sp(13), sp(8)),                        // P1 三游（贡 K）
            listOf(sp(4), sp(9)),                         // P2 二游
            listOf(sp(12), sp(7)),                        // P3 末游（贡 Q）
        )
        val plan = planTribute(hr(listOf(0, 2, 1, 3), doubleDown = true, levelUp = 3), hands, level)
        assertTrue(plan.doubleDown)
        assertEquals(2, plan.exchanges.size)
        assertEquals(13, plan.exchanges[0].give.rank)
        assertEquals(0, plan.exchanges[0].receiver)
        assertEquals(12, plan.exchanges[1].give.rank)
        assertEquals(2, plan.exchanges[1].receiver)
        assertTrue(plan.exchanges.all { it.back.rank <= 10 })
        assertNotEquals(plan.exchanges[0].back, plan.exchanges[1].back)
        assertEquals(1, plan.nextLeader)                  // 由进贡给头游者领出
    }

    @Test
    fun `单下抗贡_末游持双大王免贡_由头游领出`() {
        val hands = listOf(
            listOf(sp(3)), listOf(sp(4)), listOf(sp(5)),
            listOf(bigJ(), bigJ(), sp(9)),
        )
        val plan = planTribute(hr(listOf(0, 1, 2, 3)), hands, 2)
        assertTrue(plan.antiTribute)
        assertTrue(plan.exchanges.isEmpty())
        assertEquals(0, plan.nextLeader)
    }

    @Test
    fun `双下抗贡_进贡方任一人持双大王即全队免贡`() {
        val hands = listOf(
            listOf(sp(3)), listOf(bigJ(), bigJ()), listOf(sp(4)), listOf(sp(5)),
        )
        val plan = planTribute(hr(listOf(0, 2, 1, 3), doubleDown = true, levelUp = 3), hands, 2)
        assertTrue(plan.antiTribute)
        assertTrue(plan.exchanges.isEmpty())
    }

    // ---------------- 提示：不拆固定牌型 / 不浪费百搭 ----------------

    @Test
    fun `提示领出时不拆炸弹`() {
        val vm = GameViewModel()
        val human = listOf(sp(8), he(8), cl(8), di(8), sp(3), sp(4), sp(9))
        val e = GameEngine(2, listOf(human, listOf(sp(5)), listOf(sp(6)), listOf(sp(7))), leader = 0)
        set(vm, "engine", e)
        vm.applyHint()
        val picked = vm.uiState.selectedCards
        assertTrue(picked.isNotEmpty())
        val used8 = picked.count { it.rank == 8 }
        assertTrue("提示不应把 4 张 8 拆成 1~3 张：$picked", used8 == 0 || used8 == 4)
    }

    @Test
    fun `提示不把百搭浪费在小对子小三张上`() {
        val vm = GameViewModel()
        val level = 2
        val human = listOf(he(2), sp(3), sp(4), sp(5), sp(6), sp(9), sp(10))
        val e = GameEngine(level, listOf(human, listOf(sp(7)), listOf(sp(8)), listOf(sp(11))), leader = 0)
        set(vm, "engine", e)
        vm.applyHint()
        val picked = vm.uiState.selectedCards
        assertTrue(picked.isNotEmpty())
        if (picked.any { it.isWild(level) }) {
            val p = recognize(picked, level)!!
            assertTrue("百搭只应用于炸弹 / 顺子 / 连对 / 钢板 / 同花顺，实测 ${p.type}",
                p.type in setOf(
                    PatternType.BOMB, PatternType.STRAIGHT, PatternType.DOUBLE_STRAIGHT,
                    PatternType.TRIPLE_STRAIGHT, PatternType.FLUSH_STRAIGHT, PatternType.FOUR_KINGS,
                ))
        }
    }
}
