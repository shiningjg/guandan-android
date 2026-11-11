package com.example.guandan

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.min
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.guandan.domain.BackRisk
import com.example.guandan.domain.Card
import com.example.guandan.domain.Difficulty
import com.example.guandan.domain.counterCells
import com.example.guandan.domain.disclosedCards
import com.example.guandan.domain.wildRemaining
import com.example.guandan.domain.GameUiState
import com.example.guandan.domain.GameViewModel
import com.example.guandan.domain.HandSelection
import com.example.guandan.domain.LevelMode
import com.example.guandan.domain.levelLabel
import com.example.guandan.domain.Phase
import com.example.guandan.domain.PlayerView
import com.example.guandan.domain.Suit
import com.example.guandan.domain.TributeBackRequest
import com.example.guandan.domain.TributePlan
import kotlinx.coroutines.delay
import java.time.LocalTime

/**
 * 掼蛋主界面（**仅横屏**，Manifest 已用 sensorLandscape 锁定）。
 *
 * 适配要点：
 * - 横屏沉浸全屏：隐藏系统状态栏 / 导航栏，让出竖直空间；系统时间随之消失，
 *   改由右上角自绘时钟显示。边缘上滑可临时唤出系统栏，避免全屏后无法返回。
 * - 根布局**不滚动**：中间牌桌区用 weight(1f) 吃掉剩余高度并自行压缩，
 *   人类手牌区固定在底部 → 一屏完整显示。
 * - 手牌按可用宽度**动态重叠**：27 张牌铺满一屏、无需横向滑动，牌变少时自动展开。
 * - 只有一套横屏布局，不再保留竖屏分支。
 */
@Composable
fun GameScreen() {
    // 传入 applicationContext 让难度 / AI 速度持久化；ViewModel 只创建一次。
    val appContext = LocalContext.current.applicationContext
    val viewModel: GameViewModel = viewModel(
        factory = viewModelFactory { initializer { GameViewModel(appContext) } }
    )

    val ui = viewModel.uiState
    val level = viewModel.level
    val timeText = rememberClockText()

    // 横屏固定隐藏系统栏（全屏）。放在 GameScreen 而非 GameScreenContent，
    // 便于 Robolectric 只渲染纯布局做断言时不触碰 Window。
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { }
    }

    // 首次进入自动开一局（沿用持久化的难度），之后由「重开 / 下一局」驱动
    LaunchedEffect(Unit) { viewModel.startIfNeeded() }

    GameScreenContent(viewModel, ui, level, timeText)
}

/** 布局主体单独抽出，便于 Robolectric 直接渲染静态状态做布局断言。 */
@Composable
internal fun GameScreenContent(
    viewModel: GameViewModel,
    ui: GameUiState,
    level: Int,
    timeText: String = "--:--",
) {
    var showSettings by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var showCounter by remember { mutableStateOf(false) }
    // 换局（handNumber 变化）时重新初始化：本局有进贡公示就自动弹一次；
    // 关闭后 handNumber 不变，出牌 / 改设置等 uiState.copy 都不会把它重新弹出来。
    var showTribute by remember(ui.handNumber) { mutableStateOf(ui.tributePlan != null) }
    // 记牌器总开关关闭时，顺手收起窄带，避免设置里关掉后仍残留一行
    LaunchedEffect(ui.counterEnabled) {
        if (!ui.counterEnabled) showCounter = false
    }

    MaterialTheme {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0B6B3A))
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    // safeDrawing 水平内边距避开刘海/挖孔与手势区，不占用竖直空间
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                    )
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TopBar(
                    difficulty = ui.difficulty,
                    levelText = ui.levelText,
                    levelMode = ui.levelMode,
                    myLevel = ui.myLevel,
                    opponentLevel = ui.opponentLevel,
                    timeText = timeText,
                    counterEnabled = ui.counterEnabled,
                    hasTribute = ui.tributePlan != null,
                    counterOpen = showCounter,
                    tributeOpen = showTribute,
                    onToggleCounter = { showCounter = !showCounter },
                    onToggleTribute = { showTribute = !showTribute },
                    onSettings = { showSettings = true }
                )
                // 记牌器是**独立一行**内联在顶栏与牌桌之间，不覆盖任何座位 / 出牌 / 手牌信息；
                // TableBody 的 weight(1f) 自动让出这一行的高度，因此不会出现遮挡。
                if (ui.counterEnabled && showCounter) {
                    CounterStrip(ui, level, Modifier.fillMaxWidth())
                }
                TableBody(ui, level, Modifier.weight(1f).fillMaxWidth())
                HumanArea(viewModel, ui)
            }

            // 单局/整场结束面板（覆盖在视口上，不随内容滚动）
            if (ui.phase == Phase.HAND_OVER) {
                HandResultOverlay(viewModel, ui, maxHeight, level)
            }
            // 人类收贡：开局暂停，必须先由玩家自己挑一张还贡牌（不可关闭，选完才能继续）
            if (ui.pendingBack != null) {
                TributeBackOverlay(ui.pendingBack!!, level) { card ->
                    viewModel.chooseTributeBack(card)
                }
            }
            // 开局进贡公示：发牌后自动弹一次，也可由顶栏「贡」按钮随时打开 / 关闭
            if (showTribute && ui.tributePlan != null) {
                TributeOverlay(ui.tributePlan!!, level) { showTribute = false }
            }
            // 设置 / 教程为覆盖式面板，层级依次升高，关闭教程后回到设置
            if (showSettings) {
                SettingsOverlay(
                    viewModel = viewModel,
                    ui = ui,
                    onDismiss = { showSettings = false },
                    onOpenRules = { showRules = true }
                )
            }
            if (showRules) {
                RulesDialog(onDismiss = { showRules = false })
            }
        }
    }
}

