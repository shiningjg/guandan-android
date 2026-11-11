package com.example.guandan.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class AiSelfPlayTest {

    private fun playOneGame(level: Int, difficulty: Difficulty, rng: Random): HandResult {
        val hands = Deck.deal(rng)
        val engine = GameEngine(level, hands, leader = 0)
        var guard = 0
        while (engine.phase == Phase.PLAYING && guard < 2000) {
            guard++
            val player = engine.current
            val hand = engine.handOf(player)
            val target = engine.lastPlay?.pattern
            val owner = engine.lastPlay?.player
            val move = HeuristicAi.decideMove(
                player, hand, target, owner, level, difficulty,
                engine.hands.map { it.size }, rng
            )
            val ok = if (move.isEmpty()) engine.pass(player) else engine.play(player, move)
            if (!ok) {
                println("BADMOVE player=$player level=$level moveSize=${move.size} toSet=${move.toSet().size} hand=${hand.size} move=$move")
            }
            assertTrue("AI 返回非法操作（player=$player move=$move toSet=${move.toSet().size}）", ok)
        }
        assertEquals("对局未在合理步数内结束 (guard=$guard)", Phase.HAND_OVER, engine.phase)
        if (engine.finishOrder.toSet().size != 4) {
            println("DEBUG finishOrder=${engine.finishOrder} log=${engine.log.takeLast(10)}")
        }
        return engine.result()
    }

    @Test
    fun hard_game_completes() {
        val res = playOneGame(2, Difficulty.HARD, Random(42))
        assertEquals(4, res.finishOrder.toSet().size)   // 四人名次齐全
        assertTrue(res.levelUp in 1..3)
        assertEquals(res.finishOrder.last(), res.tributeFrom)
        assertEquals(res.finishOrder.first(), res.tributeTo)
    }

    @Test
    fun many_games_no_crash() {
        var seed = 1L
        repeat(20) {
            val r = playOneGame(2 + (it % 11), Difficulty.HARD, Random(seed))
            assertEquals(4, r.finishOrder.toSet().size)
            seed += 7
        }
    }

    @Test
    fun all_difficulties_playable() {
        for (d in Difficulty.values()) {
            val res = playOneGame(5, d, Random(99))
            assertEquals(4, res.finishOrder.toSet().size)
        }
    }
}
