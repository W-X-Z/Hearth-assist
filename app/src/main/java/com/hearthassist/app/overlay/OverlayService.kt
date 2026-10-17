package com.hearthassist.app.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.hearthassist.app.AppGraph
import com.hearthassist.app.R
import com.hearthassist.app.capture.DraftScanner
import com.hearthassist.app.capture.ScanResult
import com.hearthassist.app.capture.ScreenCapturer
import com.hearthassist.app.engine.GameEvent
import com.hearthassist.app.logs.LogState
import com.hearthassist.app.ui.CaptureRequestActivity
import com.hearthassist.app.ui.MainActivity
import com.hearthassist.core.log.LoadingScreenParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service hosting the overlay windows: the draggable HUD (deck tracker + arena
 * panel), the touch-through score badges, and the card search window.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ui = OverlayUiState()

    private var hud: OverlayWindow? = null
    private var badges: OverlayWindow? = null
    private var search: OverlayWindow? = null
    private val hudDrag = WindowDragController({ hud }) { x, y ->
        AppGraph.settings.update { it.copy(hudX = x, hudY = y) }
    }

    private var capturer: ScreenCapturer? = null
    private var scanner: DraftScanner? = null
    private var scanJob: Job? = null
    private var prefetchJob: Job? = null
    private var misses = 0

    private val actions = object : HudActions {
        override fun setExpanded(expanded: Boolean) {
            ui.expanded.value = expanded
        }

        override fun selectTab(tab: HudTab) {
            ui.tab.value = tab
        }

        override fun startCapture() {
            val intent = Intent(this@OverlayService, CaptureRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        override fun stopCapture() {
            stopCapturing()
        }

        override fun scanNow() {
            scope.launch { scanOnce(manual = true) }
        }

        override fun setAutoScan(enabled: Boolean) {
            AppGraph.settings.update { it.copy(autoScan = enabled) }
        }

        override fun setManualClass(cardClass: String?) {
            AppGraph.settings.update { it.copy(manualClass = cardClass) }
            ui.scan.value = null
            if (capturer != null) scope.launch { scanOnce(manual = false) }
        }

        override fun openSearch() {
            showSearch()
        }

        override fun closeSearch() {
            hideSearch()
        }

        override fun stopService() {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation: keep the HUD on screen.
        hud?.clampToScreen()
    }

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        createChannel()
        startInForeground(withProjection = false)
        _running.value = true
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        showWindows()
        AppGraph.logs.start()
        scope.launch {
            AppGraph.engine.events.collect { onGameEvent(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_PROJECTION -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                onProjectionResult(code, data)
            }
            ACTION_SHOW_ARENA -> {
                ui.tab.value = HudTab.ARENA
                ui.expanded.value = true
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        stopCapturing()
        scanner?.close()
        scanner = null
        hud?.remove()
        badges?.remove()
        search?.remove()
        AppGraph.logs.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun showWindows() {
        val dm = resources.displayMetrics
        badges = OverlayWindow(this, OverlayWindow.params(touchable = false, fullScreen = true)) {
            BadgesContent(ui)
        }.also { it.show() }
        val s = AppGraph.settings.current
        hud = OverlayWindow(this, OverlayWindow.params(touchable = true).apply {
            x = s.hudX.takeIf { it >= 0 } ?: (12 * dm.density).toInt()
            y = s.hudY.takeIf { it >= 0 } ?: (80 * dm.density).toInt()
        }) {
            HudContent(ui, actions, hudDrag)
        }.also {
            it.show()
            it.clampToScreen()
        }
    }

    private fun showSearch() {
        if (search?.isShown == true) return
        search = OverlayWindow(
            this,
            OverlayWindow.params(touchable = true, focusable = true, gravity = Gravity.CENTER),
        ) {
            SearchContent(actions)
        }.also { it.show() }
        ui.searchOpen.value = true
    }

    private fun hideSearch() {
        search?.remove()
        search = null
        ui.searchOpen.value = false
    }

    private fun onGameEvent(event: GameEvent) {
        val s = AppGraph.settings.current
        when (event) {
            is GameEvent.Started -> if (s.autoOpenTracker) {
                ui.tab.value = HudTab.DECK
                ui.expanded.value = true
                ui.scan.value = null
            }
            is GameEvent.Ended -> Unit
            is GameEvent.SceneChanged -> when (event.scene) {
                LoadingScreenParser.MODE_DRAFT -> {
                    ui.tab.value = HudTab.ARENA
                    if (s.autoOpenTracker) ui.expanded.value = true
                }
                else -> ui.scan.value = null
            }
        }
    }

    // --- Screen capture -------------------------------------------------------------------

    private fun onProjectionResult(code: Int, data: Intent?) {
        if (code != Activity.RESULT_OK || data == null) {
            ui.message.value = "화면 인식 권한이 거부되었습니다."
            return
        }
        stopCapturing()
        // The media projection FGS type must be active before getMediaProjection().
        startInForeground(withProjection = true)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching { mpm.getMediaProjection(code, data) }.getOrNull()
        if (projection == null) {
            ui.message.value = "화면 인식을 시작할 수 없습니다."
            startInForeground(withProjection = false)
            return
        }
        val c = ScreenCapturer(this, projection) { scope.launch { onCaptureStopped() } }
        runCatching { c.start() }.onFailure {
            ui.message.value = "화면 인식 오류: ${it.message}"
            c.stop()
            startInForeground(withProjection = false)
            return
        }
        capturer = c
        if (scanner == null) scanner = DraftScanner()
        ui.capturing.value = true
        ui.message.value = null
        ui.tab.value = HudTab.ARENA
        ui.expanded.value = true
        startScanLoop()
    }

    private fun onCaptureStopped() {
        capturer = null
        scanJob?.cancel()
        scanJob = null
        ui.capturing.value = false
        ui.scan.value = null
        if (_running.value) startInForeground(withProjection = false)
    }

    private fun stopCapturing() {
        scanJob?.cancel()
        scanJob = null
        val c = capturer ?: return
        c.stop()
        capturer = null
        ui.capturing.value = false
        ui.scan.value = null
        if (_running.value) startInForeground(withProjection = false)
    }

    private fun startScanLoop() {
        scanJob?.cancel()
        misses = 0
        scanJob = scope.launch {
            while (isActive) {
                val s = AppGraph.settings.current
                if (s.autoScan && shouldAutoScan()) {
                    scanOnce(manual = false)
                } else if (!shouldAutoScan()) {
                    ui.scan.value = null
                }
                delay(s.scanIntervalMs.coerceAtLeast(800))
            }
        }
    }

    /** With logs we know when the draft screen is open; without them, always scan. */
    private fun shouldAutoScan(): Boolean {
        val logsOk = AppGraph.logs.status.value.state == LogState.WATCHING
        val scene = AppGraph.engine.scene.value
        return !logsOk || scene == null || scene == LoadingScreenParser.MODE_DRAFT
    }

    private suspend fun scanOnce(manual: Boolean): ScanResult? {
        val cap = capturer ?: run {
            if (manual) ui.message.value = "먼저 '화면 인식 시작'을 눌러 주세요."
            return null
        }
        val sc = scanner ?: return null
        if (ui.scanning.value) return null
        ui.scanning.value = true
        try {
            val bitmap = cap.capture() ?: run {
                if (manual) ui.message.value = "화면을 가져오지 못했습니다."
                return null
            }
            // Never wait for downloads here: use what is cached and fetch the rest in background.
            val heroClass = AppGraph.effectiveClass()
            val scorer = AppGraph.stats.cached(heroClass)
            if (prefetchJob?.isActive != true) {
                prefetchJob = AppGraph.scope.launch { AppGraph.stats.scorer(heroClass) }
            }
            val exclude = listOfNotNull(hud?.screenRect(), search?.screenRect())
            val s = AppGraph.settings.current
            val result = withContext(Dispatchers.Default) {
                try {
                    sc.scan(bitmap, AppGraph.cards.db.value, s.gameLocale, scorer, exclude)
                } finally {
                    bitmap.recycle()
                }
            }
            if (result.cards.isNotEmpty()) {
                misses = 0
                ui.scan.value = result
                ui.message.value = null
            } else {
                misses++
                if (manual) ui.scan.value = result
                else if (misses >= 3) ui.scan.value = null
            }
            return result
        } catch (e: Exception) {
            if (manual) ui.message.value = "인식 실패: ${e.message}"
            return null
        } finally {
            ui.scanning.value = false
        }
    }

    // --- Foreground notification ------------------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    private fun startInForeground(withProjection: Boolean) {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (withProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIFICATION_ID, n, type)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && withProjection) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        const val ACTION_START = "com.hearthassist.app.START"
        const val ACTION_STOP = "com.hearthassist.app.STOP"
        const val ACTION_PROJECTION = "com.hearthassist.app.PROJECTION"
        const val ACTION_SHOW_ARENA = "com.hearthassist.app.SHOW_ARENA"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 42

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP))
        }

        fun sendProjection(context: Context, resultCode: Int, data: Intent?) {
            val intent = Intent(context, OverlayService::class.java)
                .setAction(ACTION_PROJECTION)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startService(intent)
        }
    }
}