// ---------------- 牌桌主体 ----------------

/** 三个扁平 AI 座位 + 牌桌/日志并排在同一行，整体高度约 68dp。 */
@Composable
private fun TableBody(ui: GameUiState, level: Int, modifier: Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(3, 2, 1).forEach { seat ->
                FlatSeatView(ui.players.getOrNull(seat), level)
            }
        }
        CenterTable(
            ui.lastPlay?.cards, ui.log, level,
            modifier = Modifier.weight(0.55f)
        )
    }
}

// ---------------- 顶部状态栏 ----------------

@Composable
private fun TopBar(
    difficulty: Difficulty,
    levelText: String,
    levelMode: LevelMode,
    myLevel: Int,
    opponentLevel: Int,
    timeText: String,
    counterEnabled: Boolean,
    hasTribute: Boolean,
    counterOpen: Boolean,
    tributeOpen: Boolean,
    onToggleCounter: () -> Unit,
    onToggleTribute: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(
        color = Color(0xFF0A4F2C),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("掼蛋 · 打 $levelText", color = Color.White,
                    fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (levelMode == LevelMode.PER_TEAM)
                        "各自记级：你方 ${levelLabel(myLevel)} · 对手 ${levelLabel(opponentLevel)} ｜ 本副级牌 = $levelText（红桃$levelText 百搭）"
                    else
                        "级牌 = $levelText（红桃$levelText 百搭） · 对家是队友",
                    color = Color(0xFFC8E6C9), fontSize = 10.sp,
                    modifier = Modifier.testTag("levelInfo")
                )
            }
            Surface(color = Color(0x33FFFFFF), shape = RoundedCornerShape(6.dp)) {
                Text("难度：${difficulty.shortLabel()}", color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp))
            }
            // 记牌器：设置里的总开关控制是否出现，点击展开 / 收起顶部窄带
            if (counterEnabled) {
                TopChip(
                    text = if (counterOpen) "🃏 收起" else "🃏 记牌器",
                    active = counterOpen, testTag = "counterButton", onClick = onToggleCounter
                )
            }
            // 进贡：本局无进贡事件（如首局）时置灰；有公示时可随时打开 / 关闭信息框
            TopChip(
                text = if (tributeOpen) "贡 ✕" else "贡",
                active = tributeOpen, enabled = hasTribute,
                testTag = "tributeButton", onClick = onToggleTribute
            )
            Button(
                onClick = onSettings,
                modifier = Modifier.height(32.dp).testTag("settingsButton"),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1B5E20), contentColor = Color.White
                )
            ) { Text("⚙ 设置", fontSize = 12.sp) }
            // 全屏隐藏了系统状态栏，右上角自绘时钟替代
            ClockBadge(timeText)
        }
    }
}

/** 顶栏紧凑胶囊：比 Button 省高度，横屏 360dp 下不会把顶栏撑高。 */
@Composable
private fun TopChip(
    text: String,
    active: Boolean,
    testTag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        color = when {
            !enabled -> Color(0x22333333)
            active -> Color(0xFFFFB300)
            else -> Color(0x33FFFFFF)
        },
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text,
            color = when {
                !enabled -> Color(0xFF9E9E9E)
                active -> Color(0xFF3E2723)
                else -> Color.White
            },
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            // testTag 与 clickable 放同一节点：禁用态语义才会落在该节点上（可断言 / 可点击）
            modifier = Modifier
                .testTag(testTag)
                .padding(horizontal = 7.dp, vertical = 5.dp)
                .clickable(enabled = enabled) { onClick() }
        )
    }
}

