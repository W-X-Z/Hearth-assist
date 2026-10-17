package com.hearthassist.core

import com.hearthassist.core.cards.CardDb
import java.io.File

object TestCards {
    /** The real bundled database (app/src/main/assets/cards.json.gz). */
    val db: CardDb by lazy {
        val path = System.getProperty("hearthassist.cardsAsset") ?: "../app/src/main/assets/cards.json.gz"
        File(path).inputStream().use { CardDb.parse(it) }
    }
}
