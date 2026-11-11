package com.example.guandan.domain

import java.util.Random

enum class Difficulty { EASY, NORMAL, HARD }

/**
 * 掼蛋启发式 AI。
 *
 * 设计要点（沿用斗地主已验证的"记牌+启发式+难度分级"方法论，并加入固定对家配合）：
 * - 领出：优先打出能快速减少手牌的牌型（顺子/连对/钢板），保留炸弹/同花顺到最后；
 *         先出小牌试探，避免过早暴露大牌。
 * - 跟牌：识别最后出牌者是不是队友——队友领出/将赢时"放手"让其控场；对手将赢时必顶。
 * - 送对家走（v1.0.8）：领出时若对家报单（只剩 1 张）而自己没把握一轮走完，
 *         考虑领出最小单张送其走完争头游——软性、概率性，团队胜利优先。
 * - 难度：HARD 零低级失误（确定性最优）；NORMAL/EASY 在"是否该顶/该放手"上加入概率性犹豫。
 * - 不拆固定牌组、不浪费百搭：与提示轮循共用 [HandShape] 评分，避免把炸弹拆成单张、
 *   或用红桃级牌去凑一个 3 点小对子。
 */
object HeuristicAi {

    /** 返回要出的牌（空列表 = 过）。 */
    fun decideMove(
        player: Int,
        hand: List<Card>,
        target: CardPattern?,
        lastPlayOwner: Int?,
        level: Int,
        difficulty: Difficulty,
        handCounts: List<Int>,     // 各玩家剩余张数（长度 4）
        rng: Random,
    ): List<Card> {
        val legal = MoveGenerator.legalPlays(hand, target, level)
        if (legal.isEmpty()) return emptyList()   // 无合法牌 → 过

        if (target == null) {
            // 领出：队友报单且自己无把握一轮走完时，先试「送对家走」的软策略
            leadToHelpTeammate(player, hand, legal, level, difficulty, handCounts, rng)
                ?.let { return it }
            return chooseLead(hand, legal, level, difficulty, rng)
        }
        // 跟牌
        return chooseFollow(player, hand, target, lastPlayOwner, level, difficulty, handCounts, legal, rng)
    }

    /**
     * 「送对家走」软策略（v1.0.8）。
     *
     * 场景：对家（(player+2)%4，与己同队）**报单、只剩 1 张**，而自己**没有一手清空**
     * 全副的把握。此时若按常规领出（顺子 / 连对 / 对子 / 大单张），对家只有 1 张单牌
     * 往往接不上或压不过，白白错过一轮；不如领出**最小的干净单张**，让对家用最后一张牌
     * 压过走完、抢下头游——团队名次（头游 + 己方）比个人多留一手大牌更重要。
     *
     * 战术依据：掼蛋通行技巧「随时关注对家牌张数，剩 1 张时按张数投送」；央广网
     * 《掼蛋竞赛规则》解读「手上的牌不好的话，可考虑打辅助牌，让对家走完牌保证不双下」。
     * 用户诉求原话「也不一定必须这么做，只是可以考虑一下争取团队胜利」——故实现为
     * **软策略**，不是硬规则：
     * - 只在领出、且对家恰好剩 1 张时触发；跟牌阶段不动（已有队友放手 / 拦对手逻辑）。
     * - 自己能一手清空（可稳拿头游）→ 不送，自己走更确定。
     * - 概率性：HARD 必考虑（团队最优），NORMAL 70%、EASY 40% 才送，保留难度差异。
     * - 只领**单张**（对家 1 张牌只有出单张才可能接），且取「不拆成型组、不浪费百搭」里
     *   主牌最小的一手，给对家最大压过机会；没有干净单张则返回 null 交回常规领出。
     *
     * @return 要送的那手牌；null 表示不触发。可单测（HARD 下确定性）。
     */
    fun leadToHelpTeammate(
        player: Int,
        hand: List<Card>,
        legal: List<List<Card>>,
        level: Int,
        difficulty: Difficulty,
        handCounts: List<Int>,
        rng: Random,
    ): List<Card>? {
        val mate = (player + 2) % 4
        if (teamOf(mate) != teamOf(player)) return null
        if (handCounts.getOrElse(mate) { 99 } != 1) return null       // 仅对家「报单」
        if (legal.any { it.size == hand.size }) return null             // 自己能一手走完 → 自己争头游

        val consider = when (difficulty) {
            Difficulty.HARD -> true
            Difficulty.NORMAL -> rng.nextFloat() < 0.7f
            Difficulty.EASY -> rng.nextFloat() < 0.4f
        }
        if (!consider) return null

        val singles = legal.filter { recognize(it, level)?.type == PatternType.SINGLE }
            .filter { HandShape.breakPenalty(hand, it, level) == 0 }       // 不拆炸弹/三张/对子
            .filter { !HandShape.wastesWild(it, recognize(it, level)!!, level) } // 不浪费百搭
        if (singles.isEmpty()) return null
        return singles.minWith(
            compareBy<List<Card>>(
                // 先别拆掉一个已成型的顺子（用户强调「不拆固定牌型」）；
                // 在没有孤立单张的极端情况下仍会退回顺子里的牌，但优先选最小的孤立单张。
                { if (inStraightWindow(hand, it.first(), level)) 1 else 0 },
                { cardStrength(it.first(), level) },   // 越小越好：给对家最大的压过机会
                { it.first().suit.ordinal },
            )
        )
    }

