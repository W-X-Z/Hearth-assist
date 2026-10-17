package com.hearthassist.core.arena

import com.hearthassist.core.cards.Card
import com.hearthassist.core.text.TextMatch
import kotlin.math.abs

/** One line of recognized text, in image pixel coordinates. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** A card name that can be matched, with every card id sharing that (normalized) name. */
data class NameCandidate(val normalized: String, val displayName: String, val cardIds: List<String>)

data class DetectedCard(
    val cardIds: List<String>,
    val name: String,
    val similarity: Double,
    val ocrText: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Finds the cards offered in an Arena draft from OCR output. Each OCR line is fuzzy-matched
 * against card names; then the best set of up to three matches lying side by side (same row,
 * different columns) is chosen, which filters out names in the deck list or card text.
 */
class DraftCardMatcher(candidates: List<NameCandidate>) {
    private val candidates = candidates.filter { it.normalized.length >= 2 }
    private val bigramIndex: Map<String, IntArray>
    private val bigramCounts: IntArray

    init {
        val index = HashMap<String, MutableList<Int>>()
        bigramCounts = IntArray(this.candidates.size)
        this.candidates.forEachIndexed { i, c ->
            val grams = bigrams(c.normalized)
            bigramCounts[i] = grams.size
            for (g in grams) index.getOrPut(g) { mutableListOf() }.add(i)
        }
        bigramIndex = index.mapValues { it.value.toIntArray() }
    }

    data class LineMatch(val line: OcrLine, val candidate: NameCandidate, val similarity: Double)

    fun matchLine(line: OcrLine): List<LineMatch> {
        val s = TextMatch.normalize(line.text)
        if (s.length < 2 || s.all { it.isDigit() }) return emptyList()
        val hits = HashMap<Int, Int>()
        for (g in bigrams(s)) {
            bigramIndex[g]?.forEach { hits[it] = (hits[it] ?: 0) + 1 }
        }
        val results = ArrayList<LineMatch>()
        for ((i, shared) in hits) {
            val c = candidates[i]
            val total = bigramCounts[i]
            if (total == 0 || shared.toDouble() / total < 0.5) continue
            val name = c.normalized
            val sim = if (s.length <= name.length + 2) {
                TextMatch.ratio(s, name)
            } else {
                if (name.length < 3) continue
                TextMatch.bestWindowRatio(name, s) * 0.95
            }
            if (sim >= threshold(name.length)) results += LineMatch(line, c, sim)
        }
        return results.sortedByDescending { it.similarity }
    }

    fun match(lines: List<OcrLine>, imageWidth: Int, imageHeight: Int, maxCards: Int = 3): List<DetectedCard> {
        val all = lines.flatMap { matchLine(it).take(2) }
            .sortedByDescending { it.similarity }
            .take(MAX_CONSIDERED)
        if (all.isEmpty()) return emptyList()
        val minDx = imageWidth * 0.12f
        val maxDy = imageHeight * 0.12f

        var best: List<LineMatch> = emptyList()
        var bestScore = 0.0
        fun compatible(a: LineMatch, b: LineMatch) =
            a.candidate !== b.candidate && a.line !== b.line &&
                abs(a.line.centerX - b.line.centerX) >= minDx &&
                abs(a.line.centerY - b.line.centerY) <= maxDy

        for (i in all.indices) {
            val a = all[i]
            if (a.similarity > bestScore) { best = listOf(a); bestScore = a.similarity }
            if (maxCards < 2) continue
            for (j in i + 1 until all.size) {
                val b = all[j]
                if (!compatible(a, b)) continue
                val pair = a.similarity + b.similarity
                if (pair > bestScore) { best = listOf(a, b); bestScore = pair }
                if (maxCards < 3) continue
                for (k in j + 1 until all.size) {
                    val c = all[k]
                    if (!compatible(a, c) || !compatible(b, c)) continue
                    val triple = pair + c.similarity
                    if (triple > bestScore) { best = listOf(a, b, c); bestScore = triple }
                }
            }
        }
        return best.sortedBy { it.line.centerX }.map {
            DetectedCard(
                cardIds = it.candidate.cardIds,
                name = it.candidate.displayName,
                similarity = it.similarity,
                ocrText = it.line.text,
                left = it.line.left, top = it.line.top, right = it.line.right, bottom = it.line.bottom,
            )
        }
    }

    private fun threshold(nameLength: Int): Double = when {
        nameLength <= 2 -> 1.0
        nameLength == 3 -> 0.66
        nameLength == 4 -> 0.74
        nameLength == 5 -> 0.79
        else -> 0.72
    }

    companion object {
        private const val MAX_CONSIDERED = 40

        private fun bigrams(s: String): Set<String> {
            if (s.length < 2) return emptySet()
            val set = HashSet<String>(s.length)
            for (i in 0 until s.length - 1) set += s.substring(i, i + 2)
            return set
        }

        /**
         * Builds candidates from [cards] using names in [locale]. When [preferredIds] is not
         * empty (cards with arena stats = the current arena pool), only those are used.
         */
        fun candidatesFor(cards: List<Card>, locale: String, preferredIds: Set<String> = emptySet()): List<NameCandidate> {
            val pool = if (preferredIds.isEmpty()) cards else cards.filter { it.id in preferredIds }.ifEmpty { cards }
            return pool.groupBy { TextMatch.normalize(it.name(locale)) }
                .filterKeys { it.length >= 2 }
                .map { (normalized, group) -> NameCandidate(normalized, group.first().name(locale), group.map { it.id }) }
        }
    }
}
