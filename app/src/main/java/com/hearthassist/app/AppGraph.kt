package com.hearthassist.app

import android.content.Context
import com.hearthassist.app.data.ArenaStatsRepository
import com.hearthassist.app.data.CardRepository
import com.hearthassist.app.engine.TrackerEngine
import com.hearthassist.app.logs.LogController
import com.hearthassist.app.logs.ShizukuAccess
import com.hearthassist.app.settings.Settings
import com.hearthassist.core.cards.HeroClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process-wide singletons. */
object AppGraph {
    lateinit var settings: Settings
        private set
    lateinit var cards: CardRepository
        private set
    lateinit var stats: ArenaStatsRepository
        private set
    lateinit var shizuku: ShizukuAccess
        private set
    lateinit var engine: TrackerEngine
        private set
    lateinit var logs: LogController
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        settings = Settings(app)
        cards = CardRepository(app)
        stats = ArenaStatsRepository(app)
        shizuku = ShizukuAccess(app)
        engine = TrackerEngine(cards.db, settings.state)
        logs = LogController(settings, shizuku, engine, scope)

        scope.launch { cards.load() }
        scope.launch {
            settings.state.map { it.statsMode to it.statsPeriod }.distinctUntilChanged().collect { (mode, period) ->
                stats.configure(mode, period)
                stats.scorer(null)
            }
        }
        // Re-render tracker snapshots when names or settings change.
        scope.launch { cards.db.collect { engine.publish(force = true) } }
        scope.launch {
            settings.state.map { Triple(it.gameLocale, it.manualDeckCode, it.logAccess) }.distinctUntilChanged().collect {
                engine.publish(force = true)
            }
        }
        scope.launch {
            settings.state.map { it.logAccess }.distinctUntilChanged().collect { logs.restart() }
        }
    }

    /** Class used for arena stats: picked by hand in the overlay, else from Arena.log. */
    fun effectiveClass(): HeroClass? =
        HeroClass.fromCardClass(settings.current.manualClass) ?: engine.draft.value.heroClass
}
