package com.hearthassist.core.log

/** Arena draft progress reconstructed from Arena.log. */
class DraftState {
    var deckId: Long? = null
    var heroCardId: String? = null
    var mode: String? = null
    val picks: MutableList<String> = mutableListOf()
    var revision: Long = 0
        internal set

    fun clear() {
        deckId = null
        heroCardId = null
        mode = null
        picks.clear()
        revision++
    }
}

/**
 * Parses the `[Arena]` log section:
 * ```
 * DraftManager.OnChoicesAndContents - Draft Deck ID: 123, Hero Card = HERO_08
 * DraftManager.OnChoicesAndContents - Draft deck contains card CS2_029
 * Client chooses: Fireball (CS2_029)
 * DraftManager.OnChosen(): hero=HERO_08 premium=STANDARD
 * SetDraftMode - DRAFTING
 * ```
 */
class ArenaLogParser(
    val state: DraftState = DraftState(),
    private val isHeroCard: (String) -> Boolean = { it.startsWith("HERO_") },
) {
    fun feed(rawLine: String) {
        val line = LogLines.content(rawLine)
        DECK_ID.find(line)?.let { m ->
            val id = m.groupValues[1].toLongOrNull()
            // The full deck is printed after this line, so rebuild it from scratch.
            state.picks.clear()
            state.deckId = id
            m.groupValues[2].takeIf { it.isNotEmpty() && it != "0" }?.let { state.heroCardId = it }
            state.revision++
            return
        }
        DECK_CONTAINS.find(line)?.let { m ->
            state.picks += m.groupValues[1]
            state.revision++
            return
        }
        CLIENT_CHOOSES.find(line)?.let { m ->
            val cardId = m.groupValues[1]
            if (isHeroCard(cardId)) {
                startNewDraft(cardId)
            } else {
                state.picks += cardId
            }
            state.revision++
            return
        }
        HERO_CHOSEN.find(line)?.let { m ->
            if (state.heroCardId != m.groupValues[1]) startNewDraft(m.groupValues[1])
            state.revision++
            return
        }
        DRAFT_MODE.find(line)?.let { m ->
            state.mode = m.groupValues[1]
            state.revision++
            return
        }
        if (NEW_DRAFT.containsMatchIn(line)) {
            state.clear()
        }
    }

    private fun startNewDraft(heroCardId: String) {
        if (state.picks.isNotEmpty() && state.heroCardId != null && state.heroCardId != heroCardId) {
            state.picks.clear()
            state.deckId = null
        }
        state.heroCardId = heroCardId
    }

    companion object {
        private val DECK_ID = Regex("Draft Deck ID: (\\d+), Hero Card = (\\S*)")
        private val DECK_CONTAINS = Regex("Draft deck contains card (\\S+)")
        private val CLIENT_CHOOSES = Regex("Client chooses: .* \\((\\S+)\\)\\s*$")
        private val HERO_CHOSEN = Regex("DraftManager\\.OnChosen\\(\\): hero=(\\S+)")
        private val DRAFT_MODE = Regex("SetDraftMode - (\\w+)")
        private val NEW_DRAFT = Regex("DraftManager\\.OnBegin|Got new draft deck")
    }
}
