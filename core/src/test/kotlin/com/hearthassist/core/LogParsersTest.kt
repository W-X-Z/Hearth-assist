package com.hearthassist.core

import com.hearthassist.core.log.ArenaLogParser
import com.hearthassist.core.log.DecksLogParser
import com.hearthassist.core.log.LoadingScreenParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogParsersTest {
    @Test
    fun arenaDraft() {
        val p = ArenaLogParser()
        listOf(
            "D 10:00:00.0000000 DraftManager.OnChosen(): hero=HERO_08 premium=STANDARD",
            "D 10:00:01.0000000 Client chooses: 화염구 (CS2_029)",
            "D 10:00:02.0000000 Client chooses: Arcane Missiles (EX1_277)",
            "D 10:00:03.0000000 SetDraftMode - DRAFTING",
        ).forEach(p::feed)
        assertEquals("HERO_08", p.state.heroCardId)
        assertEquals(listOf("CS2_029", "EX1_277"), p.state.picks)
        assertEquals("DRAFTING", p.state.mode)

        // Re-entering the arena screen prints the whole deck again.
        listOf(
            "D 11:00:00.0000000 DraftManager.OnChoicesAndContents - Draft Deck ID: 4242, Hero Card = HERO_08",
            "D 11:00:00.0000000 DraftManager.OnChoicesAndContents - Draft deck contains card CS2_029",
            "D 11:00:00.0000000 DraftManager.OnChoicesAndContents - Draft deck contains card EX1_277",
            "D 11:00:00.0000000 DraftManager.OnChoicesAndContents - Draft deck contains card CS2_032",
        ).forEach(p::feed)
        assertEquals(4242L, p.state.deckId)
        assertEquals(listOf("CS2_029", "EX1_277", "CS2_032"), p.state.picks)

        // A new run with another hero starts from an empty deck.
        p.feed("D 12:00:00.0000000 Client chooses: Garrosh Hellscream (HERO_01)")
        assertEquals("HERO_01", p.state.heroCardId)
        assertEquals(emptyList(), p.state.picks)
    }

    @Test
    fun loadingScreen() {
        val p = LoadingScreenParser()
        assertNull(p.currentMode)
        p.feed("D 10:00:00.0000000 LoadingScreen.OnSceneLoaded() - prevMode=HUB currMode=DRAFT")
        assertEquals("DRAFT", p.currentMode)
        p.feed("D 10:05:00.0000000 LoadingScreen.OnScenePreUnload() - prevMode=DRAFT nextMode=GAMEPLAY loadingScreenDisplayed=False")
        assertEquals("GAMEPLAY", p.currentMode)
    }

    @Test
    fun decksLog() {
        val p = DecksLogParser { TestCards.db }
        listOf(
            "I 10:00:00.0000000 Finding Game With Deck:",
            "I 10:00:00.0000000 ### 내 마법사 덱",
            "I 10:00:00.0000000 # Deck ID: 1234",
            "I 10:00:00.0000000 AAEBAf0EArQE8KIEAbsCAdINAwA=",
        ).forEach(p::feed)
        val deck = p.lastDeck!!
        assertEquals("내 마법사 덱", deck.name)
        assertEquals(2, deck.cards["CS2_029"])
        assertEquals(1, deck.cards["EX1_277"])
        assertEquals("HERO_08", deck.heroCardId)
    }
}
