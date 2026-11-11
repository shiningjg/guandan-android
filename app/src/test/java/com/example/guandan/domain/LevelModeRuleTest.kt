package com.example.guandan.domain

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Field

/**
 * v1.0.11 专项：设置里可选的两种升级方式。
 *
 * - 共用级数（SHARED，默认/原有行为）：全场一条级数，无论哪队赢都往上加。
 * - 各自记级（PER_TEAM，标准）：两队各自从 2 记级、只有上游方升级；
 *   下一副级牌取「上副赢家升到的级数」；只有自己级数到 A 的一方打 A 且
 *   头游+搭档非末游才算过 A 夺冠。
 *
 * 用反射构造受控局面，避免依赖随机发牌。
 */
class LevelModeRuleTest {

    private fun f(any: Any, name: String): Field =
        any.javaClass.getDeclaredField(name).also { it.isAccessible = true }
    private fun set(any: Any, name: String, value: Any?) = f(any, name).set(any, value)
    private fun setInt(any: Any, name: String, value: Int) = f(any, name).setInt(any, value)

    private fun levelsOf(vm: GameViewModel): IntArray = f(vm, "teamLevels").get(vm) as IntArray

    private fun finishedEngine(level: Int, order: List<Int>): GameEngine {
        val e = GameEngine(level, List(4) { emptyList<Card>() }, leader = 0)
        e.finishOrder.addAll(order)
        set(e, "phase", Phase.HAND_OVER)
        return e
    }

    /**
     * 构造一个待结算局面。
     * @param order 名次；头游所在队即赢家。partnerPos 决定 levelUp：
     *   队友二游→3（双上）、三游→2（单上）、末游→1（单下）。
     */
    private fun vmAt(
        mode: LevelMode,
        level: Int,
        myLevel: Int = level,
        oppLevel: Int = level,
        order: List<Int> = listOf(0, 1, 2, 3),
    ): GameViewModel {
        val vm = GameViewModel()
        set(vm, "levelMode", mode)
        setInt(vm, "level", level)
        val tl = levelsOf(vm)
        tl[0] = myLevel
        tl[1] = oppLevel
        set(vm, "engine", finishedEngine(level, order))
        return vm
    }

    private fun callOnHandOver(vm: GameViewModel) {
        val m = GameViewModel::class.java.getDeclaredMethod("onHandOver")
        m.isAccessible = true
        m.invoke(vm)
    }

    /** 重新结算下一副：清掉去重标记并换一个已结束引擎（级数沿用 ViewModel 现值）。 */
    private fun settleAgain(vm: GameViewModel, order: List<Int>) {
        set(vm, "handSettled", false)
        set(vm, "engine", finishedEngine(vm.level, order))
        callOnHandOver(vm)
    }

    // ---------------- 默认与持久化入口 ----------------

    @Test
    fun `默认升级方式为共用级数_老用户不突兀`() {
        val vm = GameViewModel()
        assertEquals(LevelMode.SHARED, vm.levelMode)
        assertEquals(LevelMode.SHARED, vm.uiState.levelMode)
    }

    @Test
    fun `级数文字映射正确`() {
        assertEquals("2", levelLabel(2))
        assertEquals("10", levelLabel(10))
        assertEquals("J", levelLabel(11))
        assertEquals("Q", levelLabel(12))
        assertEquals("K", levelLabel(13))
        assertEquals("A", levelLabel(14))
    }

    // ---------------- 共用级数：原行为保持不变 ----------------

    @Test
    fun `共用级数_不论哪队赢都累加同一条级数`() {
        // 对手（头游 P1）赢且双上 → level 2 + 3 = 5，与赢家是哪队无关
        val vm = vmAt(LevelMode.SHARED, 2, order = listOf(1, 3, 0, 2))
        callOnHandOver(vm)
        assertEquals(5, vm.level)
        assertEquals("5", vm.uiState.levelText)
        assertEquals(5, vm.uiState.myLevel)
        assertEquals(5, vm.uiState.opponentLevel)
        assertNull(vm.uiState.matchWinner)
    }

    @Test
    fun `共用级数_打A时赢家直接夺冠`() {
        val vm = vmAt(LevelMode.SHARED, 14, order = listOf(1, 3, 0, 2))
        callOnHandOver(vm)
        assertEquals(1, vm.uiState.matchWinner)
    }

    // ---------------- 各自记级：只有赢家升级 ----------------

    @Test
    fun `各自记级_只有赢家队伍升级_输家级数不变`() {
        // 你方单上（+2）：2→4；对手仍 2
        val vm = vmAt(LevelMode.PER_TEAM, 2, order = listOf(0, 1, 2, 3))
        callOnHandOver(vm)
        assertEquals(4, levelsOf(vm)[0])
        assertEquals(2, levelsOf(vm)[1])
        assertEquals(4, vm.uiState.myLevel)
        assertEquals(2, vm.uiState.opponentLevel)
        // 下一副级牌 = 上副赢家升到的级数
        assertEquals(4, vm.level)
        assertTrue(vm.uiState.message.contains("你方 4"))
        assertTrue(vm.uiState.message.contains("对手 2"))
    }

