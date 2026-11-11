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
 * v1.0.10 专项测试：
 *  - 还贡候选：牌点 ≤10、非王、非逢人配，按牌力升序；全手牌 >10 时按规程兜底；
 *  - 拆牌风险与推荐：优先「不拆对子 / 三张 / 炸弹」的最小牌，AI 自动还牌同样不再拆牌；
 *  - 人类收贡：开局暂停、AI 与出牌操作冻结，玩家自己选完才生成公示；
 *  - 玩家改选后贡 / 还牌真正落到对应玩家手里，四家仍各 27 张，公示含具体牌面。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StandardRuleV1010Test {

    private fun sp(r: Int) = Card(r, Suit.SPADES)
    private fun he(r: Int) = Card(r, Suit.HEARTS)
    private fun cl(r: Int) = Card(r, Suit.CLUBS)
    private fun di(r: Int) = Card(r, Suit.DIAMONDS)
    private fun bigJ() = Card(RANK_BIG_JOKER, Suit.JOKER)
    private fun smallJ() = Card(RANK_SMALL_JOKER, Suit.JOKER)

    private fun f(any: Any, name: String): Field =
        any.javaClass.getDeclaredField(name).also { it.isAccessible = true }
    private fun set(any: Any, name: String, value: Any?) = f(any, name).set(any, value)
    private fun setInt(any: Any, name: String, value: Int) = f(any, name).setInt(any, value)

    @Suppress("UNCHECKED_CAST")
    private fun handsOf(vm: GameViewModel): List<MutableList<Card>> =
        f(vm, "hands").get(vm) as List<MutableList<Card>>
    private fun engineOf(vm: GameViewModel): GameEngine = f(vm, "engine").get(vm) as GameEngine

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

    /**
     * nextHand 内部会重新洗牌，末游同时摸到两张大王（抗贡）是小概率事件。
     * 反复构造直到出现「人类收贡、需要自己选还牌」的确定性场景。
     */
    private fun vmWaitingBack(): GameViewModel {
        repeat(40) {
            val vm = GameViewModel()
            setInt(vm, "level", 2)
            set(vm, "engine", finishedEngine(2, listOf(0, 1, 2, 3)))
            callOnHandOver(vm)
            vm.nextHand()
            if (vm.uiState.pendingBack != null) return vm
        }
        fail("40 次随机发牌里应至少出现一次人类收贡（非抗贡）")
        error("unreachable")
    }

    // ---------------- 纯计算：候选 / 风险 / 推荐 ----------------

    @Test
    fun `还贡候选只含不大于10的非王非百搭并按牌力升序`() {
        val hand = listOf(sp(3), he(2), cl(10), di(11), bigJ(), smallJ(), sp(9), he(9))
        val c = backCandidates(hand, 2)
        assertTrue(c.all { it.rank <= 10 && !it.isJoker && !it.isWild(2) })
        assertEquals(listOf(3, 9, 9, 10), c.map { it.rank })
    }

    @Test
    fun `全手牌都大于10时兜底还最小的非王非百搭牌`() {
        val hand = listOf(sp(11), di(12), bigJ(), he(2))
        assertEquals(listOf(11, 12), backCandidates(hand, 2).map { it.rank })
    }

    @Test
    fun `本局级牌与刚收到的贡牌不进入还贡候选`() {
        // 打 4 时 ♦4 是级牌、牌力 15 高于 A，常常正好成为贡牌；
        // 它既是级牌又是刚收到的贡牌，绝不能因为「点数 4 ≤10」被当成小牌还掉。
        val give = di(4)
        val hand = listOf(sp(3), cl(3), give, he(4), sp(9), bigJ())
        val c = backCandidates(hand, 4, exclude = listOf(give))
        assertTrue("级牌（任意花色）与贡牌都不得作为还牌", c.none { it === give || it.rank == 4 })
        assertTrue(c.none { it.isJoker })
        assertEquals(listOf(3, 3, 9), c.map { it.rank })
    }

    @Test
    fun `拆牌风险按同点数自然牌张数判定对子三张炸弹`() {
        val hand = listOf(sp(3), cl(3), di(3), he(3), sp(4), cl(4), sp(5))
        assertEquals(BackRisk.BOMB, backRisk(hand, sp(3), 2))
        assertEquals(BackRisk.PAIR, backRisk(hand, sp(4), 2))
        assertEquals(BackRisk.NONE, backRisk(hand, sp(5), 2))
        assertEquals(BackRisk.NONE, backRisk(hand, bigJ(), 2))
    }

    @Test
    fun `推荐优先不拆牌的最小牌_而不是无条件还最小牌`() {
        // 3 是对子、4 是三张，最小牌 3 会拆对；6 是孤张，应优先推荐 6
        val hand = listOf(sp(3), cl(3), sp(4), cl(4), di(4), sp(6), sp(11))
        val c = backCandidates(hand, 2)
        assertEquals(3, c.first().rank)
        assertEquals(6, recommendBack(hand, c, 2).rank)
    }

    @Test
    fun `AI受贡自动还牌也不拆对子`() {
        val hands = listOf(
            listOf(sp(8), sp(9)),
            listOf(sp(3), cl(3), sp(4), cl(5), di(6)),   // 头游 P1 受贡：3 成对、4 是孤张
            listOf(sp(7)),
            listOf(sp(10), sp(14), bigJ()),              // 末游 P3 进贡：最大单张是大王
        )
        val res = HandResult(listOf(1, 0, 2, 3), 1, 2, 3, 1, false)
        val plan = planTribute(res, hands, 2)
        val x = plan.exchanges.single()
        assertTrue("贡牌应为除逢人配外的最大单张", x.give.isBigJoker)
        assertEquals("不拆 3 的对子，改还孤张 4", 4, x.back.rank)
    }

    @Test
    fun `AI受贡自动还牌也不拆炸弹`() {
        val hands = listOf(
            listOf(sp(8), sp(9)),
            listOf(sp(5), he(5), cl(5), di(5), sp(6), sp(7)),  // 5 是四张炸弹
            listOf(sp(7)),
            listOf(sp(10), sp(14), bigJ()),
        )
        val res = HandResult(listOf(1, 0, 2, 3), 1, 2, 3, 1, false)
        assertEquals(6, planTribute(res, hands, 2).exchanges.single().back.rank)
    }

    // ---------------- ViewModel：人类收贡暂停 + 玩家自选 ----------------

    @Test
    fun `人类收贡时开局暂停_不提前公示且冻结出牌与AI`() {
        val vm = vmWaitingBack()
        val req = vm.uiState.pendingBack!!
        assertNull("还牌未定，不能提前公示", vm.uiState.tributePlan)
        assertTrue(req.candidates.isNotEmpty())
        assertEquals(req.candidates.size, req.risks.size)
        assertTrue("贡牌应已到自己手里", handsOf(vm)[0].any { it === req.give })
        assertEquals("收到贡牌后、还牌前应有 28 张", 28, handsOf(vm)[0].size)
        assertTrue("等待期不得写进贡战况", engineOf(vm).log.isEmpty())
        assertTrue("等待期 AI 不得出牌", engineOf(vm).playedCards.isEmpty())

        // 冻结：出牌 / 过 / 提示 / 选牌都不生效
        assertFalse(vm.humanPlay())
        assertFalse(vm.humanPass())
        vm.applyHint()
        vm.toggleSelect(handsOf(vm)[0].first())
        assertTrue(vm.uiState.selectedCards.isEmpty())
        assertNotNull(vm.uiState.pendingBack)
    }

    @Test
    fun `拒绝非候选牌_等待态与手牌不变`() {
        val vm = vmWaitingBack()
        val outsider = handsOf(vm)[1].first()   // 别人手里的实体牌，绝不在候选中
        val before = handsOf(vm)[0].toList()
        assertFalse(vm.chooseTributeBack(outsider))
        assertNotNull(vm.uiState.pendingBack)
        assertNull(vm.uiState.tributePlan)
        assertEquals(before.size, handsOf(vm)[0].size)
    }

    @Test
    fun `玩家选定还牌后生成最终公示_牌张真正落到对应玩家手里`() {
        val vm = vmWaitingBack()
        val req = vm.uiState.pendingBack!!
        // 故意不选推荐牌，验证「自己选」确实生效
        val chosen = req.candidates.lastOrNull { it !== req.suggested } ?: req.suggested
        val handNumBefore = vm.uiState.handNumber

        assertTrue(vm.chooseTributeBack(chosen))
        val plan = vm.uiState.tributePlan!!
        assertNull(vm.uiState.pendingBack)
        assertEquals(handNumBefore + 1, vm.uiState.handNumber)

        val x = plan.exchanges.first { it.receiver == 0 }
        assertTrue("公示的还牌必须是玩家选的那张实体牌", x.back === chosen)
        assertTrue("还牌应从自己手里移除", handsOf(vm)[0].none { it === chosen })
        assertTrue("还牌应回到进贡者手里", handsOf(vm)[x.giver].any { it === chosen })
        assertTrue(
            "贡牌应留在自己手里 give=${x.give.display} " +
                "giveInHand=${handsOf(vm)[0].indexOfFirst { it === x.give }} " +
                "giveIsChosen=${x.give === chosen} handSize=${handsOf(vm)[0].size} " +
                "exchanges=${plan.exchanges.map { "${it.giver}->${it.receiver}:${it.give.display}/${it.back.display}" }}",
            handsOf(vm)[0].any { it === x.give }
        )
        assertEquals("四家仍各 27 张", listOf(27, 27, 27, 27), handsOf(vm).map { it.size })
        assertTrue(engineOf(vm).log.contains(plan.notice))
        assertTrue("战况应含玩家所选还牌的具体牌面",
            engineOf(vm).log.any { it.contains(chosen.display) })
    }

    @Test
    fun `默认推荐不会拆掉对子三张或炸弹`() {
        val vm = vmWaitingBack()
        val req = vm.uiState.pendingBack!!
        val idx = req.candidates.indexOfFirst { it === req.suggested }
        val safe = req.candidates.indices.firstOrNull { req.risks[it] == BackRisk.NONE }
        if (safe != null) {
            assertEquals("存在不拆牌的候选时，推荐必须落在不拆牌的牌上",
                BackRisk.NONE, req.risks[idx])
        }
    }

    @Test
    fun `待选候选不含刚收到的贡牌与本局级牌`() {
        val vm = vmWaitingBack()
        val req = vm.uiState.pendingBack!!
        assertTrue("刚收到的贡牌不能被原样还回去", req.candidates.none { it === req.give })
        if (req.candidates.any { it.rank <= 10 }) {
            assertTrue("本局级牌 / 逢人配 / 王不得混进 ≤10 小牌候选",
                req.candidates.none { it.rank == vm.level || it.isWild(vm.level) || it.isJoker })
        }
    }

    @Test
    fun `重开一场会清掉待选还贡请求`() {
        val vm = vmWaitingBack()
        assertNotNull(vm.uiState.pendingBack)
        vm.newMatch(Difficulty.NORMAL)
        assertNull(vm.uiState.pendingBack)
        assertNull(vm.uiState.tributePlan)
    }
}
