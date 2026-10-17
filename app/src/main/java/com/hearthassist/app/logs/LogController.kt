package com.hearthassist.app.logs

import com.hearthassist.app.engine.TrackerEngine
import com.hearthassist.app.settings.LogAccess
import com.hearthassist.app.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class LogState(val label: String) {
    STOPPED("꺼짐"),
    CONNECTING("연결 중"),
    NO_ACCESS("로그 접근 불가"),
    NO_LOGS("로그 파일 없음"),
    WATCHING("로그 읽는 중"),
}

data class LogStatus(
    val state: LogState = LogState.STOPPED,
    val source: String? = null,
    val dir: String? = null,
    val lastActivity: Long = 0,
)

/** Chooses how to reach Hearthstone's files and runs the [LogWatcher]. */
class LogController(
    private val settings: Settings,
    private val shizuku: ShizukuAccess,
    private val engine: TrackerEngine,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(LogStatus())
    val status: StateFlow<LogStatus> = _status.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                _status.value = LogStatus(LogState.CONNECTING)
                val fs = obtainFileSystem()
                if (fs == null) {
                    _status.value = LogStatus(LogState.NO_ACCESS)
                    delay(5_000)
                    continue
                }
                LogWatcher(fs, engine) { state, dir, last ->
                    _status.value = LogStatus(state, fs.label, dir, last)
                }.run()
                delay(1_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _status.value = LogStatus(LogState.STOPPED)
    }

    fun restart() {
        if (job == null) return
        stop()
        start()
    }

    suspend fun obtainFileSystem(): LogFileSystem? = withContext(Dispatchers.IO) {
        when (settings.current.logAccess) {
            LogAccess.OFF -> null
            LogAccess.DIRECT -> direct()
            LogAccess.SHIZUKU -> shizuku.connect()
            LogAccess.AUTO -> direct() ?: shizuku.connect()
        }
    }

    private fun direct(): LogFileSystem? {
        val base = File(HearthstoneFiles.baseDir)
        return if (base.isDirectory && base.listFiles() != null) DirectFileSystem() else null
    }

    /** Enables the logs Hearthstone needs to write. Returns a message for the user. */
    suspend fun installLogConfig(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val fs = obtainFileSystem() ?: error("로그 폴더에 접근할 수 없습니다. Shizuku 권한을 확인하세요.")
            if (fs.list(HearthstoneFiles.baseDir) == null) {
                error("하스스톤 폴더가 없습니다. 하스스톤을 한 번 실행한 뒤 다시 시도하세요.")
            }
            val path = HearthstoneFiles.logConfig
            val existing = if (fs.size(path) >= 0) fs.read(path, 0, 512 * 1024)?.toString(Charsets.UTF_8) else null
            if (HearthstoneFiles.isLogConfigComplete(existing)) return@runCatching "이미 설정되어 있습니다."
            val merged = HearthstoneFiles.mergeLogConfig(existing)
            check(fs.write(path, merged.toByteArray())) { "log.config를 쓸 수 없습니다." }
            val verify = fs.read(path, 0, 512 * 1024)?.toString(Charsets.UTF_8)
            check(HearthstoneFiles.isLogConfigComplete(verify)) { "log.config 확인에 실패했습니다." }
            "log.config를 설정했습니다. 하스스톤을 완전히 종료한 뒤 다시 실행하세요."
        }
    }

    suspend fun isLogConfigInstalled(): Boolean? = withContext(Dispatchers.IO) {
        val fs = obtainFileSystem() ?: return@withContext null
        val path = HearthstoneFiles.logConfig
        if (fs.size(path) < 0) return@withContext false
        HearthstoneFiles.isLogConfigComplete(fs.read(path, 0, 512 * 1024)?.toString(Charsets.UTF_8))
    }
}