/**
 * 顶部记牌器窄带：**独立一行**内联在顶栏与牌桌之间，不覆盖任何座位 / 出牌 / 手牌信息。
 *
 * 口径与斗地主一致：全场 108 张 − 已出牌 − 自己手牌 = 其他三家手里可能还有的张数，
 * 从大到小逐列显示；两副牌故每个点数满值是 8、大小王各 2。级牌行金色高亮，
 * 红桃级牌（逢人配）单独标注剩余张数；本局明牌公开的贡 / 还牌在末尾提示，便于记牌。
 */
@Composable
private fun CounterStrip(ui: GameUiState, level: Int, modifier: Modifier = Modifier) {
    val cells = counterCells(ui.playedCards, ui.humanHand, level)
    val wild = wildRemaining(ui.playedCards, ui.humanHand, level)
    val known = disclosedCards(ui.tributePlan)
    Surface(
        color = Color(0xFF083D23),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.testTag("counterStrip")
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text("剩余", fontSize = 9.sp, color = Color(0xFFA5D6A7))
            cells.forEach { c ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        color = if (c.isLevel) Color(0xFFFFB300) else Color(0x22FFFFFF),
                        shape = RoundedCornerShape(3.dp)
                    ) {
                        Text(c.label, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            color = if (c.isLevel) Color(0xFF3E2723) else Color.White,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
                    }
                    Text("${c.left}", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        color = if (c.left == 0) Color(0xFF78909C) else Color(0xFFE8F5E9))
                }
            }
            Surface(color = Color(0xFFFFB300), shape = RoundedCornerShape(3.dp)) {
                Text("配$wild", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF3E2723),
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
            }
            if (known.isNotEmpty()) {
                Text("明牌 ${known.joinToString(" ") { it.display }}",
                    fontSize = 9.sp, color = Color(0xFFFFE082), maxLines = 1)
            }
        }
    }
}

/** 右上角时钟徽章：等宽数字、半透明胶囊，替代被隐藏的状态栏时间。 */
@Composable
private fun ClockBadge(text: String) {
    Surface(color = Color(0x55000000), shape = RoundedCornerShape(8.dp)) {
        Text(
            text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp).testTag("clock")
        )
    }
}

private fun Difficulty.shortLabel() = when (this) {
    Difficulty.EASY -> "简单"
    Difficulty.NORMAL -> "普通"
    Difficulty.HARD -> "困难"
}

// ---------------- 座位（AI） ----------------

