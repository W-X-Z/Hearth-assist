package com.hearthassist.app.engine

import com.hearthassist.app.settings.SettingsState
import com.hearthassist.core.cards.CardDb
import com.hearthassist.core.cards.HeroClass
import com.hearthassist.core.deck.Deckstring
import com.hearthassist.core.game.DeckTracker
import com.hearthassist.core.game.Decklist
import com.hearthassist.core.game.GameState
import com.hearthassist.core.game.TrackedCard
import com.hearthassist.core.game.TrackerSnapshot
import com.hearthassist.core.log.ArenaLogParser
import com.hearthassist.core.log.DecksLogParser
import com.hearthassist.core.log.LoadingScreenParser
import com.hearthassist.core.log.PowerLogParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogKind(val fileName: String) {
    POWER("Power.log"),
    LOADING_SCREEN("LoadingScreen.log"),
    ARENA("Arena.log"),
    DECKS("Decks.log"),
}

data class DraftInfo(
    val heroCardId: String? = null,
    val heroClass: HeroClass? = null,
    val picks: List<TrackedCard> = emptyList(),
    val pickCount: Int = 0,
    val mode: String? = null,
    /** Cards per mana cost 0..7+ */
    val curve: List<Int> = List(8) { 0 },
)

sealed interface GameEvent {
    data object Started : GameEvent
    data class Ended(val result: String?) : GameEvent
    data class SceneChanged(val scene: String) : GameEvent
}

/**
 * Owns the log parsers and turns their state into UI snapshots. Thread-safe: log lines come
 * from the watcher thread, re-renders from settings/card db changes.
 */
class TrackerEngine(
    private val cards: StateFlow<CardDb>,
    private val settings: StateFlow<SettingsState>,
) {
    private val lock = Any()

    private var power = newPowerParser()
    private var arena = newArenaParser()
    private var loading = LoadingScreenParser()
    private var decks = DecksLogParser { cards.value }

    private val _tracker = MutableStateFlow(TrackerSnapshot())
    val tracker: StateFlow<TrackerSnapshot> = _tracker.asStateFlow()
    private val _draft = MutableStateFlow(DraftInfo())
    val draft: StateFlow<DraftInfo> = _draft.asStateFlow()
    private val _scene = MutableStateFlow<String?>(null)
    val scene: StateFlow<String?> = _scene.asStateFlow()
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private var live = false
    private val pendingEvents = ArrayList<GameEvent>()
    private var lastKey: List<Any?> = emptyList()
    private var manualDeckCache: Pair<String, Decklist?>? = null

    private fun newPowerParser() = PowerLogParser().also { p ->
        p.listener = object : PowerLogParser.Listener {
            override fun onGameCreated(game: GameState) {
                if (live) pendingEvents.add(GameEvent.Started)
            }

            override fun onGameComplete(game: GameState) {
                if (live) pendingEvents.add(GameEvent.Ended(game.localResult))
            }
        }
    }

    private fun newArenaParser() = ArenaLogParser(isHeroCard = { id ->
        val card = cards.value[id]
        if (card != null) card.type == "HERO" && card.set == "HERO_SKINS" else id.startsWith("HERO_")
    })

    /** [isLive] is false while catching up on old log content (no events then). */
    fun feed(kind: LogKind, lines: List<String>, isLive: Boolean) {
        synchronized(lock) {
            live = isLive
            when (kind) {
                LogKind.POWER -> for (line in lines) power.feed(line)
                LogKind.ARENA -> for (line in lines) arena.feed(line)
                LogKind.DECKS -> for (line in lines) decks.feed(line)
                LogKind.LOADING_SCREEN -> {
                    val before = loading.currentMode
                    for (line in lines) loading.feed(line)
                    val after = loading.currentMode
                    if (isLive && after != null && after != before) {
                        pendingEvents.add(GameEvent.SceneChanged(after))
                    }
                }
            }
            Unit
        }
    }

    fun reset(kind: LogKind) {
        synchronized(lock) {
            when (kind) {
                LogKind.POWER -> power = newPowerParser()
                LogKind.ARENA -> arena = newArenaParser()
                LogKind.DECKS -> decks = DecksLogParser { cards.value }
                LogKind.LOADING_SCREEN -> loading = LoadingScreenParser()
            }
            lastKey = emptyList()
        }
    }

    fun resetAll() = LogKind.entries.forEach { reset(it) }

    /** Recomputes snapshots when anything changed (or [force]). Cheap when nothing did. */
    fun publish(force: Boolean = false) {
        val events = synchronized(lock) {
            val db = cards.value
            val s = settings.value
            val key = listOf(
                power.game.revision, power.game.gameNumber, arena.state.revision, decks.revision,
                loading.revision, db, s.gameLocale, s.manualDeckCode,
            )
            if (force || key != lastKey) {
                lastKey = key
                val tracker = DeckTracker(db, s.gameLocale)
                _tracker.value = tracker.snapshot(power.game, decklistFor(power.game, s))
                _draft.value = buildDraft(db, s.gameLocale)
                _scene.value = loading.currentMode
            }
            val ready = pendingEvents.toList()
            pendingEvents.clear()
            ready
        }
        events.forEach { _events.tryEmit(it) }
    }

    private fun decklistFor(game: GameState, s: SettingsState): Decklist? {
        val isArena = game.gameType?.contains("ARENA") == true
        if (isArena && arena.state.picks.isNotEmpty()) {
            return Decklist("투기장 덱", arena.state.picks.groupingBy { it }.eachCount(), arena.state.heroCardId)
        }
        return decks.lastDeck ?: manualDeck(s.manualDeckCode)
    }

    private fun manualDeck(code: String?): Decklist? {
        if (code.isNullOrBlank()) return null
        manualDeckCache?.let { (c, deck) -> if (c == code) return deck }
        val db = cards.value
        val deck = runCatching { Deckstring.decode(code) }.getOrNull()?.let { decoded ->
            val list = HashMap<String, Int>()
            for ((dbf, n) in decoded.cards) {
                val id = db.byDbf(dbf)?.id ?: continue
                list[id] = (list[id] ?: 0) + n
            }
            Decklist("직접 입력한 덱", list, decoded.heroes.firstOrNull()?.let { db.byDbf(it)?.id })
        }
        if (db.size > 0) manualDeckCache = code to deck
        return deck
    }

    private fun buildDraft(db: CardDb, locale: String): DraftInfo {
        val st = arena.state
        val counts = st.picks.groupingBy { it }.eachCount()
        val picks = counts.map { (id, n) ->
            val c = db[id]
            TrackedCard(id, c?.name(locale) ?: id, c?.cost ?: 0, n, rarity = c?.rarity)
        }.sortedWith(DeckTracker.CARD_ORDER)
        val curve = MutableList(8) { 0 }
        for ((id, n) in counts) {
            val cost = (db[id]?.cost ?: 0).coerceIn(0, 7)
            curve[cost] += n
        }
        return DraftInfo(
            heroCardId = st.heroCardId,
            heroClass = HeroClass.fromCardClass(db[st.heroCardId]?.cardClass),
            picks = picks,
            pickCount = st.picks.size,
            mode = st.mode,
            curve = curve,
        )
    }
}
