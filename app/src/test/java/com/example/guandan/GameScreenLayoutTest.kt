package com.example.guandan

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.example.guandan.domain.BackRisk
import com.example.guandan.domain.GameEngine
import com.example.guandan.domain.AiSpeed
import com.example.guandan.domain.Card
import com.example.guandan.domain.Deck
import com.example.guandan.domain.Difficulty
import com.example.guandan.domain.GameUiState
import com.example.guandan.domain.GameViewModel
import com.example.guandan.domain.HandResult
import com.example.guandan.domain.LevelMode
import com.example.guandan.domain.Phase
import com.example.guandan.domain.RANK_BIG_JOKER
import com.example.guandan.domain.RANK_SMALL_JOKER
import com.example.guandan.domain.Suit
import com.example.guandan.domain.PlayerView
import com.example.guandan.domain.TributeBackRequest
import com.example.guandan.domain.TributeExchange
import com.example.guandan.domain.TributePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 真实 Compose 布局渲染测试（Robolectric，无需真机）。
 *
 * 覆盖历次用户反馈与 v1.0.4 的新功能：
 * 1. 应用锁定横屏、只有一套横屏布局（任何尺寸下都不存在整体滚动容器）；
 * 2. 27 张手牌**动态重叠铺满一屏**，最后一张牌几何边界落在视口内、无需横向滚动；
 * 3. 四个操作按钮完整显示、选牌后「出牌」按钮启用；
 * 4. 右上角自绘时钟可见；
 * 5. 「⚙ 设置」可打开面板，难度 / AI 速度切换即时生效；
 * 6. 设置里可打开玩法规则与新手教程，四个章节可切换、可关闭。
 *
 * 全屏窗口控制放在 GameScreen（不在本测试渲染范围内），本测试只渲染纯布局 GameScreenContent。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameScreenLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val hand27: List<Card> = Deck.full().shuffled(java.util.Random(1)).take(27)

    private val fakePlayers: List<PlayerView> = (0..3).map { seat ->
        PlayerView(
            seat = seat,
            name = listOf("你（南）", "下家（敌）", "对家（队友）", "上家（敌）")[seat],
            isHuman = seat == 0,
            cardCount = 27,
            lastPlay = null,
            isCurrent = seat == 0,
            finishedRank = null,
            isTeammate = seat == 0 || seat == 2,
        )
    }

    /** 单独渲染手牌操作区（静态状态，不触发 AI 协程）。 */
    private fun render(selected: List<Card> = emptyList()) {
        val vm = GameViewModel()
        composeRule.setContent {
            HumanArea(
                viewModel = vm,
                ui = GameUiState(
                    currentPlayer = 0,
                    phase = Phase.PLAYING,
                    humanHand = hand27,
                    selectedCards = selected,
                    message = "轮到你出牌"
                )
            )
        }
    }

    /** 渲染完整布局（含 BoxWithConstraints 与设置/教程面板状态）。 */
    private fun renderFull(vm: GameViewModel = GameViewModel(), interactive: Boolean = false) {
        composeRule.setContent {
            val ui = if (interactive) vm.uiState else GameUiState(
                currentPlayer = 0,
                phase = Phase.PLAYING,
                players = fakePlayers,
                humanHand = hand27,
                message = "轮到你出牌",
                hintLabel = "提示 1/12 · 顺子"
            )
            GameScreenContent(viewModel = vm, ui = ui, level = 2, timeText = "20:01")
        }
    }

    private fun openSettings() {
        composeRule.onNodeWithTag("settingsButton").performClick()
        composeRule.waitForIdle()
    }

    /** 教程里同一个词常出现在标题、正文、表格多处，用集合断言避免多节点匹配失败。 */
    private fun assertTextExists(text: String) {
        assertTrue("应显示「$text」",
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    // ---------------- 横屏锁定 ----------------

    @Test
    fun `Manifest 已锁定为 sensorLandscape`() {
        val ctx = RuntimeEnvironment.getApplication()
        val info = ctx.packageManager.getActivityInfo(
            ComponentName(ctx, MainActivity::class.java),
            PackageManager.GET_META_DATA
        )
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, info.screenOrientation)
    }

    @Test
    fun `Manifest 已配置掼蛋主题启动图标`() {
        val ctx = RuntimeEnvironment.getApplication()
        val app = ctx.packageManager.getApplicationInfo(ctx.packageName, 0)
        assertTrue("应声明 android:icon", app.icon != 0)
        assertEquals("ic_launcher", ctx.resources.getResourceEntryName(app.icon))
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `横屏_无整体滚动容器`() {
        renderFull()
        val nodes = composeRule.onAllNodesWithTag("rootScroll").fetchSemanticsNodes()
        assertTrue("应用只有横屏布局，不应存在整体滚动容器", nodes.isEmpty())
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `竖屏尺寸下仍只渲染横屏布局_不出现整体滚动`() {
        renderFull()
        val nodes = composeRule.onAllNodesWithTag("rootScroll").fetchSemanticsNodes()
        assertTrue("已锁定横屏，竖屏尺寸下也不应有整体滚动容器", nodes.isEmpty())
    }

    // ---------------- 手牌重叠一屏显示 ----------------

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `横屏_27张手牌一屏完整显示无需横向滚动`() {
        render()
        composeRule.onNodeWithTag("handRow").assertIsDisplayed()
        composeRule.onNodeWithTag("lastCard").assertIsDisplayed()

        val right = composeRule.onNodeWithTag("lastCard").getBoundsInRoot().right
        assertTrue("最后一张牌应真实排布在屏幕右侧，实测 $right", right > 500.dp)
        assertTrue("最后一张牌 $right 应落在 640dp 视口内（一屏完整显示）", right <= 640.dp)
    }

    @Test
    @Config(qualifiers = "w568dp-h320dp")
    fun `窄横屏_手牌仍一屏完整显示`() {
        render()
        val right = composeRule.onNodeWithTag("lastCard").getBoundsInRoot().right
        assertTrue("窄横屏最后一张牌 $right 应落在 568dp 视口内", right <= 568.dp)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `选中牌后出牌按钮变为可用`() {
        val one = hand27.first()
        render(selected = listOf(one))
        composeRule.onNodeWithText("已选 1 张").assertExists()
        composeRule.onNodeWithText("出牌").assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w568dp-h320dp")
    fun `窄横屏_操作按钮完整显示且未选牌时出牌不可用`() {
        render()
        composeRule.onNodeWithText("出牌").assertExists()
        composeRule.onNodeWithText("过").assertExists()
        composeRule.onNodeWithText("提示").assertExists()
        composeRule.onNodeWithText("重开").assertExists()
        composeRule.onNodeWithText("出牌").assertIsNotEnabled()
    }

    // ---------------- 一屏完整显示与时钟 ----------------

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `横屏_一屏完整显示手牌与按钮`() {
        renderFull()
        val handBottom = composeRule.onNodeWithTag("handRow").getBoundsInRoot().bottom
        val btnBottom = composeRule.onNodeWithText("出牌").getBoundsInRoot().bottom
        assertTrue("手牌应位于视口下半部，实测 $handBottom", handBottom > 180.dp)
        assertTrue("手牌底部 $handBottom 应落在 360dp 视口内", handBottom <= 360.dp)
        assertTrue("出牌按钮底部 $btnBottom 应落在 360dp 视口内", btnBottom <= 360.dp)
        composeRule.onNodeWithTag("handRow").assertIsDisplayed()
        composeRule.onNodeWithText("出牌").assertIsDisplayed()
        composeRule.onNodeWithText("提示 1/12 · 顺子").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `横屏_右上角显示自绘时钟与级牌说明`() {
        renderFull()
        composeRule.onNodeWithTag("clock").assertIsDisplayed()
        composeRule.onNodeWithText("20:01").assertIsDisplayed()
        composeRule.onNodeWithText("级牌 = 2（红桃2 百搭） · 对家是队友", substring = true).assertExists()
    }

    // ---------------- 设置面板 ----------------

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `设置按钮打开面板_含难度AI速度与教程入口`() {
        renderFull()
        openSettings()
        composeRule.onNodeWithTag("settingsPanel").assertIsDisplayed()
        // 顶栏「难度：普通」与面板标题「难度」都会命中 substring，故用集合断言
        assertTrue(composeRule.onAllNodesWithText("难度", substring = true)
            .fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("AI 出牌速度", substring = true)
            .fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithTag("openRules").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `设置里切换难度立即生效且顶栏回显`() {
        val vm = GameViewModel()
        renderFull(vm, interactive = true)
        assertEquals(Difficulty.NORMAL, vm.difficulty)

        openSettings()
        composeRule.onNodeWithTag("difficulty_2").performClick()   // 困难

        assertEquals(Difficulty.HARD, vm.difficulty)
        assertEquals(Difficulty.HARD, vm.uiState.difficulty)
        composeRule.onNodeWithText("难度：困难").assertExists()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `设置里切换AI速度立即生效`() {
        val vm = GameViewModel()
        renderFull(vm, interactive = true)
        assertEquals(AiSpeed.NORMAL, vm.aiSpeed)

        openSettings()
        composeRule.onNodeWithTag("speed_0").performClick()   // 快

        assertEquals(AiSpeed.FAST, vm.aiSpeed)
        assertEquals(AiSpeed.FAST, vm.uiState.aiSpeed)
    }

    // ---------------- 玩法规则与新手教程 ----------------

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `设置里可打开教程并切换四个章节`() {
        renderFull()
        openSettings()
        composeRule.onNodeWithTag("openRules").performClick()
        composeRule.waitForIdle()
        val bounds = composeRule.onNodeWithTag("rulesPanel").getBoundsInRoot()
        val w = bounds.right - bounds.left
        val h = bounds.bottom - bounds.top
        assertTrue("教程面板应有可见面积，实测 $bounds", w > 200.dp && h > 200.dp)

        // 第一章：快速上手
        assertTextExists("掼蛋玩法规则")
        assertTextExists("第一次进游戏")
        assertTextExists("按这 7 步走")

        // 第二章：牌型速查
        composeRule.onNodeWithTag("rulesTab_1").performClick()
        composeRule.waitForIdle()
        assertTextExists("十种牌型")
        assertTextExists("天王炸")

        // 第三章：规则详解
        composeRule.onNodeWithTag("rulesTab_2").performClick()
        composeRule.waitForIdle()
        assertTextExists("级牌与逢人配")
        assertTextExists("双上")

        // 第四章：操作与技巧
        composeRule.onNodeWithTag("rulesTab_3").performClick()
        composeRule.waitForIdle()
        assertTextExists("七条实战技巧")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `教程可关闭`() {
        renderFull()
        openSettings()
        composeRule.onNodeWithTag("openRules").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("rulesPanel").fetchSemanticsNodes().isNotEmpty())

        composeRule.onNodeWithTag("rulesClose").performClick()
        composeRule.waitForIdle()
        assertTrue("关闭后教程面板应消失",
            composeRule.onAllNodesWithTag("rulesPanel").fetchSemanticsNodes().isEmpty())
        // 设置面板仍在（关闭教程回到设置）
        composeRule.onNodeWithTag("settingsPanel").assertIsDisplayed()
    }

    @Test
    fun `大小王改为卡通小丑牌面_不再显示大王小王文字`() {
        composeRule.setContent {
            androidx.compose.foundation.layout.Row {
                CardView(Card(RANK_BIG_JOKER, Suit.JOKER), level = 2, cardHeight = 68.dp)
                CardView(Card(RANK_SMALL_JOKER, Suit.JOKER), level = 2, cardHeight = 68.dp)
            }
        }
        composeRule.waitForIdle()
        // 与斗地主一致：小丑用矢量绘制，靠红帽 / 黑帽区分大小王，牌面无「大」「小」「王」文字
        listOf("大", "小", "王").forEach { t ->
            assertTrue("卡通王牌不应出现文字「$t」",
                composeRule.onAllNodesWithText(t).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun `普通牌仍显示点数与花色文字`() {
        composeRule.setContent {
            CardView(Card(7, Suit.SPADES), level = 2, cardHeight = 68.dp)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("7").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `横向滑动手牌可一次连续选中多张`() {
        val vm = GameViewModel()
        val hand = Deck.full().shuffled(java.util.Random(7)).take(27)
        val e = GameEngine(2, listOf(
            hand,
            listOf(Card(9, Suit.SPADES)),
            listOf(Card(10, Suit.SPADES)),
            listOf(Card(11, Suit.SPADES)),
        ), leader = 0)
        vm.javaClass.getDeclaredField("engine").also { it.isAccessible = true }.set(vm, e)
        vm.javaClass.getDeclaredField("level").also { it.isAccessible = true }.setInt(vm, 2)
        vm.clearSelection()      // 同步 uiState 到受控局面（轮到人类）
        composeRule.setContent {
            GameScreenContent(viewModel = vm, ui = vm.uiState, level = 2, timeText = "20:01")
        }
        composeRule.waitForIdle()
        assertEquals(0, vm.uiState.selectedCards.size)

        composeRule.onNodeWithTag("handRow").performTouchInput {
            val y = height * 0.75f
            val x0 = width * 0.08f
            val x1 = width * 0.55f
            down(Offset(x0, y))
            // 多步 MOVE：真机滑动会连续派发，detectDragGestures 在过 touch slop 后
            // 每次 MOVE 都会回调 onDrag，从而逐段扩展选中区间。
            for (i in 1..8) moveTo(Offset(x0 + (x1 - x0) * i / 8f, y))
            up()
        }
        composeRule.waitForIdle()
        assertTrue("滑动应一次选中一段连续手牌，实测 ${vm.uiState.selectedCards.size} 张",
            vm.uiState.selectedCards.size >= 3)
    }

    // ---------------- 开局进贡公示（公开信息，v1.0.9 起移到发牌后） ----------------

    private fun renderTributeOverlay(tributePlan: TributePlan) {
        val vm = GameViewModel()
        val ui = GameUiState(
            currentPlayer = 3,
            phase = Phase.PLAYING,
            players = fakePlayers,
            humanHand = hand27,
            tributePlan = tributePlan,
            message = "轮到你出牌",
        )
        composeRule.setContent {
            GameScreenContent(viewModel = vm, ui = ui, level = 2, timeText = "20:01")
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `结算面板不再提前显示任何进贡信息`() {
        composeRule.setContent {
            GameScreenContent(
                viewModel = GameViewModel(),
                ui = GameUiState(
                    phase = Phase.HAND_OVER,
                    players = fakePlayers,
                    humanHand = hand27,
                    handResult = HandResult(listOf(0, 1, 2, 3), 0, 2, 3, 0, false),
                    tributePlan = null,
                    message = "本局结束",
                ),
                level = 2, timeText = "20:01"
            )
        }
        composeRule.waitForIdle()
        assertTrue("结算阶段不应出现进贡公示卡片",
            composeRule.onAllNodesWithTag("tributeDisclosure").fetchSemanticsNodes().isEmpty())
        assertTrue("结算阶段不应弹出进贡信息框",
            composeRule.onAllNodesWithTag("tributeOverlay").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `开局信息框公示具体贡牌与还牌`() {
        val plan = TributePlan(
            exchanges = listOf(
                TributeExchange(3, 0, Card(14, Suit.SPADES), Card(3, Suit.HEARTS))
            ),
            nextLeader = 3,
            notice = "上家 向 你 进贡，由下游领出",
            antiTribute = false,
            doubleDown = false,
        )
        renderTributeOverlay(plan)
        composeRule.onNodeWithTag("tributeOverlay").assertIsDisplayed()
        composeRule.onNodeWithTag("tributeDisclosure").assertIsDisplayed()
        assertTextExists("进贡 / 抗贡公示 · 公开信息")
        assertTextExists("上家 → 你")           // 进出双方可见
        assertTextExists("A")                    // 贡牌 ♠A 的牌面
        assertTextExists("还")                   // 还贡标注
        assertTextExists("3")                    // 还牌 ♥3 的牌面
        assertTextExists("单贡")                 // 单贡说明
    }

    @Test
    fun `开局信息框公示抗贡者亮出的双大王`() {
        val plan = TributePlan(
            exchanges = emptyList(),
            nextLeader = 0,
            notice = "上家 亮出双大王抗贡，免贡；由你领出",
            antiTribute = true,
            doubleDown = false,
            antiTributeBy = listOf(3),
            antiTributeCards = listOf(
                Card(RANK_BIG_JOKER, Suit.JOKER), Card(RANK_BIG_JOKER, Suit.JOKER)
            ),
        )
        renderTributeOverlay(plan)
        composeRule.onNodeWithTag("tributeDisclosure").assertIsDisplayed()
        assertTextExists("上家 亮出")            // 抗贡者可见
        assertTextExists("双大王抗贡，免贡")      // 抗贡依据可见
    }

    @Test
    fun `开局自动弹公示_可关闭_顶栏贡按钮可随时重开`() {
        val plan = TributePlan(
            exchanges = listOf(TributeExchange(3, 0, sp14(), he3())),
            nextLeader = 3, notice = "上家 向 你 进贡，由下游领出",
            antiTribute = false, doubleDown = false,
        )
        renderTributeOverlay(plan)
        composeRule.onNodeWithTag("tributeOverlay").assertIsDisplayed()   // 发牌后自弹一次

        composeRule.onNodeWithTag("tributeClose").performClick()
        composeRule.waitForIdle()
        assertTrue("关闭后信息框应消失",
            composeRule.onAllNodesWithTag("tributeOverlay").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("tributeButton").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tributeOverlay").assertIsDisplayed()
    }

    @Test
    fun `首局无进贡时贡按钮置灰且不弹公示`() {
        renderFull()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tributeButton").assertIsNotEnabled()
        assertTrue(composeRule.onAllNodesWithTag("tributeOverlay").fetchSemanticsNodes().isEmpty())
    }

    // ---------------- 记牌器：两层开关、顶部窄带、不遮挡 ----------------

    @Test
    fun `记牌器默认收起_点顶栏按钮展开顶部窄带且不遮挡手牌`() {
        renderFull()
        composeRule.waitForIdle()
        assertTrue("记牌器窄带默认应收起",
            composeRule.onAllNodesWithTag("counterStrip").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("counterButton").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("counterStrip").assertIsDisplayed()

        // 关键：窄带是 Column 里独立的一行，位于手牌区之上，不覆盖手牌 / 出牌按钮
        val stripBottom = composeRule.onNodeWithTag("counterStrip").getBoundsInRoot().bottom
        val handTop = composeRule.onNodeWithTag("handRow").getBoundsInRoot().top
        assertTrue("记牌器窄带不应覆盖手牌区（bottom=$stripBottom handTop=$handTop）",
            stripBottom <= handTop)
        composeRule.onNodeWithText("出牌").assertIsDisplayed()
        composeRule.onNodeWithTag("lastCard").assertIsDisplayed()
    }

    @Test
    fun `记牌器窄带显示各点数剩余与级牌高亮_再次点击可收起`() {
        val vm = GameViewModel()
        composeRule.setContent {
            GameScreenContent(
                viewModel = vm,
                ui = GameUiState(
                    currentPlayer = 0, phase = Phase.PLAYING,
                    players = fakePlayers, humanHand = hand27,
                    playedCards = listOf(Card(9, Suit.SPADES)),
                    message = "轮到你出牌",
                ),
                level = 2, timeText = "20:01"
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("counterButton").performClick()
        composeRule.waitForIdle()
        assertTextExists("剩余")
        assertTextExists("配")            // 逢人配单独标注
        composeRule.onNodeWithTag("counterButton").performClick()
        composeRule.waitForIdle()
        assertTrue("再次点击应收起窄带",
            composeRule.onAllNodesWithTag("counterStrip").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `设置里关闭记牌器总开关后顶栏按钮消失`() {
        val vm = GameViewModel()
        renderFull(vm, interactive = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("counterButton").assertIsDisplayed()

        openSettings()
        composeRule.onNodeWithTag("counter_1").performClick()   // 「关」
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settingsClose").performClick()
        composeRule.waitForIdle()
        assertTrue("总开关关闭后顶栏不应再有记牌器按钮",
            composeRule.onAllNodesWithTag("counterButton").fetchSemanticsNodes().isEmpty())
        assertTrue("总开关关闭后窄带也不应出现",
            composeRule.onAllNodesWithTag("counterStrip").fetchSemanticsNodes().isEmpty())
    }

    // ---------------- v1.0.10：迷你牌花色显示 / 玩家自选还贡 ----------------

    @Test
    fun `电脑玩家出的迷你牌点数与花色都完整显示在牌面内`() {
        composeRule.setContent {
            Box(Modifier.testTag("miniCard")) {
                CardView(Card(10, Suit.HEARTS), level = 2, cardHeight = 36.dp)
            }
        }
        composeRule.waitForIdle()
        val card = composeRule.onNodeWithTag("miniCard").getBoundsInRoot()
        val rank = composeRule.onNodeWithText("10").getBoundsInRoot()
        val suit = composeRule.onNodeWithText("♥").getBoundsInRoot()
        assertTrue("花色应在点数下方且不重叠：rank=$rank suit=$suit", suit.top >= rank.bottom)
        assertTrue("花色下沿不得超出迷你牌：suit=$suit card=$card", suit.bottom <= card.bottom)
        assertTrue("点数上沿不得超出迷你牌", rank.top >= card.top)
    }

    @Test
    fun `系统字体放大后迷你牌花色仍不被挤出牌面`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 1.6f)) {
                Box(Modifier.testTag("miniCardBigFont")) {
                    CardView(Card(10, Suit.HEARTS), level = 2, cardHeight = 36.dp)
                }
            }
        }
        composeRule.waitForIdle()
        val card = composeRule.onNodeWithTag("miniCardBigFont").getBoundsInRoot()
        val suit = composeRule.onNodeWithText("♥").getBoundsInRoot()
        assertTrue("字体放大后花色仍须完整显示：suit=$suit card=$card", suit.bottom <= card.bottom)
    }

    @Test
    fun `人类收贡时弹出选还贡牌框_可改选且标注拆牌风险`() {
        val vm = GameViewModel()
        val candidates = listOf(
            Card(3, Suit.SPADES), Card(3, Suit.CLUBS),
            Card(4, Suit.HEARTS), Card(6, Suit.DIAMONDS)
        )
        val req = TributeBackRequest(
            giver = 3,
            give = Card(14, Suit.SPADES),
            candidates = candidates,
            risks = listOf(BackRisk.PAIR, BackRisk.PAIR, BackRisk.NONE, BackRisk.NONE),
            suggested = candidates[3],
            notice = "上家 向 你 进贡，由下游领出",
            nextLeader = 3,
            doubleDown = false,
        )
        composeRule.setContent {
            GameScreenContent(
                viewModel = vm,
                ui = GameUiState(
                    currentPlayer = 0, phase = Phase.PLAYING,
                    players = fakePlayers, humanHand = hand27,
                    pendingBack = req, message = "请先选择本局还贡牌",
                ),
                level = 2, timeText = "20:01"
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tributeBackOverlay").assertIsDisplayed()
        composeRule.onNodeWithText("请选择本局还贡牌").assertIsDisplayed()
        composeRule.onNodeWithTag("tributeBackConfirm").assertIsEnabled()
        assertTextExists("拆对子")
        assertTextExists("不拆牌")
        assertEquals(3, composeRule.onAllNodesWithTag("tributeBackCandidate")
            .fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("tributeBackChosen").assertExists()

        // 改选一张非推荐牌，已选文案随之更新
        composeRule.onAllNodesWithTag("tributeBackCandidate")[0].performClick()
        composeRule.waitForIdle()
        assertTextExists("已选 ♠3")
        composeRule.onNodeWithTag("tributeBackConfirm").assertIsEnabled()
    }

    // ---------------- v1.0.11：升级方式可选 ----------------

    /** 用自定义 UiState 渲染完整布局，便于静态断言两种模式的顶栏/结算面板。 */
    private fun renderFullWith(ui: GameUiState, vm: GameViewModel = GameViewModel()) {
        composeRule.setContent {
            GameScreenContent(viewModel = vm, ui = ui, level = 2, timeText = "20:01")
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `设置面板含升级方式两个选项且默认选中共用级数`() {
        renderFull()
        openSettings()
        composeRule.onNodeWithTag("settingsPanel").assertIsDisplayed()
        assertTextExists("升级方式")
        composeRule.onNodeWithTag("levelMode_0").assertIsDisplayed()   // 共用级数
        composeRule.onNodeWithTag("levelMode_1").assertIsDisplayed()   // 各自记级
        // 记牌器选项仍在（并排两列，不会被挤出可视区）
        composeRule.onNodeWithTag("counter_1").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `设置里切换升级方式立即生效并重开一场`() {
        val vm = GameViewModel()
        renderFull(vm, interactive = true)
        assertEquals(LevelMode.SHARED, vm.levelMode)

        openSettings()
        composeRule.onNodeWithTag("levelMode_1").performClick()   // 各自记级（标准）
        composeRule.waitForIdle()

        assertEquals(LevelMode.PER_TEAM, vm.levelMode)
        assertEquals(LevelMode.PER_TEAM, vm.uiState.levelMode)
        assertEquals(2, vm.level)
        assertEquals(2, vm.uiState.myLevel)
        assertEquals(2, vm.uiState.opponentLevel)
    }

    @Test
    @Config(qualifiers = "w568dp-h320dp")
    fun `窄横屏320dp下升级方式选项滚动后仍可点并生效`() {
        val vm = GameViewModel()
        renderFull(vm, interactive = true)
        openSettings()
        // 面板本身可纵向滚动：320dp 高度下第二行选项需滚动后进入可视区，
        // 这与真实用户操作一致；滚动后必须能命中并生效（不存在点不到的死区）。
        composeRule.onNodeWithTag("levelMode_1").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(LevelMode.PER_TEAM, vm.levelMode)
        assertEquals(LevelMode.PER_TEAM, vm.uiState.levelMode)
    }

    @Test
    fun `各自记级模式顶栏显示双方级数_共用模式不显示`() {
        renderFullWith(
            GameUiState(
                currentPlayer = 0, phase = Phase.PLAYING,
                players = fakePlayers, humanHand = hand27,
                levelMode = LevelMode.PER_TEAM,
                levelText = "5", myLevel = 6, opponentLevel = 3,
                message = "轮到你出牌",
            )
        )
        composeRule.waitForIdle()
        assertTextExists("各自记级：你方 6 · 对手 3")
        assertTextExists("本副级牌 = 5")
        composeRule.onNodeWithTag("levelInfo").assertIsDisplayed()
    }

    @Test
    fun `共用级数模式顶栏仍显示单条级数说明`() {
        renderFullWith(
            GameUiState(
                currentPlayer = 0, phase = Phase.PLAYING,
                players = fakePlayers, humanHand = hand27,
                levelMode = LevelMode.SHARED, levelText = "2",
                myLevel = 2, opponentLevel = 2,
                message = "轮到你出牌",
            )
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("级牌 = 2（红桃2 百搭） · 对家是队友", substring = true).assertExists()
        assertTrue("共用模式不应出现「各自记级」字样",
            composeRule.onAllNodesWithText("各自记级", substring = true).fetchSemanticsNodes().isEmpty())
    }

    private val sampleResult = HandResult(
        finishOrder = listOf(0, 1, 2, 3), winnerTeam = 0, levelUp = 2,
        tributeFrom = 3, tributeTo = 0, doubleDown = false
    )

    @Test
    fun `各自记级模式结算面板显示双方级数`() {
        renderFullWith(
            GameUiState(
                currentPlayer = 0, phase = Phase.HAND_OVER,
                players = fakePlayers, humanHand = hand27,
                handResult = sampleResult, levelMode = LevelMode.PER_TEAM,
                levelText = "4", myLevel = 4, opponentLevel = 2,
                message = "本局你方胜",
            )
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("resultLevels").assertIsDisplayed()
        assertTextExists("你方 4 · 对手 2")
    }

    @Test
    fun `共用级数模式结算面板不显示双方级数`() {
        renderFullWith(
            GameUiState(
                currentPlayer = 0, phase = Phase.HAND_OVER,
                players = fakePlayers, humanHand = hand27,
                handResult = sampleResult, levelMode = LevelMode.SHARED,
                levelText = "4", myLevel = 4, opponentLevel = 4,
                message = "本局你方胜",
            )
        )
        composeRule.waitForIdle()
        assertTrue("共用模式结算面板不应显示双方级数",
            composeRule.onAllNodesWithTag("resultLevels").fetchSemanticsNodes().isEmpty())
    }

    private fun sp14() = Card(14, Suit.SPADES)
    private fun he3() = Card(3, Suit.HEARTS)
}
