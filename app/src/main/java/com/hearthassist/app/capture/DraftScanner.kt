package com.hearthassist.app.capture

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.hearthassist.core.arena.ArenaScorer
import com.hearthassist.core.arena.CardScore
import com.hearthassist.core.arena.DetectedCard
import com.hearthassist.core.arena.DraftCardMatcher
import com.hearthassist.core.arena.OcrLine
import com.hearthassist.core.cards.CardDb
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ScoredDetection(
    val detected: DetectedCard,
    val cardId: String,
    val name: String,
    val cost: Int?,
    val score: CardScore?,
)

data class ScanResult(
    val timestamp: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val cards: List<ScoredDetection>,
    val linesRead: Int,
) {
    val best: ScoredDetection? get() = cards.filter { it.score?.score != null }.maxByOrNull { it.score!!.score!! }
}

/** OCRs a screenshot (ML Kit, Korean + Latin) and finds the offered draft cards in it. */
class DraftScanner {
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private var matcher: DraftCardMatcher? = null
    private var matcherKey: Triple<Any, String, Int>? = null

    private fun matcherFor(db: CardDb, locale: String, scorer: ArenaScorer?): DraftCardMatcher {
        val pool = scorer?.cardIds ?: emptySet()
        val key = Triple<Any, String, Int>(db, locale, pool.size)
        matcher?.takeIf { key == matcherKey }?.let { return it }
        val m = DraftCardMatcher(DraftCardMatcher.candidatesFor(db.draftable, locale, pool))
        matcher = m
        matcherKey = key
        return m
    }

    /** [exclude]: screen areas covered by our own overlay, ignored by the matcher. */
    suspend fun scan(bitmap: Bitmap, db: CardDb, locale: String, scorer: ArenaScorer?, exclude: List<Rect>): ScanResult {
        val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            if (exclude.any { it.contains(box.centerX(), box.centerY()) }) return@mapNotNull null
            OcrLine(line.text, box.left, box.top, box.right, box.bottom)
        }
        val detected = matcherFor(db, locale, scorer).match(lines, bitmap.width, bitmap.height)
        val scored = detected.map { d ->
            // Several printings can share a name; prefer the one with the most arena data.
            val id = d.cardIds.maxByOrNull { scorer?.score(it)?.drawnGames ?: -1L } ?: d.cardIds.first()
            val card = db[id]
            ScoredDetection(d, id, card?.name(locale) ?: d.name, card?.cost, scorer?.score(id))
        }
        return ScanResult(System.currentTimeMillis(), bitmap.width, bitmap.height, scored, lines.size)
    }

    fun close() {
        recognizer.close()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
