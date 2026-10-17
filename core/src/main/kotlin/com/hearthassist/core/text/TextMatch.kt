package com.hearthassist.core.text

import java.text.Normalizer

object TextMatch {
    /** Lowercases, applies NFKC and keeps only letters and digits (Hangul included). */
    fun normalize(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        var i = 0
        while (i < nfkc.length) {
            val cp = nfkc.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(Character.toLowerCase(cp))
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    fun levenshtein(a: CharSequence, b: CharSequence, maxDistance: Int = Int.MAX_VALUE): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (kotlin.math.abs(a.length - b.length) > maxDistance) return maxDistance + 1
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > maxDistance) return maxDistance + 1
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    /** 1.0 for identical strings, 0.0 for completely different ones. */
    fun ratio(a: String, b: String): Double {
        val max = maxOf(a.length, b.length)
        if (max == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / max
    }

    /**
     * Best similarity of [needle] against any window of [haystack] with a length close to the
     * needle's length. Used when OCR merges a card name with surrounding text.
     */
    fun bestWindowRatio(needle: String, haystack: String): Double {
        if (needle.isEmpty()) return 0.0
        if (haystack.length <= needle.length) return ratio(needle, haystack)
        if (haystack.contains(needle)) return 1.0
        var best = 0.0
        for (len in (needle.length - 1)..(needle.length + 1)) {
            if (len <= 0 || len > haystack.length) continue
            for (start in 0..(haystack.length - len)) {
                val r = ratio(needle, haystack.substring(start, start + len))
                if (r > best) best = r
            }
        }
        return best
    }
}
