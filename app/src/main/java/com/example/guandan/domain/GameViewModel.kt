package com.example.guandan.domain

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Random

/**
 * 掼蛋整场对局控制器（ViewModel 层）。
 * 管理：发牌 → 单局引擎驱动 → 升级/进贡 → 下一局，直到某队打过 A 夺冠。
 *
 * @param appContext 可选。传入时难度与 AI 速度会通过 SharedPreferences 持久化；
 *                   不传（单元测试直接构造）时退化为纯内存状态，不触碰 Android API。
 */
class GameViewModel(appContext: Context? = null) : ViewModel() {

    companion object {
        private const val PREF_NAME = "guandan_settings"
        private const val KEY_DIFFICULTY = "difficulty"
        private const val KEY_AI_SPEED = "ai_speed"
        private const val KEY_COUNTER = "counter"
        private const val KEY_LEVEL_MODE = "level_mode"
    }

    // 注意声明顺序：prefs 必须先于 difficulty / aiSpeed 初始化，后者会读取它。
    private val prefs = appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 本副牌所打的级牌（级数）。共用模式下即全场级数；各自记级模式下为「上副赢家升到的级数」。 */
    var level = 2
        private set
    /**
     * 升级方式。默认「共用级数」保持 APP 原有行为；切到「各自记级」后按标准规则，
     * 两队各自从 2 记级、只有上游方升级。
     */
    var levelMode: LevelMode = loadLevelMode()
        private set
    /** 各自记级模式下两队的级数：下标 0 = 你方（0&2），1 = 对手（1&3）。共用模式不使用。 */
    private val teamLevels = intArrayOf(2, 2)
    var difficulty: Difficulty = loadDifficulty()
        private set
    var aiSpeed: AiSpeed = loadAiSpeed()
        private set
    /** 记牌器总开关（与斗地主一致：设置里控制顶栏按钮是否出现，默认开启）。 */
    var counterEnabled: Boolean = loadCounter()
        private set

    private var hands: List<MutableList<Card>> = emptyList()
    private var engine: GameEngine? = null
    private val rng = Random()

    private var lastPlayByPlayer = mutableMapOf<Int, CardPattern>()   // 仅用于牌型描述
    private var lastPlayCardsByPlayer = mutableMapOf<Int, List<Card>>() // 真实牌（用于展示）
    private var prevHadLastPlay = false
    private var aiJob: Job? = null
    private var handSettled = false       // 当前局是否已结算（防重复升级）
    private var generation = 0            // 局次代号：换局后旧 AI 协程必须自行退出
    private var aFailures = 0             // 打 A 连续未过关次数（三次不过退回 2）
    // 人类收贡时暂存的进贡计划与待替换的交换下标：玩家选定还牌后才生成最终公示并开局
    private var pendingPlan: TributePlan? = null
    private var pendingExchangeIndex = -1

    // 「提示」轮循缓存：同一局面内连续点提示会依次切换不同候选；
    // 出牌/过/换局后由 hintStateKey 自动失效并重建。
    private var hintCache: List<List<Card>> = emptyList()
    private var hintCursor = 0
    private var hintKey = ""

    // 选中牌只保存在 ViewModel 内部；UI 通过 GameUiState.selectedCards 读取快照。
    // 绝不能让 UI 直接读这个 MutableList——它的变化不会触发 Compose 重组。
    private val selected = mutableListOf<Card>()

    // 初始状态就要带上已持久化的难度/速度/升级方式，否则首帧与设置面板会显示默认值
    var uiState by mutableStateOf(
        GameUiState(
            difficulty = difficulty, aiSpeed = aiSpeed, counterEnabled = counterEnabled,
            levelMode = levelMode, myLevel = teamLevels[0], opponentLevel = teamLevels[1]
        )
    )
        private set

    // ---------------- 设置（难度 / AI 速度 / 记牌器 / 升级方式） ----------------

    private fun loadDifficulty(): Difficulty = when (prefs?.getString(KEY_DIFFICULTY, null)) {
        "EASY" -> Difficulty.EASY
        "HARD" -> Difficulty.HARD
        else -> Difficulty.NORMAL
    }

    private fun loadAiSpeed(): AiSpeed = when (prefs?.getString(KEY_AI_SPEED, null)) {
        "FAST" -> AiSpeed.FAST
        "SLOW" -> AiSpeed.SLOW
        else -> AiSpeed.NORMAL
    }

    private fun loadLevelMode(): LevelMode = when (prefs?.getString(KEY_LEVEL_MODE, null)) {
        "PER_TEAM" -> LevelMode.PER_TEAM
        else -> LevelMode.SHARED          // 老用户无该键：沿用原有「共用级数」行为，不突兀
    }

