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
import java.util.Random

/**
 * v1.0.8 专项测试：
 *  - 团队配合「送对家走」软策略：对家报单且自己走不完时领出最小单张，
 *    不拆炸弹/三张/对子/顺子，自己能一手走完或对家非报单则不送，只在领出触发，HARD 确定性。
 *  - 进贡 / 还贡 / 抗贡「公示」（公开信息）：v1.0.9 起移到**下一局发牌后的开局信息框**，
 *    结算阶段不公示；贡 / 还牌真正落到对应玩家手里（不再二次洗牌），夺冠不公示。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StandardRuleV108Test {

    // level=10：避免 2~7 里出现级牌导致 cardStrength 与 rank 不一致，便于断言「最小单张」
    private val level = 10
    private fun sp(r: Int) = Card(r, Suit.SPADES)
    private fun he(r: Int) = Card(r, Suit.HEARTS)
    private fun cl(r: Int) = Card(r, Suit.CLUBS)
    private fun di(r: Int) = Card(r, Suit.DIAMONDS)
    private fun bigJ() = Card(RANK_BIG_JOKER, Suit.JOKER)

    private fun f(any: Any, name: String): Field =
        any.javaClass.getDeclaredField(name).also { it.isAccessible = true }
    private fun set(any: Any, name: String, value: Any?) = f(any, name).set(any, value)
    private fun setInt(any: Any, name: String, value: Int) = f(any, name).setInt(any, value)

    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    /** P2（对家、AI）领出，mate=(2+2)%4=0 是人类，handCounts[0] 控制其是否报单。 */
    private fun leadCounts(humanCards: Int, aiCards: Int) = listOf(humanCards, 5, aiCards, 5)

    // ---------------- 送对家走（团队配合） ----------------

    @Test
    fun `对家报单且自己走不完_HARD领出送单张_而非按常规领出打组合`() {
        val hand = listOf(sp(3), he(4), cl(5), di(6), sp(7), cl(2))  // 3-7 顺子 + 孤立 2
        val legal = MoveGenerator.legalPlays(hand, null, level)

        // 常规领出（对家没报单）会优先打 5 张顺子
        val normal = HeuristicAi.decideMove(
            2, hand, null, null, level, Difficulty.HARD, leadCounts(5, 6), Random(1)
        )
        assertEquals("常规领出应优先多张组合", 5, normal.size)

        // 对家报单、自己无法一手走完 → 改为送一手单张，让对家 1 张牌有机会接走
        val send = HeuristicAi.decideMove(
            2, hand, null, null, level, Difficulty.HARD, leadCounts(1, 6), Random(1)
        )
        assertEquals("送牌必须领出单张，否则只剩 1 张的对家接不了", 1, send.size)
        assertEquals(PatternType.SINGLE, recognize(send, level)!!.type)
        assertEquals("应送最小的干净单张", 2, send.first().rank)
    }

    @Test
    fun `送牌是确定性且HARD任意随机种子都执行`() {
        val hand = listOf(sp(3), he(4), cl(5), di(6), sp(7), cl(2))
        for (seed in 1L..30L) {
            val legal = MoveGenerator.legalPlays(hand, null, level)
            val pick = HeuristicAi.leadToHelpTeammate(
                2, hand, legal, level, Difficulty.HARD, leadCounts(1, 6), Random(seed)
            )
            assertNotNull("HARD 应确定性送牌（seed=$seed）", pick)
            assertEquals(1, pick!!.size)
        }
    }

    @Test
    fun `对家不是报单时不送`() {
        val hand = listOf(sp(3), he(4), cl(5), di(6), sp(7), cl(2))
        val legal = MoveGenerator.legalPlays(hand, null, level)
        for (c in listOf(0, 2, 3, 5, 10)) {
            assertNull(
                "对家 $c 张不该触发送牌",
                HeuristicAi.leadToHelpTeammate(2, hand, legal, level, Difficulty.HARD, leadCounts(c, 6), Random(1))
            )
        }
    }

    @Test
    fun `自己能一手走完时自己争头游不送`() {
        val hand = listOf(sp(3), he(3))   // 一对 = 一手清空
        val legal = MoveGenerator.legalPlays(hand, null, level)
        val pick = HeuristicAi.leadToHelpTeammate(
            2, hand, legal, level, Difficulty.HARD, leadCounts(1, 2), Random(1)
        )
        assertNull("自己能一手走完应自己走，不送", pick)
    }

    @Test
    fun `送牌不拆炸弹_三张_对子`() {
        // 4 张 6（炸弹）+ 1 张 3：干净单张只有 3，不能从炸弹里抽 6 去送
        val hand = listOf(sp(6), he(6), cl(6), di(6), sp(3))
        val legal = MoveGenerator.legalPlays(hand, null, level)
        val pick = HeuristicAi.leadToHelpTeammate(
            2, hand, legal, level, Difficulty.HARD, leadCounts(1, 5), Random(1)
        )
        assertNotNull(pick)
        assertEquals(1, pick!!.size)
        assertEquals("不该拆 4 张 6 的炸弹去送单张", 3, pick.first().rank)
    }

    @Test
    fun `送牌优先不拆顺子_没有孤立小牌时宁选孤立单张`() {
        // 3-7 顺子 + 孤立 K：3~7 都在顺子窗口内，只有 K 是孤立单张
        val hand = listOf(sp(3), he(4), cl(5), di(6), sp(7), cl(13))
        val legal = MoveGenerator.legalPlays(hand, null, level)
        val pick = HeuristicAi.leadToHelpTeammate(
            2, hand, legal, level, Difficulty.HARD, leadCounts(1, 6), Random(1)
        )
        assertNotNull(pick)
        assertEquals(1, pick!!.size)
        assertEquals("应保留顺子、送孤立单张 K", 13, pick.first().rank)
    }

    @Test
    fun `跟牌阶段不触发送牌_仍按压制逻辑走`() {
        val hand = listOf(sp(3), sp(9), he(13))
        val target = recognize(listOf(cl(5)), level)!!
        // 即便对家报单，跟牌也必须压过上家，不会去「送」
        val move = HeuristicAi.decideMove(
            2, hand, target, 1, level, Difficulty.HARD, leadCounts(1, 3), Random(1)
        )
        assertTrue("跟牌应压过单张 5", move.isNotEmpty())
        assertTrue(beats(target, recognize(move, level)!!))
    }

    @Test
    fun `加入送牌策略后AI自对弈仍能正常结束`() {
        for (seed in listOf(1L, 7L, 42L)) {
            val hands = Deck.deal(Random(seed))
            val engine = GameEngine(2, hands, leader = 0)
            var guard = 0
            while (engine.phase == Phase.PLAYING && guard < 3000) {
                guard++
                val p = engine.current
                val move = HeuristicAi.decideMove(
                    p, engine.handOf(p), engine.lastPlay?.pattern, engine.lastPlay?.player,
                    2, Difficulty.HARD, engine.hands.map { it.size }, Random(seed + guard)
                )
                val ok = if (move.isEmpty()) engine.pass(p) else engine.play(p, move)
                assertTrue("AI 返回非法操作：$move", ok)
            }
            assertEquals("对局应正常结束 seed=$seed", Phase.HAND_OVER, engine.phase)
            assertEquals(4, engine.finishOrder.toSet().size)
        }
    }

    // ---------------- 进贡 / 还贡 / 抗贡公示（公开信息） ----------------

    private fun hr(order: List<Int>, doubleDown: Boolean = false, levelUp: Int = 2, winner: Int = 0) =
        HandResult(order, winner, levelUp, order[3], order[0], doubleDown)

    @Test
    fun `单贡公示带具体贡牌与还牌_战况含牌面文字`() {
        val hands = listOf(
            listOf(sp(3), sp(9), sp(14)),                 // P0 头游收贡
            listOf(sp(4)), listOf(sp(5)),
            listOf(he(2), sp(14), bigJ(), sp(9)),         // P3 末游进贡
        )
        val plan = planTribute(hr(listOf(0, 1, 2, 3)), hands, 2)
        assertFalse(plan.antiTribute)
        assertEquals(1, plan.exchanges.size)
        val x = plan.exchanges[0]
        assertTrue("贡牌应为除红心级牌外的最大单张（大王）", x.give.isBigJoker)
        assertTrue("还贡牌点 ≤10", x.back.rank <= 10)
        val lines = tributeLogLines(plan)
        assertEquals(1, lines.size)
        assertTrue("战况应含贡牌牌面 ${lines[0]}", lines[0].contains(x.give.display))
        assertTrue("战况应含还牌牌面 ${lines[0]}", lines[0].contains(x.back.display))
    }

    @Test
    fun `双贡公示给出两条明细_各自含牌面`() {
        val hands = listOf(
            listOf(sp(3), sp(10), sp(14)),
            listOf(sp(13), sp(8)),
            listOf(sp(4), sp(9)),
            listOf(sp(12), sp(7)),
        )
        val plan = planTribute(hr(listOf(0, 2, 1, 3), doubleDown = true, levelUp = 3), hands, 5)
        assertEquals(2, plan.exchanges.size)
        val lines = tributeLogLines(plan)
        assertEquals(2, lines.size)
        plan.exchanges.forEachIndexed { i, x ->
            assertTrue(lines[i].contains(x.give.display))
            assertTrue(lines[i].contains(x.back.display))
        }
    }

    @Test
    fun `抗贡公示列出抗贡者与亮出的双大王`() {
        val hands = listOf(
            listOf(sp(3), sp(4)), listOf(sp(5)),
            listOf(sp(6)), listOf(bigJ(), bigJ(), sp(9)),
        )
        val plan = planTribute(hr(listOf(0, 1, 2, 3)), hands, 2)
        assertTrue(plan.antiTribute)
        assertTrue(plan.exchanges.isEmpty())
        assertEquals(listOf(3), plan.antiTributeBy)
        assertEquals(2, plan.antiTributeCards.size)
        assertTrue("亮出的应都是大王", plan.antiTributeCards.all { it.isBigJoker })
        assertTrue(tributeLogLines(plan).any { it.contains("双大王抗贡") })
    }

    @Test
    fun `双下两人各持双大王_两人都列入公示且共亮四张大王`() {
        val hands = listOf(
            listOf(sp(3), sp(4)),
            listOf(bigJ(), bigJ(), sp(5)),              // 三游持双大王
            listOf(sp(6)),
            listOf(bigJ(), bigJ(), sp(7)),              // 末游持双大王
        )
        val plan = planTribute(hr(listOf(0, 2, 1, 3), doubleDown = true, levelUp = 3), hands, 2)
        assertTrue(plan.antiTribute)
        assertEquals(2, plan.antiTributeBy.size)
        assertEquals(4, plan.antiTributeCards.size)
        assertTrue(plan.antiTributeCards.all { it.isBigJoker })
    }

    // ---------------- ViewModel：结算不公示、发牌后开局才公示 ----------------

    /** 造一个已结算引擎，名次由 order 指定。 */
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
    private fun engineOf(vm: GameViewModel): GameEngine =
        f(vm, "engine").get(vm) as GameEngine

    /** v1.0.10：人类收贡时 nextHand 会暂停等玩家挑还牌；测试里按推荐牌确认后再看公示。 */
    private fun settleBack(vm: GameViewModel) {
        vm.uiState.pendingBack?.let { assertTrue(vm.chooseTributeBack(it.suggested)) }
    }

    /** 108 张真实牌，且把两张大王都放到 P3，用于构造抗贡（nextLeader=头游 P0，不驱动 AI）。 */
    private fun handsWithDoubleKingAtP3(): List<MutableList<Card>> {
        val all = Deck.full()
        val kings = all.filter { it.isBigJoker }
        val rest = all.filter { !it.isBigJoker }
        val h3 = (kings + rest.take(25)).toMutableList()
        val remaining = rest.drop(25)
        return listOf(
            remaining.take(27).toMutableList(),
            remaining.drop(27).take(27).toMutableList(),
            remaining.drop(54).take(27).toMutableList(),
            h3,
        )
    }

    @Test
    fun `结算阶段不公示进贡_下一局发牌后才开局公示`() {
        val vm = GameViewModel()
        setInt(vm, "level", 2)
        set(vm, "engine", finishedEngine(2, listOf(0, 1, 2, 3)))
        set(vm, "hands", handsWithDoubleKingAtP3())
        callOnHandOver(vm)

        assertNotNull(vm.uiState.handResult)
        assertNull("结算面板不应提前剧透下一局进贡（规范 5.1.3 / 5.5）", vm.uiState.tributePlan)

        vm.nextHand()
        settleBack(vm)
        val shown = vm.uiState.tributePlan
        assertNotNull("新一局发牌后应给出开局公示", shown)
        assertTrue(shown!!.exchanges.isNotEmpty() || shown.antiTribute)
        assertTrue("公示明细应写入本局战况", engineOf(vm).log.contains(shown.notice))
    }

    @Test
    fun `下一局发牌后才公示_贡还牌真正落到对应玩家手里_不再二次洗牌`() {
        val vm = GameViewModel()
        setInt(vm, "level", 2)
        set(vm, "engine", finishedEngine(2, listOf(0, 1, 2, 3)))
        set(vm, "hands", handsWithDoubleKingAtP3())
        callOnHandOver(vm)
        assertNull(vm.uiState.tributePlan)

        vm.nextHand()
        settleBack(vm)
        val shown = vm.uiState.tributePlan!!
        val e = engineOf(vm)
        assertTrue("战况应含公示明细", e.log.contains(shown.notice))
        assertEquals("公示的领出者应成为本局首位行动者", shown.nextLeader, e.leader)

        // 关键回归：旧实现「交换完再 Deal.split 洗牌」，贡 / 还牌会被洗乱而消失。
        // 现在直接读 ViewModel 交换后的 hands（引擎 hands 是构造时的拷贝，可能被 AI 出牌改动）。
        @Suppress("UNCHECKED_CAST")
        val hands = f(vm, "hands").get(vm) as List<MutableList<Card>>
        for (x in shown.exchanges) {
            assertTrue("贡牌 ${x.give.display} 应落到 P${x.receiver} 手里",
                hands[x.receiver].any { it === x.give })
            assertTrue("还牌 ${x.back.display} 应回到 P${x.giver} 手里",
                hands[x.giver].any { it === x.back })
            assertEquals("每人仍应恰好 27 张", 27, hands[x.giver].size)
            assertEquals("每人仍应恰好 27 张", 27, hands[x.receiver].size)
        }
    }

    @Test
    fun `本场夺冠无下一局_不产生进贡公示`() {
        val vm = GameViewModel()
        setInt(vm, "level", 14)
        set(vm, "engine", finishedEngine(14, listOf(0, 2, 1, 3)))
        set(vm, "hands", handsWithDoubleKingAtP3())
        callOnHandOver(vm)
        assertNotNull(vm.uiState.matchWinner)
        assertNull("夺冠本场结束，无需公示下一局进贡", vm.uiState.tributePlan)
    }
}
