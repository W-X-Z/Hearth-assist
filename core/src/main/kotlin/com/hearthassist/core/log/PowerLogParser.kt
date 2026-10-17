package com.hearthassist.core.log

import com.hearthassist.core.game.Entity
import com.hearthassist.core.game.GameState
import com.hearthassist.core.game.Zone

/**
 * Incremental parser for Hearthstone's Power.log (`[Power]` section of log.config with
 * `Verbose=true`). Feed it lines in order; [game] always reflects the latest game.
 */
class PowerLogParser(val game: GameState = GameState()) {

    interface Listener {
        fun onGameCreated(game: GameState) {}
        fun onGameComplete(game: GameState) {}
    }

    var listener: Listener? = null

    private var current: Entity? = null
    private var currentIsCreation = false
    private var sawGameStateLines = false
    private val blocks = ArrayDeque<Block>()

    private data class Block(val type: String, val entityId: Int)

    fun feed(rawLine: String) {
        val content = LogLines.content(rawLine)
        val body = when {
            content.startsWith(GS_POWER) -> {
                sawGameStateLines = true
                content.substring(GS_POWER.length)
            }
            content.startsWith(PTL_POWER) -> {
                // PowerTaskList repeats the GameState lines (delayed for animations).
                if (sawGameStateLines) return
                content.substring(PTL_POWER.length)
            }
            content.startsWith(GS_GAME) -> {
                handleDebugPrintGame(content.substring(GS_GAME.length).trim())
                return
            }
            else -> return
        }
        handlePower(body)
    }

    private fun handleDebugPrintGame(body: String) {
        PLAYER_NAME.matchEntire(body)?.let { m ->
            val playerId = m.groupValues[1].toInt()
            game.playerNames[playerId] = m.groupValues[2]
            return
        }
        when {
            body.startsWith("GameType=") -> game.gameType = body.removePrefix("GameType=")
            body.startsWith("FormatType=") -> game.formatType = body.removePrefix("FormatType=")
        }
    }

    private fun handlePower(body: String) {
        val line = body.trim()
        if (line.startsWith("tag=")) {
            val e = current ?: return
            TAG.matchEntire(line)?.let { applyTag(e, it.groupValues[1], it.groupValues[2], currentIsCreation) }
            return
        }
        finishCurrent()
        when {
            line == "CREATE_GAME" -> createGame()
            line.startsWith("GameEntity EntityID=") -> {
                val id = line.removePrefix("GameEntity EntityID=").trim().toIntOrNull() ?: return
                game.gameEntityId = id
                begin(game.entity(id), creation = true)
            }
            line.startsWith("Player EntityID=") -> {
                val m = PLAYER_ENTITY.find(line) ?: return
                val entityId = m.groupValues[1].toInt()
                val playerId = m.groupValues[2].toInt()
                game.playerEntityIds[playerId] = entityId
                begin(game.entity(entityId), creation = true)
            }
            line.startsWith("FULL_ENTITY - Creating") -> {
                val m = FULL_ENTITY_CREATING.find(line) ?: return
                val e = game.entity(m.groupValues[1].toInt())
                setCardId(e, m.groupValues[2])
                begin(e, creation = true)
            }
            line.startsWith("FULL_ENTITY - Updating") -> {
                val m = FULL_ENTITY_UPDATING.find(line) ?: return
                val e = resolveEntity(m.groupValues[1]) ?: return
                setCardId(e, m.groupValues[2])
                begin(e, creation = true)
            }
            line.startsWith("SHOW_ENTITY - Updating") || line.startsWith("CHANGE_ENTITY - Updating") -> {
                val m = UPDATING_ENTITY.find(line) ?: return
                val e = resolveEntity(m.groupValues[1]) ?: return
                setCardId(e, m.groupValues[2])
                begin(e, creation = false)
            }
            line.startsWith("HIDE_ENTITY - Entity=") -> {
                val m = HIDE_ENTITY.find(line) ?: return
                val e = resolveEntity(m.groupValues[1]) ?: return
                applyTag(e, m.groupValues[2], m.groupValues[3], creation = false)
            }
            line.startsWith("TAG_CHANGE Entity=") -> {
                val m = TAG_CHANGE.find(line) ?: return
                val e = resolveEntity(m.groupValues[1]) ?: return
                applyTag(e, m.groupValues[2], m.groupValues[3], creation = false)
            }
            line.startsWith("BLOCK_START") -> {
                if (game.gameNumber > 0 && !game.started) game.started = true
                val m = BLOCK_START.find(line)
                val entityId = m?.let { resolveEntity(it.groupValues[2])?.id } ?: 0
                blocks.addLast(Block(m?.groupValues?.get(1) ?: "", entityId))
            }
            line == "BLOCK_END" -> blocks.removeLastOrNull()
        }
    }

    private fun begin(e: Entity, creation: Boolean) {
        current = e
        currentIsCreation = creation
        game.revision++
    }

    private fun finishCurrent() {
        val e = current ?: return
        current = null
        if (currentIsCreation && e.id != game.gameEntityId && game.playerIdForEntity(e.id) == null) {
            e.createdDuringGame = game.started
            if (!game.started && (e.zone == Zone.DECK || e.zone == Zone.HAND)) e.isOriginalDeckCard = true
        }
        detectLocalPlayer(e)
    }

