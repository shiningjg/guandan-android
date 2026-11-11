package com.example.guandan.domain

/**
 * 掼蛋单局引擎（不含跨局升级/进贡——由上层 Match 管理）。
 *
 * 规则要点：
 * - 4 人固定对家：0&2 一队、1&3 一队；0 号为人类玩家。
 * - 领出者可出任意合法牌；跟牌须压过当前最大牌，或选择过。
 * - 连续三家过，则由最后出牌者重新领出。
 * - 当 3 人走完（仅剩 1 人），本局结束；剩余者为末游。
 */
enum class Phase { PLAYING, HAND_OVER }

data class Play(val player: Int, val pattern: CardPattern, val cards: List<Card>)

/** 单局结果。 */
data class HandResult(
    val finishOrder: List<Int>,   // 头游,二游,三游,末游
    val winnerTeam: Int,           // 0 或 1（含头游的队伍）
    val levelUp: Int,              // 胜队升级数（双上3/单上2/单下1）
    val tributeFrom: Int,          // 单贡时的进贡者（末游）；双贡时仍为末游
    val tributeTo: Int,            // 单贡时的收贡者（头游）
    val doubleDown: Boolean,       // 三游与末游同队（4.9「双下」）→ 需双贡
)

fun teamOf(player: Int): Int = if (player == 0 || player == 2) 0 else 1

class GameEngine(
    val level: Int,
    hands: List<List<Card>>,
    val leader: Int,
) {
    val hands: MutableList<MutableList<Card>> = hands.map { it.toMutableList() }.toMutableList()
    var current = leader
        private set
    var lastPlay: Play? = null
        private set
    var passStreak = 0
        private set
    val finishOrder = mutableListOf<Int>()
    val log = mutableListOf<String>()
    /** 本局累计已打出的实体牌（供记牌器统计剩余）。换局时新引擎实例自然清空。 */
    val playedCards: MutableList<Card> = mutableListOf()
    var phase = Phase.PLAYING
        private set

    private var lastPlayOwner = leader

    fun handOf(player: Int): List<Card> = hands[player]
    fun isMyTurn(player: Int): Boolean = phase == Phase.PLAYING && current == player
    fun canPass(player: Int): Boolean = isMyTurn(player) && lastPlay != null

    fun legalPlaysFor(player: Int): List<List<Card>> {
        if (!isMyTurn(player)) return emptyList()
        return MoveGenerator.legalPlays(hands[player], lastPlay?.pattern, level)
    }

    /** 出牌。返回是否成功。 */
    fun play(player: Int, cards: List<Card>): Boolean {
        if (!isMyTurn(player)) return false
        if (cards.isEmpty()) return false
        val hand = hands[player]
        if (!cards.all { it in hand }) return false
        val pattern = recognize(cards, level) ?: return false
        if (lastPlay != null && !beats(lastPlay!!.pattern, pattern)) return false

        // 两副牌里「同点数同花色」的牌有两张（不同实例但 equals 相等）。
        // 绝不能用 hand.removeAll(cards)：它按 equals 删除，出 1 张会把手上另一张同牌一起删掉。
        // 先按 (点数,花色) 做多重集校验，再逐张移除「第一个匹配实例」。
        val need = cards.groupingBy { it.rank to it.suit }.eachCount()
        val have = hand.groupingBy { it.rank to it.suit }.eachCount()
        if (need.any { (k, n) -> n > (have[k] ?: 0) }) return false
        cards.forEach { c -> hand.remove(c) }
        lastPlay = Play(player, pattern, cards)
        lastPlayOwner = player
        passStreak = 0
        playedCards.addAll(cards)
        log.add("P$player 出 ${pattern.type}·${cards.size}张")
        if (hand.isEmpty()) {
            finishOrder.add(player)
            log.add("P$player 走完（第 ${finishOrder.size} 游）")
        }
        advance()
        checkHandOver()
        return true
    }

    /** 过牌。 */
    fun pass(player: Int): Boolean {
        if (!canPass(player)) return false
        passStreak++
        log.add("P$player 过")
        advance()
        return true
    }

    /**
     * 推进到下一位行动者。
     *
     * 标准 4.8「借风」：最后出牌者出完最后一手且其余各家均放弃（三家过）时，
     * 由**其己方同伴（对家）**承接下一圈领出；若对家也已走完，则顺延到下一位在场者。
     * 若最后出牌者仍在场，则由其本人重新领出。
     */
    private fun advance() {
        if (phase != Phase.PLAYING) return
        // 下一位仍在场的玩家（顺时针，跳过已走完者）
        var next = (current + 1) % 4
        var guard = 0
        while (next in finishOrder && guard < 4) { next = (next + 1) % 4; guard++ }

        val owner = lastPlayOwner
        val ownerFinished = owner in finishOrder
        // 「其余各家均放弃」= 除最后出牌者外仍在场的玩家都过了一次
        val othersActive = (0..3).count { it != owner && it !in finishOrder }
        val roundReset = lastPlay != null &&
            (next == owner || (ownerFinished && passStreak >= maxOf(1, othersActive)))

        if (roundReset) {
            lastPlay = null
            passStreak = 0
            val newLeader = if (ownerFinished) {
                val partner = (owner + 2) % 4
                if (partner !in finishOrder) partner else firstActiveFrom(partner)
            } else owner
            current = newLeader
            log.add(if (ownerFinished) "P$owner 走完，P$newLeader 接风领出" else "P$newLeader 重新领出")
        } else {
            current = next
        }
    }

    /** 从 start 起顺时针找第一位仍在场的玩家。 */
    private fun firstActiveFrom(start: Int): Int {
        for (i in 0..3) {
            val p = (start + i) % 4
            if (p !in finishOrder) return p
        }
        return start
    }

    private fun checkHandOver() {
        if (phase == Phase.HAND_OVER) return
        if (finishOrder.size >= 3) {
            phase = Phase.HAND_OVER
            val remaining = (0..3).firstOrNull { it !in finishOrder }
            if (remaining != null) {
                finishOrder.add(remaining)   // 末游
                log.add("本局结束，末游 P$remaining")
            }
        }
    }

    /** 结算单局：升级数与进贡关系。仅在 HAND_OVER 后调用。 */
    fun result(): HandResult {
        require(phase == Phase.HAND_OVER) { "对局未结束" }
        val order = finishOrder.toList()           // 4 人完整顺序
        val winnerTeam = teamOf(order[0])          // 头游所在队
        val partnerOfHead = (order[0] + 2) % 4
        val partnerPos = order.indexOf(partnerOfHead)  // 队友在名次中的位置（0头..3末）
        // 4.9「双下」：三游与末游同队（等价于头游与二游同队）→ 双贡
        val doubleDown = teamOf(order[2]) == teamOf(order[3])
        val levelUp = when (partnerPos) {
            1 -> 3   // 双上
            2 -> 2   // 单上
            3 -> 1   // 单下（队友末游）
            else -> 0
        }
        return HandResult(
            finishOrder = order,
            winnerTeam = winnerTeam,
            levelUp = levelUp,
            tributeFrom = order[3],    // 末游进贡
            tributeTo = order[0],      // 头游收贡
            doubleDown = doubleDown,
        )
    }
}