    /** 调整难度：立即对后续 AI 决策生效，不打断当前牌局；记住偏好。 */
    fun setDifficulty(d: Difficulty) {
        difficulty = d
        prefs?.edit()?.putString(KEY_DIFFICULTY, d.name)?.apply()
        uiState = uiState.copy(difficulty = d, revision = uiState.revision + 1)
    }

    /** 调整 AI 出牌速度：下一次思考延时即生效；记住偏好。 */
    fun setAiSpeed(s: AiSpeed) {
        aiSpeed = s
        prefs?.edit()?.putString(KEY_AI_SPEED, s.name)?.apply()
        uiState = uiState.copy(aiSpeed = s, revision = uiState.revision + 1)
    }

    private fun loadCounter(): Boolean = prefs?.getBoolean(KEY_COUNTER, true) ?: true

    /** 记牌器总开关：关闭后顶栏不再显示「记牌器」按钮与窄带；记住偏好。 */
    fun setCounterEnabled(on: Boolean) {
        counterEnabled = on
        prefs?.edit()?.putBoolean(KEY_COUNTER, on)?.apply()
        uiState = uiState.copy(counterEnabled = on, revision = uiState.revision + 1)
    }

    /**
     * 切换升级方式（设置面板入口）。两种级数模型互不兼容（全场一条 vs 双方各自记级），
     * 因此切换后**立即重开一场**，双方级数都回到 2，避免中途换算法造成状态错乱。
     * 偏好写入 SharedPreferences，下次进入依然生效。
     */
    fun setLevelMode(mode: LevelMode) {
        if (mode == levelMode) return
        levelMode = mode
        prefs?.edit()?.putString(KEY_LEVEL_MODE, mode.name)?.apply()
        newMatch(difficulty)
    }

    /** Activity 首次进入时开一局（若尚未开局），沿用已持久化的难度设置。 */
    fun startIfNeeded() {
        if (engine == null) newMatch(difficulty)
    }

    // ---------------- 生命周期 ----------------

    fun newMatch(diff: Difficulty) {
        generation++
        aiJob?.cancel()          // 停掉上一局仍在思考的 AI 协程
        difficulty = diff
        level = 2
        teamLevels[0] = 2
        teamLevels[1] = 2
        aFailures = 0
        selected.clear()
        lastPlayByPlayer.clear()
        lastPlayCardsByPlayer.clear()
        prevHadLastPlay = false
        handSettled = false
        aFailures = 0
        resetHint()
        pendingPlan = null
        pendingExchangeIndex = -1
        uiState = uiState.copy(
            handResult = null, matchWinner = null, message = "",
            selectedCards = emptyList(), errorText = null, hintLabel = null,
            tributePlan = null, pendingBack = null, playedCards = emptyList(),
            handNumber = uiState.handNumber + 1
        )
        val all = Deck.full()
        val dealt = Deal.split(all)
        hands = dealt
        // 首局由持有梅花3者领出，否则人类领出
        val leader = holderOfLowestClub(dealt)
        engine = GameEngine(level, hands, leader)
        updateUi()
        maybeDriveAi()
    }

