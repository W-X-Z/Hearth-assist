package com.hearthassist.app.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogAccess { AUTO, SHIZUKU, DIRECT, OFF }

data class SettingsState(
    /** Hearthstone client language, used for card names and OCR matching. */
    val gameLocale: String = "koKR",
    val statsMode: String = "arena",
    val statsPeriod: String = "last-patch",
    val logAccess: LogAccess = LogAccess.AUTO,
    val autoScan: Boolean = true,
    val scanIntervalMs: Long = 2000,
    val overlayAlpha: Float = 0.9f,
    val overlayScale: Float = 1f,
    val autoOpenTracker: Boolean = true,
    val showCardArt: Boolean = true,
    /** Class picked by hand in the overlay (CardClass name), overrides the log. */
    val manualClass: String? = null,
    /** Deck code pasted by the user for constructed games. */
    val manualDeckCode: String? = null,
    /** Last position of the overlay HUD in pixels (-1 = default). */
    val hudX: Int = -1,
    val hudY: Int = -1,
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    val current: SettingsState get() = _state.value

    fun update(transform: (SettingsState) -> SettingsState) {
        synchronized(this) {
            val next = transform(_state.value)
            if (next == _state.value) return
            save(next)
            _state.value = next
        }
    }

    private fun load(): SettingsState {
        val d = SettingsState()
        return SettingsState(
            gameLocale = prefs.getString("gameLocale", d.gameLocale) ?: d.gameLocale,
            statsMode = prefs.getString("statsMode", d.statsMode) ?: d.statsMode,
            statsPeriod = prefs.getString("statsPeriod", d.statsPeriod) ?: d.statsPeriod,
            logAccess = runCatching { LogAccess.valueOf(prefs.getString("logAccess", d.logAccess.name)!!) }
                .getOrDefault(d.logAccess),
            autoScan = prefs.getBoolean("autoScan", d.autoScan),
            scanIntervalMs = prefs.getLong("scanIntervalMs", d.scanIntervalMs),
            overlayAlpha = prefs.getFloat("overlayAlpha", d.overlayAlpha),
            overlayScale = prefs.getFloat("overlayScale", d.overlayScale),
            autoOpenTracker = prefs.getBoolean("autoOpenTracker", d.autoOpenTracker),
            showCardArt = prefs.getBoolean("showCardArt", d.showCardArt),
            manualClass = prefs.getString("manualClass", null),
            manualDeckCode = prefs.getString("manualDeckCode", null),
            hudX = prefs.getInt("hudX", d.hudX),
            hudY = prefs.getInt("hudY", d.hudY),
        )
    }

    private fun save(s: SettingsState) {
        prefs.edit()
            .putString("gameLocale", s.gameLocale)
            .putString("statsMode", s.statsMode)
            .putString("statsPeriod", s.statsPeriod)
            .putString("logAccess", s.logAccess.name)
            .putBoolean("autoScan", s.autoScan)
            .putLong("scanIntervalMs", s.scanIntervalMs)
            .putFloat("overlayAlpha", s.overlayAlpha)
            .putFloat("overlayScale", s.overlayScale)
            .putBoolean("autoOpenTracker", s.autoOpenTracker)
            .putBoolean("showCardArt", s.showCardArt)
            .putString("manualClass", s.manualClass)
            .putString("manualDeckCode", s.manualDeckCode)
            .putInt("hudX", s.hudX)
            .putInt("hudY", s.hudY)
            .apply()
    }
}