    /**
     * 该单张点数是否落在手牌能凑出的某个 5 张连续点数窗口内（拆它会破坏潜在顺子）。
     * A 可作 1 点（A-2-3-4-5），J/Q/K/A 用 rank 11/12/13/14。王与百搭不参与顺子。
     */
    private fun inStraightWindow(hand: List<Card>, card: Card, level: Int): Boolean {
        if (card.isJoker) return false
        val ranks = hand.filter { !it.isJoker && !it.isWild(level) }
            .map { it.rank }.toMutableSet()
        if (14 in ranks) ranks += 1                       // A 作 1 点
        val r = if (card.rank == 14 && 1 in ranks) 1 else card.rank
        for (s in maxOf(1, r - 4)..minOf(r, 10)) {
            if ((s..s + 4).all { it in ranks }) return true
        }
        return false
    }

    // ---------------- 领出 ----------------

    private fun chooseLead(
        hand: List<Card>,
        legal: List<List<Card>>,
        level: Int,
        difficulty: Difficulty,
        rng: Random,
    ): List<Card> {
        val scored = legal.map { cards ->
            val p = recognize(cards, level)!!
            var s = leadScore(p, cards, hand, level)
            // 难度噪声：低难度偶尔打出次优领出
            if (difficulty != Difficulty.HARD) {
                val noise = if (difficulty == Difficulty.EASY) rng.nextFloat() else rng.nextFloat() * 0.5f
                s += noise * 6f
            }
            cards to s
        }
        val best = scored.maxBy { it.second }
        return best.first   // 返回真实牌组合（非 CardPattern 的合成 cards 字段）
    }

    /** 领出评分：越高越优先打出。 */
    private fun leadScore(p: CardPattern, cards: List<Card>, hand: List<Card>, level: Int): Float {
        var s = 0f
        // 多牌型（顺子/连对/钢板）优先：一次甩掉多张
        s += cards.size * 1.5f
        // 大牌早期保留，惩罚按标准层级递增：
        // 4/5 张炸弹 < 同花顺 < 6 张及以上炸弹 < 天王炸
        when (p.type) {
            PatternType.BOMB -> s -= if (p.size >= 6) 18f else 14f
            PatternType.FLUSH_STRAIGHT -> s -= 12f
            PatternType.FOUR_KINGS -> s -= 20f
            else -> Unit
        }
        // 小牌优先领出（主点数越低越该先打）
        s -= p.mainRank * 0.6f
        // 不拆已成型牌组（炸弹 / 三张 / 对子），不把百搭浪费在小对 / 小三张上
        s -= HandShape.breakPenalty(hand, cards, level) * 5f
        if (HandShape.wastesWild(cards, p, level)) s -= 10f
        // 三带二的带牌对子不影响牌型大小（5.3.6），别把大对子白白带出去
        s -= HandShape.kickerStrength(cards, p, level) * 0.9f
        return s
    }

