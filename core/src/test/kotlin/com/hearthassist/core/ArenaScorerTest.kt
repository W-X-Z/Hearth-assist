package com.hearthassist.core

import com.hearthassist.core.arena.ArenaScorer
import com.hearthassist.core.arena.ArenaStatsParser
import com.hearthassist.core.arena.Tier
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArenaScorerTest {
    private fun cardJson(id: String, drawn: Int, wins: Int) =
        """{"cardId":"$id","context":"global","stats":{"inStartingDeck":${drawn * 2},"wins":$wins,"decksWithCard":$drawn,
            "decksWithCardThenWin":$wins,"drawn":$drawn,"drawnThenWin":$wins,"played":$drawn,"playedThenWin":$wins},
            "matchups":[{"opponentClass":"mage","stats":{"drawn":1}}]}"""

    private val cards = """{"lastUpdated":"2026-10-01T00:00:00.000Z","context":"global","stats":[
        ${cardJson("GOOD", 10000, 6000)}, ${cardJson("AVG", 10000, 5000)}, ${cardJson("BAD", 10000, 4000)},
        ${cardJson("RARE", 5, 5)}]}"""

    private val draft = """{"lastUpdateDate":"2026-10-01","context":"global","dataPoints":100,"stats":[
        {"cardId":"GOOD","statsByWins":{"0":{"offered":100,"picked":90},"6":{"offered":10,"picked":10}},"statsByWinsGlobal":{}}]}"""

    @Test
    fun scoresAndTiers() {
        val scorer = ArenaScorer("global", ArenaStatsParser.parseCardStats(cards), ArenaStatsParser.parseDraftStats(draft))
        val good = scorer.score("GOOD")
        val avg = scorer.score("AVG")
        val bad = scorer.score("BAD")
        assertTrue(good.score!! > avg.score!! && avg.score!! > bad.score!!)
        assertEquals(Tier.S, good.tier)
        assertEquals(Tier.D, bad.tier)
        assertEquals(0.9, good.pickRate!!, 1e-9)
        assertEquals(1.0, good.pickRateHighWins!!, 1e-9)
        assertEquals(0.6, good.drawnWinrate!!, 1e-9)
        // Few games: pulled towards the average instead of 100%.
        val rare = scorer.score("RARE")
        assertTrue(rare.score!! < 55.0)
        assertNull(scorer.score("MISSING").score)
        assertEquals(listOf("GOOD", "RARE", "AVG", "BAD"), scorer.ranking().map { it.cardId })
    }

    @Test
    fun fallsBackToGlobal() {
        val global = ArenaScorer("global", ArenaStatsParser.parseCardStats(cards), null)
        val mageCards = """{"context":"mage","stats":[${cardJson("AVG", 10, 10)}]}"""
        val mage = ArenaScorer("mage", ArenaStatsParser.parseCardStats(mageCards), null, fallback = global)
        assertEquals("global", mage.score("AVG").context)
        assertEquals("global", mage.score("GOOD").context)
        assertTrue(mage.hasCard("BAD"))
    }

    @Test
    fun parsesGzip() {
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(cards.toByteArray()) } }.toByteArray()
        val parsed = ArenaStatsParser.parseCardStats(bytes.inputStream())
        assertEquals(4, parsed.stats.size)
        assertNotNull(parsed.lastUpdated)
    }
}
