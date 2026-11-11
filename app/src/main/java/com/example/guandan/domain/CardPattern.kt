package com.example.guandan.domain

/**
 * 掼蛋牌型（含逢人配/红桃级牌百搭）。
 *
 * 规则依据：T/SQGD XH001—2019《掼蛋运动竞技规范》4.3 / 5.3.10，
 * 以及国家体育总局棋牌运动管理中心《竞技掼蛋扑克竞赛规则》第四条 / 第五条。
 * 强度层级（大→小）：
 *   四王（天王炸） > 六张及以上炸弹 > 同花顺 > 五张炸弹 > 四张炸弹 > 其它普通牌型。
 *
 * 关键：炸弹按「张数」跨在同花顺两侧——4/5 张炸弹低于同花顺，6 张及以上炸弹高于同花顺。
 * 同花顺固定恰好 5 张，不存在「用更多张同花顺压更大炸弹」的玩法。
 *
 * A 的双重位置（第四条(一)2）：A 在顺子 / 同花顺 / 连对 / 钢板中「与 2 相连」时视作 1 点（最小），
 * 「与 K 相连」时保持 14 点（最大）；只能在窗口两端之一，不允许 Q-K-A-2-3 这类跨 A 循环。
 * 级牌按第五条(一)「只能按级牌牌点大小插带」，即按自然点数参与连续牌型，不享受单张时的升权。
 */
enum class PatternType {
    SINGLE,                 // 单张
    PAIR,                   // 对子（4.3.2：含两张大王、两张小王；5.3.2 对大王>对小王>对级牌）
    TRIPLE,                 // 三张
    TRIPLE_WITH_PAIR,       // 三带二
    STRAIGHT,               // 顺子（恰好 5 张连续单牌，4.3.7）
    DOUBLE_STRAIGHT,        // 连对（恰好 3 对 / 6 张，4.3.3）
    TRIPLE_STRAIGHT,        // 钢板（恰好 2 组三同张 / 6 张，4.3.5）
    FLUSH_STRAIGHT,         // 同花顺 / 火箭（恰好 5 张，4.3.8）
    BOMB,                   // 炸弹（4 张及以上同点，4.3.9）
    FOUR_KINGS              // 天王炸（4 张王，4.3.10）
}

/**
 * 一个被识别出的牌型。
 * @param type 牌型
 * @param mainRank 用于同型比较的主点数（连续牌型取顶张自然点数；单张/对子等用 [cardStrength]）
 * @param size 张数（炸弹按张数比较；同花顺固定 5 张，只比 mainRank）
 * @param cards 组成牌
 */
data class CardPattern(
    val type: PatternType,
    val mainRank: Int,
    val size: Int,
    val cards: List<Card>,
)

/** 同一手牌在领出时可形成哪些牌型（用于 AI/合法性枚举）。 */
fun recognize(cards: List<Card>, level: Int): CardPattern? {
    if (cards.isEmpty()) return null
    val wilds = cards.filter { it.isWild(level) }
    val nonWild = cards.filter { !it.isWild(level) }
    val w = wilds.size

    fourKings(cards)?.let { return it }
    flushStraight(nonWild, w, level)?.let { return it }
    bomb(nonWild, w, level)?.let { return it }
    tripleStraight(nonWild, w, level)?.let { return it }
    doubleStraight(nonWild, w, level)?.let { return it }
    straight(nonWild, w, level)?.let { return it }
    tripleWithPair(nonWild, w, level)?.let { return it }
    triple(nonWild, w, level)?.let { return it }
    pair(nonWild, w, cards, level)?.let { return it }
    single(cards, level)?.let { return it }
    return null
}

/**
 * 标准牌型层级（数值越大越强），依据《掼蛋运动竞技规范》5.3.10：
 *   四王 > 六张和六张以上炸弹 > 同花顺 > 五张炸弹 > 四张炸弹 > 其它牌型。
 *
 * 炸弹按张数分到同花顺的两侧：4/5 张 → 层级 1（低于同花顺）；
 * 6 张及以上 → 层级 3（高于同花顺）。因此「5 张同花顺能压 4 张、5 张炸弹，
 * 但压不了 6 张及以上炸弹；反过来 6 张及以上炸弹可以压同花顺」。
 */
private fun tier(p: CardPattern): Int = when (p.type) {
    PatternType.FOUR_KINGS -> 4
    PatternType.BOMB -> if (p.size >= 6) 3 else 1
    PatternType.FLUSH_STRAIGHT -> 2
    else -> 0
}

