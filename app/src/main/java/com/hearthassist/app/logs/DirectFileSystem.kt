package com.hearthassist.app.logs

import java.io.File
import java.io.RandomAccessFile

/** Plain java.io access. Works on Android 10 and older (and rooted/odd devices). */
class DirectFileSystem : LogFileSystem {
    override val label: String = "직접 접근"

    override fun list(dir: String): List<FileEntry>? = FileOps.list(dir)
    override fun size(path: String): Long = FileOps.size(path)
    override fun read(path: String, offset: Long, maxBytes: Int): ByteArray? = FileOps.read(path, offset, maxBytes)
    override fun write(path: String, data: ByteArray): Boolean = FileOps.write(path, data)
}

/** Shared implementation, also used inside the Shizuku user service process. */
object FileOps {
    fun list(dir: String): List<FileEntry>? {
        val files = File(dir).listFiles() ?: return null
        return files.map { FileEntry(it.name, it.absolutePath, it.isDirectory, it.length(), it.lastModified()) }
    }

    fun size(path: String): Long {
        val f = File(path)
        return if (f.isFile) f.length() else -1
    }

    fun read(path: String, offset: Long, maxBytes: Int): ByteArray? = try {
        RandomAccessFile(path, "r").use { raf ->
            val length = raf.length()
            if (offset >= length) {
                ByteArray(0)
            } else {
                val n = minOf(maxBytes.toLong(), length - offset).toInt()
                val buf = ByteArray(n)
                raf.seek(offset)
                raf.readFully(buf)
                buf
            }
        }
    } catch (e: Exception) {
        null
    }

    fun write(path: String, data: ByteArray): Boolean = try {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeBytes(data)
        // Files created by the shell user must stay readable for Hearthstone itself.
        f.setReadable(true, false)
        f.setWritable(true, false)
        true
    } catch (e: Exception) {
        false
    }
}
