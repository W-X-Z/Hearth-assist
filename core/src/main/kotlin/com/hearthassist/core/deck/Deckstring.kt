package com.hearthassist.core.deck

import java.io.ByteArrayOutputStream
import java.util.Base64

/** Hearthstone deck code (https://hearthsim.info/docs/deckstrings/). */
data class DecodedDeck(
    val format: Int,
    val heroes: List<Int>,
    /** dbfId -> copies */
    val cards: Map<Int, Int>,
)

object Deckstring {
    fun decode(deckstring: String): DecodedDeck {
        val code = extractCode(deckstring) ?: throw IllegalArgumentException("Not a deckstring")
        val bytes = Base64.getDecoder().decode(code)
        val r = VarIntReader(bytes)
        require(r.read() == 0) { "Invalid deckstring header" }
        val version = r.read()
        require(version == 1) { "Unsupported deckstring version $version" }
        val format = r.read()
        val heroes = List(r.read()) { r.read() }
        val cards = LinkedHashMap<Int, Int>()
        repeat(r.read()) { cards[r.read()] = 1 }
        repeat(r.read()) { cards[r.read()] = 2 }
        repeat(r.read()) {
            val dbfId = r.read()
            cards[dbfId] = r.read()
        }
        return DecodedDeck(format, heroes, cards)
    }

    fun encode(deck: DecodedDeck): String {
        val out = ByteArrayOutputStream()
        fun w(v: Int) = writeVarInt(out, v)
        w(0); w(1); w(deck.format)
        w(deck.heroes.size); deck.heroes.forEach { w(it) }
        val singles = deck.cards.filterValues { it == 1 }.keys.sorted()
        val doubles = deck.cards.filterValues { it == 2 }.keys.sorted()
        val multi = deck.cards.filterValues { it > 2 }.toSortedMap()
        w(singles.size); singles.forEach { w(it) }
        w(doubles.size); doubles.forEach { w(it) }
        w(multi.size); multi.forEach { (id, n) -> w(id); w(n) }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** Pulls the code out of text copied from the game ("### Name ... AAE... # comments"). */
    fun extractCode(text: String): String? =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .flatMap { it.split(Regex("\\s+")).asSequence() }
            .firstOrNull { it.startsWith("AAE") && it.length >= 12 }

    private fun writeVarInt(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7f.inv() == 0) {
                out.write(v)
                return
            }
            out.write((v and 0x7f) or 0x80)
            v = v ushr 7
        }
    }

    private class VarIntReader(private val bytes: ByteArray) {
        private var pos = 0
        fun read(): Int {
            var result = 0
            var shift = 0
            while (true) {
                require(pos < bytes.size) { "Unexpected end of deckstring" }
                val b = bytes[pos++].toInt() and 0xff
                result = result or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
    }
}
