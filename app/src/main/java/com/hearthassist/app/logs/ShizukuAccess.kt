package com.hearthassist.app.logs

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.hearthassist.app.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

enum class ShizukuState(val label: String) {
    NOT_INSTALLED("Shizuku 미설치"),
    NOT_RUNNING("Shizuku 실행 안 됨"),
    OUTDATED("Shizuku 버전이 너무 낮음"),
    NO_PERMISSION("권한 필요"),
    READY("사용 가능"),
}

/** Wraps the Shizuku API: availability, permission, and the file user service. */
class ShizukuAccess(private val context: Context) {
    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    @Volatile private var client: FileServiceClient? = null
    @Volatile private var pending: CompletableDeferred<FileServiceClient?>? = null
    private val connectLock = Mutex()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, FileUserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("files")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val c = service?.takeIf { it.pingBinder() }?.let { FileServiceClient(it) }
            client = c
            pending?.complete(c)
            pending = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            client = null
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            client = null
            refresh()
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    fun refresh() {
        _state.value = try {
            when {
                !isInstalled() && !Shizuku.pingBinder() -> ShizukuState.NOT_INSTALLED
                !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
                Shizuku.isPreV11() || Shizuku.getVersion() < 11 -> ShizukuState.OUTDATED
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NO_PERMISSION
                else -> ShizukuState.READY
            }
        } catch (e: RuntimeException) {
            // Shizuku died between calls (e.g. wireless debugging dropped).
            ShizukuState.NOT_RUNNING
        }
    }

    fun requestPermission() {
        runCatching {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    /** Binds (or reuses) the file service. Must not be called on the main thread. */
    suspend fun connect(): FileServiceClient? = connectLock.withLock {
        refresh()
        if (_state.value != ShizukuState.READY) return@withLock null
        client?.takeIf { it.isAlive }?.let { return@withLock it }
        val deferred = CompletableDeferred<FileServiceClient?>()
        pending = deferred
        val bound = runCatching { Shizuku.bindUserService(userServiceArgs, connection) }.isSuccess
        if (!bound) return@withLock null
        withTimeoutOrNull(10_000) { deferred.await() } ?: client?.takeIf { it.isAlive }
    }

    fun disconnect() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        client = null
    }

    private fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 4211
    }
}
