package com.hearthassist.app.logs

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import kotlin.system.exitProcess

/**
 * Binder protocol between the app and [FileUserService]. Hand-written (instead of AIDL) so the
 * build doesn't need the aidl tool; keep both sides in sync.
 */
object FileServiceProtocol {
    const val DESCRIPTOR = "com.hearthassist.app.IFileService"

    /** Shizuku sends this transaction to stop a user service. */
    const val TRANSACTION_DESTROY = 16777115
    const val TRANSACTION_LIST = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_SIZE = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TRANSACTION_READ = IBinder.FIRST_CALL_TRANSACTION + 3
    const val TRANSACTION_WRITE = IBinder.FIRST_CALL_TRANSACTION + 4
    const val TRANSACTION_PING = IBinder.FIRST_CALL_TRANSACTION + 5

    /** Binder transactions are limited to ~1 MB; stay well below. */
    const val MAX_READ = 256 * 1024
}

/**
 * Runs in a separate process started by Shizuku with shell (adb) privileges, which may read
 * `/sdcard/Android/data/<package>` on Android 11+.
 */
class FileUserService : Binder() {

    init {
        attachInterface(null, FileServiceProtocol.DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            FileServiceProtocol.TRANSACTION_DESTROY -> {
                exitProcess(0)
            }
            FileServiceProtocol.TRANSACTION_PING -> {
                data.enforceInterface(FileServiceProtocol.DESCRIPTOR)
                reply?.writeNoException()
                reply?.writeInt(android.os.Process.myUid())
                return true
            }
            FileServiceProtocol.TRANSACTION_LIST -> {
                data.enforceInterface(FileServiceProtocol.DESCRIPTOR)
                val entries = FileOps.list(data.readString().orEmpty())
                reply?.writeNoException()
                if (entries == null) {
                    reply?.writeInt(-1)
                } else {
                    reply?.writeInt(entries.size)
                    for (e in entries) {
                        reply?.writeString(e.name)
                        reply?.writeString(e.path)
                        reply?.writeInt(if (e.isDirectory) 1 else 0)
                        reply?.writeLong(e.size)
                        reply?.writeLong(e.lastModified)
                    }
                }
                return true
            }
            FileServiceProtocol.TRANSACTION_SIZE -> {
                data.enforceInterface(FileServiceProtocol.DESCRIPTOR)
                val size = FileOps.size(data.readString().orEmpty())
                reply?.writeNoException()
                reply?.writeLong(size)
                return true
            }
            FileServiceProtocol.TRANSACTION_READ -> {
                data.enforceInterface(FileServiceProtocol.DESCRIPTOR)
                val path = data.readString().orEmpty()
                val offset = data.readLong()
                val max = data.readInt().coerceIn(0, FileServiceProtocol.MAX_READ)
                val bytes = FileOps.read(path, offset, max)
                reply?.writeNoException()
                if (bytes == null) {
                    reply?.writeInt(0)
                } else {
                    reply?.writeInt(1)
                    reply?.writeByteArray(bytes)
                }
                return true
            }
            FileServiceProtocol.TRANSACTION_WRITE -> {
                data.enforceInterface(FileServiceProtocol.DESCRIPTOR)
                val path = data.readString().orEmpty()
                val bytes = data.createByteArray() ?: ByteArray(0)
                val ok = FileOps.write(path, bytes)
                reply?.writeNoException()
                reply?.writeInt(if (ok) 1 else 0)
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }
}

/** Client side of [FileUserService]. */
class FileServiceClient(private val binder: IBinder) : LogFileSystem {
    override val label: String = "Shizuku"

    val isAlive: Boolean get() = binder.isBinderAlive

    private inline fun <T> call(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(FileServiceProtocol.DESCRIPTOR)
            write(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
            return read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun ping(): Int = call(FileServiceProtocol.TRANSACTION_PING, {}, { it.readInt() })

    override fun list(dir: String): List<FileEntry>? = runCatching {
        call(FileServiceProtocol.TRANSACTION_LIST, { it.writeString(dir) }) { r ->
            val n = r.readInt()
            if (n < 0) null else List(n) {
                FileEntry(
                    name = r.readString().orEmpty(),
                    path = r.readString().orEmpty(),
                    isDirectory = r.readInt() == 1,
                    size = r.readLong(),
                    lastModified = r.readLong(),
                )
            }
        }
    }.getOrNull()

    override fun size(path: String): Long = runCatching {
        call(FileServiceProtocol.TRANSACTION_SIZE, { it.writeString(path) }) { it.readLong() }
    }.getOrDefault(-1)

    override fun read(path: String, offset: Long, maxBytes: Int): ByteArray? = runCatching {
        call(FileServiceProtocol.TRANSACTION_READ, {
            it.writeString(path)
            it.writeLong(offset)
            it.writeInt(minOf(maxBytes, FileServiceProtocol.MAX_READ))
        }) { r -> if (r.readInt() == 1) r.createByteArray() else null }
    }.getOrNull()

    override fun write(path: String, data: ByteArray): Boolean = runCatching {
        call(FileServiceProtocol.TRANSACTION_WRITE, {
            it.writeString(path)
            it.writeByteArray(data)
        }) { it.readInt() == 1 }
    }.getOrDefault(false)
}