    /**
     * 进入下一局：**先洗牌发牌，再用发到手的牌进贡**（规范 5.1.3「下游首抓」、
     * 5.1.5「抓牌……直至抓完全副牌」、5.5「进贡的牌必须是自己手中最大的单张」）。
     *
     * 旧实现是「拿上一局的发牌快照算贡牌 → 交换 → Deal.split 再洗一次牌」，
     * 交换结果会被洗牌抹掉，贡 / 还牌既不是新一局手牌里的牌、也不会落到对应玩家手里，
     * 抗贡的「双大王」也是按上一局的牌判的；planTribute 还在升级前调用，级牌基准用的是旧级数。
     * 现在改为：发牌 → 以**升级后的新 level** 用新牌算 [planTribute] → 在 hands 上直接交换
     * （不再二次洗牌）→ 建引擎 → 明细存入 uiState 供开局公示与随时查看。
     */
    fun nextHand() {
        val e = engine ?: return
        if (e.phase != Phase.HAND_OVER) return
        generation++
        aiJob?.cancel()
        val res = e.result()

        // 1) 先洗牌发牌，四家各 27 张全新手牌
        hands = Deal.split(Deck.full())
        // 2) 用新发到手的牌、按新一局的级牌算进贡 / 还贡 / 抗贡
        val plan = planTribute(res, hands, level, ::playerName)
        pendingPlan = null
        pendingExchangeIndex = -1

        // 3) 先执行「进贡」（贡牌落到收贡者手里）；AI 收贡立即自动还牌。
        //    人类收贡则暂缓还牌：默认最小牌经常拆掉对子 / 三张 / 炸弹，改由玩家自己挑。
        var humanIndex = -1
        for ((i, x) in plan.exchanges.withIndex()) {
            // 引用相等删除：两副牌同点数同花色的实体有两张，remove(equals) 会删错实例
            val gi = hands[x.giver].indexOfFirst { it === x.give }
            if (gi >= 0) {
                hands[x.giver].removeAt(gi)
                hands[x.receiver].add(x.give)
            }
            if (x.receiver == 0) {
                humanIndex = i
            } else {
                val ri = hands[x.receiver].indexOfFirst { it === x.back }
                if (ri >= 0) {
                    hands[x.receiver].removeAt(ri)
                    hands[x.giver].add(x.back)
                }
            }
        }

        lastPlayByPlayer.clear()
        lastPlayCardsByPlayer.clear()
        prevHadLastPlay = false
        handSettled = false
        selected.clear()
        resetHint()
        engine = GameEngine(level, hands, plan.nextLeader)

        if (humanIndex >= 0) {
            // 候选基于「收到贡牌之后」的真实手牌（此时 28 张），拆牌风险判断才准确
            val hand = hands[0].toList()
            val x = plan.exchanges[humanIndex]
            // 候选里剔除刚收到的贡牌：进贡 / 还贡必须是两张不同的牌
            val candidates = backCandidates(hand, level, exclude = listOf(x.give))
            val suggested = recommendBack(hand, candidates, level)
            pendingPlan = plan
            pendingExchangeIndex = humanIndex
            uiState = uiState.copy(
                handResult = null,
                matchWinner = null,
                tributePlan = null,       // 还牌未定，不能提前公示
                pendingBack = TributeBackRequest(
                    giver = x.giver,
                    give = x.give,
                    candidates = candidates,
                    risks = candidates.map { backRisk(hand, it, level) },
                    suggested = suggested,
                    notice = plan.notice,
                    nextLeader = plan.nextLeader,
                    doubleDown = plan.doubleDown,
                ),
                selectedCards = emptyList(), errorText = null, hintLabel = null,
                playedCards = emptyList(),
                // handNumber 保持不动：玩家确认还牌后才递增，触发开局公示自动弹一次
            )
            updateUi()   // 冻结状态下不会驱动 AI
        } else {
            finishTribute(plan)
        }
    }

    /**
     * 玩家确认还贡牌：把选中的实体牌从自己手里还给进贡者，生成最终公示明细并开局。
     * 等待期间 AI 与出牌操作均被冻结、引擎不会有任何动作，因此用最终手牌重建引擎是安全的。
     */
    fun chooseTributeBack(card: Card): Boolean {
        val req = uiState.pendingBack ?: return false
        val plan = pendingPlan ?: return false
        val idx = pendingExchangeIndex
        if (idx !in plan.exchanges.indices) return false
        if (req.candidates.none { it === card }) {
            showError("只能从候选牌里选择还贡牌")
            return false
        }
        val x = plan.exchanges[idx]
        val ri = hands[0].indexOfFirst { it === card }
        if (ri < 0) return false
        hands[0].removeAt(ri)
        hands[x.giver].add(card)

        val finalPlan = plan.copy(
            exchanges = plan.exchanges.mapIndexed { i, ex ->
                if (i == idx) ex.copy(back = card) else ex
            }
        )
        pendingPlan = null
        pendingExchangeIndex = -1
        engine = GameEngine(level, hands, plan.nextLeader)
        finishTribute(finalPlan)
        return true
    }

    /** 公示最终进贡明细并开局：写战况、清空等待态、递增局号（驱动 UI 自动弹一次公示）。 */
    private fun finishTribute(plan: TributePlan) {
        engine!!.log.add(plan.notice)
        engine!!.log.addAll(tributeLogLines(plan))
        uiState = uiState.copy(
            tributePlan = plan,
            pendingBack = null,
            handNumber = uiState.handNumber + 1,
        )
        updateUi()
        maybeDriveAi()
    }

    private fun awaitingBack(): Boolean = uiState.pendingBack != null

    private fun resetHint() {
        hintCache = emptyList()
        hintCursor = 0
        hintKey = ""
    }

