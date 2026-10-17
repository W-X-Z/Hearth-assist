package com.hearthassist.core

import com.hearthassist.core.arena.DraftCardMatcher
import com.hearthassist.core.arena.OcrLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftCardMatcherTest {
    private val matcher by lazy {
        DraftCardMatcher(DraftCardMatcher.candidatesFor(TestCards.db.draftable, "koKR"))
    }

    private fun line(text: String, cx: Int, cy: Int, w: Int = 220, h: Int = 40) =
        OcrLine(text, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)

    @Test
    fun findsThreeOfferedCards() {
        val lines = listOf(
            // Card banners (one OCR mistake in the middle one, spaces in the last one).
            line("화염구", 500, 420),
            line("신비한 화실", 1000, 425),
            line("얼음 창", 1500, 418),
            // Card text and UI noise.
            line("피해를 6 줍니다.", 500, 600, 300),
            line("무작위 적에게 피해를 1씩 3번 줍니다", 1000, 610, 400),
            line("4", 430, 250),
            // Deck list on the right edge, one entry per row.
            line("화염구", 2150, 200),
            line("얼음창", 2150, 260),
            line("내 덱", 2150, 120),
        )
        val found = matcher.match(lines, imageWidth = 2400, imageHeight = 1080)
        assertEquals(listOf("화염구", "신비한 화살", "얼음창"), found.map { it.name })
        assertTrue("CS2_029" in found[0].cardIds)
        assertTrue(found[0].centerX < found[1].centerX && found[1].centerX < found[2].centerX)
    }

    @Test
    fun englishNames() {
        val en = DraftCardMatcher(DraftCardMatcher.candidatesFor(TestCards.db.draftable, "enUS"))
        val found = en.match(
            listOf(line("Fireba11", 500, 400), line("Arcane Missiles", 1000, 400), line("Frostbolt", 1500, 400)),
            imageWidth = 2000, imageHeight = 1000,
        )
        assertEquals(listOf("Fireball", "Arcane Missiles", "Frostbolt"), found.map { it.name })
    }

    @Test
    fun ignoresUnrelatedText() {
        val found = matcher.match(listOf(line("확인", 500, 400), line("12", 900, 400)), 2000, 1000)
        assertTrue(found.isEmpty(), "got $found")
    }
}
