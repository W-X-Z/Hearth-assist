package com.hearthassist.core.cards

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * In-memory card database.
 *
 * Reads HearthstoneJSON-format arrays (https://hearthstonejson.com). `name` may be a plain
 * string (single-locale files) or a `{locale: name}` object (the bundled asset / "all" files).
 */
class CardDb(cards: Collection<Card>) {
    val cards: List<Card> = cards.toList()
    private val byId: Map<String, Card> = this.cards.associateBy { it.id }
    private val byDbfId: Map<Int, Card> = this.cards.associateBy { it.dbfId }

    val size: Int get() = cards.size

    operator fun get(cardId: String?): Card? = if (cardId.isNullOrEmpty()) null else byId[cardId]

    fun byDbf(dbfId: Int): Card? = byDbfId[dbfId]

    fun name(cardId: String?, locale: String): String =
        get(cardId)?.name(locale) ?: cardId.orEmpty()

    val draftable: List<Card> by lazy { this.cards.filter { it.isDraftable } }

    /** Returns a new db where names from [other] are added for cards both dbs know about. */
    fun mergeNames(other: CardDb): CardDb = CardDb(cards.map { card ->
        val extra = other[card.id]?.names ?: return@map card
        card.copy(names = card.names + extra)
    })

    companion object {
        val EMPTY = CardDb(emptyList())

        @OptIn(ExperimentalSerializationApi::class)
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        /**
         * Parses a HearthstoneJSON array. [localeHint] names the locale of plain-string names
         * (e.g. "koKR" for `/v1/latest/koKR/cards.json`). Gzip input is detected automatically.
         */
        @OptIn(ExperimentalSerializationApi::class)
        fun parse(input: InputStream, localeHint: String = Card.DEFAULT_LOCALE): CardDb {
            val stream = maybeGunzip(input)
            val raw = json.decodeFromStream(ListSerializer(RawCard.serializer()), stream)
            return CardDb(raw.mapNotNull { it.toCard(localeHint) })
        }

        fun parse(text: String, localeHint: String = Card.DEFAULT_LOCALE): CardDb =
            parse(text.byteInputStream(), localeHint)

        /** Writes the db in the bundled-asset format (multi-locale names). */
        fun write(db: CardDb, out: OutputStream) {
            val raw = db.cards.map { c ->
                RawCard(
                    id = c.id, dbfId = c.dbfId,
                    name = JsonObject(c.names.mapValues { JsonPrimitive(it.value) }),
                    type = c.type, cardClass = c.cardClass, cost = c.cost, attack = c.attack,
                    health = c.health, rarity = c.rarity, set = c.set, collectible = c.collectible,
                )
            }
            out.write(json.encodeToString(ListSerializer(RawCard.serializer()), raw).toByteArray())
        }

        fun maybeGunzip(input: InputStream): InputStream {
            val buffered = if (input.markSupported()) input else java.io.BufferedInputStream(input)
            buffered.mark(2)
            val b1 = buffered.read()
            val b2 = buffered.read()
            buffered.reset()
            return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered
        }
    }
}

@Serializable
internal data class RawCard(
    val id: String,
    val dbfId: Int = 0,
    val name: JsonElement? = null,
    val type: String? = null,
    val cardClass: String? = null,
    val cost: Int? = null,
    val attack: Int? = null,
    val health: Int? = null,
    val rarity: String? = null,
    val set: String? = null,
    val collectible: Boolean = false,
) {
    fun toCard(localeHint: String): Card? {
        val cardType = type ?: "INVALID"
        if (cardType == "ENCHANTMENT") return null
        val names: Map<String, String> = when (val n = name) {
            is JsonObject -> n.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
            is JsonPrimitive -> n.contentOrNull?.let { mapOf(localeHint to it) } ?: emptyMap()
            else -> emptyMap()
        }
        return Card(
            id = id, dbfId = dbfId, names = names, type = cardType,
            cardClass = cardClass ?: "NEUTRAL", cost = cost, attack = attack, health = health,
            rarity = rarity, set = set, collectible = collectible,
        )
    }
}