    private fun createGame() {
        game.reset()
        game.gameNumber++
        game.revision++
        current = null
        blocks.clear()
        listener?.onGameCreated(game)
    }

    private fun setCardId(e: Entity, cardId: String) {
        if (cardId.isNotEmpty()) e.cardId = cardId
    }

    private fun applyTag(e: Entity, tag: String, value: String, creation: Boolean) {
        val old = e.tags.put(tag, value)
        game.revision++
        if (e.id == game.gameEntityId) {
            when (tag) {
                "STATE" -> if (value == "COMPLETE" && !game.complete) {
                    game.complete = true
                    listener?.onGameComplete(game)
                }
                "TURN" -> value.toIntOrNull()?.let { game.turn = it }
                "STEP", "NEXT_STEP" -> if (value == "BEGIN_MULLIGAN" || value == "MAIN_READY") game.started = true
            }
            return
        }
        val playerId = game.playerIdForEntity(e.id)
        if (playerId != null) {
            if (tag == "PLAYSTATE" && value in FINAL_PLAYSTATES) game.playerResults[playerId] = value
            return
        }
        if (tag == "ZONE") {
            if (value == Zone.HAND) e.wasInHand = true
            if (!creation && old == Zone.HAND && isPlay(e, value)) e.playedFromHand = true
            if (!creation) detectLocalPlayer(e)
        }
    }

    private fun isPlay(e: Entity, newZone: String): Boolean = when (newZone) {
        Zone.PLAY, Zone.SECRET -> true
        Zone.GRAVEYARD -> blocks.any { it.type == "PLAY" && it.entityId == e.id }
        else -> false
    }

    private fun detectLocalPlayer(e: Entity) {
        if (game.localPlayerId != 0) return
        if (e.cardId.isEmpty() || e.cardId == COIN || e.zone != Zone.HAND) return
        val controller = e.controller
        if (controller == 1 || controller == 2) game.localPlayerId = controller
    }

    private fun resolveEntity(ref: String): Entity? {
        val r = ref.trim()
        if (r.startsWith("[")) {
            val id = ID_IN_REF.find(r)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val e = game.entity(id)
            CARD_IN_REF.find(r)?.groupValues?.get(1)?.let { if (e.cardId.isEmpty() && it.isNotEmpty()) e.cardId = it }
            if (!e.tags.containsKey("CONTROLLER")) {
                PLAYER_IN_REF.find(r)?.groupValues?.get(1)?.let { e.tags["CONTROLLER"] = it }
            }
            return e
        }
        r.toIntOrNull()?.let { return game.entity(it) }
        if (r == "GameEntity") return game.entity(game.gameEntityId)
        val playerId = playerIdForName(r) ?: return null
        val entityId = game.playerEntityIds[playerId] ?: return null
        return game.entity(entityId)
    }

    private fun playerIdForName(name: String): Int? {
        game.playerNames.entries.firstOrNull { it.value == name }?.let { return it.key }
        // Names are sometimes logged as "UNKNOWN HUMAN PLAYER" until revealed.
        val unnamed = game.playerEntityIds.keys.filter {
            val n = game.playerNames[it]
            n == null || n.startsWith("UNKNOWN")
        }
        if (unnamed.size == 1) {
            game.playerNames[unnamed[0]] = name
            return unnamed[0]
        }
        return null
    }

    companion object {
        private const val GS_POWER = "GameState.DebugPrintPower() - "
        private const val PTL_POWER = "PowerTaskList.DebugPrintPower() - "
        private const val GS_GAME = "GameState.DebugPrintGame() - "
        private const val COIN = "GAME_005"
        private val FINAL_PLAYSTATES = setOf("WON", "LOST", "TIED", "CONCEDED")

        private val TAG = Regex("^tag=(\\S+) value=(\\S*)")
        private val PLAYER_NAME = Regex("^PlayerID=(\\d+), PlayerName=(.*)$")
        private val PLAYER_ENTITY = Regex("Player EntityID=(\\d+) PlayerID=(\\d+)")
        private val FULL_ENTITY_CREATING = Regex("FULL_ENTITY - Creating ID=(\\d+) CardID=(\\S*)")
        private val FULL_ENTITY_UPDATING = Regex("FULL_ENTITY - Updating (.+) CardID=(\\S*)\\s*$")
        private val UPDATING_ENTITY = Regex("_ENTITY - Updating Entity=(.+) CardID=(\\S*)\\s*$")
        private val HIDE_ENTITY = Regex("HIDE_ENTITY - Entity=(.+?) tag=(\\S+) value=(\\S*)")
        private val TAG_CHANGE = Regex("^TAG_CHANGE Entity=(.+?) tag=(\\S+) value=(\\S*)")
        private val BLOCK_START = Regex("BLOCK_START BlockType=(\\S+) Entity=(.+?) EffectCardId=")
        private val ID_IN_REF = Regex("\\bid=(\\d+)")
        private val CARD_IN_REF = Regex("\\bcardId=(\\S*) player=")
        private val PLAYER_IN_REF = Regex("\\bplayer=(\\d+)\\]")
    }
}
