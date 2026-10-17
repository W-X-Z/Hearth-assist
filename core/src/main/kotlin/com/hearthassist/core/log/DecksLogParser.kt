package com.hearthassist.core.log

import com.hearthassist.core.cards.CardDb
import com.hearthassist.core.deck.Deckstring
import com.hearthassist.core.game.Decklist

/**
 * Reads the deck used for the next game from Decks.log:
 * ```
 * Finding Game With Deck:
 * ### My Deck
 * # Deck ID: 1234
 * AAECAf0EBu0F...
 * ```
 */
class DecksLogParser(private val cards: () -> CardDb) {
    var lastDeck: Decklist? = null
        private set
    var revision: Long = 0
        private set

    private var expecting = false
    private var pendingName: String? = null

    fun feed(rawLine: String) {
        val line = LogLines.content(rawLine).trim()
        if (line.startsWith("Finding Game With Deck") || line.startsWith("Starting Arena Game With Deck")) {
            expecting = true
            pendingName = null
            return
        }
        if (!expecting) return
        when {
            line.startsWith("###") -> pendingName = line.removePrefix("###").trim()
            line.startsWith("#") -> Unit
            line.isEmpty() -> Unit
            else -> {
                expecting = false
                val decoded = runCatching { Deckstring.decode(line) }.getOrNull() ?: return
                val db = cards()
                val list = HashMap<String, Int>()
                for ((dbfId, count) in decoded.cards) {
                    val id = db.byDbf(dbfId)?.id ?: continue
                    list[id] = (list[id] ?: 0) + count
                }
                lastDeck = Decklist(pendingName, list, decoded.heroes.firstOrNull()?.let { db.byDbf(it)?.id })
                revision++
            }
        }
    }
}
