package com.example.guandan.domain

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Field
import java.util.Random

/**
 * v1.0.7 专项测试：
 *  - 规范 4.3.2 / 5.3.2：对大王、对小王各自成对；混合王对非法；百搭不能当王（5.7）。
 *  - 规范 5.7：红桃级牌按「所替代的牌点」入顺子，多种解释取最大牌型。
 *  - 三带二带牌不甩大对子；百搭撑起的牌组允许拆用；滑动连续选牌。
 */
class StandardRuleV107Test {

    private val level = 2   // 打 2，红桃 2 为百搭
    private fun sp(r: Int) = Card(r, Suit.SPADES)
    private fun he(r: Int) = Card(r, Suit.HEARTS)
    private fun cl(r: Int) = Card(r, Suit.CLUBS)
    private fun di(r: Int) = Card(r, Suit.DIAMONDS)
    private fun bigJ() = Card(RANK_BIG_JOKER, Suit.JOKER)
    private fun smallJ() = Card(RANK_SMALL_JOKER, Suit.JOKER)
    private fun wild() = he(2)                       // 红桃 2 = 逢人配

    private fun f(any: Any, name: String): Field =
        any.javaClass.getDeclaredField(name).also { it.isAccessible = true }
    private fun set(any: Any, name: String, value: Any?) = f(any, name).set(any, value)
    private fun setInt(any: Any, name: String, value: Int) = f(any, name).setInt(any, value)

    @Suppress("UNCHECKED_CAST")
    private fun hintPlays(vm: GameViewModel, e: GameEngine): List<List<Card>> {
        val m = GameViewModel::class.java.getDeclaredMethod("hintPlays", GameEngine::class.java)
        m.isAccessible = true
        return m.invoke(vm, e) as List<List<Card>>
    }

    // ---------------- 4.3.2 / 5.3.2 王对 ----------------

    @Test
    fun `两大王两小王各自成对_混合王对不是对子`() {
        val bb = recognize(listOf(bigJ(), bigJ()), level)
        assertEquals(PatternType.PAIR, bb!!.type)
        assertEquals(17, bb.mainRank)
        val ss = recognize(listOf(smallJ(), smallJ()), level)
        assertEquals(PatternType.PAIR, ss!!.type)
        assertEquals(16, ss.mainRank)
        assertNull("一大王+一小王牌点不同，4.3.2 不承认对子",
            recognize(listOf(bigJ(), smallJ()), level))
    }

    @Test
    fun `对子大小_对大王大于对小王大于对级牌大于AA`() {
        val bb = recognize(listOf(bigJ(), bigJ()), level)!!
        val ss = recognize(listOf(smallJ(), smallJ()), level)!!
        val ll = recognize(listOf(sp(2), cl(2)), level)!!      // 对级牌（黑桃2+梅花2）
        val aa = recognize(listOf(sp(14), cl(14)), level)!!
        assertEquals(15, ll.mainRank)
        assertTrue(beats(ss, bb))
        assertTrue(beats(ll, ss))
        assertTrue(beats(aa, ll))
        assertFalse(beats(bb, aa))
    }

    @Test
    fun `天王炸仍是两大王加两小王`() {
        val fk = recognize(listOf(bigJ(), bigJ(), smallJ(), smallJ()), level)!!
        assertEquals(PatternType.FOUR_KINGS, fk.type)
        assertTrue(beats(recognize(listOf(bigJ(), bigJ()), level)!!, fk))
    }

    @Test
    fun `合法出牌枚举含对大王对小王_不含混合王对`() {
        val hand = listOf(bigJ(), bigJ(), smallJ(), smallJ(), sp(3), sp(4))
        val plays = MoveGenerator.legalPlays(hand, null, level)
        assertTrue(plays.any { it.size == 2 && it.all { c -> c.isBigJoker } })
        assertTrue(plays.any { it.size == 2 && it.all { c -> c.isSmallJoker } })
        assertTrue("混合王对不应出现在合法出牌里", plays.none {
            it.size == 2 && it.count { c -> c.isBigJoker } == 1 && it.count { c -> c.isSmallJoker } == 1
        })
    }

