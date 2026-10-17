package com.hearthassist.app.logs

import android.os.Environment

object HearthstoneFiles {
    const val PACKAGE = "com.blizzard.wtcg.hearthstone"

    val baseDir: String
        get() = "${Environment.getExternalStorageDirectory().absolutePath}/Android/data/$PACKAGE/files"

    val logConfig: String get() = "$baseDir/log.config"
    val logsDir: String get() = "$baseDir/Logs"

    val LOG_SECTIONS = listOf("Power", "LoadingScreen", "Arena", "Decks")

    private fun parse(text: String?): LinkedHashMap<String, LinkedHashMap<String, String>> {
        val sections = LinkedHashMap<String, LinkedHashMap<String, String>>()
        var current: LinkedHashMap<String, String>? = null
        text.orEmpty().lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("[") && line.endsWith("]") ->
                    current = sections.getOrPut(line.substring(1, line.length - 1)) { LinkedHashMap() }
                line.contains("=") -> current?.put(line.substringBefore("=").trim(), line.substringAfter("=").trim())
            }
        }
        return sections
    }

    private fun serialize(sections: Map<String, Map<String, String>>): String = buildString {
        for ((name, keys) in sections) {
            append('[').append(name).append("]
")
            for ((k, v) in keys) append(k).append('=').append(v).append('
')
        }
    }

    /**
     * Merges the sections the tracker needs into an existing log.config, keeping anything else
     * (other sections, unrelated keys) as is.
     */
    fun mergeLogConfig(existing: String?): String {
        val sections = parse(existing)
        for (name in LOG_SECTIONS) {
            val s = sections.getOrPut(name) { LinkedHashMap() }
            s["LogLevel"] = "1"
            s["FilePrinting"] = "true"
            s["ConsolePrinting"] = "false"
            s["ScreenPrinting"] = "false"
            s["Verbose"] = if (name == "Power") "true" else (s["Verbose"] ?: "false")
        }
        return serialize(sections)
    }

    /** True when [existing] already enables every log the tracker reads. */
    fun isLogConfigComplete(existing: String?): Boolean =
        existing != null && serialize(parse(existing)) == mergeLogConfig(existing)
}
