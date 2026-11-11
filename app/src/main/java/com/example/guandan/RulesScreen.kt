package com.example.guandan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.guandan.domain.Card
import com.example.guandan.domain.RANK_BIG_JOKER
import com.example.guandan.domain.RANK_SMALL_JOKER
import com.example.guandan.domain.Suit

/**
 * 玩法规则 + 新手教程（覆盖式全屏面板，内容按四个章节分页、可滚动）。
 *
 * 所有规则描述均与 domain 层实现一致：牌力顺序、牌型构成、压制层级、
 * 升级（双上/单上/单下）、**先发牌后进贡**与开局公示、逢人配限制、
 * 提示轮循策略、记牌器两层开关等。
 */
@Composable
internal fun RulesDialog(onDismiss: () -> Unit) {
    val tabs = listOf("快速上手", "牌型速查", "规则详解", "操作技巧")
    var tab by remember { mutableStateOf(0) }

    // 铺满视口：横屏高度有限，面板若按内容 wrap 会溢出，导致关闭按钮落在可交互区外。
    Box(Modifier.fillMaxSize().background(Color(0xCC000000))) {
        Surface(
            color = Color(0xFFF7FAF7),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .testTag("rulesPanel")
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "📖 掼蛋玩法规则 · 新手教程",
                        fontSize = 19.sp, fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B5E20), modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.height(32.dp).testTag("rulesClose"),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE0E0E0),
                            contentColor = Color(0xFF37474F)
                        )
                    ) { Text("关闭", fontSize = 12.sp) }
                }

                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tabs.forEachIndexed { i, name ->
                        val on = i == tab
                        Button(
                            onClick = { tab = i },
                            modifier = Modifier.weight(1f).height(34.dp)
                                .testTag("rulesTab_$i"),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (on) Color(0xFF1B5E20) else Color(0xFFE0E0E0),
                                contentColor = if (on) Color.White else Color(0xFF37474F)
                            )
                        ) { Text(name, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal) }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // weight(1f) 只占用「标题 + 标签页」之外的剩余高度，避免内容区
                // 再用 fillMaxSize 撑爆面板导致按钮被挤出可交互区域。
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .testTag("rulesContent")
                ) {
                    when (tab) {
                        0 -> QuickStart()
                        1 -> PatternGuide()
                        2 -> RulesDetail()
                        else -> OperationAndTips()
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

// ---------------- 第一章：快速上手（新手教程） ----------------

@Composable
private fun QuickStart() {
    SectionTitle("一、这游戏在玩什么")
    Body(
        "掼蛋是 4 人、2 副牌（共 108 张）的组队升级类扑克游戏，每人发 27 张。" +
            "你和「对家」（正上方）是一队，左右两家是另一队。两队都从打 2 开始，" +
            "谁先把手牌出完谁就是「头游」，头游所在队获胜并升级；一级级打到 A，再打过 A 就夺冠。"
    )

    SectionTitle("二、第一次进游戏，按这 7 步走")
    Step(1, "看清牌桌", "屏幕下方是你和你的 27 张手牌；正上方「对家」是队友（带蓝色「队友」标记），左边「上家」、右边「下家」是对手。中间牌桌显示本轮当前最大牌，旁边是战况记录。")
    Step(2, "认识级牌", "顶部「掼蛋 · 打 2」表示本局级数是 2。级牌（所有花色的 2）牌力仅次于王，比 A 还大；其中「红桃 2」是逢人配（百搭），可当任意牌用，牌面右上角有「配」字。")
    Step(3, "等轮到你", "首局由持有梅花 3 的人先出；之后每局由上一局进贡的「下游」先出（若双大王抗贡，则由头游先出）。轮到你时底部提示「轮到你出牌」，按钮才会亮起。电脑出牌前有短暂思考时间，可在「⚙ 设置」里调快或调慢。")
    Step(4, "选牌并出牌", "点一下手牌把它选起来（会抬起一截），再点一下取消；也可以按住手牌横向滑动，一次把划过的一整段连续牌全部选中，不用一张张点。选好一手后点「出牌」。跟牌时必须出「同牌型且更大」的一手，否则会提示压不过；不想出就点「过」。")
    Step(5, "不会出就点提示", "点「提示」会自动帮你选好一手合法牌。可以连续点，在多个候选之间轮循，并显示「提示 n/m · 牌型」。选中后点「出牌」即可，出牌失败还会给出具体原因。")
    Step(6, "看懂一轮", "一人领出后，其余三家依次跟牌或过；连续三家过，由最后出牌的人重新领出。若他已走完，则按标准「接风」由他的对家领出，可出任意牌型。谁先把牌出完就锁定名次，先走完的是头游。")
    Step(7, "结算与下一局", "三人走完本局结束，按名次升级（双上 +3 / 单上 +2 / 单下 +1）。点「下一局」后先洗牌发牌，再用新发到手的牌进贡：末游向头游贡最大的一张牌（红桃级牌除外），头游还一张不超过 10 的牌；双下时两人各贡一次，抓到双大王可抗贡。**若由你收贡，会先弹出选牌框让你自己挑还哪张**（默认已选中最不容易拆牌的一张），确认后才开局并自动弹出进贡信息框，顶栏「贡」按钮可随时重看或关闭。打 A 须头游且队友不是末游，三次不过退回打 2。")

    NoteBox("新手最容易忘的两件事：① 跟牌要同牌型，顺子不能压对子；② 队友领出、对手没威胁时果断点「过」，别去压自己人。")
}

// ---------------- 第二章：牌型速查 ----------------

@Composable
private fun PatternGuide() {
    SectionTitle("十种牌型（附真实牌面示例）")
    Body("规则依据：T/SQGD XH001—2019《掼蛋运动竞技规范》4.3。除炸弹 / 同花顺 / 天王炸外，普通牌型只能用「相同牌型、张数相同、且主牌更大」来压。")

    PatternRow("单张", "任意 1 张牌", listOf(sp(7)))
    PatternRow("对子", "2 张同点数。两张大王、两张小王各自成对（4.3.2），且对大王＞对小王＞对级牌；一大王+一小王牌点不同，不算对子", listOf(sp(9), he(9)))
    PatternRow("三张", "3 张同点数", listOf(sp(6), he(6), cl(6)))
    PatternRow("三带二", "3 张同点数 + 1 个对子（共 5 张）", listOf(sp(8), he(8), cl(8), sp(4), he(4)))
    PatternRow("顺子", "恰好 5 张连续单牌；A 可作 1 组成 A-2-3-4-5（最小），也可作最大组成 10-J-Q-K-A（最大）；不含王", listOf(sp(3), he(4), cl(5), di(6), sp(7)))
    PatternRow("连对", "恰好 3 组连续的对子（共 6 张）；AA-22-33 最小、QQ-KK-AA 最大", listOf(sp(3), he(3), sp(4), he(4), sp(5), he(5)))
    PatternRow("钢板", "恰好 2 组连续的三张（共 6 张）；AAA-222 合法", listOf(sp(3), he(3), cl(3), sp(4), he(4), cl(4)))
    PatternRow("炸弹", "4 张及以上同点数（两副牌最多 10 张）", listOf(sp(9), he(9), cl(9), di(9)))
    PatternRow("同花顺", "恰好 5 张同花色的连续牌，又称「火箭」；A-2-3-4-5 最小、10-J-Q-K-A 最大", listOf(sp(3), sp(4), sp(5), sp(6), sp(7)))
    PatternRow("天王炸", "2 张大王 + 2 张小王（全场最大）", listOf(BIG, BIG, SMALL, SMALL))

    SectionTitle("压制层级（重点）")
    Body("天王炸 ＞ 6 张及以上炸弹 ＞ 同花顺 ＞ 5 张炸弹 ＞ 4 张炸弹 ＞ 其它普通牌型。")
    Bullet("同花顺恰好 5 张，可压 4 张和 5 张炸弹；但压不了 6 张及以上炸弹，也压不了天王炸。")
    Bullet("6 张及以上炸弹可以压任何同花顺；炸弹 / 同花顺 / 天王炸也都能压所有普通牌型。")
    Bullet("炸弹之间：张数越多越大（6 张炸压 5 张炸），张数相同再比点数。")
    Bullet("同花顺之间：都是 5 张，只比最大那张牌（10-J-Q-K-A 最大）。")
    Bullet("顺子 / 连对 / 钢板：必须同型、张数相同才能比较，再比其中最大的点数。")

    NoteBox("常见误区：没有「6 张同花顺」这种牌型，不能靠加长同花顺去压更大的炸弹——同花顺永远只有 5 张，能不能压只看炸弹是几张。")
    NoteBox("A 只能出现在顺子 / 连对 / 钢板 / 同花顺的两端：与 2 相连时算 1 点（最小），与 K 相连时算最大。Q-K-A-2-3、J-Q-K-A-2 这类「跨 A 绕回」不合法；普通级牌按自身点数插带，红桃级牌（百搭）按它替代的牌点计算，王一律不入顺。")
    NoteBox("两副牌里「同点数同花色」的牌各有 2 张，所以对子、三张、炸弹都可能出现重复实体牌，这是合法的。")
}

// ---------------- 第三章：规则详解 ----------------

@Composable
private fun RulesDetail() {
    SectionTitle("级牌与逢人配（百搭）")
    Body("每局有一个「级数」（2→3→…→10→J→Q→K→A），这个点数的牌就是级牌。完整牌力顺序为：")
    Body("2 ＜ 3 ＜ … ＜ 10 ＜ J ＜ Q ＜ K ＜ A ＜ 级牌 ＜ 小王 ＜ 大王")
    Body("注意：级牌无论本身是几，一律排在 A 之上、小王之下。例如打 2 时，所有花色的 2 都比 A 大。")
    Body("「红桃级牌」叫逢人配 / 百搭，牌面右上角标「配」，可以代替任意一张非王的牌来凑牌型。两个限制：不能当作王使用（所以「百搭 + 大王」不算对大王）；顺子 / 连对 / 钢板 / 同花顺不允许含王。普通级牌按自身点数插带（例如打 2 时，黑桃 2 仍可进 2-3-4-5-6），不会因为它是级牌就升到 A 之上；但红桃级牌作百搭时按「它所替代的牌点」计算，不按自身点数 2 算。")
    Body("举例（打 2 时）：红桃2 + 三张 8 = 四张 8 的炸弹；红桃2 + 一张 K = 一对 K；红桃2 + ♠3 ♥4 ♣5 ♦6 会补成 3-4-5-6-7，也就是把百搭当 7（顶张 7），而不是当自身的 2 去组成 2-3-4-5-6（顶张 6）——按官方「组成最大的牌型」取对出牌方最有利的解释。两副牌共有 2 张红桃级牌，最多可同时用两张百搭。")
    NoteBox("由百搭撑起来的对子 / 三张 / 炸弹不是「固定牌型」，随时可以拆开，把百搭拿去补顺子、同花顺或更大的炸弹；受保护的只有纯粹由自然牌凑成的对子、三张和炸弹。")

    SectionTitle("出牌与过牌")
    Bullet("领出：每轮开始时可以出任意合法牌型，领出者不能「过」，必须出牌。")
    Bullet("跟牌：必须出与上家「同牌型、张数一致、且更大」的一手；或用炸弹 / 同花顺 / 天王炸压制。")
    Bullet("过牌：本轮放弃出牌，要等下一轮重新领出时才能再出。")
    Bullet("重新领出 / 接风：连续三家过后开启新一轮；最后出牌者仍在场时由他本人重新领出；若他已走完，则由他的对家「接风」领出（标准 4.8）。")
    Bullet("走完：手牌出完即锁定名次，之后不再参与本局出牌。")

    SectionTitle("名次与升级")
    Body("谁先把 27 张牌出完谁就是「头游」，依次是二游、三游、末游。头游所在队获胜，按「队友拿到第几名」决定升几级：")
    Bullet("双上：队友拿二游 → 升 3 级")
    Bullet("单上：队友拿三游 → 升 2 级")
    Bullet("单下：队友是末游 → 升 1 级")
    Body("A 是必打关卡，不能跳过（升级到 A 后要实打实地过一次）：打 A 时须本方拿到头游、且对家不是末游（二游或三游）才算打过 A 夺冠；若头游但队友末游，本局不升级、继续打 A，连续三次未过则按规则退回打 2 重新开始。")
    Body("本 APP 在「⚙ 设置 → 升级方式」里提供两种记级法，可随时切换（切换会重开一场）：")
    Bullet("**共用级数（默认，单机简化）**：全场只有一条级数，不论哪队赢都往上加，打 A 时赢家过 A 即夺冠。节奏快，但前面每一局的胜负不影响「谁最后夺冠」，只影响级数涨得多快。")
    Bullet("**各自记级（国家体育总局标准）**：两队各自从 2 记级，**只有上游方可以升级**；每副牌打的级牌取「上一副赢家升到的级数」，所以级牌会随赢家切换。顶栏会显示「你方 X · 对手 Y」。只有**自己级数已到 A** 的那一方，在打 A 的那副拿到头游且搭档不是末游，才算过 A 夺冠——对手级数还低时即使抢到头游也不算过 A，只是换打它自己的级数。前面每局胜负都会累积成级数差，正式比赛在规定副数/时间打完时也是「级数高的一方胜」，不是只看最后一副。")

    SectionTitle("进贡、还贡与抗贡")
    Bullet("单贡：上一局末游把全手牌最大的一张单牌进贡给头游，红桃级牌（逢人配）除外；头游还一张牌点不超过 10 的牌（若全手牌都大于 10，则还最小的一张）。")
    Bullet("**还贡由你自己挑**：规程只要求还牌 ≤10，并没有规定必须还最小的一张——还最小牌常常正好拆掉手里的对子、三张甚至炸弹。若收贡的是你，开局会先暂停并弹出选牌框，列出全部合法候选（王、本局级牌与刚收到的贡牌不可选），逐张标注「拆对子 / 拆三张 / 拆炸弹」，默认帮你选中最不容易拆牌的一张，点任意一张即可改；电脑收贡时也按同一标准自动挑，不再白送成型牌组。")
    Bullet("双贡：上一局「双下」（对方两人包揽三游和末游）时，两人各贡一张；头游拿较大的贡牌、二游拿较小的，各自还牌给对应的进贡者。")
    Bullet("抗贡：进贡方任一人手里有两张大王（双大王）即可免贡（双下时全队免贡），此时由头游先出。")
    Bullet("领出：有进贡时由下游领出（双下时由进贡给头游者领出）；抗贡时由头游领出。")
    Bullet("下一局**先洗牌发牌**（规范 5.1.3「下游首抓」），再用发到手的新牌进贡 / 还牌；级牌随升级变化，红桃新级牌成为新的逢人配。贡牌、还牌都必须是本人新一局手牌里的牌，交换后不会再洗一次牌。")

    SectionTitle("这些都是公开信息 · 开局才公示")
    Body("按竞赛规程，贡牌与还牌要放到「提醒区」并亮牌，抗贡必须向所有牌手亮出双大王证明。因此**具体进了哪张、还了哪张、谁靠双大王抗贡**大家都能看到，也是出牌前唯一公开暴露的牌张信息。")
    Body("这些信息属于**新一局**：本局结算面板只报名次与升级，点「下一局」发完牌后才自动弹出「进贡 / 抗贡公示」信息框，用真实牌面逐一亮出进出双方、贡牌、还牌，以及抗贡者亮出的两张大王。**若由你收贡，会先弹出选还贡牌框**：还牌没定下来之前不能提前公示，整局也暂停（AI 不会出牌、你也不能出牌），选完确认后才弹公示并开局。信息框可随时关闭，顶栏「贡」按钮可再打开；战况里也记下牌面供回看。养成「记贡还牌」的习惯——看到对手把大王进贡给了对家，就知道这张大牌在谁手里，据此调整出牌。")

    NoteBox("升级后级牌随之改变：比如从打 2 升到打 5，所有花色的 5 都变成级牌，红桃 5 成为新的逢人配。")
}

// ---------------- 第四章：操作与技巧 ----------------

@Composable
private fun OperationAndTips() {
    SectionTitle("界面上都有什么")
    Bullet("顶部：当前级数与级牌说明、右上角实时时钟（横屏全屏时替代被隐藏的系统时间）、🃏 记牌器 与「贡」按钮、⚙ 设置。你收贡时会出现覆盖式「选还贡牌」框，选完才能继续。")
    Bullet("中间：三家电脑的剩余张数、队友标记、各自最近出的一手牌，以及牌桌和战况记录。")
    Bullet("底部：你的手牌（点选或按住横向滑动连续多选，选中会抬起）、已选张数与「清空」、出牌 / 过 / 提示 / 重开四个按钮。")
    Bullet("设置里：调整难度、AI 出牌速度、升级方式（共用级数 / 各自记级）、记牌器总开关，以及随时打开本教程。设置会自动保存。")

    SectionTitle("记牌器（顶部窄带）")
    Body("设置里开启「记牌器」（默认开）后，顶栏出现「🃏 记牌器」按钮；点开就在顶栏下方展开一条窄带，再点一次收起。窄带是**独立一行**，不会盖住座位、出牌记录、手牌和任何按钮。")
    Body("口径与斗地主一致：全场 108 张 − 已出牌 − 自己手牌 = 其他三家手里可能还有几张，按大王 → 小王 → A → … → 2 从大到小逐列显示；两副牌所以每个点数满值是 **8**、大小王各 2。当前级牌那一行金色高亮，旁边「配 N」单独标出红桃级牌（逢人配）还剩几张；本局亮过牌的贡 / 还牌也会在末尾提示。")
    Body("百搭按打出的**实体牌**统计，不做「所替代点数」折算，避免与手牌 / 出牌记录对不上。关掉设置里的总开关即可完全隐藏该按钮与窄带。")

    SectionTitle("「提示」是怎么帮你选牌的")
    Body("提示会列出当前所有合法出牌并智能排序：领出时优先推荐顺子 / 连对 / 钢板 / 三带二这类一次能走多张的组合；跟牌时优先用「刚好压过、主牌最小」的一手，把大牌留到后面；大牌按标准层级殿后（4/5 张炸弹·同花顺 → 6 张及以上炸弹 → 天王炸）。")
    Body("提示与电脑都遵循「不拆成型牌组、不浪费逢人配」：只要还有别的选择，就不会把 4 张炸弹拆成单张 / 对子去压牌，也不会把红桃级牌拿去凑一个 3 点小对子或小三张；百搭优先留给炸弹、同花顺和顺子。由百搭撑起来的牌组不算固定牌型，可以拆开再用。")
    Body("三带二只比三张的点数，带哪个对子都不影响大小，所以提示会优先带最小的对子，不会把 AA、对 K 这类大对子白白带出去。")
    Body("连续点「提示」会依次切换候选并显示「提示 n/m · 牌型」；出牌、过牌或换局后自动从第 1 手重新开始。")

    SectionTitle("七条实战技巧")
    Bullet("先走多张：领出优先打顺子、连对、钢板，一次多消耗手牌，减少出牌轮次。")
    Bullet("炸弹留到最后：开局别急着炸，留给「对手只剩 1~2 张」或自己冲刺头游的关键时刻。")
    Bullet("会用逢人配：优先拿它补炸弹或同花顺，价值远高于补一个普通对子。")
    Bullet("配合队友：队友领出且对手没威胁时果断「过」，把出牌权留给队友；对手快走完时三家合力顶住。")
    Bullet("送对家走：你只剩 1 张牌时，电脑对家若自己没把握一轮走完，会领出最小的单张「送」你走完争头游——团队名次比自己多留一手大牌更重要。这是概率性软策略（困难档更积极），它自己也快能走完时则自己争。")
    Bullet("记大牌：留意已经出掉的大王、小王、级牌和炸弹，判断自己的牌什么时候是最大的。")
    Bullet("控场收尾：手上剩牌少时，先出能一次走完的大牌型，避免被对手用小牌卡住。")

    SectionTitle("难度说明")
    Bullet("简单 / 普通：电脑在「该顶还是该放」上带有概率性犹豫，适合熟悉规则、练手。")
    Bullet("困难：确定性最优打法，不出低级失误，懂得与队友配合、在对手将走完时顶牌。")

    NoteBox("把「AI 出牌速度」调成「快」练牌节奏明显加快；调成「慢」方便看清电脑每一手出了什么。")
}

// ---------------- 通用排版组件 ----------------

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(10.dp))
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
    Spacer(Modifier.height(5.dp))
}

