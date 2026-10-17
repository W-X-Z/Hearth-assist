package com.hearthassist.core.log

object LogLines {
    private val PREFIX = Regex("^[DIWE] \\d{1,2}:\\d{2}:\\d{2}(?:\\.\\d+)? ")

    /** Strips the `D 12:34:56.1234567 ` prefix Hearthstone puts on every log line. */
    fun content(line: String): String {
        val match = PREFIX.find(line) ?: return line.trimEnd('\r')
        return line.substring(match.range.last + 1).trimEnd('\r')
    }
}
