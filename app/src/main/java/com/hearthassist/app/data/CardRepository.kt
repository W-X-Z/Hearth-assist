package com.hearthassist.app.data

import android.content.Context
import com.hearthassist.core.cards.CardDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPOutputStream

data class CardDbInfo(
    val loaded: Boolean = false,
    val source: String = "",
    val count: Int = 0,
    val updatedAt: Long = 0,
    val error: String? = null,
)

/**
 * Card names and stats. Starts from the database bundled in the APK and can be refreshed from
 * HearthstoneJSON when a new patch adds cards.
 */
class CardRepository(private val context: Context) {
    private val _db = MutableStateFlow(CardDb.EMPTY)
    val db: StateFlow<CardDb> = _db.asStateFlow()
    private val _info = MutableStateFlow(CardDbInfo())
    val info: StateFlow<CardDbInfo> = _info.asStateFlow()

    private val downloaded: File get() = File(context.filesDir, "cards.json.gz")

    suspend fun load() = withContext(Dispatchers.IO) {
        if (_info.value.loaded) return@withContext
        val file = downloaded
        val result = runCatching {
            if (file.exists()) {
                file.inputStream().use { CardDb.parse(it) } to "HearthstoneJSON"
            } else {
                context.assets.open(ASSET).use { CardDb.parse(it) } to "내장 데이터"
            }
        }.recoverCatching {
            // A broken download should never leave the app without cards.
            file.delete()
            context.assets.open(ASSET).use { CardDb.parse(it) } to "내장 데이터"
        }
        result.onSuccess { (db, source) ->
            _db.value = db
            _info.value = CardDbInfo(true, source, db.size, if (file.exists()) file.lastModified() else 0)
        }.onFailure {
            _info.value = CardDbInfo(false, "", 0, 0, it.message)
        }
    }

    /** Downloads the latest cards for [locale] (+ English names) from HearthstoneJSON. */
    suspend fun update(locale: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val locales = linkedSetOf("enUS", locale)
            var merged: CardDb? = null
            for (loc in locales) {
                val bytes = Http.get("https://api.hearthstonejson.com/v1/latest/$loc/cards.json", timeoutMs = 60_000)
                val db = CardDb.parse(bytes.inputStream(), localeHint = loc)
                merged = merged?.mergeNames(db) ?: db
            }
            val db = merged!!
            require(db.size > 1000) { "카드 데이터가 너무 적습니다 (${db.size})" }
            val tmp = File(context.filesDir, "cards.json.gz.tmp")
            GZIPOutputStream(tmp.outputStream()).use { CardDb.write(db, it) }
            if (!tmp.renameTo(downloaded)) {
                downloaded.delete()
                tmp.renameTo(downloaded)
            }
            _db.value = db
            _info.value = CardDbInfo(true, "HearthstoneJSON", db.size, downloaded.lastModified())
            db.size
        }
    }

    companion object {
        private const val ASSET = "cards.json.gz"
    }
}
