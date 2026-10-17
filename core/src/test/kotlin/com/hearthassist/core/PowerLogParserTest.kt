package com.hearthassist.core

import com.hearthassist.core.game.DeckTracker
import com.hearthassist.core.game.Decklist
import com.hearthassist.core.log.PowerLogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerLogParserTest {
    private fun parseSample(upTo: String? = null): PowerLogParser {
        val parser = PowerLogParser()
        val lines = javaClass.getResource("/power_sample.log")!!.readText().lines()
        for (line in lines) {
            if (upTo != null && line.contains(upTo)) break
            parser.feed(line)
        }
        return parser
    }

    @Test
    fun detectsPlayersAndOriginalDeck() {
        val game = parseSample().game
        assertEquals(1, game.gameNumber)
        assertEquals(1, game.localPlayerId)
        assertEquals("GT_ARENA", game.gameType)
        assertEquals(3, game.turn)
        assertTrue(game.entity(4).isOriginalDeckCard)
        assertFalse(game.entity(64).isOriginalDeckCard)
        assertTrue(game.entity(68).createdDuringGame)
        assertTrue(game.entity(4).playedFromHand)
        assertEquals("Opponent#5678", game.playerNames[2])
    }

    @Test
    fun tracksDeckAgainstDecklist() {
        val parser = parseSample(upTo = "PLAYSTATE")
        val tracker = DeckTracker(TestCards.db, "koKR")
        val decklist = Decklist("Test", mapOf("CS2_029" to 2, "EX1_277" to 1, "CS2_032" to 1))
        val snap = tracker.snapshot(parser.game, decklist)
        assertTrue(snap.inProgress)
        assertEquals(2, snap.playerDeckCount)
        assertEquals(1, snap.playerHandCount)
        val remaining = snap.playerRemaining.associate { it.cardId to it.count }
        assertEquals(1, remaining["CS2_029"])
        assertEquals(0, remaining["EX1_277"])
        assertEquals(1, remaining["CS2_032"])
        assertEquals("화염구", snap.playerRemaining.first { it.cardId == "CS2_029" }.name)
        assertEquals(listOf("EX1_277", "CS2_029"), snap.playerDrawn.map { it.cardId })
        assertEquals(listOf("CS2_106"), snap.opponentPlayed.map { it.cardId })
        assertEquals(listOf("GAME_005"), snap.opponentCreated.map { it.cardId })
        assertEquals(1, snap.opponentDeckCount)
        assertEquals(0, snap.opponentHandCount)
    }

    @Test
    fun detectsGameEnd() {
        var completed = 0
        val parser = PowerLogParser()
        parser.listener = object : PowerLogParser.Listener {
            override fun onGameComplete(game: com.hearthassist.core.game.GameState) { completed++ }
        }
        javaClass.getResource("/power_sample.log")!!.readText().lines().forEach(parser::feed)
        assertEquals(1, completed)
        assertTrue(parser.game.complete)
        assertEquals("WON", parser.game.localResult)
        assertFalse(parser.game.inProgress)
    }

    @Test
    fun newGameResetsState() {
        val parser = parseSample()
        parser.feed("D 22:00:00.0000000 GameState.DebugPrintPower() - CREATE_GAME")
        assertEquals(2, parser.game.gameNumber)
        assertEquals(0, parser.game.localPlayerId)
        assertTrue(parser.game.inProgress)
    }
}