    // ---------------- 跟牌 ----------------

    private fun chooseFollow(
        player: Int,
        hand: List<Card>,
        target: CardPattern,
        lastPlayOwner: Int?,
        level: Int,
        difficulty: Difficulty,
        handCounts: List<Int>,
        legal: List<List<Card>>,
        rng: Random,
    ): List<Card> {
        val teammate = lastPlayOwner?.let { teamOf(it) == teamOf(player) } ?: false
        val oppMinCount = (0..3).filter { teamOf(it) != teamOf(player) }.minOf { handCounts[it] }
        val teammateCount = lastPlayOwner?.let { handCounts[it] } ?: 99

        // 候选压制牌（都不含炸弹类，先找最小代价普通压制）
        val normalBeats = legal.filter { it !in bombsOnly(legal, level) }
        val bombBeats = legal.filter { isBombType(recognize(it, level)!!.type) }

        // 队友正领出/将赢：放手让其控场（除非低难度偶尔误顶）
        if (teammate) {
            val hesitate = difficulty == Difficulty.HARD && false  // HARD 永不误顶队友
            if (!hesitate) {
                if (teammateCount <= 2) return emptyList()        // 队友快走完，坚决放手
                if (difficulty != Difficulty.EASY) return emptyList()
                if (rng.nextFloat() < 0.7f) return emptyList()
            }
        }

        // 对手将赢（剩 <=2 张）：必须尽力压制
        if (oppMinCount <= 2) {
            // 优先用最小代价普通压制；没有则用炸弹
            val pick = cheapest(normalBeats, hand, level) ?: cheapest(bombBeats, hand, level)
            return pick ?: emptyList()
        }

        // 普通情况：用最小代价压制，但避免浪费炸弹
        if (normalBeats.isNotEmpty()) {
            val pick = cheapest(normalBeats, hand, level)
            // 若代价过高（压的是很小的牌却要出很大牌），低难度更可能放手
            if (difficulty == Difficulty.HARD) return pick!!
            val costRatio = pick!!.let { recognize(it, level)!!.mainRank.toFloat() / target.mainRank }
            val passProb = when (difficulty) {
                Difficulty.NORMAL -> if (costRatio > 2f) 0.4f else 0.1f
                Difficulty.EASY -> if (costRatio > 1.5f) 0.6f else 0.3f
                else -> 0f
            }
            if (rng.nextFloat() < passProb) return emptyList()
            return pick
        }

        // 只有炸弹能压：仅在对手手牌不多或终局时使用
        if (bombBeats.isNotEmpty()) {
            val useBomb = when {
                oppMinCount <= 5 -> true
                difficulty == Difficulty.HARD -> oppMinCount <= 8
                difficulty == Difficulty.NORMAL -> rng.nextFloat() < 0.5f
                else -> rng.nextFloat() < 0.25f
            }
            return if (useBomb) cheapest(bombBeats, hand, level)!! else emptyList()
        }
        return emptyList()
    }

    private fun isBombType(t: PatternType) = t == PatternType.BOMB || t == PatternType.FLUSH_STRAIGHT || t == PatternType.FOUR_KINGS

    private fun bombsOnly(legal: List<List<Card>>, level: Int): List<List<Card>> =
        legal.filter { isBombType(recognize(it, level)!!.type) }

    /**
     * 选代价最小的压制牌：先避开「拆固定组」「浪费百搭」的候选，
     * 再按主点数低、张数少优先。hard 保证不会为压一张小牌而拆掉炸弹。
     */
    private fun cheapest(cands: List<List<Card>>, hand: List<Card>, level: Int): List<Card>? {
        if (cands.isEmpty()) return null
        return cands.minWith(
            compareBy<List<Card>>(
                { HandShape.breakPenalty(hand, it, level) },
                { if (HandShape.wastesWild(it, recognize(it, level)!!, level)) 1 else 0 },
                { recognize(it, level)!!.let { p -> p.mainRank * 10 - p.size } },
                { recognize(it, level)!!.let { p -> HandShape.kickerStrength(it, p, level) } },
            )
        )
    }
}