    /**
     * 单局结束处理：升级 / 判定夺冠。分两种升级方式：
     * - [LevelMode.SHARED] 共用级数（APP 原有简化）：全场一条级数，谁赢都往上加；打 A 时头游+队友非末游即夺冠。
     * - [LevelMode.PER_TEAM] 各自记级（标准）：两队独立从 2 记级、只有上游方升级，
     *   下一副级牌取上副赢家升到的级数；只有自己级数到 A 的一方才能过 A 夺冠。
     *
     * 结算阶段**不展示**任何进贡信息：按规范 5.1.3 / 5.5，进贡发生在下一副牌
     * 洗牌发牌之后、首圈出牌之前，公示由 nextHand() 在新一局开局时给出。
     */
    private fun onHandOver() {
        val e = engine ?: return
        if (e.phase != Phase.HAND_OVER) return
        if (handSettled) return          // 防止同一局被重复结算（否则会重复升级）
        handSettled = true
        val res = e.result()

        if (levelMode == LevelMode.PER_TEAM) onHandOverPerTeam(res) else onHandOverShared(res)
    }

    /** 共用级数：全场一条级数，无论哪队赢都累加；A 必打、三次不过退回 2。原行为保持不变。 */
    private fun onHandOverShared(res: HandResult) {
        if (level == 14) {
            if (res.levelUp >= 2) {
                aFailures = 0
                uiState = uiState.copy(
                    phase = Phase.HAND_OVER, handResult = res,
                    matchWinner = res.winnerTeam, tributePlan = null,
                    message = if (res.winnerTeam == 0) "🎉 你方打过 A，夺冠！" else "对手打过 A 夺冠，再接再厉"
                ).withLevels()
                return
            }
            aFailures++
            val backToTwo = aFailures >= 3
            if (backToTwo) { aFailures = 0; level = 2 }
            uiState = uiState.copy(
                phase = Phase.HAND_OVER, handResult = res,
                tributePlan = null,
                message = if (backToTwo)
                    "打 A 三次不过，按规则退回打 2 重新开始"
                else
                    "打 A 未过（第 $aFailures 次）：头游但队友末游，本局不升级，继续打 A"
            ).withLevels()
            return
        }

        // 常规升级：A 必须打，不能靠一次 +3 直接跳过 A
        level = (level + res.levelUp).coerceAtMost(14)
        uiState = uiState.copy(
            phase = Phase.HAND_OVER, handResult = res,
            tributePlan = null,
            message = "本局 ${teamName(res.winnerTeam)}胜，升级到 ${levelText()}"
        ).withLevels()
    }

    /**
     * 各自记级（标准规则）。依据：
     * - 「只有上游一方可以升级」「双方升级级数的高低决定胜负」（掼蛋国际竞赛标准第二条、第十四条）；
     * - 「级牌牌点与上副牌上游所升至级数相同的牌为级牌」（2022《竞技掼蛋竞赛规则（试行）》）：
     *   本副打几由**上一副赢家升到的级数**决定，因此级牌会随赢家切换；
     * - 「过 A 是指打 A 方一家上游，其搭档不是下游」：只有**自己级数已到 A** 的那一方，
     *   在打 A 的那副拿到头游且搭档不是末游（levelUp ≥ 2），才算过 A 夺冠。
     *   对方级数还低时即使抢到头游也不算过 A，只是换打它自己的级数。
     */
    private fun onHandOverPerTeam(res: HandResult) {
        val w = res.winnerTeam
        val playingA = level == 14             // 本副打的是 A（上一副赢家已升到 A）
        val winnerAtA = teamLevels[w] == 14   // 赢家自己级数是否已到 A
        val crossA = playingA && winnerAtA && res.levelUp >= 2

        if (crossA) {
            aFailures = 0
            uiState = uiState.copy(
                phase = Phase.HAND_OVER, handResult = res,
                matchWinner = w, tributePlan = null,
                message = if (w == 0) "🎉 你方打过 A，夺冠！" else "对手打过 A 夺冠，再接再厉"
            ).withLevels()
            return
        }

        if (playingA) {
            // 打 A 未过：连续三次按赛事规程退回打 2（双方级数一并重置，避免卡死在 A）
            aFailures++
            val backToTwo = aFailures >= 3
            when {
                backToTwo -> {
                    aFailures = 0
                    teamLevels[0] = 2
                    teamLevels[1] = 2
                    level = 2
                }
                // 对方级数还低却抢到头游：不算过 A，它按自己的级数升级，下一副改打它升到的级数
                !winnerAtA -> {
                    teamLevels[w] = (teamLevels[w] + res.levelUp).coerceAtMost(14)
                    level = teamLevels[w]
                }
                // 打 A 方自己头游但队友末游（单下）：本局不升级，继续打 A
                else -> { /* level 保持 14 */ }
            }
            uiState = uiState.copy(
                phase = Phase.HAND_OVER, handResult = res,
                tributePlan = null,
                message = if (backToTwo)
                    "打 A 三次不过，双方级数退回打 2 重新开始"
                else
                    "打 A 未过（第 $aFailures 次）：${teamName(w)}未过 A，下一副打 ${levelText()}"
            ).withLevels()
            return
        }

        // 常规：只有赢家队伍升级，下一副级牌 = 赢家升到的级数
        teamLevels[w] = (teamLevels[w] + res.levelUp).coerceAtMost(14)
        level = teamLevels[w]
        aFailures = 0
        uiState = uiState.copy(
            phase = Phase.HAND_OVER, handResult = res,
            tributePlan = null,
            message = "本局 ${teamName(w)}胜，${teamName(w)}升到 ${levelText()}；" +
                "你方 ${levelLabel(teamLevels[0])} · 对手 ${levelLabel(teamLevels[1])}"
        ).withLevels()
    }

