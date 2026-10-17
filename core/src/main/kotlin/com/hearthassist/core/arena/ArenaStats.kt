package com.hearthassist.core.arena

import com.hearthassist.core.cards.CardDb
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream

/**
 * Arena card statistics published by Firestone (https://www.firestoneapp.com):
 *  - `https://static.zerotoheroes.com/api/arena/stats/cards/{mode}/{period}/{context}.gz.json`
 *  - `https://static.zerotoheroes.com/api/arena/stats/draft/{mode}/{period}/{context}.gz.json`
 * where mode is `arena` / `arena-underground`, period `last-patch` / `past-7` / ... and
 * context `global` or a lowercase class (`mage`, `deathknight`...).
 */
object ArenaStatsUrls {
    const val BASE = "https://static.zerotoheroes.com/api/arena/stats"

    fun cardStats(mode: String, period: String, context: String) = "$BASE/cards/$mode/$period/$context.gz.json"
    fun draftStats(mode: String, period: String, context: String) = "$BASE/draft/$mode/$period/$context.gz.json"

    val MODES = listOf("arena", "arena-underground")
    val PERIODS = listOf("last-patch", "past-7", "past-3", "past-20", "current-season")
}

@Serializable
data class ArenaCardStatsFile(
    val lastUpdated: String? = null,
    val context: String? = null,
    val stats: List<ArenaCardStat> = emptyList(),
)

@Serializable
data class ArenaCardStat(
    val cardId: String,
    val context: String? = null,
    val stats: ArenaCardData = ArenaCardData(),
)

@Serializable
data class ArenaCardData(
    val inStartingDeck: Long = 0,
    val wins: Long = 0,
    val decksWithCard: Long = 0,
    val decksWithCardThenWin: Long = 0,
    val drawnBeforeMulligan: Long = 0,
    val keptInMulligan: Long = 0,
    val inHandAfterMulligan: Long = 0,
    val inHandAfterMulliganThenWin: Long = 0,
    val drawn: Long = 0,
    val drawnThenWin: Long = 0,
    val played: Long = 0,
    val playedThenWin: Long = 0,
    val playedOnCurve: Long = 0,
    val playedOnCurveThenWin: Long = 0,
)

@Serializable
data class DraftStatsFile(
    val lastUpdateDate: String? = null,
    val context: String? = null,
    val dataPoints: Long = 0,
    val stats: List<DraftCardStat> = emptyList(),
)

@Serializable
data class DraftCardStat(
    val cardId: String,
    val statsByWins: Map<String, PickStat> = emptyMap(),
    val statsByWinsGlobal: Map<String, PickStat> = emptyMap(),
)

@Serializable
data class PickStat(val offered: Long = 0, val picked: Long = 0)

object ArenaStatsParser {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun parseCardStats(input: InputStream): ArenaCardStatsFile =
        json.decodeFromStream(ArenaCardStatsFile.serializer(), CardDb.maybeGunzip(input))

    @OptIn(ExperimentalSerializationApi::class)
    fun parseDraftStats(input: InputStream): DraftStatsFile =
        json.decodeFromStream(DraftStatsFile.serializer(), CardDb.maybeGunzip(input))

    fun parseCardStats(text: String) = parseCardStats(text.byteInputStream())
    fun parseDraftStats(text: String) = parseDraftStats(text.byteInputStream())
}

enum class Tier(val label: String) { S("S"), A("A"), B("B"), C("C"), D("D"), UNKNOWN("?") }

data class CardScore(
    val cardId: String,
    /** Main score: smoothed win rate (%) of games where the card was drawn. */
    val score: Double?,
    val tier: Tier,
    val drawnWinrate: Double?,
    val deckWinrate: Double?,
    val playedWinrate: Double?,
    val drawnGames: Long,
    /** Pick rate when offered (all players). */
    val pickRate: Double?,
    /** Pick rate when offered, among runs that reached 6+ wins. */
    val pickRateHighWins: Double?,
    /** Which stats context produced the numbers ("global" or a class). */
    val context: String,
)