/** 扁平座位：名字/张数合并一行、出牌区 42dp（36dp 迷你牌上下各留 3dp 余量），整体约 70dp。 */
@Composable
private fun FlatSeatView(player: PlayerView?, level: Int) {
    if (player == null) return
    val active = player.isCurrent
    val shortName = when (player.seat) {
        1 -> "下家"; 2 -> "对家"; 3 -> "上家"; else -> "你"
    }
    Surface(
        color = if (active) Color(0xFF1565C0) else Color(0xFF0E7A42),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            if (active) 2.dp else 1.dp,
            if (active) Color(0xFFFFEB3B) else Color(0x33FFFFFF)
        ),
        modifier = Modifier.width(104.dp).padding(2.dp)
    ) {
        Column(Modifier.padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$shortName ×${player.cardCount}", color = Color.White,
                fontSize = 10.sp, maxLines = 1, fontWeight = FontWeight.SemiBold)
            if (player.isTeammate && !player.isHuman) {
                Text("队友", color = Color(0xFFBBDEFB), fontSize = 9.sp)
            } else {
                Spacer(Modifier.height(11.dp))   // 占位，保证三个座位高度一致
            }
            Spacer(Modifier.height(2.dp))
            Box(Modifier.height(42.dp), contentAlignment = Alignment.Center) {
                when {
                    player.finishedRank != null -> FinishedBadge(player.finishedRank)
                    player.lastPlay != null ->
                        Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
                            player.lastPlay!!.take(5).forEach {
                                CardView(it, level, cardHeight = 36.dp)
                            }
                        }
                    else -> Text("等待出牌", color = Color(0xFFA5D6A7), fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun FinishedBadge(rank: Int) {
    val label = when (rank) { 1 -> "头游"; 2 -> "二游"; 3 -> "三游"; else -> "末游" }
    Surface(color = Color(0xFFFFF59D), shape = RoundedCornerShape(6.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5D4037),
            modifier = Modifier.padding(7.dp, 3.dp))
    }
}

// ---------------- 中央牌桌 ----------------

@Composable
private fun CenterTable(
    lastCards: List<Card>?,
    log: List<String>,
    level: Int,
    modifier: Modifier = Modifier,
) {
    val tableH = 68.dp

    @Composable
    fun TableBox(modifier: Modifier) {
        Surface(color = Color(0xFF0A5C32), shape = RoundedCornerShape(12.dp), modifier = modifier) {
            Box(Modifier.fillMaxWidth().height(tableH).padding(6.dp), contentAlignment = Alignment.Center) {
                if (lastCards.isNullOrEmpty()) {
                    Text("等待领出", color = Color(0xFFA5D6A7), fontSize = 14.sp)
                } else {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy((-16).dp)
                    ) {
                        lastCards.forEach { CardView(it, level, small = true) }
                    }
                }
            }
        }
    }

    @Composable
    fun LogBox(modifier: Modifier) {
        Surface(color = Color(0x55222222), shape = RoundedCornerShape(8.dp), modifier = modifier) {
            Column(Modifier.fillMaxWidth().padding(6.dp).heightIn(max = tableH)) {
                log.reversed().take(3).forEach {
                    Text(it, color = Color(0xFFE0E0E0), fontSize = 10.sp, textAlign = TextAlign.Start)
                }
            }
        }
    }

    Row(
        modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TableBox(Modifier.weight(1f))
        LogBox(Modifier.weight(1f))
    }
}

// ---------------- 人类操作区 ----------------

@Composable
internal fun HumanArea(viewModel: GameViewModel, ui: GameUiState) {
    val level = viewModel.level
    // 等待玩家选还贡牌期间冻结所有出牌操作，避免「牌还没还就开打」
    val myTurn = ui.currentPlayer == 0 && ui.phase == Phase.PLAYING && ui.pendingBack == null
    val selectedCount = ui.selectedCards.size
    val cardH: Dp = 48.dp
    val handRowH: Dp = cardH + 18.dp   // 额外 18dp 给「选中抬升」留空间
    val btnH = 34.dp

    Surface(
        color = Color(0xFF0A4F2C),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(4.dp)) {
            // 状态行 + 已选数量 + 清空
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ui.message.ifEmpty { " " },
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (selectedCount > 0) {
                    Text("已选 $selectedCount 张", color = Color(0xFFFFEB3B), fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("清空", color = Color(0xFF90CAF9), fontSize = 12.sp,
                        modifier = Modifier.clickable { viewModel.clearSelection() })
                }
            }

            // 错误提示 / 轮循提示进度，避免「点了没反应」
            if (ui.errorText != null) {
                Spacer(Modifier.height(4.dp))
                Surface(color = Color(0xFF5D2A0C), shape = RoundedCornerShape(6.dp)) {
                    Text("⚠ ${ui.errorText}", color = Color(0xFFFFCC80), fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(6.dp))
                }
            } else {
                val tip = ui.hintLabel
                    ?: if (myTurn) "点选手牌 → 出牌；连点「提示」可轮循候选" else ""
                if (tip.isNotEmpty()) {
                    Text(
                        tip,
                        color = if (ui.hintLabel != null) Color(0xFFFFEB3B) else Color(0xFFA5D6A7),
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            // 操作按钮：等宽平分，窄屏也不会挤爆
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("出牌", myTurn && selectedCount > 0, Color(0xFF43A047),
                    Modifier.weight(1f), btnH) { viewModel.humanPlay() }
                ActionButton("过", ui.canPass, Color(0xFF757575),
                    Modifier.weight(1f), btnH) { viewModel.humanPass() }
                ActionButton("提示", myTurn, Color(0xFF1976D2),
                    Modifier.weight(1f), btnH) { viewModel.applyHint() }
                ActionButton("重开", true, Color(0xFF6A1B9A),
                    Modifier.weight(1f), btnH) { viewModel.newMatch(ui.difficulty) }
            }
            Spacer(Modifier.height(4.dp))

            // 手牌：按可用宽度动态重叠，27 张铺满一屏、无需横向滚动。
            // 每张牌至少露出 14dp 供点选，选中的牌 zIndex 抬高并上移，保证可逐张操作。
            // 支持「按住后横向滑动」连续选中整段手牌（与逐张点按共存）。
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .height(handRowH)
                    .testTag("handRow")
            ) {
                val hand = ui.humanHand
                val n = hand.size
                val cardW = cardH * 0.70f
                val inner = 2.dp
                val avail = maxWidth - inner * 2
                val spacing = if (n <= 1) 0.dp else
                    ((avail - cardW * n) / (n - 1))
                        .coerceAtMost(4.dp)
                        .coerceAtLeast(-(cardW - 14.dp))
                val density = LocalDensity.current
                val slotPx = with(density) { (cardW + spacing).toPx() }
                val innerPx = with(density) { inner.toPx() }

                // 用 detectDragGestures 等待 touch slop：轻点仍是单张 toggle，
                // 真正拖动才批量选中；父节点消费 MOVE 后，子卡片的 clickable 会被自动取消，
                // 不会在滑动结束时误把起点那张牌反选掉。
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(hand, slotPx, innerPx, myTurn) {
                            var anchor = -1
                            detectDragGestures(
                                onDragStart = { down ->
                                    anchor = HandSelection.indexAt(down.x - innerPx, slotPx, n)
                                    if (myTurn && anchor in 0 until n) {
                                        viewModel.selectCards(listOf(hand[anchor]))
                                    }
                                },
                                onDrag = { change, _ ->
                                    val cur = HandSelection.indexAt(
                                        change.position.x - innerPx, slotPx, n
                                    )
                                    val range = HandSelection.indicesBetween(anchor, cur, n)
                                    if (myTurn && !range.isEmpty()) {
                                        viewModel.selectCards(
                                            hand.subList(range.first, range.last + 1)
                                        )
                                    }
                                },
                                onDragEnd = { anchor = -1 },
                                onDragCancel = { anchor = -1 },
                            )
                        }
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp, start = inner, end = inner),
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        hand.forEachIndexed { i, card ->
                            val isSel = ui.selectedCards.any { it === card }
                            CardView(
                                card = card,
                                level = level,
                                cardHeight = cardH,
                                selected = isSel,
                                onClick = { if (myTurn) viewModel.toggleSelect(card) },
                                modifier = Modifier
                                    .zIndex(if (isSel) 1f else 0f)
                                    .offset(y = if (isSel) (-14.dp) else 0.dp)
                                    .then(if (i == n - 1) Modifier.testTag("lastCard") else Modifier)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    enabled: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 38.dp,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF37474F),
            disabledContentColor = Color(0xFF90A4AE)
        ),
        modifier = modifier.height(height)
    ) {
        Text(text, fontSize = 13.sp)
    }
}

// ---------------- 单局/整场结算面板 ----------------

@Composable
private fun HandResultOverlay(viewModel: GameViewModel, ui: GameUiState, viewportH: Dp, level: Int) {
    val res = ui.handResult ?: return
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
        Surface(
            color = Color(0xFFF5F5F5),
            shape = RoundedCornerShape(16.dp),
            // 高度不超过视口，横屏下也完整可见；内容超出时内部可滚动
            modifier = Modifier.fillMaxWidth(0.9f)
                .heightIn(max = (viewportH - 16.dp).coerceAtLeast(220.dp))
                .padding(12.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (ui.matchWinner != null) "🏆 本场结束" else "本局结束",
                    fontSize = 21.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))

                val names = listOf("你", "下家", "对家", "上家")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    res.finishOrder.forEachIndexed { i, seat ->
                        val medal = listOf("①", "②", "③", "④")[i]
                        Surface(color = Color(0xFFE0E0E0), shape = RoundedCornerShape(6.dp)) {
                            Text("$medal ${names[seat]}", fontSize = 12.sp,
                                modifier = Modifier.padding(6.dp, 3.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                val youWin = res.winnerTeam == 0
                Text(
                    if (ui.matchWinner != null)
                        (if (ui.matchWinner == 0) "🎉 你方打过 A，夺冠！" else "对手夺冠，再接再厉")
                    else "${if (youWin) "你方" else "对手"}获胜，升级 +${res.levelUp}",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = if (youWin) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
                // 各自记级模式下把双方级数摆出来：级数差是本模式下的胜负依据，
                // 也让玩家看清「只有自己先到 A 才能过 A」的进度。
                if (ui.levelMode == LevelMode.PER_TEAM) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "级数：你方 ${levelLabel(ui.myLevel)} · 对手 ${levelLabel(ui.opponentLevel)} ｜ 下一副打 ${ui.levelText}",
                        fontSize = 13.sp, color = Color(0xFF37474F),
                        modifier = Modifier.testTag("resultLevels")
                    )
                }

                // 结算面板只报本局名次与升级：按规范 5.1.3 / 5.5，进贡发生在下一副牌
                // 发牌之后，其公示由开局信息框给出，不在这里提前剧透。
                Spacer(Modifier.height(16.dp))
                if (ui.matchWinner != null) {
                    Button(onClick = { viewModel.newMatch(ui.difficulty) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A))) {
                        Text("再来一场", fontSize = 15.sp)
                    }
                } else {
                    Button(onClick = { viewModel.nextHand() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047))) {
                        Text("下一局", fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

/**
 * 选择还贡牌（v1.0.10）：人类收贡时开局暂停，必须先由玩家自己挑一张还牌。
 *
 * 规程只要求还牌牌点 ≤10，并未规定必须还最小的一张；默认最小牌经常恰好是
 * 对子 / 三张 / 炸弹里的一张，等于白送一手牌型。这里列出全部合法候选、
 * 逐张标注拆牌风险，并默认帮玩家选中最不容易拆牌的一张，玩家可任意改选。
 */
@Composable
private fun TributeBackOverlay(
    req: TributeBackRequest,
    level: Int,
    onConfirm: (Card) -> Unit,
) {
    var chosen by remember(req) { mutableStateOf(req.suggested) }
    val names = listOf("你", "下家", "对家", "上家")
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
        Surface(
            color = Color(0xFFF5F5F5),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .heightIn(max = 270.dp)
                .padding(12.dp)
                .testTag("tributeBackOverlay")
        ) {
            Column(
                Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("请选择本局还贡牌", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF3E2723))
                Text(
                    "${names[req.giver]} 贡给你一张牌，按规则你须还一张牌点 ≤10 的牌" +
                        "（王、本局级牌与刚收到的贡牌不可选）。" +
                        "系统默认选了最不容易拆牌的一张（金色框），点其它牌可以改；" +
                        "橙色 / 红色的牌会拆掉你手里的对子 / 三张 / 炸弹。",
                    fontSize = 11.sp, color = Color(0xFF6D4C41)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("收到贡牌：", fontSize = 12.sp)
                    CardView(req.give, level, cardHeight = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("共 ${req.candidates.size} 张可选", fontSize = 11.sp, color = Color(0xFF8D6E63))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag("tributeBackCandidates"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    req.candidates.forEachIndexed { i, card ->
                        val isChosen = chosen === card
                        val risk = req.risks.getOrElse(i) { BackRisk.NONE }
                        val riskColor = when (risk) {
                            BackRisk.NONE -> Color(0xFF2E7D32)
                            BackRisk.PAIR -> Color(0xFFEF6C00)
                            BackRisk.TRIPLE -> Color(0xFFC62828)
                            BackRisk.BOMB -> Color(0xFFB71C1C)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .testTag(if (isChosen) "tributeBackChosen" else "tributeBackCandidate")
                                    .border(
                                        2.dp,
                                        if (isChosen) Color(0xFFFFB300) else Color.Transparent,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(2.dp)
                                    .clickable { chosen = card }
                            ) {
                                CardView(card, level, cardHeight = 52.dp)
                            }
                            Text(risk.label, fontSize = 9.sp, color = riskColor,
                                fontWeight = if (risk == BackRisk.NONE) FontWeight.Normal
                                else FontWeight.Bold)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${chosen.display}", fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Button(
                        onClick = { onConfirm(chosen) },
                        modifier = Modifier.testTag("tributeBackConfirm"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) { Text("确认还这张", fontSize = 13.sp) }
                }
            }
        }
    }
}

/**
 * 开局进贡公示信息框：新一局**发牌后**自动弹一次，顶栏「贡」按钮可随时打开 / 关闭。
 * 用覆盖式卡片（跟结算 / 设置一致），带明确关闭按钮；不做常驻，避免长期占用牌桌空间。
 */
@Composable
private fun TributeOverlay(plan: TributePlan, level: Int, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
        Surface(
            color = Color(0xFFF5F5F5),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .padding(12.dp)
                .testTag("tributeOverlay")
        ) {
            // 关闭按钮放在顶部标题行：横屏下始终可见可点，不需要内部滚动（避免点击被裁剪）。
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("本局进贡 / 抗贡 · 开局公示", fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, color = Color(0xFF3E2723))
                        Text("新一局发牌后按规则公开这些信息，可据此记牌；点顶栏「贡」随时重看。",
                            fontSize = 10.sp, color = Color(0xFF8D6E63))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("tributeClose"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D4C41))
                    ) { Text("✕ 关闭", fontSize = 12.sp) }
                }
                Spacer(Modifier.height(8.dp))
                TributeDisclosure(plan, level, listOf("你", "下家", "对家", "上家"))
            }
        }
    }
}

/**
 * 进贡 / 还贡 / 抗贡公示明细（公开信息，v1.0.8 起，v1.0.9 移到开局信息框）。
 *
 * 竞赛规程要求贡牌、还牌放到本家「提醒区」并亮牌，抗贡须向所有牌手亮出双大王以证明；
 * 技巧文献亦称贡还牌是「出牌前唯一公开暴露的牌张信息」，应记其点数与花色。
 * 因此这里把具体牌张、进出双方、抗贡者及其双大王牌面一并展示，玩家可据此记牌决策。
 */
@Composable
private fun TributeDisclosure(plan: TributePlan, level: Int, names: List<String>) {
    Surface(
        color = Color(0xFFFFF8E1),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFFFFB300)),
        modifier = Modifier.fillMaxWidth().testTag("tributeDisclosure")
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("进贡 / 抗贡公示 · 公开信息", fontSize = 13.sp,
                fontWeight = FontWeight.Bold, color = Color(0xFF6D4C41))
            Text("贡牌、还牌须明牌亮出，抗贡须向所有牌手亮出双大王；这些都是公开信息，可据此记牌。",
                fontSize = 10.sp, color = Color(0xFF8D6E63))

            if (plan.antiTribute) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${plan.antiTributeBy.joinToString("、") { names[it] }} 亮出", fontSize = 12.sp)
                    plan.antiTributeCards.forEach { CardView(it, level, cardHeight = 40.dp) }
                    Text("→ 双大王抗贡，免贡", fontSize = 12.sp, color = Color(0xFFC62828))
                }
            } else {
                plan.exchanges.forEach { x ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${names[x.giver]} → ${names[x.receiver]}", fontSize = 12.sp)
                        Text("贡", fontSize = 11.sp, color = Color(0xFF6D4C41))
                        CardView(x.give, level, cardHeight = 40.dp)
                        Text("还", fontSize = 11.sp, color = Color(0xFF6D4C41))
                        CardView(x.back, level, cardHeight = 40.dp)
                    }
                }
                Text(
                    if (plan.doubleDown) "双下双贡：头游拿大贡、二游拿小贡，还牌均 ≤10"
                    else "单贡：贡除红心级牌外的最大单张，还牌 ≤10",
                    fontSize = 10.sp, color = Color(0xFF8D6E63)
                )
            }
            Text("下一局由${names[plan.nextLeader]}领出", fontSize = 11.sp,
                color = Color(0xFF5D4037), fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------------- 通用牌面 ----------------

@Composable
fun CardView(
    card: Card,
    level: Int,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    small: Boolean = false,
    cardHeight: Dp? = null,
    onClick: (() -> Unit)? = null,
) {
    val isRed = card.suit == Suit.HEARTS || card.suit == Suit.DIAMONDS
    val wild = card.isWild(level)
    val bg = if (wild) Color(0xFFFFF4CC) else Color.White
    val borderColor = if (wild) Color(0xFFFFB300) else Color(0xFFBDBDBD)
    val contentColor = when {
        card.isBigJoker -> Color(0xFFD32F2F)
        card.isSmallJoker -> Color(0xFF1565C0)
        isRed -> Color(0xFFD32F2F)
        else -> Color(0xFF212121)
    }
    val (rankText, suitChar) = when {
        card.isBigJoker -> "大" to "王"
        card.isSmallJoker -> "小" to "王"
        else -> {
            val r = when (card.rank) {
                11 -> "J"; 12 -> "Q"; 13 -> "K"; 14 -> "A"; else -> card.rank.toString()
            }
            val s = when (card.suit) {
                Suit.SPADES -> "♠"; Suit.HEARTS -> "♥"; Suit.CLUBS -> "♣"; else -> "♦"
            }
            r to s
        }
    }
    val w = when {
        cardHeight != null -> cardHeight * 0.70f
        small -> 34.dp
        else -> 48.dp
    }
    val h = cardHeight ?: if (small) 48.dp else 68.dp
    // 牌面文字按卡片尺寸自适应，且用 dp→sp 固定「视觉大小」：
    // 旧实现写死 10.sp / 17.sp，系统字体一放大，「点数 + 花色」两行总高就会超过
    // 36dp 的座位迷你牌，花色那行被挤到牌面下方显示不全。这里两行总高约 2.1×字号，
    // 永远小于卡片高度；再配合禁止自动换行、固定行高、关闭字体上下留白，彻底不溢出。
    // 除以 fontScale 再交给 sp：渲染时 Compose 会乘回来，视觉尺寸恒等于 dp 计算值，
    // 因此无论用户在系统里把字体调到多大，两行都不会被挤出牌面。
    val density = LocalDensity.current
    val faceSp = { dp: Dp -> (dp.value / density.fontScale).sp }
    val faceSize = faceSp(minOf(w * 0.60f, h * 0.32f))
    val faceStyle = TextStyle(
        lineHeight = (faceSize.value * 1.05f).sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(if (wild) 2.dp else 1.dp, borderColor),
        shadowElevation = if (selected) 8.dp else 2.dp,
        modifier = modifier
            .size(w, h)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (card.isBigJoker || card.isSmallJoker) {
                // 与斗地主一致的矢量卡通小丑：大王红帽红衣、小王黑帽黑衣，靠颜色区分大小
                JokerFace(
                    jokerColor = if (card.isBigJoker) Color(0xFFD23232) else Color(0xFF1A1A1A),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(rankText, fontSize = faceSize, style = faceStyle,
                        fontWeight = FontWeight.Bold, color = contentColor,
                        maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
                    Text(suitChar, fontSize = faceSize, style = faceStyle,
                        color = contentColor, maxLines = 1, softWrap = false,
                        textAlign = TextAlign.Center)
                }
            }
            if (wild) {
                Text("配", Modifier.align(Alignment.TopEnd).padding(2.dp),
                    fontSize = faceSp(h * 0.20f),
                    color = Color(0xFFFFB300), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 矢量卡通小丑（与斗地主同一形象）：尖顶帽（帽尖绒球）+ 圆脸 + 眼睛 + 红鼻 + 笑嘴 + 波浪衣领。
 * 大王红帽红衣、小王黑帽黑衣，靠颜色区分大小。所有元素按牌面宽高等比缩放，
 * 因此在手牌、出牌记录、教程迷你牌三种尺寸下都能正确显示。
 */
@Composable
private fun JokerFace(jokerColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // 波浪衣领（底部一排绒球）
        val ruffleR = w * 0.082f
        val ruffleY = h * 0.84f
        for (i in 0..5) {
            val cx = w * (0.13f + 0.148f * i)
            drawCircle(jokerColor, radius = ruffleR, center = Offset(cx, ruffleY))
        }
        // 圆脸
        val faceR = min(w, h) * 0.30f
        drawCircle(Color(0xFFF4D7B0), radius = faceR, center = Offset(w * 0.5f, h * 0.55f))
        // 尖顶帽
        val hat = Path().apply {
            moveTo(w * 0.5f, h * 0.04f)
            lineTo(w * 0.16f, h * 0.43f)
            lineTo(w * 0.84f, h * 0.43f)
            close()
        }
        drawPath(hat, jokerColor)
        // 帽尖绒球
        drawCircle(Color.White, radius = w * 0.07f, center = Offset(w * 0.5f, h * 0.045f))
        // 眼睛
        val eyeR = w * 0.045f
        val eyeY = h * 0.51f
        drawCircle(Color(0xFF1A1A1A), radius = eyeR, center = Offset(w * 0.39f, eyeY))
        drawCircle(Color(0xFF1A1A1A), radius = eyeR, center = Offset(w * 0.61f, eyeY))
        // 红鼻
        drawCircle(Color(0xFFD23232), radius = w * 0.055f, center = Offset(w * 0.5f, h * 0.59f))
        // 笑嘴（弧线）
        val mouth = Path().apply {
            moveTo(w * 0.37f, h * 0.63f)
            quadraticTo(w * 0.5f, h * 0.78f, w * 0.63f, h * 0.63f)
        }
        drawPath(mouth, Color(0xFF9B3B2A), style = Stroke(width = w * 0.04f, cap = StrokeCap.Round))
    }
}

// ---------------- 时钟与窗口工具 ----------------

/** 每 10 秒刷新一次的 HH:mm，用于横屏全屏时替代被隐藏的系统状态栏时钟。 */
@Composable
private fun rememberClockText(): String {
    var text by remember { mutableStateOf(clockText()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000L)
            text = clockText()
        }
    }
    return text
}

private fun clockText(): String {
    val now = LocalTime.now()
    return "%02d:%02d".format(now.hour, now.minute)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
