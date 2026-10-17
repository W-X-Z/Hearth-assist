package com.hearthassist.app.data

import android.content.Context
import com.hearthassist.core.arena.ArenaCardStatsFile
import com.hearthassist.core.arena.ArenaScorer
import com.hearthassist.core.arena.ArenaStatsParser
import com.hearthassist.core.arena.ArenaStatsUrls
import com.hearthassist.core.arena.DraftStatsFile
import com.hearthassist.core.cards.HeroClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class StatsStatus(
    val loading: Boolean = false,
    val mode: String = "",
    val period: String = "",
    val cards: Int = 0,
    val lastUpdated: String? = null,
    val fetchedAt: Long = 0,
    val error: String? = null,
)

/**
 * Downloads and caches Firestone arena statistics, and builds [ArenaScorer]s per class
 * (class-specific numbers, falling back to the global ones).
 */
class ArenaStatsRepository(private val context: Context) {
    private val mutex = Mutex()
    private val scorers = HashMap<String, ArenaScorer>()
    private val _status = MutableStateFlow(StatsStatus())
    val status: StateFlow<StatsStatus> = _status.asStateFlow()

    /** Bumps whenever cached scorers change, so the UI can re-query. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private var mode = "arena"
    private var period = "last-patch"

    private fun dir(): File = File(context.filesDir, "stats/$mode/$period").apply { mkdirs() }
    private fun cardsFile(ctx: String) = File(dir(), "cards_$ctx.json")
    private fun draftFile(ctx: String) = File(dir(), "draft_$ctx.json")

    suspend fun configure(mode: String, period: String) = mutex.withLock {
        if (mode == this.mode && period == this.period) return@withLock
        this.mode = mode
        this.period = period
        scorers.clear()
        _status.value = StatsStatus(mode = mode, period = period)
        _version.value++
    }

    /** Returns a scorer for [heroClass] (or global), loading from cache or network as needed. */
    suspend fun scorer(heroClass: HeroClass?, allowNetwork: Boolean = true): ArenaScorer? = mutex.withLock {
        val global = loadScorer(GLOBAL, null, allowNetwork) ?: return@withLock null
        if (heroClass == null) global else loadScorer(heroClass.statsKey, global, allowNetwork) ?: global
    }

    /** Returns an already loaded scorer without any I/O. */
    fun cached(heroClass: HeroClass?): ArenaScorer? {
        val global = scorers[GLOBAL] ?: return null
        return heroClass?.let { scorers[it.statsKey] } ?: global
    }

    suspend fun refresh(): Result<Int> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val keys = scorers.keys.toList().ifEmpty { listOf(GLOBAL) }
            scorers.clear()
            runCatching {
                for (key in listOf(GLOBAL) + keys.filter { it != GLOBAL }) {
                    download(key)
                }
                loadScorer(GLOBAL, null, allowNetwork = false)?.cardIds?.size ?: 0
            }.onFailure { e ->
                _status.value = _status.value.copy(loading = false, error = e.message ?: e.toString())
            }
        }
    }

    private suspend fun loadScorer(context: String, fallback: ArenaScorer?, allowNetwork: Boolean): ArenaScorer? =
        withContext(Dispatchers.IO) {
            scorers[context]?.let { return@withContext it }
            val cards = cardsFile(context)
            val stale = !cards.exists() || System.currentTimeMillis() - cards.lastModified() > MAX_AGE_MS
            if (stale && allowNetwork) {
                runCatching { download(context) }.onFailure { e ->
                    if (context == GLOBAL) {
                        _status.value = _status.value.copy(loading = false, error = e.message ?: e.toString())
                    }
                }
            }
            if (!cards.exists()) return@withContext null
            val cardStats: ArenaCardStatsFile? = runCatching { cards.inputStream().use { ArenaStatsParser.parseCardStats(it) } }.getOrNull()
            val draft = draftFile(context)
            val draftStats: DraftStatsFile? =
                if (draft.exists()) runCatching { draft.inputStream().use { ArenaStatsParser.parseDraftStats(it) } }.getOrNull() else null
            if (cardStats == null) {
                cards.delete()
                return@withContext null
            }
            val scorer = ArenaScorer(context, cardStats, draftStats, fallback)
            scorers[context] = scorer
            if (context == GLOBAL) {
                _status.value = StatsStatus(
                    loading = false, mode = mode, period = period, cards = cardStats.stats.size,
                    lastUpdated = cardStats.lastUpdated, fetchedAt = cards.lastModified(), error = null,
                )
            }
            _version.value++
            scorer
        }

    private fun download(context: String) {
        if (context == GLOBAL) _status.value = _status.value.copy(loading = true, mode = mode, period = period, error = null)
        val cards = Http.get(ArenaStatsUrls.cardStats(mode, period, context), timeoutMs = 60_000)
        // Validate before replacing the cache.
        ArenaStatsParser.parseCardStats(cards.inputStream())
        cardsFile(context).writeBytes(cards)
        runCatching {
            val draft = Http.get(ArenaStatsUrls.draftStats(mode, period, context), timeoutMs = 60_000)
            ArenaStatsParser.parseDraftStats(draft.inputStream())
            draftFile(context).writeBytes(draft)
        }
    }

    companion object {
        private const val GLOBAL = "global"
        private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L
    }
}
