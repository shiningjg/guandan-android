package com.example.guandan.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 设置持久化：难度与 AI 速度写入 SharedPreferences 后，新实例应能读回。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsPersistenceTest {

    @Test
    fun `难度与AI速度写入后新实例可读回`() {
        val ctx = RuntimeEnvironment.getApplication()
        val first = GameViewModel(ctx)
        first.setDifficulty(Difficulty.HARD)
        first.setAiSpeed(AiSpeed.FAST)

        val second = GameViewModel(ctx)
        assertEquals(Difficulty.HARD, second.difficulty)
        assertEquals(AiSpeed.FAST, second.aiSpeed)
        assertEquals(Difficulty.HARD, second.uiState.difficulty)
        assertEquals(AiSpeed.FAST, second.uiState.aiSpeed)
    }

    @Test
    fun `未传Context时退化为默认值且不抛异常`() {
        val vm = GameViewModel()
        assertEquals(Difficulty.NORMAL, vm.difficulty)
        assertEquals(AiSpeed.NORMAL, vm.aiSpeed)
        vm.setDifficulty(Difficulty.EASY)     // 无 Context 也不应崩
        vm.setAiSpeed(AiSpeed.SLOW)
        assertEquals(Difficulty.EASY, vm.difficulty)
        assertEquals(AiSpeed.SLOW, vm.aiSpeed)
    }

    @Test
    fun `三档AI速度延时严格递增`() {
        assertTrue(AiSpeed.FAST.delayMs < AiSpeed.NORMAL.delayMs)
        assertTrue(AiSpeed.NORMAL.delayMs < AiSpeed.SLOW.delayMs)
    }

    @Test
    fun `记牌器总开关写入后新实例可读回`() {
        val ctx = RuntimeEnvironment.getApplication()
        val first = GameViewModel(ctx)
        first.setCounterEnabled(false)
        org.junit.Assert.assertFalse(first.uiState.counterEnabled)

        val second = GameViewModel(ctx)
        org.junit.Assert.assertFalse(second.counterEnabled)
        org.junit.Assert.assertFalse(second.uiState.counterEnabled)

        second.setCounterEnabled(true)   // 复位，避免污染其它测试
        assertTrue(GameViewModel(ctx).counterEnabled)
    }

    @Test
    fun `未传Context时记牌器默认开启且不抛异常`() {
        val vm = GameViewModel()
        assertTrue(vm.uiState.counterEnabled)
        vm.setCounterEnabled(false)
        org.junit.Assert.assertFalse(vm.uiState.counterEnabled)
    }

    // ---------------- v1.0.11：升级方式 ----------------

    @Test
    fun `升级方式写入后新实例可读回`() {
        val ctx = RuntimeEnvironment.getApplication()
        val first = GameViewModel(ctx)
        first.setLevelMode(LevelMode.PER_TEAM)
        assertEquals(LevelMode.PER_TEAM, first.levelMode)
        assertEquals(LevelMode.PER_TEAM, first.uiState.levelMode)

        val second = GameViewModel(ctx)
        assertEquals(LevelMode.PER_TEAM, second.levelMode)
        assertEquals(LevelMode.PER_TEAM, second.uiState.levelMode)
        assertEquals(2, second.level)
        assertEquals(2, second.uiState.myLevel)
        assertEquals(2, second.uiState.opponentLevel)

        second.setLevelMode(LevelMode.SHARED)   // 复位，避免污染其它测试
        assertEquals(LevelMode.SHARED, GameViewModel(ctx).levelMode)
    }

    @Test
    fun `切换升级方式会重开一场且双方级数回到2`() {
        val vm = GameViewModel()
        assertEquals(LevelMode.SHARED, vm.levelMode)
        vm.setLevelMode(LevelMode.PER_TEAM)
        assertEquals(LevelMode.PER_TEAM, vm.levelMode)
        assertEquals(LevelMode.PER_TEAM, vm.uiState.levelMode)
        assertEquals(2, vm.level)
        assertEquals(2, vm.uiState.myLevel)
        assertEquals(2, vm.uiState.opponentLevel)
        org.junit.Assert.assertNull(vm.uiState.matchWinner)
    }

    @Test
    fun `未传Context时升级方式默认共用级数`() {
        val vm = GameViewModel()
        assertEquals(LevelMode.SHARED, vm.levelMode)
        assertEquals(LevelMode.SHARED, vm.uiState.levelMode)
        vm.setLevelMode(LevelMode.PER_TEAM)   // 无 Context 也不应崩
        assertEquals(LevelMode.PER_TEAM, vm.levelMode)
    }
}