/** 判断 candidate 是否能压过 target（target 为场上当前最大牌）。 */
fun beats(target: CardPattern, candidate: CardPattern): Boolean {
    val tt = tier(target)
    val ct = tier(candidate)
    // 跨层级：高层级压一切低层级（含所有普通牌型）；低层级一律压不过
    if (ct != tt) return ct > tt

    // 同层级内部比较
    return when (target.type) {
        PatternType.FOUR_KINGS -> false                 // 天王炸无敌（全场也仅有一副）
        PatternType.BOMB ->                             // 同为炸弹：张数多者大，同张数比点数
            candidate.size > target.size ||
                (candidate.size == target.size && candidate.mainRank > target.mainRank)
        PatternType.FLUSH_STRAIGHT ->                   // 同花顺恒定 5 张：只比最大那张牌
            candidate.mainRank > target.mainRank
        else -> {
            // 普通牌型：必须同型；连对/钢板等组合牌型还要求张数相同
            if (target.type != candidate.type) return false
            val lengthType = candidate.type in setOf(
                PatternType.STRAIGHT, PatternType.DOUBLE_STRAIGHT, PatternType.TRIPLE_STRAIGHT
            )
            if (lengthType && target.size != candidate.size) return false
            candidate.mainRank > target.mainRank
        }
    }
}

// ---------------------- 各牌型识别（wild = 红桃级牌百搭） ----------------------

private fun single(cards: List<Card>, level: Int): CardPattern? {
    if (cards.size != 1) return null
    val c = cards.first()
    // 单张主点数按牌力（级牌=15、小王=16、大王=17），保证级牌 > A
    return CardPattern(PatternType.SINGLE, cardStrength(c, level), 1, cards)
}

private fun pair(nonWild: List<Card>, w: Int, all: List<Card>, level: Int): CardPattern? {
    if (all.size != 2) return null
    // 4.3.2「对子：两张牌点相同的牌，包括两张大王和两张小王」；
    // 5.3.2「对大王>对小王>对级牌>AA>…>22」。一大王+一小王牌点不同，不是对子。
    val jokers = all.filter { it.isJoker }
    if (jokers.size == 2 && all.all { it.isJoker }) {
        if (all[0].rank != all[1].rank) return null
        return CardPattern(PatternType.PAIR, cardStrength(all.first(), level), 2, all)
    }
    // 两张红桃级牌牌点相同，按「单独打出视为普通级牌」组成对级牌（5.3.2）
    if (all.all { it.isWild(level) }) {
        return CardPattern(PatternType.PAIR, cardStrength(all.first(), level), 2, all)
    }
    if (w > 0) {
        // 1 张百搭 + 1 张普通 → 对子，主点数为普通牌点数。
        // 5.7「但不能当大小王」：百搭 + 1 张王不得拼成「对大王 / 对小王」。
        if (nonWild.size == 1) {
            if (nonWild.first().isJoker) return null
            return CardPattern(PatternType.PAIR, cardStrength(nonWild.first(), level), 2, all)
        }
        return null
    }
    // 两张同自然点数
    if (nonWild.size == 2 && nonWild[0].rank == nonWild[1].rank) {
        return CardPattern(PatternType.PAIR, cardStrength(nonWild.first(), level), 2, all)
    }
    return null
}

private fun triple(nonWild: List<Card>, w: Int, level: Int): CardPattern? {
    if (nonWild.size + w != 3) return null
    if (nonWild.any { it.isJoker }) return null          // 5.7：百搭不能补成「三张王」
    val rankCounts = nonWild.groupingBy { it.rank }.eachCount()
    // 需要恰好一个点数（可由百搭补足到 3），且不能有其它点数的牌
    if (rankCounts.size != 1) return null
    val (rank, cnt) = rankCounts.entries.first()
    if (cnt > 3) return null
    if (3 - cnt != w) return null
    return CardPattern(PatternType.TRIPLE, cardStrength(Card(rank, nonWild.first().suit), level), 3,
        nonWild + List(w) { nonWild.first() }) // cards 仅用于展示/回放，这里以非百搭占位
}

private fun tripleWithPair(nonWild: List<Card>, w: Int, level: Int): CardPattern? {
    if (nonWild.size + w != 5) return null
    val jokerRanks = setOf(RANK_BIG_JOKER, RANK_SMALL_JOKER)
    val counts = nonWild.groupingBy { it.rank }.eachCount()
    for ((tRank, tCount) in counts) {
        if (tRank in jokerRanks) continue                // 王不能作三同张部分
        val cT = minOf(3, tCount)
        val remaining = counts.toMutableMap().also { it[tRank] = tCount - cT }
        if (remaining[tRank] == 0) remaining.remove(tRank)
        if (remaining.size > 1) continue                 // 剩余必须是单一对子候选
        val remCards = remaining.values.sum()
        if (2 - remCards < 0) continue
        val wildForPair = 2 - remCards
        // 带牌对子里若有王，必须是两张真王（对大王 / 对小王）；
        // 「1 张王 + 1 张百搭」等价于百搭当王，违反 5.7。
        if (wildForPair > 0 && remaining.keys.any { it in jokerRanks }) continue
        if ((3 - cT) + wildForPair == w) {
            return CardPattern(PatternType.TRIPLE_WITH_PAIR, cardStrength(Card(tRank, nonWild.first().suit), level), 5,
                nonWild + List(w) { nonWild.first() })
        }
    }
    return null
}

// ---------------------- 连续牌型（顺子 / 连对 / 钢板 / 同花顺） ----------------------

/** 连续牌型窗口里「A 作 1 点」的记号；窗口点数范围为 1..14。 */
private const val ACE_AS_ONE = 1