    @Test
    fun `三带二可以带对大王或对小王_不能带混合王对`() {
        val t = listOf(sp(8), cl(8), di(8))
        assertEquals(PatternType.TRIPLE_WITH_PAIR,
            recognize(t + listOf(bigJ(), bigJ()), level)!!.type)
        assertEquals(PatternType.TRIPLE_WITH_PAIR,
            recognize(t + listOf(smallJ(), smallJ()), level)!!.type)
        assertNull(recognize(t + listOf(bigJ(), smallJ()), level))
    }

    // ---------------- 5.7 百搭不能当王 ----------------

    @Test
    fun `百搭不能当大小王凑对子三张或三带二`() {
        assertNull("百搭+大王 ≠ 对大王", recognize(listOf(wild(), bigJ()), level))
        assertNull("百搭+两张大王 ≠ 三张大王", recognize(listOf(wild(), bigJ(), bigJ()), level))
        // 三带二里「1 张王 + 1 张百搭」作带牌，等价于百搭当王
        assertNull(recognize(listOf(sp(8), cl(8), di(8), wild(), bigJ()), level))
        // 但两张真王作带牌是合法的（对大王）
        assertEquals(PatternType.TRIPLE_WITH_PAIR,
            recognize(listOf(sp(8), cl(8), di(8), bigJ(), bigJ()), level)!!.type)
    }

    @Test
    fun `两张红桃级牌组成对级牌`() {
        val p = recognize(listOf(wild(), wild()), level)!!
        assertEquals(PatternType.PAIR, p.type)
        assertEquals(15, p.mainRank)
    }

    // ---------------- 5.7 百搭按所替代牌点入顺子 ----------------

    @Test
    fun `红桃2补入3456按最大牌型解释为34567_即当7`() {
        // 红桃2 是百搭：可当 2（组成 2-3-4-5-6，顶张 6），也可当 7（3-4-5-6-7，顶张 7）。
        // 腾讯大掼蛋官方「代替非大/小王牌组成最大的牌型」→ 取顶张最大的 7，
        // 而不是按自身级牌点数 2 插带。
        val p = recognize(listOf(sp(3), he(4), cl(5), di(6), wild()), level)!!
        assertEquals(PatternType.STRAIGHT, p.type)
        assertEquals(7, p.mainRank)
    }

    @Test
    fun `真实23456顺子顶张为6_压不过百搭当7的34567`() {
        val real = recognize(listOf(sp(2), sp(3), he(4), cl(5), di(6)), level)!!
        val withWild = recognize(listOf(sp(3), he(4), cl(5), di(6), wild()), level)!!
        assertEquals(6, real.mainRank)
        assertEquals(7, withWild.mainRank)
        assertTrue(beats(real, withWild))
        assertFalse(beats(withWild, real))
    }

    @Test
    fun `百搭补连对也按顶张最大解释`() {
        // 33 44 55 + 百搭 → 可解释成 22 33 44（顶张 4）或 33 44 55 缺 5（顶张 5）
        val p = recognize(listOf(sp(3), he(3), sp(4), he(4), sp(5), wild()), level)!!
        assertEquals(PatternType.DOUBLE_STRAIGHT, p.type)
        assertEquals(5, p.mainRank)
    }

    // ---------------- 百搭撑起的牌组允许拆用 ----------------

    @Test
    fun `百搭撑起的对子不算固定组_可拆去凑顺子`() {
        // 手上只有 1 张黑桃9 + 百搭，凑出的「对子9」是百搭撑起来的假组
        val hand = listOf(wild(), sp(9), sp(3), he(4), cl(5), di(6))
        val straight = listOf(sp(3), he(4), cl(5), di(6), wild())
        assertEquals("拆百搭伪对子去补顺子不应被判拆牌",
            0, HandShape.breakPenalty(hand, straight, level))
        assertEquals(PatternType.STRAIGHT, recognize(straight, level)!!.type)
        // 对照：真实对子（两张 9）只出 1 张才罚
        val realPairHand = listOf(sp(9), cl(9), sp(3), he(4), cl(5), di(6), sp(7))
        assertTrue(HandShape.breakPenalty(realPairHand, listOf(sp(9)), level) > 0)
    }

    @Test
    fun `百搭凑AA或对级牌不算浪费_小对子算浪费`() {
        val aa = listOf(wild(), sp(14))
        assertFalse(HandShape.wastesWild(aa, recognize(aa, level)!!, level))
        val ll = listOf(wild(), sp(2))                 // 百搭 + 黑桃2 = 对级牌
        assertFalse(HandShape.wastesWild(ll, recognize(ll, level)!!, level))
        val small = listOf(wild(), sp(3))
        assertTrue(HandShape.wastesWild(small, recognize(small, level)!!, level))
    }

