package com.example.guandan.domain

/**
 * 进贡 / 还贡 / 抗贡的纯计算，依据《掼蛋运动竞技规范》4.9 / 4.10 / 5.5 / 5.6
 * 与通行赛事规程（中国石油大学《机关职工掼牌比赛规则》等）。
 *
 * 不修改手牌，只返回「谁给谁哪张、还哪张、下一局谁领出」的计划，由 GameViewModel 执行，
 * 便于在单元测试里直接构造任意手牌验证细则。
 *
 * **信息公开性（v1.0.8）**：竞赛规程要求贡牌 / 还牌放到本家「提醒区」并亮牌、
 * 抗贡须向所有牌手亮出双大王，因此贡 / 还牌的具体牌张、抗贡者与其双大王都是**公开信息**，
 * 所有牌手可见、可据此记牌调整策略。故 [TributePlan] 保留完整明细供 UI 公示，
 * 而不只是给一句文字日志。
 */
data class TributeExchange(
    val giver: Int,
    val receiver: Int,
    val give: Card,     // 进贡牌（该玩家手上最大的单张，红心级牌除外）
    val back: Card,     // 还贡牌（牌点 ≤10）
)

data class TributePlan(
    val exchanges: List<TributeExchange>,
    val nextLeader: Int,
    val notice: String,
    val antiTribute: Boolean,
    val doubleDown: Boolean,
    /** 抗贡时：持有双大王、据以免贡的进贡方座位（双下时可能不止一人）。非抗贡为空。 */
    val antiTributeBy: List<Int> = emptyList(),
    /** 抗贡时：这些玩家亮出的双大王牌（每人取 2 张），供 UI 公示、供他人核验。 */
    val antiTributeCards: List<Card> = emptyList(),
)

/**
 * 还掉某张牌会不会拆散自己手里已成型的小牌组（v1.0.10）。
 * 竞赛规程只要求还牌牌点 ≤10，并没有规定必须还最小的一张；还最小牌常常会把
 * 手里的对子 / 三张 / 炸弹拆掉，属于明显吃亏的选择。据此给玩家逐张标注风险。
 */
enum class BackRisk(val label: String) {
    NONE("不拆牌"), PAIR("拆对子"), TRIPLE("拆三张"), BOMB("拆炸弹"),
}

/**
 * 人类玩家（P0，收贡方）**自行选择还贡牌**时所需的全部信息（v1.0.10）。
 * AI 受贡仍由 [planTribute] 自动还牌；只有人类收贡时才暂停开局等待选择。
 *
 * @param candidates 合法候选（牌点 ≤10、非王、非百搭；极端时按规程兜底），已按牌力升序
 * @param risks 与 [candidates] 一一对应的拆牌风险，不能用 Map（两副牌里同点同花色实体 equals 相等）
 * @param suggested 系统默认选中的一张：优先「不拆牌的最小牌」，玩家可改选任意候选
 */
data class TributeBackRequest(
    val giver: Int,
    val give: Card,
    val candidates: List<Card>,
    val risks: List<BackRisk>,
    val suggested: Card,
    val notice: String,
    val nextLeader: Int,
    val doubleDown: Boolean,
)

/**
 * @param nameOf 座位显示名（用于战况记录文案），默认用 P0/P1…
 */
fun planTribute(
    res: HandResult,
    hands: List<List<Card>>,
    level: Int,
    nameOf: (Int) -> String = { "P$it" },
): TributePlan {
    // 4.9：双下时下游方两人各贡；单下时仅末游进贡
    val givers = if (res.doubleDown)
        listOf(res.finishOrder[2], res.finishOrder[3])
    else listOf(res.finishOrder[3])

    // 5.6：双下时进贡方一人或两人拥有双大王无需贡牌；单下时进贡方拥有双大王无需贡牌
    val holders = givers.filter { p -> hands[p].count { it.isBigJoker } >= 2 }
    if (holders.isNotEmpty()) {
        // 抗贡须向所有牌手亮出双大王：把亮出的牌一并带出供 UI 公示
        val shownKings = holders.flatMap { p -> hands[p].filter { it.isBigJoker }.take(2) }
        return TributePlan(
            exchanges = emptyList(),
            nextLeader = res.finishOrder[0],   // 抗贡时由头游领出
            notice = "${holders.joinToString("、") { nameOf(it) }} 亮出双大王抗贡，免贡；" +
                "由${nameOf(res.finishOrder[0])}领出",
            antiTribute = true,
            doubleDown = res.doubleDown,
            antiTributeBy = holders,
            antiTributeCards = shownKings,
        )
    }

    // 双贡时两个受贡者不能还同一张物理牌
    val usedBacks = java.util.IdentityHashMap<Card, Unit>()

    /** 还贡：牌点不超过 10（含 10）；若全手牌都大于 10，则还最小的一张（规程兜底）。
     *  v1.0.10 起优先挑「不拆对子 / 三张 / 炸弹」的最小一张，AI 也不再白送成型牌组。 */
    fun pickBack(player: Int): Card {
        val hand = hands[player]
        val free = hand.filter { !usedBacks.containsKey(it) }
        val pick = recommendBack(hand, backCandidates(free, level), level)
        usedBacks[pick] = Unit
        return pick
    }

    /** 进贡：全手牌最大的单张，红心级牌（逢人配）除外（5.5）。 */
    fun pickGive(player: Int): Card =
        hands[player].filter { !it.isWild(level) }.maxBy { cardStrength(it, level) }

    val exchanges = mutableListOf<TributeExchange>()
    val nextLeader: Int
    val notice: String

    if (res.doubleDown) {
        val receivers = listOf(res.finishOrder[0], res.finishOrder[1])
        // 头游拿较大的贡牌、二游拿较小的；同点时按座位顺序（顺时针贡牌）
        val offered = givers.map { p -> p to pickGive(p) }
            .sortedWith(
                compareByDescending<Pair<Int, Card>> { cardStrength(it.second, level) }
                    .thenBy { it.first }
            )
        for (i in 0..1) {
            val (giver, card) = offered[i]
            val receiver = receivers[i]
            exchanges += TributeExchange(giver, receiver, card, pickBack(receiver))
        }
        // 规程：双下还牌后均由「进贡给头游者」首圈领出
        nextLeader = offered[0].first
        notice = "双下双贡：${nameOf(offered[0].first)} 与 ${nameOf(offered[1].first)} 各贡一张"
    } else {
        val giver = res.finishOrder[3]
        val receiver = res.finishOrder[0]
        exchanges += TributeExchange(giver, receiver, pickGive(giver), pickBack(receiver))
        // 5.5「并由下游出牌」
        nextLeader = giver
        notice = "${nameOf(giver)} 向 ${nameOf(receiver)} 进贡，由下游领出"
    }

    return TributePlan(exchanges, nextLeader, notice, false, res.doubleDown)
}

