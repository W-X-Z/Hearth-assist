package com.hearthassist.core.game

object Zone {
    const val DECK = "DECK"
    const val HAND = "HAND"
    const val PLAY = "PLAY"
    const val GRAVEYARD = "GRAVEYARD"
    const val SECRET = "SECRET"
    const val SETASIDE = "SETASIDE"
    const val REMOVED = "REMOVEDFROMGAME"
}

class Entity(val id: Int) {
    var cardId: String = ""
    val tags: HashMap<String, String> = HashMap()

    /** Was in the controller's deck (or opening hand) when the game was created. */
    var isOriginalDeckCard: Boolean = false
    var createdDuringGame: Boolean = false
    var wasInHand: Boolean = false
    var playedFromHand: Boolean = false

    val zone: String get() = tags["ZONE"] ?: ""
    val controller: Int get() = tags["CONTROLLER"]?.toIntOrNull() ?: 0
    val cardType: String get() = tags["CARDTYPE"] ?: ""

    fun intTag(name: String): Int = tags[name]?.toIntOrNull() ?: 0

    override fun toString(): String = "Entity(id=$id, cardId=$cardId, zone=$zone, controller=$controller)"
}

/** Reconstructed state of the current (or last) game from Power.log. */
class GameState {
    val entities: HashMap<Int, Entity> = HashMap()
    var gameEntityId: Int = 1
    /** PlayerID (1/2) -> entity id of the Player entity. */
    val playerEntityIds: HashMap<Int, Int> = HashMap()
    /** PlayerID -> BattleTag-like name. */
    val playerNames: HashMap<Int, String> = HashMap()
    val playerResults: HashMap<Int, String> = HashMap()

    var localPlayerId: Int = 0
    var gameType: String? = null
    var formatType: String? = null
    /** True once the first block after CREATE_GAME has started (mulligan or later). */
    var started: Boolean = false
    var complete: Boolean = false
    var turn: Int = 0
    /** Increments on every state change so observers can cheaply detect updates. */
    var revision: Long = 0
        internal set
    /** Increments on every CREATE_GAME. */
    var gameNumber: Int = 0
        internal set

    val opponentPlayerId: Int
        get() = when (localPlayerId) {
            1 -> 2
            2 -> 1
            else -> 0
        }

    val inProgress: Boolean get() = gameNumber > 0 && !complete

    val localResult: String? get() = playerResults[localPlayerId]

    fun entity(id: Int): Entity = entities.getOrPut(id) { Entity(id) }

    fun playerIdForEntity(entityId: Int): Int? =
        playerEntityIds.entries.firstOrNull { it.value == entityId }?.key

    fun cardsOf(playerId: Int): Sequence<Entity> =
        entities.values.asSequence().filter { it.controller == playerId && it.id != gameEntityId && playerIdForEntity(it.id) == null }

    fun count(playerId: Int, zone: String): Int = cardsOf(playerId).count { it.zone == zone }

    fun reset() {
        entities.clear()
        playerEntityIds.clear()
        playerNames.clear()
        playerResults.clear()
        localPlayerId = 0
        gameType = null
        formatType = null
        started = false
        complete = false
        turn = 0
        gameEntityId = 1
    }
}