@Composable
private fun Body(text: String) {
    Text(text, fontSize = 12.5.sp, lineHeight = 19.sp, color = Color(0xFF263238))
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("•", fontSize = 13.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(
            text, fontSize = 12.5.sp, lineHeight = 19.sp,
            color = Color(0xFF263238), modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Step(n: Int, title: String, text: String) {
    Surface(
        color = Color(0xFFE8F5E9), shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Row(Modifier.padding(10.dp)) {
            Surface(color = Color(0xFF2E7D32), shape = CircleShape, modifier = Modifier.size(24.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("$n", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                Spacer(Modifier.height(2.dp))
                Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xFF263238))
            }
        }
    }
}

@Composable
private fun NoteBox(text: String) {
    Spacer(Modifier.height(8.dp))
    Surface(
        color = Color(0xFFFFF3CD), shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "💡 $text", fontSize = 12.sp, lineHeight = 18.sp,
            color = Color(0xFF6D4C41), modifier = Modifier.padding(10.dp)
        )
    }
}

@Composable
private fun PatternRow(name: String, desc: String, cards: List<Card>) {
    Surface(
        color = Color.White, shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                Spacer(Modifier.width(10.dp))
                Text(desc, fontSize = 11.5.sp, color = Color(0xFF546E7A), lineHeight = 16.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                // level = 0 让示例牌不显示「配」角标，避免与教程上下文混淆
                cards.forEach { CardView(it, level = 0, cardHeight = 34.dp) }
            }
        }
    }
}

// ---------------- 示例牌构造 ----------------

private fun sp(r: Int) = Card(r, Suit.SPADES)
private fun he(r: Int) = Card(r, Suit.HEARTS)
private fun cl(r: Int) = Card(r, Suit.CLUBS)
private fun di(r: Int) = Card(r, Suit.DIAMONDS)
private val BIG = Card(RANK_BIG_JOKER, Suit.JOKER)
private val SMALL = Card(RANK_SMALL_JOKER, Suit.JOKER)