    /** 把当前级数快照同步进 UiState（顶栏要显示双方级数、本副级牌与模式）。 */
    private fun GameUiState.withLevels(): GameUiState {
        // 注意：这是 GameUiState 的扩展，裸写 levelMode 会被 receiver 的同名属性遮蔽，
        // 必须显式限定 this@GameViewModel，否则切到各自记级后仍按共用模式算双方级数。
        val mode = this@GameViewModel.levelMode
        return copy(
            levelText = levelText(),
            levelMode = mode,
            myLevel = if (mode == LevelMode.PER_TEAM) teamLevels[0] else level,
            opponentLevel = if (mode == LevelMode.PER_TEAM) teamLevels[1] else level,
        )
    }

    // ---------------- 玩家操作 ----------------

    fun toggleSelect(card: Card) {
        if (awaitingBack()) return
        if (uiState.phase != Phase.PLAYING || uiState.currentPlayer != 0) return
        // 必须用「引用相等」判定：两副牌里同点数同花色的牌有两张（equals 相等），
        // 若用 equals 判定，第二次点击第二张同牌会把第一张取消选中，导致无法打出对子。
        val idx = selected.indexOfFirst { it === card }
        if (idx >= 0) selected.removeAt(idx) else selected.add(card)
        updateUi()
    }

    /** 某张具体的牌是否已被选中（引用相等）。 */
    fun isSelected(card: Card): Boolean = selected.any { it === card }

    /**
     * 滑动连续选牌：把划过的一段手牌批量加入选中（只加不减，已选中的不重复加入）。
     * 与 [toggleSelect] 一样用引用相等判定，避免两副牌中同点数同花色的重复实体互相抵消。
     * 取消单张仍用点按 toggle，或用「清除」。没有新增任何牌时不触发重组，避免拖动时频繁刷新。
     */
    fun selectCards(cards: List<Card>) {
        if (awaitingBack()) return
        if (uiState.phase != Phase.PLAYING || uiState.currentPlayer != 0) return
        var added = false
        for (c in cards) {
            if (selected.none { it === c }) { selected.add(c); added = true }
        }
        if (added) updateUi()
    }

    fun clearSelection() { if (!awaitingBack()) { selected.clear(); updateUi() } }

    /** 出牌。返回是否成功；失败时给出可读原因，避免「点了没反应」。 */
    fun humanPlay(): Boolean {
        if (awaitingBack()) return false
        val e = engine ?: return false
        if (!e.isMyTurn(0)) { showError("还没轮到你出牌"); return false }
        val cards = selected.toList()
        if (cards.isEmpty()) { showError("请先点选手牌，或点「提示」自动选一手"); return false }

        val pattern = recognize(cards, level)
        if (pattern == null) { showError("所选的牌不构成合法牌型，请重新选择"); return false }

        val target = e.lastPlay?.pattern
        if (target != null && !beats(target, pattern)) {
            showError("这手牌压不过上家的${typeName(target.type)}，换更大的牌或点「过」")
            return false
        }
        if (!e.play(0, cards)) { showError("出牌失败，请重新选择"); return false }
        selected.clear()
        afterMove()
        return true
    }

    fun humanPass(): Boolean {
        if (awaitingBack()) return false
        val e = engine ?: return false
        if (!e.isMyTurn(0)) { showError("还没轮到你出牌"); return false }
        if (!e.canPass(0)) { showError("本轮由你领出，必须出牌（可点「提示」）"); return false }
        if (!e.pass(0)) return false
        selected.clear()
        afterMove()
        return true
    }

