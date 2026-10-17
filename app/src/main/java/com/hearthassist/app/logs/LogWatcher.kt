package com.hearthassist.app.logs

import android.os.SystemClock
import com.hearthassist.app.engine.LogKind
import com.hearthassist.app.engine.TrackerEngine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream

/** Tails Hearthstone's log files through a [LogFileSystem] and feeds the engine. */
class LogWatcher(
    private val fs: LogFileSystem,
    private val engine: TrackerEngine,
    private val onStatus: (LogState, String?, Long) -> Unit,
) {
    private class Tail(val kind: LogKind) {
        var offset = 0L
        var caughtUp = false
        val partial = ByteArrayOutputStream()

        fun reset() {
            offset = 0
            caughtUp = false
            partial.reset()
        }
    }

    private val tails = LogKind.entries.map { Tail(it) }
    private var dir: String? = null
    private var lastDirCheck = 0L
    private var lastActivity = 0L

    /** Runs until cancelled or the file system dies. */
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            if (fs is FileServiceClient && !fs.isAlive) return
            tick()
            delay(POLL_MS)
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (dir == null || now - lastDirCheck > DIR_CHECK_MS) {
            lastDirCheck = now
            val found = locateLogDir()
            if (found != dir) {
                dir = found
                tails.forEach { it.reset() }
                engine.resetAll()
            }
        }
        val d = dir
        if (d == null) {
            onStatus(LogState.NO_LOGS, null, lastActivity)
            return
        }
        for (t in tails) {
            val path = "$d/${t.kind.fileName}"
            val size = fs.size(path)
            if (size < 0) continue
            if (size < t.offset) {
                // Hearthstone restarted and truncated the file.
                t.reset()
                engine.reset(t.kind)
            }
            var budget = MAX_BYTES_PER_TICK
            while (t.offset < size && budget > 0) {
                val chunk = fs.read(path, t.offset, CHUNK) ?: break
                if (chunk.isEmpty()) break
                t.offset += chunk.size
                budget -= chunk.size
                val lines = splitLines(t, chunk)
                if (t.offset >= size) {
                    val live = t.caughtUp
                    t.caughtUp = true
                    engine.feed(t.kind, lines, isLive = live)
                } else {
                    engine.feed(t.kind, lines, isLive = t.caughtUp)
                }
                lastActivity = System.currentTimeMillis()
            }
            if (t.offset >= size) t.caughtUp = true
        }
        engine.publish()
        onStatus(LogState.WATCHING, d, lastActivity)
    }

    private fun splitLines(t: Tail, chunk: ByteArray): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        for (i in chunk.indices) {
            if (chunk[i] == '\n'.code.toByte()) {
                t.partial.write(chunk, start, i - start)
                lines += t.partial.toString(Charsets.UTF_8.name()).trimEnd('\r')
                t.partial.reset()
                start = i + 1
            }
        }
        if (start < chunk.size) t.partial.write(chunk, start, chunk.size - start)
        return lines
    }

    /** Picks the directory with the most recently written logs. */
    private fun locateLogDir(): String? {
        val candidates = ArrayList<Pair<String, Long>>()
        fun consider(path: String, entries: List<FileEntry>?) {
            val logs = entries.orEmpty().filter { e -> !e.isDirectory && LogKind.entries.any { it.fileName == e.name } }
            if (logs.isNotEmpty()) candidates += path to logs.maxOf { it.lastModified }
        }
        val logsDir = HearthstoneFiles.logsDir
        val logsEntries = fs.list(logsDir)
        consider(logsDir, logsEntries)
        logsEntries.orEmpty()
            .filter { it.isDirectory }
            .sortedByDescending { it.lastModified }
            .take(3)
            .forEach { consider(it.path, fs.list(it.path)) }
        consider(HearthstoneFiles.baseDir, fs.list(HearthstoneFiles.baseDir))
        return candidates.maxByOrNull { it.second }?.first
    }

    companion object {
        private const val POLL_MS = 300L
        private const val DIR_CHECK_MS = 5_000L
        private const val CHUNK = 256 * 1024
        private const val MAX_BYTES_PER_TICK = 8 * 1024 * 1024
    }
}
