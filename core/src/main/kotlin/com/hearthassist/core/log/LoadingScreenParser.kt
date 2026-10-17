package com.hearthassist.core.log

/** Tracks the current scene from LoadingScreen.log (HUB, DRAFT, GAMEPLAY, TOURNAMENT...). */
class LoadingScreenParser {
    var currentMode: String? = null
        private set
    var revision: Long = 0
        private set

    fun feed(rawLine: String) {
        val line = LogLines.content(rawLine)
        val mode = CURR_MODE.find(line)?.groupValues?.get(1)
            ?: NEXT_MODE.find(line)?.groupValues?.get(1)
            ?: return
        if (mode != currentMode) {
            currentMode = mode
            revision++
        }
    }

    companion object {
        private val CURR_MODE = Regex("OnSceneLoaded\\(\\).*currMode=(\\w+)")
        private val NEXT_MODE = Regex("OnScenePreUnload\\(\\).*nextMode=(\\w+)")

        const val MODE_DRAFT = "DRAFT"
        const val MODE_GAMEPLAY = "GAMEPLAY"
    }
}