    /**
     * 当前局面下按「智能度」排序的全部候选（供轮循提示与单手提示共用）。
     *
     * - 跟牌（有上家牌）：优先用主牌最小的一手压过，最大限度保留大牌；
     * - 领出（无上家牌）：优先一次能多走张的组合牌（顺子/连对/钢板/三带二），
     *   而不是每次都推一张最小单张；
     * - 大牌按标准层级殿后：普通牌 → 4/5 张炸弹·同花顺 → 6 张及以上炸弹 → 天王炸；
     * - **不拆成型牌组**：只要还有不拆炸弹 / 三张 / 对子的候选，就剔除拆组候选；
     * - **不浪费百搭**：只要还有不用百搭凑小对 / 小三张 / 三带二的候选，就剔除这类候选；
     * - 若所有候选都「不干净」（残局只剩拆牌才能压），则全部保留兜底并按代价排序，避免提示无牌可出；
     * - 同「牌型 + 主牌 + 张数」只保留一个代表，避免不同花色/组合把轮循刷成几十条。
     */
    private fun hintPriority(p: CardPattern): Int = when (p.type) {
        PatternType.FOUR_KINGS -> 3
        PatternType.BOMB -> if (p.size >= 6) 2 else 1
        PatternType.FLUSH_STRAIGHT -> 1
        else -> 0
    }

    private fun hintPlays(e: GameEngine): List<List<Card>> {
        val hand = e.handOf(0)
        val following = e.lastPlay?.pattern != null
        val scored: List<Pair<List<Card>, CardPattern>> = e.legalPlaysFor(0)
            .mapNotNull { c -> recognize(c, level)?.let { p -> c to p } }

        // 软过滤：存在干净候选时剔除坏候选；否则全部保留兜底
        val clean = scored.filter { (c, p) ->
            HandShape.breakPenalty(hand, c, level) == 0 && !HandShape.wastesWild(c, p, level)
        }
        val pool = if (clean.isNotEmpty()) clean else scored

        val sorted = pool.sortedWith(
            compareBy<Pair<List<Card>, CardPattern>> { hintPriority(it.second) }
                .thenBy { HandShape.breakPenalty(hand, it.first, level) }
                .thenBy { if (HandShape.wastesWild(it.first, it.second, level)) 1 else 0 }
                .thenBy { if (following) it.second.mainRank else -it.first.size }
                .thenBy { it.second.mainRank }
                // 三带二只比三张的牌点（5.3.6），带牌对子不影响大小 → 优先带最小的对子，
                // 避免把 AA / 对 K 这类大对子当累赘甩出去。
                .thenBy { HandShape.kickerStrength(it.first, it.second, level) }
                .thenBy { it.first.size }
        )
        val seen = HashSet<String>()
        return sorted.mapNotNull { (cards, p) ->
            // 三带二的去重键额外包含带牌强度：否则「同三张 + 不同带牌」会被合并成一条，
            // 轮循里根本看不到「带小对」的那手，用户只能拿到带大对的候选。
            val key = if (p.type == PatternType.TRIPLE_WITH_PAIR)
                "${p.type}:${p.mainRank}:${cards.size}:${HandShape.kickerStrength(cards, p, level)}"
            else "${p.type}:${p.mainRank}:${cards.size}"
            if (seen.add(key)) cards else null
        }
    }

    /** 单手提示：排序后的第一手（保留既有语义，供测试与快速出牌使用）。 */
    fun hint(): List<Card>? {
        if (awaitingBack()) return null
        val e = engine ?: return null
        if (!e.isMyTurn(0)) return null
        return hintPlays(e).firstOrNull()
    }

    /** 局面键：点提示不改变它，出牌 / 过 / 换局必定改变它。 */
    private fun hintStateKey(e: GameEngine): String = "$generation:${e.log.size}:${e.current}"

    /**
     * 轮循提示：连续点击依次选中不同的合法候选，并显示「提示 n/m · 牌型」。
     * 缓存按局面键失效，出牌或换局后自动从第 1 手重新开始。
     */
    fun applyHint() {
        if (awaitingBack()) return
        val e = engine ?: return
        if (!e.isMyTurn(0)) { showError("还没轮到你出牌"); return }
        val key = hintStateKey(e)
        if (key != hintKey) {
            hintCache = hintPlays(e)
            hintCursor = 0
            hintKey = key
        }
        if (hintCache.isEmpty()) { showError("没有能压过的牌，请点「过」"); return }
        val idx = hintCursor % hintCache.size
        val pick = hintCache[idx]
        hintCursor = idx + 1
        selected.clear()
        selected.addAll(pick)
        val p = recognize(pick, level)
        uiState = uiState.copy(
            selectedCards = selected.toList(),
            errorText = null,
            hintLabel = "提示 ${idx + 1}/${hintCache.size}" +
                (p?.let { " · ${typeName(it.type)}" } ?: ""),
            revision = uiState.revision + 1,
        )
    }

