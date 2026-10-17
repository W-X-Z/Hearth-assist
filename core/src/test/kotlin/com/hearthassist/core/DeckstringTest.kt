package com.hearthassist.core

import com.hearthassist.core.deck.DecodedDeck
import com.hearthassist.core.deck.Deckstring
import kotlin.test.Test
import kotlin.test.assertEquals

class DeckstringTest {
    @Test
    fun decodesReferenceDeckstring() {
        // Generated with python-hearthstone's write_deckstring.
        val deck = Deckstring.decode("AAEBAf0EArQE8KIEAbsCAdINAwA=")
        assertEquals(1, deck.format)
        assertEquals(listOf(637), deck.heroes)
        assertEquals(mapOf(315 to 2, 564 to 1, 1746 to 3, 70000 to 1), deck.cards)
    }

    @Test
    fun decodesStandardDeck() {
        val deck = Deckstring.decode("AAECAR8GxwPJBLsFmQfZB/gIDI0B2AGoArUDhwSSBe0G6wfbCe0JgQr+DAA=")
        assertEquals(2, deck.format)
        assertEquals(listOf(31), deck.heroes)
        assertEquals(30, deck.cards.values.sum())
        assertEquals(2, deck.cards[141])
        assertEquals(1, deck.cards[455])
    }

    @Test
    fun extractsCodeFromCopiedText() {
        val text = """
            ### 내 덱
            # 직업: 마법사
            #
            AAEBAf0EArQE8KIEAbsCAdINAwA=
            #
            # 이 덱을 사용하려면 클립보드에 복사한 후...
        """.trimIndent()
        assertEquals("AAEBAf0EArQE8KIEAbsCAdINAwA=", Deckstring.extractCode(text))
    }

    @Test
    fun roundTrip() {
        val deck = DecodedDeck(2, listOf(7), mapOf(1 to 1, 2 to 2, 300 to 4))
        assertEquals(deck, Deckstring.decode(Deckstring.encode(deck)))
    }
}