// ---------------- 还贡选牌（v1.0.10） ----------------

/**
 * 合法还贡候选：牌点 ≤10、非王、非级牌（含红桃级牌逢人配）；按牌力升序排列。
 *
 * 两个容易踩的坑：
 * - 级牌的「牌点」可能只有 4 / 5，但本局它是仅次于王的主牌（cardStrength=15），
 *   绝不能因为点数小就被当成小牌还掉；
 * - [exclude] 用来排除**刚收到的贡牌**：进贡与还贡必须是不同牌张，
 *   否则玩家把贡牌原样还回去，等于白进贡、还拆了自己的牌。
 *
 * 若手上没有 ≤10 的牌（规程兜底），退化为最小的非王非级牌；极端情况再退化为全部手牌。
 * [exclude] 用引用相等判定（两副牌里同点同花色的实体有两张，equals 会误删）。
 */
fun backCandidates(
    hand: List<Card>,
    level: Int,
    exclude: List<Card> = emptyList(),
): List<Card> {
    fun isNormal(c: Card) = !c.isJoker && !c.isWild(level) && c.rank != level
    fun notExcluded(c: Card) = exclude.none { ex -> ex === c }
    val normal = hand.filter { isNormal(it) && notExcluded(it) }
        .ifEmpty { hand.filter { isNormal(it) } }
    val eligible = normal.filter { it.rank <= 10 }
    val pool = when {
        eligible.isNotEmpty() -> eligible
        normal.isNotEmpty() -> normal
        else -> hand.filter { notExcluded(it) }.ifEmpty { hand }
    }
    return pool.sortedWith(
        compareBy<Card> { cardStrength(it, level) }
            .thenBy { it.rank }
            .thenBy { it.suit.ordinal }
    )
}

/**
 * 系统推荐：候选中优先取「还掉后不拆任何成型牌组」的最小一张。
 * 用完整手牌 [hand]（含刚收到的贡牌）判断拆牌，避免推荐一张恰好凑成对子的牌。
 * 若每张候选都会拆牌，则退回最小的一张，并在 UI 上如实标注风险。
 */
fun recommendBack(hand: List<Card>, candidates: List<Card>, level: Int): Card =
    candidates.firstOrNull { HandShape.breakPenalty(hand, listOf(it), level) == 0 }
        ?: candidates.first()

/** 还掉 [card] 会拆掉什么组：按同点数自然牌张数 2/3/4+ 判定对子 / 三张 / 炸弹。 */
fun backRisk(hand: List<Card>, card: Card, level: Int): BackRisk {
    if (card.isJoker || card.isWild(level)) return BackRisk.NONE
    val n = hand.count { !it.isJoker && !it.isWild(level) && it.rank == card.rank }
    return when {
        n >= 4 -> BackRisk.BOMB
        n == 3 -> BackRisk.TRIPLE
        n == 2 -> BackRisk.PAIR
        else -> BackRisk.NONE
    }
}

/** 把进贡明细转成可读战况文字（含具体牌面），写入本局 log，供玩家回看公开信息。 */
fun tributeLogLines(plan: TributePlan): List<String> = buildList {
    if (plan.antiTribute) {
        add("${plan.antiTributeBy.joinToString("、") { "P$it" }} 亮出双大王抗贡，免贡")
    } else {
        plan.exchanges.forEach { x ->
            add("P${x.giver} 贡 ${x.give.display} → P${x.receiver}，还 ${x.back.display}")
        }
    }
}