/**
 * 把一张非百搭牌映射到连续牌型窗口内的点数。
 * - 王不能进入连续牌型；
 * - A（14）在窗口含 1 时解释为 1（A-2-3-4-5 / AA-22-33 / 最小同花顺）；
 * - 其余按自然点数，且必须落在窗口内。
 *
 * 因为窗口最多 5 连，不可能同时含 1 和 14，所以 A 的歧义自动消解，
 * Q-K-A-2-3、J-Q-K-A-2 这类跨 A 循环没有任何窗口能容纳 → 自动判非法。
 */
private fun seqRank(card: Card, window: IntRange): Int? {
    if (card.isJoker) return null
    if (card.rank == 14 && ACE_AS_ONE in window) return ACE_AS_ONE
    return card.rank.takeIf { it in window }
}

/**
 * 识别连续牌型。窗口从 1 开始，保证 A-2-3-4-5 是最小顺子、10-J-Q-K-A 是最大顺子。
 *
 * @param nRanks 连续点数组数（顺子/同花顺 5，连对 3，钢板 2）
 * @param perRank 每个点数所需张数（顺子/同花顺 1，连对 2，钢板 3）
 * @param sameSuit 是否要求非百搭牌同花色（同花顺）；百搭可代表该花色
 * @return 顶张点数（窗口最大点数）。A-2-3-4-5 → 5，10-J-Q-K-A → 14，符合 5.3.7 / 5.3.8。
 */
private fun sequence(
    nonWild: List<Card>, w: Int, level: Int,
    nRanks: Int, perRank: Int, type: PatternType, sameSuit: Boolean,
): CardPattern? {
    val total = nonWild.size + w
    if (total != nRanks * perRank) return null
    if (nonWild.any { it.isJoker }) return null
    if (sameSuit) {
        val suit = nonWild.firstOrNull()?.suit ?: return null
        if (nonWild.any { it.suit != suit }) return null
    }
    // 级牌按自然点数插带（第五条(一)），红桃级牌已作为百搭剔除。
    // 从大往小遍历：百搭能补进多个窗口时取「顶张最大」的解释（对出牌方最有利），
    // 例如 3-4-5-6 + 1 张百搭 应解释为 3-7（顶张 7）而不是 2-6（顶张 6）。
    // 这也是棋牌中心规则第五条(二)「百搭摆放于所替代牌点位置」在自动识别下的默认取值。
    for (start in (14 - nRanks + 1) downTo ACE_AS_ONE) {
        val window = start..(start + nRanks - 1)
        val counts = IntArray(nRanks)
        var ok = true
        for (c in nonWild) {
            val v = seqRank(c, window)
            if (v == null) { ok = false; break }
            val i = v - start
            counts[i]++
            if (counts[i] > perRank) { ok = false; break }   // 同点数超量（顺子不可有重复点数）
        }
        if (!ok) continue
        val missing = (0 until nRanks).sumOf { perRank - counts[it] }
        if (missing != w) continue                            // 百搭必须恰好补齐缺口
        val placeholder = nonWild.firstOrNull() ?: Card(2, Suit.SPADES)
        return CardPattern(type, start + nRanks - 1, total, nonWild + List(w) { placeholder })
    }
    return null
}

private fun straight(nonWild: List<Card>, w: Int, level: Int): CardPattern? =
    sequence(nonWild, w, level, 5, 1, PatternType.STRAIGHT, false)          // 4.3.7

private fun doubleStraight(nonWild: List<Card>, w: Int, level: Int): CardPattern? =
    sequence(nonWild, w, level, 3, 2, PatternType.DOUBLE_STRAIGHT, false)    // 4.3.3

private fun tripleStraight(nonWild: List<Card>, w: Int, level: Int): CardPattern? =
    sequence(nonWild, w, level, 2, 3, PatternType.TRIPLE_STRAIGHT, false)    // 4.3.5

private fun flushStraight(nonWild: List<Card>, w: Int, level: Int): CardPattern? =
    sequence(nonWild, w, level, 5, 1, PatternType.FLUSH_STRAIGHT, true)      // 4.3.8

private fun bomb(nonWild: List<Card>, w: Int, level: Int): CardPattern? {
    val total = nonWild.size + w
    // 4.3.9：四张及以上同点牌；两副牌每点最多 8 张 + 2 张百搭 = 最多 10 张（5.2）
    if (total < 4) return null
    if (nonWild.isEmpty()) return null
    if (nonWild.any { it.isJoker }) return null                    // 王不入普通炸弹
    val rank = nonWild.first().rank
    if (nonWild.any { it.rank != rank }) return null              // 必须同点
    return CardPattern(PatternType.BOMB, cardStrength(Card(rank, nonWild.first().suit), level), total,
        nonWild + List(w) { nonWild.first() })
}

private fun fourKings(cards: List<Card>): CardPattern? {
    if (cards.size != 4) return null
    if (cards.count { it.isBigJoker } == 2 && cards.count { it.isSmallJoker } == 2) {
        return CardPattern(PatternType.FOUR_KINGS, Int.MAX_VALUE, 4, cards)
    }
    return null
}
