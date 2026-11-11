package com.example.guandan.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GameViewModel 集成测试（JVM 单元测试，无需真机）。
 *
 * 关键点：ViewModel 的 AI 驱动跑在 viewModelScope（Dispatchers.Main）里，且每次出牌前有 650ms 延时。
 * 在 JVM 测试里用 UnconfinedTestDispatcher 替换 Main 调度器：
 * - 协程立即执行（Unconfined），无需等待真实线程；
 * - delay 走虚拟时间，由 scheduler.advanceUntilIdle() 一次性推进完。
 * 于是"AI 链一路推进到人类回合"变成确定性的同步行为。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GameViewModelTest {

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var vm: GameViewModel

    @Before
    fun setup() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        vm = GameViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 推进所有挂起的虚拟任务，直到 AI 链回到人类回合或本局结束。 */
    private fun settle() = scheduler.advanceUntilIdle()

    /**
     * 推进到「人类领出」局面（canPass=false，场上没有需要压过的牌）。
     * 领出时手上必然存在合法出牌，因此 hint() / applyHint() 不会因随机牌局而为空。
     * 标准接风规则会让对局轮次变多，极少数随机牌局下三家 AI 会先走完而提前结算，
     * 此时重开一局再推进，保证测试稳定。
     */
    private fun driveToHumanLead() {
        var guard = 0
        while (guard++ < 200) {
            settle()
            val st = vm.uiState
            if (st.phase != Phase.PLAYING) {
                vm.newMatch(Difficulty.HARD)
                settle()
                continue
            }
            if (st.currentPlayer != 0) { settle(); continue }
            if (!st.canPass) return
            if (!vm.humanPass()) return
        }
    }

    @Test
    fun `新开一局后结构正确且轮到人类`() {
        vm.newMatch(Difficulty.HARD)
        settle()

        assertEquals(4, vm.uiState.players.size)
        assertEquals(27, vm.uiState.humanHand.size)
        assertEquals("2", vm.uiState.levelText)
        // AI 链推进到人类回合为止（人类 27 张不可能先走完）
        assertEquals(0, vm.uiState.currentPlayer)
        assertEquals(Phase.PLAYING, vm.uiState.phase)
        assertEquals(27, vm.uiState.players[0].cardCount)
    }

    @Test
    fun `队友与对手标识正确`() {
        vm.newMatch(Difficulty.NORMAL)
        settle()

        // 0&2 一队、1&3 一队
        assertTrue(vm.uiState.players[0].isTeammate)
        assertTrue(vm.uiState.players[2].isTeammate)
        assertFalse(vm.uiState.players[1].isTeammate)
        assertFalse(vm.uiState.players[3].isTeammate)
    }

    @Test
    fun `选牌与取消选牌`() {
        vm.newMatch(Difficulty.NORMAL)
        settle()
        val card = vm.uiState.humanHand.first()

        vm.toggleSelect(card)
        assertTrue(vm.uiState.selectedCards.any { it === card })

        vm.toggleSelect(card)
        assertFalse(vm.uiState.selectedCards.any { it === card })
    }

    @Test
    fun `人类出牌后手牌减少且回到人类回合`() {
        vm.newMatch(Difficulty.HARD)
        settle()
        driveToHumanLead()

        // 用提示拿一手「保证合法」的牌（人类可能需要压过场上牌，不能随便选一张）
        val move = vm.hint()
        assertNotNull(move)
        assertTrue(move!!.isNotEmpty())

        vm.clearSelection()
        move.forEach { vm.toggleSelect(it) }
        vm.humanPlay()
        settle()

        assertEquals(27 - move.size, vm.uiState.players[0].cardCount)
        assertEquals(27 - move.size, vm.uiState.humanHand.size)
        assertEquals(0, vm.uiState.currentPlayer)
        assertTrue(vm.uiState.selectedCards.isEmpty())
    }

    @Test
    fun `两张同点同花色的牌可以同时选中`() {
        vm.newMatch(Difficulty.NORMAL)
        settle()

        // 两副牌里同点数同花色的牌有两张，必须能各自独立选中
        val hand = vm.uiState.humanHand
        val dup = hand.groupBy { it.rank to it.suit }.values.firstOrNull { it.size >= 2 }
        assertNotNull("发牌后应存在同点数同花色的两张牌", dup)
        val (a, b) = dup!!.take(2)

        vm.toggleSelect(a)
        vm.toggleSelect(b)
        assertEquals(2, vm.uiState.selectedCards.size)
        assertTrue(vm.isSelected(a))
        assertTrue(vm.isSelected(b))
    }

    @Test
    fun `提示会给出非空推荐`() {
        vm.newMatch(Difficulty.EASY)
        settle()
        driveToHumanLead()
        val hint = vm.hint()
        assertNotNull(hint)
        assertTrue(hint!!.isNotEmpty())

        vm.applyHint()
        assertEquals(hint.size, vm.uiState.selectedCards.size)
    }

    @Test
    fun `人类可完整打完一局直到本局结束`() {
        vm.newMatch(Difficulty.HARD)
        settle()

        var guard = 0
        while (vm.uiState.phase == Phase.PLAYING && guard < 2000) {
            if (vm.uiState.currentPlayer != 0) {
                settle()
                if (vm.uiState.currentPlayer != 0) break   // 本局已结束
                continue
            }
            val move = vm.hint()
            if (move.isNullOrEmpty()) {
                vm.humanPass()
            } else {
                vm.clearSelection()
                move.forEach { vm.toggleSelect(it) }
                vm.humanPlay()
            }
            settle()
            guard++
        }

        assertEquals(Phase.HAND_OVER, vm.uiState.phase)
        assertNotNull(vm.uiState.handResult)
        assertEquals(4, vm.uiState.handResult!!.finishOrder.size)
        // 升级数必须在 0..3
        assertTrue(vm.uiState.handResult!!.levelUp in 0..3)
    }

    @Test
    fun `级牌随升级递增`() {
        vm.newMatch(Difficulty.HARD)
        settle()
        assertEquals(2, vm.level)

        // 打到本局结束，验证升级后级牌随之变化
        var guard = 0
        while (vm.uiState.phase == Phase.PLAYING && guard < 2000) {
            if (vm.uiState.currentPlayer != 0) { settle(); if (vm.uiState.currentPlayer != 0) break; continue }
            val move = vm.hint()
            if (move.isNullOrEmpty()) vm.humanPass()
            else { vm.clearSelection(); move.forEach { vm.toggleSelect(it) }; vm.humanPlay() }
            settle()
            guard++
        }
        assertEquals(Phase.HAND_OVER, vm.uiState.phase)
        // 胜方至少升 1 级
        assertTrue(vm.level > 2)
    }

    // ---------------- 回归：屏幕适配与「没法出牌」----------------

    @Test
    fun `选牌会改变 uiState 快照并递增 revision`() {
        vm.newMatch(Difficulty.NORMAL)
        settle()
        val before = vm.uiState
        val card = vm.uiState.humanHand.first()

        vm.toggleSelect(card)

        // 回归「选牌不刷新 UI」：copy 出的对象必须与旧对象 equals 不同，
        // 否则 mutableStateOf 的 structuralEqualityPolicy 不通知重组，出牌按钮会一直灰着。
        assertNotEquals(before, vm.uiState)
        assertTrue(vm.uiState.revision > before.revision)
        assertTrue(vm.uiState.selectedCards.any { it === card })
    }

    @Test
    fun `出牌成功返回 true 并清空选中`() {
        vm.newMatch(Difficulty.HARD)
        settle()
        driveToHumanLead()
        val move = vm.hint()!!
        vm.applyHint()
        assertTrue(vm.uiState.selectedCards.isNotEmpty())
        val before = vm.uiState.players[0].cardCount

        assertTrue(vm.humanPlay())
        settle()

        assertEquals(before - move.size, vm.uiState.players[0].cardCount)
        assertTrue(vm.uiState.selectedCards.isEmpty())
        assertEquals(null, vm.uiState.errorText)
    }

    @Test
    fun `未选牌点出牌会提示而不是无反应`() {
        vm.newMatch(Difficulty.NORMAL)
        settle()
        vm.clearSelection()

        assertFalse(vm.humanPlay())
        assertNotNull(vm.uiState.errorText)   // 用户能看到原因
    }

    @Test
    fun `连续重开后仍能正常出牌`() {
        vm.newMatch(Difficulty.HARD)
        settle()
        // 模拟用户在 AI 思考期间反复点「重开」：旧协程不得污染新一局
        repeat(3) {
            vm.newMatch(Difficulty.HARD)
            settle()
        }
        assertEquals(0, vm.uiState.currentPlayer)
        assertEquals(27, vm.uiState.humanHand.size)

        driveToHumanLead()
        val move = vm.hint()
        assertNotNull(move)
        vm.applyHint()
        assertTrue(vm.humanPlay())
        settle()
        assertEquals(0, vm.uiState.currentPlayer)
    }

    @Test
    fun `非法牌型出牌失败并给出原因`() {
        vm.newMatch(Difficulty.HARD)
        settle()
        // 3 张点数互不相同、且都不是百搭的牌，不可能构成任何合法牌型
        val distinct = vm.uiState.humanHand
            .filter { !it.isJoker && !it.isWild(vm.level) && it.rank >= 3 }
            .distinctBy { it.rank }
            .take(3)
        assertEquals(3, distinct.size)

        vm.clearSelection()
        distinct.forEach { vm.toggleSelect(it) }

        assertFalse(vm.humanPlay())
        assertNotNull(vm.uiState.errorText)
        assertEquals(3, vm.uiState.selectedCards.size)   // 选牌保留，方便用户改
    }

    // ---------------- 回归：智能轮循提示 ----------------

    @Test
    fun `提示轮循依次给出不同且合法的候选`() {
        vm.newMatch(Difficulty.HARD)
        settle()
        driveToHumanLead()
        val picks = (1..8).map {
            vm.applyHint()
            vm.uiState.selectedCards.toList()
        }
        picks.forEach { pick ->
            assertTrue(pick.isNotEmpty())
            assertNotNull("每一手提示都必须是合法牌型", recognize(pick, vm.level))
        }
        val total = vm.uiState.hintLabel!!
            .substringAfter("提示 ").substringBefore("/").toInt()
        if (total > 1) {
            assertTrue("候选多于 1 手时，连续提示必须轮循出不同组合",
                picks.distinct().size >= 2)
        }
    }

    @Test
    fun `提示轮循标签显示序号总数与牌型`() {
        vm.newMatch(Difficulty.HARD)
        settle()
        driveToHumanLead()
        vm.applyHint()
        val first = vm.uiState.hintLabel
        assertNotNull(first)
        assertTrue(first!!.contains("1/"))
        assertTrue(first.contains("·"))
        vm.applyHint()
        val second = vm.uiState.hintLabel
        assertNotNull(second)
        assertTrue(second!!.contains("2/"))
    }

    @Test
    fun `出牌后提示缓存失效并从头开始轮循`() {
        vm.newMatch(Difficulty.HARD)
        settle()
        driveToHumanLead()
        vm.applyHint()
        vm.applyHint()
        assertTrue(vm.uiState.hintLabel!!.contains("2/"))
        assertTrue(vm.humanPlay())     // 出第二手提示选中的牌
        settle()
        vm.applyHint()
        // 出牌后局面键变化，缓存必然重建：有候选时从第 1 手重新开始；
        // 若这一手出完恰好被 AI 顶到「无牌可压」，则正确行为是提示点「过」。
        val label = vm.uiState.hintLabel
        if (label != null) {
            assertTrue("局面变化后应从第 1 手重新开始，实测 $label", label.contains("1/"))
        } else {
            assertTrue("无合法候选时应给出原因而不是静默",
                vm.uiState.errorText?.contains("没有能压过的牌") == true)
        }
    }

    // ---------------- 回归：设置（难度 / AI 速度）----------------

    @Test
    fun `调整难度立即生效且不重开当前牌局`() {
        vm.newMatch(Difficulty.NORMAL)
        settle()
        val before = vm.uiState.players[0].cardCount
        val beforeRev = vm.uiState.revision

        vm.setDifficulty(Difficulty.HARD)

        assertEquals(Difficulty.HARD, vm.difficulty)
        assertEquals(Difficulty.HARD, vm.uiState.difficulty)
        assertEquals("调难度不应重开牌局", before, vm.uiState.players[0].cardCount)
        assertTrue(vm.uiState.revision > beforeRev)
        assertEquals(0, vm.uiState.currentPlayer)
    }

    @Test
    fun `调整AI速度即时更新状态且不影响牌局`() {
        vm.newMatch(Difficulty.NORMAL)
        settle()
        val before = vm.uiState.humanHand.size

        vm.setAiSpeed(AiSpeed.SLOW)

        assertEquals(AiSpeed.SLOW, vm.aiSpeed)
        assertEquals(AiSpeed.SLOW, vm.uiState.aiSpeed)
        assertEquals(before, vm.uiState.humanHand.size)
    }

    @Test
    fun `startIfNeeded 只在未开局时开一局且沿用当前难度`() {
        vm.setDifficulty(Difficulty.HARD)
        vm.startIfNeeded()
        settle()
        assertEquals(27, vm.uiState.humanHand.size)
        assertEquals(Difficulty.HARD, vm.difficulty)
        assertEquals(0, vm.uiState.currentPlayer)

        // 再次调用不应重开（手牌数保持、级牌仍为 2）
        vm.startIfNeeded()
        assertEquals(27, vm.uiState.humanHand.size)
        assertEquals(2, vm.level)
    }
}