/**
 * Turns raw Firestone numbers into a per-card score. The score is the drawn win rate shrunk
 * towards the average of all cards (so a card with few games doesn't look amazing by luck).
 */
class ArenaScorer(
    private val contextName: String,
    cardStats: ArenaCardStatsFile?,
    draftStats: DraftStatsFile?,
    private val fallback: ArenaScorer? = null,
    private val priorGames: Double = 100.0,
) {
    private val byCard: Map<String, ArenaCardData> = cardStats?.stats.orEmpty().associate { it.cardId to it.stats }
    private val picks: Map<String, DraftCardStat> = draftStats?.stats.orEmpty().associateBy { it.cardId }

    val averageDrawnWinrate: Double = run {
        val drawn = byCard.values.sumOf { it.drawn }
        val wins = byCard.values.sumOf { it.drawnThenWin }
        if (drawn > 0) wins.toDouble() / drawn else 0.5
    }

    val cardIds: Set<String> get() = byCard.keys + (fallback?.cardIds ?: emptySet())

    val isEmpty: Boolean get() = byCard.isEmpty() && picks.isEmpty() && (fallback?.isEmpty ?: true)

    fun hasCard(cardId: String): Boolean = byCard.containsKey(cardId) || fallback?.hasCard(cardId) == true

    fun score(cardId: String): CardScore {
        val data = byCard[cardId]
        if ((data == null || data.drawn < MIN_DRAWN) && fallback != null && fallback.hasCard(cardId)) {
            val fb = fallback.score(cardId)
            // Keep this context's pick rates when available.
            val pick = picks[cardId]
            return if (pick != null) fb.copy(pickRate = pickRate(pick, "0"), pickRateHighWins = pickRate(pick, "6")) else fb
        }
        val pick = picks[cardId]
        if (data == null || data.drawn == 0L) {
            return CardScore(
                cardId, null, Tier.UNKNOWN, null, null, null, 0,
                pick?.let { pickRate(it, "0") }, pick?.let { pickRate(it, "6") }, contextName,
            )
        }
        val smoothed = (data.drawnThenWin + priorGames * averageDrawnWinrate) / (data.drawn + priorGames)
        return CardScore(
            cardId = cardId,
            score = smoothed * 100,
            tier = tierFor(smoothed - averageDrawnWinrate),
            drawnWinrate = ratio(data.drawnThenWin, data.drawn),
            deckWinrate = ratio(data.decksWithCardThenWin, data.decksWithCard),
            playedWinrate = ratio(data.playedThenWin, data.played),
            drawnGames = data.drawn,
            pickRate = pick?.let { pickRate(it, "0") },
            pickRateHighWins = pick?.let { pickRate(it, "6") },
            context = contextName,
        )
    }

    /** All known cards, best first. */
    fun ranking(): List<CardScore> = cardIds.map { score(it) }
        .sortedWith(compareByDescending<CardScore> { it.score ?: -1.0 }.thenBy { it.cardId })

    private fun pickRate(stat: DraftCardStat, minWins: String): Double? {
        val s = stat.statsByWins[minWins] ?: stat.statsByWinsGlobal[minWins] ?: return null
        return ratio(s.picked, s.offered)
    }

    private fun ratio(a: Long, b: Long): Double? = if (b > 0) a.toDouble() / b else null

    companion object {
        const val MIN_DRAWN = 30L

        /** [delta] is the smoothed drawn win rate minus the average, as a fraction. */
        fun tierFor(delta: Double): Tier = when {
            delta >= 0.05 -> Tier.S
            delta >= 0.025 -> Tier.A
            delta >= -0.005 -> Tier.B
            delta >= -0.035 -> Tier.C
            else -> Tier.D
        }
    }
}