    @Test
    fun `各自记级_级牌随赢家切换_对手赢时切换到对手的级数`() {
        // 第一副你方赢到 6（双上 +3 从 3 起）
        val vm = vmAt(LevelMode.PER_TEAM, 3, myLevel = 3, oppLevel = 3,
            order = listOf(0, 2, 1, 3))
        callOnHandOver(vm)
        assertEquals(6, levelsOf(vm)[0])
        assertEquals(6, vm.level)

        // 第二副对手赢（单上 +2）：对手 3→5，你方仍 6；下一副改打 5
        settleAgain(vm, listOf(1, 0, 3, 2))
        assertEquals(6, levelsOf(vm)[0])
        assertEquals(5, levelsOf(vm)[1])
        assertEquals(5, vm.level)
        assertEquals("5", vm.uiState.levelText)
        assertNull("打 6 这副没人过 A", vm.uiState.matchWinner)
    }

    @Test
    fun `各自记级_级数钳制到A_不能跳过A`() {
        // 你方 13 起、双上 +3 → 钳到 14，但本副打的不是 A，不算过 A
        val vm = vmAt(LevelMode.PER_TEAM, 13, order = listOf(0, 2, 1, 3))
        callOnHandOver(vm)
        assertEquals(14, levelsOf(vm)[0])
        assertEquals(14, vm.level)
        assertNull(vm.uiState.matchWinner)
    }

    // ---------------- 各自记级：过 A 资格 ----------------

    @Test
    fun `各自记级_自己级数到A且打A头游非末游_夺冠`() {
        // 本副打 A（上副你方升到 14），你方单上 +2
        val vm = vmAt(LevelMode.PER_TEAM, 14, myLevel = 14, oppLevel = 8,
            order = listOf(0, 1, 2, 3))
        callOnHandOver(vm)
        assertEquals(0, vm.uiState.matchWinner)
        assertTrue(vm.uiState.message.contains("夺冠"))
    }

    @Test
    fun `各自记级_对手级数还低时抢到打A那副头游_不算过A`() {
        // 本副打的是你方的 A；对手（级数 8）抢到头游且双上 → 不算过 A，
        // 对手按自己级数升到 11，下一副改打 11；你方仍是 A，等待下次打自己的 A
        val vm = vmAt(LevelMode.PER_TEAM, 14, myLevel = 14, oppLevel = 8,
            order = listOf(1, 3, 0, 2))
        callOnHandOver(vm)
        assertNull("对手级数没到 A，赢这一副不算过 A", vm.uiState.matchWinner)
        assertEquals(14, levelsOf(vm)[0])
        assertEquals(11, levelsOf(vm)[1])
        assertEquals(11, vm.level)
        assertTrue(vm.uiState.message.contains("未过 A"))
    }

    @Test
    fun `各自记级_打A方头游但队友末游_不算过A且继续打A`() {
        val vm = vmAt(LevelMode.PER_TEAM, 14, myLevel = 14, oppLevel = 10,
            order = listOf(0, 1, 3, 2))
        callOnHandOver(vm)
        assertNull(vm.uiState.matchWinner)
        assertEquals(14, levelsOf(vm)[0])
        assertEquals(14, vm.level)   // 继续打 A
        assertTrue(vm.uiState.message.contains("打 A 未过"))
    }

    @Test
    fun `各自记级_在自己A级被对手抢级后赢回来_下一副又打自己的A`() {
        // 你方已到 A，但本副因对手上副赢而打对手的级 9；你方赢回 → 下一副打回 A
        val vm = vmAt(LevelMode.PER_TEAM, 9, myLevel = 14, oppLevel = 9,
            order = listOf(0, 2, 1, 3))
        callOnHandOver(vm)
        assertEquals(14, levelsOf(vm)[0])
        assertEquals(9, levelsOf(vm)[1])
        assertEquals(14, vm.level)
        assertNull("当前这副打的是 9，不是打自己的 A，不算过 A", vm.uiState.matchWinner)
    }

    @Test
    fun `各自记级_双方都到A时先过者夺冠`() {
        val vm = vmAt(LevelMode.PER_TEAM, 14, myLevel = 14, oppLevel = 14,
            order = listOf(1, 0, 3, 2))
        callOnHandOver(vm)
        assertEquals(1, vm.uiState.matchWinner)
    }

    @Test
    fun `各自记级_打A三次不过_双方级数退回2`() {
        val vm = vmAt(LevelMode.PER_TEAM, 14, myLevel = 14, oppLevel = 10,
            order = listOf(0, 1, 3, 2))
        repeat(3) {
            set(vm, "handSettled", false)
            set(vm, "engine", finishedEngine(14, listOf(0, 1, 3, 2)))
            callOnHandOver(vm)
        }
        assertEquals(2, vm.level)
        assertEquals(2, levelsOf(vm)[0])
        assertEquals(2, levelsOf(vm)[1])
        assertEquals(2, vm.uiState.myLevel)
        assertEquals(2, vm.uiState.opponentLevel)
        assertTrue(vm.uiState.message.contains("退回打 2"))
    }
}
