package com.hearthassist.core

import com.hearthassist.core.cards.CardDb
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardDbTest {
    @Test
    fun loadsBundledAsset() {
        val db = TestCards.db
        assertTrue(db.size > 10_000)
        val fireball = db["CS2_029"]!!
        assertEquals("화염구", fireball.name("koKR"))
        assertEquals("Fireball", fireball.name("enUS"))
        assertEquals(4, fireball.cost)
        assertEquals("MAGE", fireball.cardClass)
        assertEquals(315, fireball.dbfId)
        assertEquals("CS2_029", db.byDbf(315)!!.id)
        assertTrue(db.draftable.isNotEmpty())
        assertTrue(db.draftable.none { it.set == "HERO_SKINS" })
    }

    @Test
    fun parsesHearthstoneJsonSingleLocale() {
        val json = """
            [{"id":"CS2_029","dbfId":315,"name":"화염구","text":"피해를 6 줍니다.","type":"SPELL","cardClass":"MAGE","cost":4,
              "rarity":"FREE","set":"LEGACY","collectible":true,"mechanics":["X"]},
             {"id":"X_ench","dbfId":1,"name":"버프","type":"ENCHANTMENT"}]
        """.trimIndent()
        val db = CardDb.parse(json, localeHint = "koKR")
        assertEquals(1, db.size)
        assertEquals("화염구", db["CS2_029"]!!.name("koKR"))
        // Falls back to whatever locale exists.
        assertEquals("화염구", db["CS2_029"]!!.name("enUS"))
    }

    @Test
    fun writeRoundTrip() {
        val en = CardDb.parse("""[{"id":"A","dbfId":1,"name":"Alpha","type":"MINION","cost":1,"collectible":true}]""", "enUS")
        val ko = CardDb.parse("""[{"id":"A","dbfId":1,"name":"알파","type":"MINION","cost":1}]""", "koKR")
        val merged = en.mergeNames(ko)
        val out = ByteArrayOutputStream()
        CardDb.write(merged, out)
        val back = CardDb.parse(out.toString(Charsets.UTF_8))
        assertEquals("Alpha", back["A"]!!.name("enUS"))
        assertEquals("알파", back["A"]!!.name("koKR"))
        assertTrue(back["A"]!!.collectible)
    }
}
