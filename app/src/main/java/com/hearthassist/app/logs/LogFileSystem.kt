package com.hearthassist.app.logs

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/** Minimal file access to Hearthstone's external files directory. Calls block; use Dispatchers.IO. */
interface LogFileSystem {
    val label: String

    /** Entries of [dir], or null when it can't be read. */
    fun list(dir: String): List<FileEntry>?

    /** Size of [path] in bytes, or -1 when missing/unreadable. */
    fun size(path: String): Long

    /** Up to [maxBytes] bytes starting at [offset]; empty at end of file; null on error. */
    fun read(path: String, offset: Long, maxBytes: Int): ByteArray?

    /** Writes [data] to [path], creating parent directories when possible. */
    fun write(path: String, data: ByteArray): Boolean
}
