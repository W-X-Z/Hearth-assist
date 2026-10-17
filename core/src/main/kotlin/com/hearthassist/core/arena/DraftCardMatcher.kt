package com.hearthassist.core.arena

import com.hearthassist.core.cards.Card
import com.hearthassist.core.text.TextMatch
import kotlin.math.abs
import kotlin.math.max

/** One line of recognized text, in image pixel coordinates. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val height: Int get() = bottom - top
}

/** A card name that can be matched, with every card id sharing that (normalized) name. */
data class NameCandidate(
    val normalized: String,
    val displayName: String,
    val cardIds: List<String>,
    /** Normalized rules text, used when the (curved) name banner is unreadable. */
    val normalizedText: String = "",
)

data class DetectedCard(
    val cardIds: List<String>,
    val name: String,
    val similarity: Double,
    val ocrText: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** Found through the card's rules text rather than its name. */
    val byText: Boolean = false,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Finds the cards offered in an Arena draft from OCR output.
 *
 * Every OCR line is fuzzy-matched against card names, and every paragraph (vertically adjacent
 * lines in one column) against card rules text. Then the best set of up to three matches lying
 * side by side (same row, different columns) is chosen, which filters out names that appear in
 * the deck list or inside other cards' text.
 */
class DraftCardMatcher(candidates: List<NameCandidate>) {
    private val candidates = candidates.filter { it.normalized.length >= 2 }
    private val nameIndex = BigramIndex(this.candidates.map { it.normalized })
    private val textIndex = BigramIndex(this.candidates.map { it.normalizedText })

    data class LineMatch(
        val line: OcrLine,
        val candidate: NameCandidate,
        val similarity: Double,
        val byText: Boolean = false,
    )

    fun matchLine(line: OcrLine): List<LineMatch> {
        val s = TextMatch.normalize(line.text)
        if (s.length < 2 || s.all { it.isDigit() }) return emptyList()
        val results = ArrayList<LineMatch>()
        for ((i, shared) in nameIndex.hits(s)) {
            val c = candidates[i]
            val total = nameIndex.size(i)
            if (total == 0 || shared.toDouble() / total < 0.5) continue
            val name = c.normalized
            val sim = if (s.length <= name.length + 2) {
                TextMatch.ratio(s, name)
            } else {
                if (name.length < 3) continue
                TextMatch.bestWindowRatio(name, s) * 0.95
            }
            if (sim >= nameThreshold(name.length)) results += LineMatch(line, c, sim)
        }
        return results.sortedByDescending { it.similarity }
    }

    /** Matches a paragraph against rules text; returns the single best card, if unambiguous. */
    fun matchText(paragraph: OcrLine): LineMatch? {
        val s = TextMatch.normalize(paragraph.text)
        if (s.length < MIN_TEXT_LENGTH) return null
        val lineGrams = bigramCount(s)
        val scored = textIndex.hits(s).toList()
            .filter { (i, shared) ->
                val total = textIndex.size(i)
                total > 0 && (shared.toDouble() / lineGrams >= 0.5 || shared.toDouble() / total >= 0.6)
            }
            .sortedByDescending { (i, shared) -> shared.toDouble() / max(lineGrams, textIndex.size(i)) }
            .take(8)
            .map { (i, _) ->
                val text = candidates[i].normalizedText
                val window = max(TextMatch.bestWindowRatio(text, s), TextMatch.bestWindowRatio(s, text))
                val full = TextMatch.ratio(s, text)
                i to 0.75 * window + 0.25 * full
            }
            .sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        if (best.second < TEXT_THRESHOLD) return null
        val runnerUp = scored.drop(1).firstOrNull { candidates[it.first].normalized != candidates[best.first].normalized }
        if (runnerUp != null && best.second - runnerUp.second < 0.05) return null
        return LineMatch(paragraph, candidates[best.first], best.second * TEXT_WEIGHT, byText = true)
    }

    fun match(lines: List<OcrLine>, imageWidth: Int, imageHeight: Int, maxCards: Int = 3): List<DetectedCard> {
        val nameMatches = lines.flatMap { matchLine(it).take(2) }
        val textMatches = paragraphs(lines, imageWidth).mapNotNull { matchText(it) }
        val all = mergeEvidence(nameMatches, textMatches, imageWidth)
            .sortedByDescending { it.similarity }
            .take(MAX_CONSIDERED)
        if (all.isEmpty()) return emptyList()
        val minDx = imageWidth * 0.12f

        fun compatible(a: LineMatch, b: LineMatch): Boolean {
            if (a.candidate === b.candidate || a.line === b.line) return false
            if (abs(a.line.centerX - b.line.centerX) < minDx) return false
            val maxDy = imageHeight * when {
                a.byText != b.byText -> 0.35f
                a.byText -> 0.2f
                else -> 0.12f
            }
            return abs(a.line.centerY - b.line.centerY) <= maxDy
        }

        var best: List<LineMatch> = emptyList()
        var bestScore = 0.0
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
                byText = it.byText,
            )
        }
    }

    /**
     * A name and the same card's text in one column reinforce each other; text-only matches
     * are kept for cards whose name OCR missed.
     */
    private fun mergeEvidence(names: List<LineMatch>, texts: List<LineMatch>, imageWidth: Int): List<LineMatch> {
        val column = imageWidth * 0.12f
        val usedTexts = HashSet<LineMatch>()
        val boosted = names.map { n ->
            val support = texts.firstOrNull { it.candidate === n.candidate && abs(it.line.centerX - n.line.centerX) < column }
            if (support != null) {
                usedTexts += support
                n.copy(similarity = minOf(1.2, n.similarity + 0.15))
            } else {
                n
            }
        }
        return boosted + texts.filter { it !in usedTexts }
    }

    private fun nameThreshold(nameLength: Int): Double = when {
        nameLength <= 2 -> 1.0
        nameLength == 3 -> 0.66
        nameLength == 4 -> 0.74
        nameLength == 5 -> 0.79
        else -> 0.72
    }

    /** Bigram -> candidate indices, for cheap pre-filtering before edit distances. */
    private class BigramIndex(strings: List<String>) {
        private val index = HashMap<String, IntArray>()
        private val sizes = IntArray(strings.size)

        init {
            val tmp = HashMap<String, MutableList<Int>>()
            strings.forEachIndexed { i, s ->
                val grams = bigrams(s)
                sizes[i] = grams.size
                for (g in grams) tmp.getOrPut(g) { mutableListOf() }.add(i)
            }
            tmp.forEach { (k, v) -> index[k] = v.toIntArray() }
        }

        fun size(i: Int): Int = sizes[i]

        /** Candidate index -> number of distinct bigrams shared with [s]. */
        fun hits(s: String): Map<Int, Int> {
            val hits = HashMap<Int, Int>()
            for (g in bigrams(s)) index[g]?.forEach { hits[it] = (hits[it] ?: 0) + 1 }
            return hits
        }
    }

    companion object {
        private const val MAX_CONSIDERED = 40
        private const val MIN_TEXT_LENGTH = 6
        private const val TEXT_THRESHOLD = 0.8
        private const val TEXT_WEIGHT = 0.9

        private fun bigrams(s: String): Set<String> {
            if (s.length < 2) return emptySet()
            val set = HashSet<String>(s.length)
            for (i in 0 until s.length - 1) set += s.substring(i, i + 2)
            return set
        }

        private fun bigramCount(s: String): Int = bigrams(s).size.coerceAtLeast(1)

        /** Joins vertically adjacent lines of the same column into paragraphs. */
        fun paragraphs(lines: List<OcrLine>, imageWidth: Int): List<OcrLine> {
            val groups = ArrayList<MutableList<OcrLine>>()
            for (line in lines.sortedBy { it.top }) {
                val group = groups.lastOrNull { g ->
                    val last = g.last()
                    val gap = line.top - last.bottom
                    val h = max(last.height, line.height).coerceAtLeast(1)
                    abs(line.centerX - last.centerX) < imageWidth * 0.06f && gap > -h / 2 && gap < h * 1.2
                }
                if (group != null) group += line else groups += mutableListOf(line)
            }
            return groups.map { g ->
                OcrLine(
                    text = g.joinToString(" ") { it.text },
                    left = g.minOf { it.left }, top = g.minOf { it.top },
                    right = g.maxOf { it.right }, bottom = g.maxOf { it.bottom },
                )
            }
        }

        /**
         * Builds candidates from [cards] using names in [locale]. When [preferredIds] is not
         * empty (cards with arena stats = the current arena pool), only those are used.
         */
        fun candidatesFor(cards: List<Card>, locale: String, preferredIds: Set<String> = emptySet()): List<NameCandidate> {
            val pool = if (preferredIds.isEmpty()) cards else cards.filter { it.id in preferredIds }.ifEmpty { cards }
            return pool.groupBy { TextMatch.normalize(it.name(locale)) }
                .filterKeys { it.length >= 2 }
                .map { (normalized, group) ->
                    NameCandidate(
                        normalized = normalized,
                        displayName = group.first().name(locale),
                        cardIds = group.map { it.id },
                        normalizedText = TextMatch.normalize(group.first().plainText(locale)),
                    )
                }
        }
    }
}
