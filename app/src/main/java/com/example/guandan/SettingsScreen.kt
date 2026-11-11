package com.example.guandan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.guandan.domain.AiSpeed
import com.example.guandan.domain.Difficulty
import com.example.guandan.domain.GameUiState
import com.example.guandan.domain.GameViewModel
import com.example.guandan.domain.LevelMode

/**
 * 设置面板（覆盖式，不使用系统 Dialog，便于横屏全屏与布局测试）。
 *
 * 包含：难度、AI 出牌速度、玩法规则 / 新手教程入口。
 * 难度与速度改动即时生效（不打断当前牌局），并由 ViewModel 持久化。
 */
@Composable
internal fun SettingsOverlay(
    viewModel: GameViewModel,
    ui: GameUiState,
    onDismiss: () -> Unit,
    onOpenRules: () -> Unit,
) {
    // 铺满视口：横屏高度有限，若让面板按内容 wrap 高度，底部按钮会被挤出屏幕而点不到。
    Box(Modifier.fillMaxSize().background(Color(0xCC000000))) {
        Surface(
            color = Color(0xFFF7FAF7),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .testTag("settingsPanel")
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "⚙ 设置",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B5E20), modifier = Modifier.weight(1f)
                    )
                    Surface(color = Color(0xFFE0E0E0), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "✕ 关闭", fontSize = 13.sp, color = Color(0xFF37474F),
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .clickable { onDismiss() }
                                .testTag("settingsClose")
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // 教程入口放在最上方：横屏高度有限，保证它始终可见、可点
                Button(
                    onClick = onOpenRules,
                    modifier = Modifier.fillMaxWidth().height(36.dp).testTag("openRules"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1565C0), contentColor = Color.White
                    )
                ) {
                    Text("📖 玩法规则 & 新手教程", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(10.dp))

                // 横屏宽度充裕：难度与速度并排两列，把整体高度压到一屏内，
                // 避免下方按钮落在可交互区之外（横屏高度常只有 320~360dp）。
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(1f)) {
                        SettingTitle("难度")
                        SettingHint("立即生效；困难档不出低级失误。")
                        OptionRow(
                            options = Difficulty.entries.map { it.label() to it },
                            current = ui.difficulty,
                            onPick = { viewModel.setDifficulty(it) },
                            testTagPrefix = "difficulty"
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        SettingTitle("AI 出牌速度")
                        SettingHint("下次出牌起生效，不影响打法。")
                        OptionRow(
                            options = AiSpeed.entries.map { it.labelWithHint() to it },
                            current = ui.aiSpeed,
                            onPick = { viewModel.setAiSpeed(it) },
                            testTagPrefix = "speed"
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                // 升级方式与记牌器并排两列：横屏高度有限，避免把下面内容挤出可视区
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(1f)) {
                        SettingTitle("升级方式")
                        SettingHint("切换会重开一场，详细说明见教程。")
                        OptionRow(
                            options = listOf(
                                "共用级数" to LevelMode.SHARED,
                                "各自记级（标准）" to LevelMode.PER_TEAM
                            ),
                            current = ui.levelMode,
                            onPick = { viewModel.setLevelMode(it) },
                            testTagPrefix = "levelMode"
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        SettingTitle("记牌器")
                        SettingHint("开：顶栏窄带看剩余牌数；关：隐藏。")
                        OptionRow(
                            options = listOf("开" to true, "关" to false),
                            current = ui.counterEnabled,
                            onPick = { viewModel.setCounterEnabled(it) },
                            testTagPrefix = "counter"
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "设置自动保存 · 详见「玩法规则 & 新手教程」。",
                    fontSize = 11.sp, color = Color(0xFF78909C), lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun SettingTitle(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
    Spacer(Modifier.height(3.dp))
}

@Composable
private fun SettingHint(text: String) {
    Text(text, fontSize = 12.sp, color = Color(0xFF546E7A), lineHeight = 16.sp, maxLines = 2)
    Spacer(Modifier.height(4.dp))
}

/** 通用分段选择器：等宽按钮，当前项高亮。 */
@Composable
private fun <T> OptionRow(
    options: List<Pair<String, T>>,
    current: T,
    onPick: (T) -> Unit,
    testTagPrefix: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, (label, value) ->
            val on = value == current
            Button(
                onClick = { onPick(value) },
                modifier = Modifier.weight(1f).height(38.dp)
                    .testTag("${testTagPrefix}_$index"),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (on) Color(0xFF2E7D32) else Color(0xFFE0E0E0),
                    contentColor = if (on) Color.White else Color(0xFF37474F)
                )
            ) {
                Text(label, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

private fun Difficulty.label() = when (this) {
    Difficulty.EASY -> "简单"
    Difficulty.NORMAL -> "普通"
    Difficulty.HARD -> "困难"
}

private fun AiSpeed.labelWithHint() = when (this) {
    AiSpeed.FAST -> "快 · 0.25s"
    AiSpeed.NORMAL -> "中 · 0.65s"
    AiSpeed.SLOW -> "慢 · 1.2s"
}