    // ---------------- 三带二不甩大对子 ----------------

    @Test
    fun `带牌强度按被带走的对子计算`() {
        val smallKick = listOf(sp(8), cl(8), di(8), sp(3), he(3))
        val bigKick = listOf(sp(8), cl(8), di(8), sp(14), he(14))
        val ps = recognize(smallKick, level)!!
        val pb = recognize(bigKick, level)!!
        assertEquals(3, HandShape.kickerStrength(smallKick, ps, level))
        assertEquals(14, HandShape.kickerStrength(bigKick, pb, level))
        assertEquals("三带二只比三张点数", ps.mainRank, pb.mainRank)
    }

    @Test
    fun `提示轮循里第一手三带二带小对33_不带大对AA`() {
        val vm = GameViewModel()
        val human = listOf(
            sp(8), cl(8), di(8), sp(3), he(3), sp(14), he(14), sp(5), cl(6), di(7),
        )
        val e = GameEngine(level,
            listOf(human, listOf(sp(9)), listOf(sp(10)), listOf(sp(11))), leader = 0)
        setInt(vm, "level", level)
        set(vm, "engine", e)
        val plays = hintPlays(vm, e)
        val twps = plays.filter { recognize(it, level)!!.type == PatternType.TRIPLE_WITH_PAIR }
        assertTrue("应同时给出带 33 与带 AA 的两种三带二", twps.size >= 2)
        val first = twps.first()
        assertTrue("第一手三带二应带最小的 33，实测 $first", first.none { it.rank == 14 })
        assertTrue(first.count { it.rank == 3 } == 2)
    }

    @Test
    fun `AI跟牌压三带二时优先带小对_不把AA带出去`() {
        val target = recognize(listOf(sp(4), cl(4), di(4), sp(3), he(3)), level)!!
        val hand = listOf(sp(8), cl(8), di(8), sp(3), he(3), sp(14), he(14))
        val move = HeuristicAi.decideMove(
            0, hand, target, 3, level, Difficulty.HARD,
            listOf(27, 5, 5, 5), Random(1),
        )
        assertEquals(PatternType.TRIPLE_WITH_PAIR, recognize(move, level)!!.type)
        assertTrue("AI 不该把大对 AA 当带牌甩出去：$move", move.none { it.rank == 14 })
        assertTrue(move.count { it.rank == 3 } == 2)
    }

    // ---------------- 滑动连续选牌 ----------------

    @Test
    fun `滑动索引换算与连续区间`() {
        assertEquals(0, HandSelection.indexAt(0f, 14f, 27))
        assertEquals(1, HandSelection.indexAt(14f, 14f, 27))
        assertEquals(9, HandSelection.indexAt(14f * 9.6f, 14f, 27))
        assertEquals(26, HandSelection.indexAt(10000f, 14f, 27))
        assertEquals(0, HandSelection.indexAt(-5f, 14f, 27))
        assertEquals(-1, HandSelection.indexAt(0f, 14f, 0))
        assertEquals(2..5, HandSelection.indicesBetween(5, 2, 27))
        assertTrue(HandSelection.indicesBetween(-1, 2, 27).isEmpty())
    }

    @Test
    fun `批量选中只加不减且按引用相等保留重复实体牌`() {
        val vm = GameViewModel()
        val a = sp(3)
        val b = sp(3)      // 与 a equals 相等、但两张不同的物理牌
        val c = sp(4)
        val hand = listOf(a, b, c, sp(5), sp(6))
        val e = GameEngine(level,
            listOf(hand, listOf(sp(9)), listOf(sp(10)), listOf(sp(11))), leader = 0)
        set(vm, "engine", e)
        vm.clearSelection()                 // 同步 uiState（轮到人类）
        vm.selectCards(listOf(a, b, c))
        assertEquals(3, vm.uiState.selectedCards.size)
        vm.selectCards(listOf(b, a))        // 重复不应新增，也不应取消
        assertEquals(3, vm.uiState.selectedCards.size)
        assertTrue(vm.isSelected(a))
        assertTrue(vm.isSelected(b))
        assertTrue(vm.isSelected(c))
        vm.clearSelection()
        assertEquals(0, vm.uiState.selectedCards.size)
    }
}
