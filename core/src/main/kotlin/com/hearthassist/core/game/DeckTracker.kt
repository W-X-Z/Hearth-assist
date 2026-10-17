package com.hearthassist.core.game

import com.hearthassist.core.cards.CardDb

data class TrackedCard(
    val cardId: String,
    val name: String,
    val cost: Int,
    /** Copies left (remaining list) or copies seen (drawn / opponent lists). */
    val count: Int,
    /** Copies in the original decklist, when known. */
    val total: Int? = null,
    val rarity: String? = null,
    /** Added to the deck by a card effect rather than part of the decklist. */
    val created: Boolean = false,
)

data class TrackerSnapshot(
    val gameNumber: Int = 0,
    val inProgress: Boolean = false,
    val complete: Boolean = false,
    val result: String? = null,
    val turn: Int = 0,
    val gameType: String? = null,
    val localPlayerKnown: Boolean = false,
    val deckName: String? = null,
    val hasDecklist: Boolean = false,
    val playerDeckCount: Int = 0,
    val playerHandCount: Int = 0,
    val opponentDeckCount: Int = 0,
    val opponentHandCount: Int = 0,
    /** With a decklist: every decklist card with copies left (0 = all drawn). */
    val playerRemaining: List<TrackedCard> = emptyList(),
    /** Cards that left the player's deck (drawn, burned, tutored...). */
    val playerDrawn: List<TrackedCard> = emptyList(),
    /** Cards from the opponent's original deck they have revealed (played, etc.). */
    val opponentPlayed: List<TrackedCard> = emptyList(),
    /** Cards the opponent revealed that were created during the game. */
    val opponentCreated: List<TrackedCard> = emptyList(),
) {
    val remainingUnknown: Int get() = playerDeckCount - playerRemaining.sumOf { it.count }
}

/** A decklist: card id -> copies. */
data class Decklist(val name: String?, val cards: Map<String, Int>, val heroCardId: String? = null) {
    val size: Int get() = cards.values.sum()
}

class DeckTracker(private val cards: CardDb, private val locale: String) {

    fun snapshot(game: GameState, decklist: Decklist?): TrackerSnapshot {
        val me = game.localPlayerId
        val opp = game.opponentPlayerId
        if (me == 0) {
            return TrackerSnapshot(
                gameNumber = game.gameNumber, inProgress = game.inProgress, complete = game.complete,
                turn = game.turn, gameType = game.gameType, deckName = decklist?.name,
                hasDecklist = decklist != null,
                playerRemaining = decklist?.let { remaining(it, emptyList(), emptyList()) } ?: emptyList(),
            )
        }
        val mine = game.cardsOf(me).filter { isCard(it) }.toList()
        val theirs = game.cardsOf(opp).filter { isCard(it) }.toList()

        val leftDeck = mine.filter { it.isOriginalDeckCard && it.zone != Zone.DECK && it.cardId.isNotEmpty() }
        val createdInDeck = mine.filter { !it.isOriginalDeckCard && it.zone == Zone.DECK && it.cardId.isNotEmpty() }

        val opponentRevealed = theirs.filter { it.cardId.isNotEmpty() && it.zone != Zone.DECK && it.zone != Zone.HAND }
        return TrackerSnapshot(
            gameNumber = game.gameNumber,
            inProgress = game.inProgress,
            complete = game.complete,
            result = game.localResult,
            turn = game.turn,
            gameType = game.gameType,
            localPlayerKnown = true,
            deckName = decklist?.name,
            hasDecklist = decklist != null,
            playerDeckCount = mine.count { it.zone == Zone.DECK },
            playerHandCount = mine.count { it.zone == Zone.HAND },
            opponentDeckCount = theirs.count { it.zone == Zone.DECK },
            opponentHandCount = theirs.count { it.zone == Zone.HAND },
            playerRemaining = decklist?.let { remaining(it, leftDeck, createdInDeck) } ?: emptyList(),
            playerDrawn = group(leftDeck),
            opponentPlayed = group(opponentRevealed.filter { it.isOriginalDeckCard }),
            opponentCreated = group(opponentRevealed.filter { !it.isOriginalDeckCard && it.createdDuringGame }),
        )
    }

    private fun remaining(decklist: Decklist, leftDeck: List<Entity>, createdInDeck: List<Entity>): List<TrackedCard> {
        val left = HashMap(decklist.cards)
        for (e in leftDeck) {
            val n = left[e.cardId] ?: continue
            left[e.cardId] = (n - 1).coerceAtLeast(0)
        }
        val result = decklist.cards.keys.map { id ->
            tracked(id, count = left[id] ?: 0, total = decklist.cards[id])
        }.toMutableList()
        createdInDeck.groupingBy { it.cardId }.eachCount().forEach { (id, n) ->
            result += tracked(id, count = n, created = true)
        }
        return result.sortedWith(CARD_ORDER)
    }

    private fun group(entities: List<Entity>): List<TrackedCard> =
        entities.groupingBy { it.cardId }.eachCount()
            .map { (id, n) -> tracked(id, count = n) }
            .sortedWith(CARD_ORDER)

    private fun tracked(cardId: String, count: Int, total: Int? = null, created: Boolean = false): TrackedCard {
        val card = cards[cardId]
        return TrackedCard(
            cardId = cardId,
            name = card?.name(locale) ?: cardId,
            cost = card?.cost ?: 0,
            count = count,
            total = total,
            rarity = card?.rarity,
            created = created,
        )
    }

    private fun isCard(e: Entity): Boolean {
        val type = e.cardType.ifEmpty { cards[e.cardId]?.type ?: "" }
        return type !in NON_DECK_TYPES
    }

    companion object {
        private val NON_DECK_TYPES = setOf("HERO_POWER", "ENCHANTMENT", "PLAYER", "GAME")
        val CARD_ORDER: Comparator<TrackedCard> = compareBy<TrackedCard>({ it.cost }, { it.name })
    }
}