    /** 显示一条错误提示；revision 自增确保即使文案相同也能触发重组。 */
    private fun showError(msg: String) {
        uiState = uiState.copy(errorText = msg, hintLabel = null, revision = uiState.revision + 1)
    }

    private fun typeName(t: PatternType): String = when (t) {
        PatternType.SINGLE -> "单张"
        PatternType.PAIR -> "对子"
        PatternType.TRIPLE -> "三张"
        PatternType.TRIPLE_WITH_PAIR -> "三带二"
        PatternType.STRAIGHT -> "顺子"
        PatternType.DOUBLE_STRAIGHT -> "连对"
        PatternType.TRIPLE_STRAIGHT -> "钢板"
        PatternType.FLUSH_STRAIGHT -> "同花顺"
        PatternType.BOMB -> "炸弹"
        PatternType.FOUR_KINGS -> "天王炸"
    }

    // ---------------- AI 驱动 ----------------

    private fun afterMove() { updateUi(); maybeDriveAi() }

    private fun maybeDriveAi() {
        // 还贡牌未选定前整局暂停：AI 不得出牌，玩家也不得出牌（由各操作入口一并冻结）
        if (awaitingBack()) return
        val e = engine ?: return
        if (e.phase == Phase.HAND_OVER) { onHandOver(); return }
        if (e.current == 0) { updateUi(); return }      // 轮到人类
        if (aiJob?.isActive == true) return             // 已有 AI 协程在跑，避免重复驱动

        // 单个协程内连续驱动所有 AI，直到再次轮到人类或本局结束。
        // 注意：不能用「递归调用 maybeDriveAi」的方式——递归发生在协程内部，
        // 此时 aiJob 仍是自身（isActive=true），会被重入保护直接挡掉，导致 AI 只走一步就卡死。
        aiJob = viewModelScope.launch {
            val myGen = generation
            while (true) {
                if (generation != myGen) break   // 已重开/进入下一局，旧协程退出
                val g = engine ?: break
                val cur = g.current
                if (cur == 0 || g.phase != Phase.PLAYING) break

                // 速度档位在每次等待时读取，设置里改动后下一次出牌即生效
                delay(aiSpeed.delayMs)

                // 延时期间可能已换局，重新取一次当前行动者
                if (generation != myGen) break
                val g2 = engine ?: break
                val who = g2.current
                if (who == 0 || g2.phase != Phase.PLAYING) break

                val move = HeuristicAi.decideMove(
                    who, g2.handOf(who), g2.lastPlay?.pattern, g2.lastPlay?.player,
                    level, difficulty, g2.hands.map { it.size }, rng
                )
                if (move.isEmpty()) g2.pass(who) else g2.play(who, move)
                updateUi()
            }
            if (generation == myGen && engine?.phase == Phase.HAND_OVER) onHandOver()
        }
    }

    // ---------------- 状态构建 ----------------

    private fun updateUi() {
        val e = engine ?: return
        val lp = e.lastPlay
        if (lp != null) {
            if (!prevHadLastPlay) { lastPlayByPlayer.clear(); lastPlayCardsByPlayer.clear() }
            lastPlayByPlayer[lp.player] = lp.pattern
            lastPlayCardsByPlayer[lp.player] = lp.cards   // 真实牌（展示用）
            prevHadLastPlay = true
        } else {
            lastPlayByPlayer.clear()
            lastPlayCardsByPlayer.clear()
            prevHadLastPlay = false
        }

        val players = (0..3).map { seat ->
            PlayerView(
                seat = seat,
                name = playerName(seat),
                isHuman = seat == 0,
                cardCount = e.handOf(seat).size,
                lastPlay = lastPlayCardsByPlayer[seat],
                isCurrent = e.current == seat && e.phase == Phase.PLAYING,
                finishedRank = if (seat in e.finishOrder) e.finishOrder.indexOf(seat) + 1 else null,
                isTeammate = teamOf(seat) == teamOf(0),
            )
        }
        uiState = uiState.copy(
            levelText = levelText(),
            levelMode = levelMode,
            myLevel = if (levelMode == LevelMode.PER_TEAM) teamLevels[0] else level,
            opponentLevel = if (levelMode == LevelMode.PER_TEAM) teamLevels[1] else level,
            difficulty = difficulty,
            aiSpeed = this.aiSpeed,
            counterEnabled = this.counterEnabled,
            playedCards = e.playedCards.toList(),
            players = players,
            humanHand = sortForDisplay(e.handOf(0), level),
            // 选中牌快照必须进 uiState：否则选牌时 copy 出的对象与原对象 equals 相同，
            // mutableStateOf 的 structuralEqualityPolicy 会判定「无变化」而不重组，
            // 表现为卡片不抬升、出牌按钮一直灰着（无法出牌）。
            selectedCards = selected.toList(),
            currentPlayer = e.current,
            phase = e.phase,
            canPass = e.canPass(0),
            lastPlay = e.lastPlay,
            log = e.log.takeLast(8),
            errorText = null,
            hintLabel = null,   // 任何局面刷新都清除轮循标签；applyHint 不走 updateUi，故不受影响
            revision = uiState.revision + 1,   // 强制每次状态构建都刷新 UI
            message = if (awaitingBack()) "请先选择本局还贡牌"
            else if (e.phase == Phase.PLAYING)
                (if (e.current == 0) "轮到你出牌" else "${playerName(e.current)} 行动中…")
            else uiState.message,
        )
    }

