package com.example.guandan.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field

/**
 * v1.0.9 专项测试：
 *  - 进贡执行顺序对齐规范 5.1.3 / 5.5：先发牌 → 用新发到手的牌（新级牌）算进贡
 *    → 直接交换不二次洗牌 → 开局公示；结算阶段不再提前剧透。
 *  - 记牌器：两副牌计数口径、级牌 / 逢人配高亮、已出牌累计、明牌提示。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StandardRuleV109Test {

    private fun sp(r: Int) = Card(r, Suit.SPADES)
    private fun he(r: Int) = Card(r, Suit.HEARTS)
    private fun cl(r: Int) = Card(r, Suit.CLUBS)
    private fun di(r: Int) = Card(r, Suit.DIAMONDS)
    private fun bigJ() = Card(RANK_BIG_JOKER, Suit.JOKER)

    private fun f(any: Any, name: String): Field =
        any.javaClass.getDeclaredField(name).also { it.isAccessible = true }
    private fun set(any: Any, name: String, value: Any?) = f(any, name).set(any, value)
    private fun setInt(any: Any, name: String, value: Int) = f(any, name).setInt(any, value)

    /** v1.0.10：人类收贡时 nextHand 会暂停等玩家挑还牌；测试里按推荐牌确认后再看公示。 */
    private fun settleBack(vm: GameViewModel) {
        vm.uiState.pendingBack?.let { assertTrue(vm.chooseTributeBack(it.suggested)) }
    }

    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun finishedEngine(level: Int, order: List<Int>): GameEngine {
        val e = GameEngine(level, List(4) { emptyList<Card>() }, leader = 0)
        e.finishOrder.addAll(order)
        set(e, "phase", Phase.HAND_OVER)
        return e
    }

    private fun callOnHandOver(vm: GameViewModel) {
        val m = GameViewModel::class.java.getDeclaredMethod("onHandOver")
        m.isAccessible = true
        m.invoke(vm)
    }

    @Suppress("UNCHECKED_CAST")
    private fun engineOf(vm: GameViewModel): GameEngine = f(vm, "engine").get(vm) as GameEngine

    @Suppress("UNCHECKED_CAST")
    private fun handsOf(vm: GameViewModel): List<MutableList<Card>> =
        f(vm, "hands").get(vm) as List<MutableList<Card>>

    // ---------------- 记牌器计数 ----------------

    @Test
    fun `两副牌每个点数满8张_大小王各2张_按牌力降序排列`() {
        val cells = counterCells(emptyList(), emptyList(), level = 2)
        assertEquals(15, cells.size)
        assertEquals(listOf(17, 16, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2),
            cells.map { it.rank })
        assertEquals(2, cells.first { it.rank == 17 }.left)   // 大王
        assertEquals(2, cells.first { it.rank == 16 }.left)   // 小王
        assertTrue(cells.filter { it.rank in 2..14 }.all { it.left == 8 })
        assertEquals("A", cells.first { it.rank == 14 }.label)
        assertEquals("2", cells.first { it.rank == 2 }.label)
    }

    @Test
    fun `记牌器扣除已出牌与自己手牌_且不会出现负数`() {
        val played = listOf(sp(9), he(9), bigJ())
        val hand = listOf(cl(9), di(9), sp(9), he(9))   // 同点数 4 张在自己手里
        val cells = counterCells(played, hand, level = 5)
        assertEquals("9 全场 8 张 − 已出 2 − 手牌 4 = 2", 2,
            cells.first { it.rank == 9 }.left)
        assertEquals(1, cells.first { it.rank == 17 }.left)
        // 极端：扣超过 8 张也不得为负
        val over = counterCells(List(10) { sp(3) }, emptyList(), 5)
        assertEquals(0, over.first { it.rank == 3 }.left)
    }

    @Test
    fun `级牌行高亮_随升级变化_逢人配单独统计剩余2张`() {
        val cells = counterCells(emptyList(), emptyList(), level = 10)
        assertTrue(cells.first { it.rank == 10 }.isLevel)
        assertTrue(cells.filter { it.rank != 10 }.none { it.isLevel })
        assertTrue(counterCells(emptyList(), emptyList(), 14).first { it.rank == 14 }.isLevel)

        assertEquals(2, wildRemaining(emptyList(), emptyList(), 10))
        assertEquals(1, wildRemaining(listOf(he(10)), emptyList(), 10))
        assertEquals(0, wildRemaining(listOf(he(10)), listOf(he(10)), 10))
        // 非红桃的 10 不是逢人配，不扣
        assertEquals(2, wildRemaining(listOf(sp(10)), emptyList(), 10))
    }

    @Test
    fun `明牌提示列出贡还牌_抗贡时为空`() {
        val x = TributeExchange(3, 0, sp(14), he(3))
        val plan = TributePlan(listOf(x), nextLeader = 3, notice = "", antiTribute = false,
            doubleDown = false)
        assertEquals(listOf(sp(14), he(3)), disclosedCards(plan))
        val anti = plan.copy(exchanges = emptyList(), antiTribute = true)
        assertTrue(disclosedCards(anti).isEmpty())
        assertTrue(disclosedCards(null).isEmpty())
    }

    // ---------------- 已出牌累计 ----------------

    @Test
    fun `引擎累计已出实体牌_换局后清空`() {
        val e = GameEngine(2, listOf(
            mutableListOf(sp(3), he(3)),
            mutableListOf(sp(4)), mutableListOf(sp(5)), mutableListOf(sp(6))
        ), leader = 0)
        assertTrue(e.play(0, listOf(sp(3), he(3))))
        assertEquals(2, e.playedCards.size)
        assertTrue(e.playedCards.containsAll(listOf(sp(3), he(3))))

        // 新一局是全新引擎实例，playedCards 自然为空
        val next = GameEngine(5, List(4) { mutableListOf(sp(7)) }, leader = 0)
        assertTrue(next.playedCards.isEmpty())
    }

    // ---------------- 进贡：先发牌、新级牌、不二次洗牌 ----------------

    @Test
    fun `下一局用升级后的新级牌建引擎并算进贡`() {
        val vm = GameViewModel()
        setInt(vm, "level", 2)                                    // 本局打 2
        set(vm, "engine", finishedEngine(2, listOf(0, 2, 1, 3))) // 双上 +3 → 下局打 5
        callOnHandOver(vm)
        assertEquals(5, vm.level)
        assertNull(vm.uiState.tributePlan)

        vm.nextHand()
        settleBack(vm)
        assertEquals("新一局引擎应使用升级后的级牌 5", 5, engineOf(vm).level)
        val plan = vm.uiState.tributePlan!!
        // 贡牌须排除「新級牌」的红桃 5，而非旧级牌红桃 2
        assertTrue(plan.exchanges.all { !it.give.isWild(5) })
        assertTrue(plan.exchanges.all { !it.back.isWild(5) })
    }

    @Test
    fun `planTribute按新级牌排除红心参谋_而非旧级牌`() {
        val hands = listOf(
            listOf(sp(3), sp(9)),      // P0 头游（收贡）
            listOf(sp(4)),
            listOf(sp(5)),
            listOf(he(5), he(2), sp(14), bigJ()),  // P3 末游：打 5 时红桃5 是逢人配
        )
        val res = HandResult(listOf(0, 1, 2, 3), 0, 2, 3, 0, false)
        val plan = planTribute(res, hands, level = 5)
        val give = plan.exchanges.single().give
        assertTrue("应贡除红心级牌外的最大单张（大王）", give.isBigJoker)
        assertFalse("红桃 5（本级逢人配）不得作为贡牌", give.isWild(5))
    }

    @Test
    fun `抗贡按新发到手的牌判定_公示与新手牌一致`() {
        val vm = GameViewModel()
        setInt(vm, "level", 2)
        set(vm, "engine", finishedEngine(2, listOf(0, 1, 2, 3)))
        callOnHandOver(vm)
        vm.nextHand()
        settleBack(vm)

        val plan = vm.uiState.tributePlan!!
        val hands = handsOf(vm)
        assertEquals("四家各 27 张", listOf(27, 27, 27, 27), hands.map { it.size })
        if (plan.antiTribute) {
            assertTrue("抗贡则无贡还交换", plan.exchanges.isEmpty())
            plan.antiTributeBy.forEach { p ->
                assertTrue("P$p 的新手牌里确有两张大王", hands[p].count { it.isBigJoker } >= 2)
            }
            assertTrue(plan.antiTributeCards.size >= 2)
            assertTrue(plan.antiTributeCards.all { it.isBigJoker })
        } else {
            assertTrue(plan.exchanges.isNotEmpty())
        }
    }

    @Test
    fun `重开一场清空开局公示与已出牌`() {
        val vm = GameViewModel()
        setInt(vm, "level", 2)
        set(vm, "engine", finishedEngine(2, listOf(0, 1, 2, 3)))
        callOnHandOver(vm)
        vm.nextHand()
        settleBack(vm)
        assertNotNull(vm.uiState.tributePlan)

        vm.newMatch(Difficulty.NORMAL)
        assertNull(vm.uiState.tributePlan)
        assertTrue(vm.uiState.playedCards.isEmpty())
        assertEquals(2, vm.level)
    }
}