    private fun levelText(): String = levelLabel(level)

    private fun playerName(seat: Int) = when (seat) {
        0 -> "你（南）"
        1 -> "下家（敌）"
        2 -> "对家（队友）"
        else -> "上家（敌）"
    }

    private fun teamName(team: Int) = if (team == 0) "你方" else "对手"

    /** 首局由持有梅花3者领出；找不到则人类领出。 */
    private fun holderOfLowestClub(dealt: List<List<Card>>): Int {
        dealt.forEachIndexed { i, hand ->
            if (hand.any { it.rank == 3 && it.suit == Suit.CLUBS }) return i
        }
        return 0
    }
}

/** 级数点数转显示文字：11→J、12→Q、13→K、14→A。UI 与结算文案共用。 */
fun levelLabel(level: Int): String = when (level) {
    11 -> "J"; 12 -> "Q"; 13 -> "K"; 14 -> "A"; else -> level.toString()
}

/** 发牌辅助（两副牌洗牌均分 4×27）。 */
object Deal {
    fun split(cards: List<Card>): List<MutableList<Card>> {
        val shuffled = cards.shuffled(Random())
        return List(4) { i -> shuffled.subList(i * 27, (i + 1) * 27).toMutableList() }
    }
}

data class PlayerView(
    val seat: Int,
    val name: String,
    val isHuman: Boolean,
    val cardCount: Int,
    val lastPlay: List<Card>?,   // 真实牌（展示用）；不取 CardPattern.cards（其为合成占位）
    val isCurrent: Boolean,
    val finishedRank: Int?,
    val isTeammate: Boolean,
)

data class GameUiState(
    val levelText: String = "2",
    /** 当前升级方式；顶栏据此决定显示「全场一条级数」还是「双方各自级数」。 */
    val levelMode: LevelMode = LevelMode.SHARED,
    /** 各自记级模式下你方 / 对手的级数（2..14）；共用模式下两者恒等于本副级牌。 */
    val myLevel: Int = 2,
    val opponentLevel: Int = 2,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val aiSpeed: AiSpeed = AiSpeed.NORMAL,
    /** 记牌器总开关（设置里持久化）：关闭后顶栏不出现「记牌器」按钮与窄带。 */
    val counterEnabled: Boolean = true,
    /** 本局累计已打出的实体牌，供记牌器统计「其他三家手里可能还有几张」。 */
    val playedCards: List<Card> = emptyList(),
    val players: List<PlayerView> = emptyList(),
    val humanHand: List<Card> = emptyList(),
    val selectedCards: List<Card> = emptyList(),  // 选中牌快照（驱动按钮可用态与卡片抬升）
    val currentPlayer: Int = 0,
    val phase: Phase = Phase.PLAYING,
    /** 换局序号：newMatch / nextHand 递增，供 UI 判断「新一局开局」以自动弹一次进贡公示。 */
    val handNumber: Int = 0,
    val canPass: Boolean = false,
    val lastPlay: Play? = null,
    val log: List<String> = emptyList(),
    val handResult: HandResult? = null,
    /** 本局开局的进贡 / 还贡 / 抗贡公示（公开信息，含具体牌张与亮出的双大王）。
     *  仅在 nextHand() 发牌后生成：结算面板不显示、开局自动弹一次、顶栏「贡」按钮可随时查看。 */
    val tributePlan: TributePlan? = null,
    /** 人类收贡时的待选还贡请求（v1.0.10）：非空表示开局暂停、等待玩家自己挑还牌。 */
    val pendingBack: TributeBackRequest? = null,
    val matchWinner: Int? = null,
    val message: String = "",
    val errorText: String? = null,   // 出牌失败等原因提示
    val hintLabel: String? = null,   // 轮循提示进度，如「提示 3/12 · 顺子」
    val revision: Long = 0,          // 单调递增，保证状态变化一定触发重组
)
