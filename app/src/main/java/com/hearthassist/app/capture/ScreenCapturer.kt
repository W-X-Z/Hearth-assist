package com.hearthassist.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mirrors the screen into an [ImageReader] and hands out bitmaps on request. Frames are only
 * converted when someone asks for one, so an idle capture costs almost nothing.
 */
class ScreenCapturer(
    private val context: Context,
    private val projection: MediaProjection,
    private val onStopped: () -> Unit,
) {
    private val thread = HandlerThread("screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0

    @Volatile private var request: CompletableDeferred<Bitmap?>? = null
    @Volatile private var lastFrame: Bitmap? = null
    @Volatile var running = false
        private set

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            release()
            onStopped()
        }
    }

    fun start() {
        // Required before createVirtualDisplay on Android 14+.
        projection.registerCallback(callback, handler)
        val size = screenSize()
        width = size.x
        height = size.y
        val r = newReader(width, height)
        reader = r
        display = projection.createVirtualDisplay(
            "HearthAssistCapture", width, height, context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
        running = true
    }

    /** The latest screen content, or null if nothing arrived within [timeoutMs]. */
    suspend fun capture(timeoutMs: Long = 600): Bitmap? {
        if (!running) return null
        handler.post { ensureSize() }
        val deferred = CompletableDeferred<Bitmap?>()
        request = deferred
        val frame = withTimeoutOrNull(timeoutMs) { deferred.await() }
        request = null
        return frame ?: lastFrame?.takeIf { !it.isRecycled }?.let { it.copy(it.config ?: Bitmap.Config.ARGB_8888, false) }
    }

    /** Follows rotation: Hearthstone runs in landscape, the phone may have started in portrait. */
    private fun ensureSize() {
        val size = screenSize()
        if (size.x == width && size.y == height) return
        val d = display ?: return
        width = size.x
        height = size.y
        val old = reader
        val r = newReader(width, height)
        reader = r
        d.resize(width, height, context.resources.displayMetrics.densityDpi)
        d.surface = r.surface
        old?.close()
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ ir ->
                val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    val pending = request ?: return@setOnImageAvailableListener
                    val plane = image.planes[0]
                    val pixelStride = plane.pixelStride
                    val rowPadding = plane.rowStride - pixelStride * image.width
                    val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val bitmap = if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
                    lastFrame?.recycle()
                    lastFrame = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    request = null
                    pending.complete(bitmap)
                } finally {
                    image.close()
                }
            }, handler)
        }

    private fun screenSize(): Point {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }

    fun stop() {
        if (!running) return
        runCatching { projection.unregisterCallback(callback) }
        release()
        runCatching { projection.stop() }
    }

    private fun release() {
        running = false
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        request?.complete(null)
        request = null
        thread.quitSafely()
    }
}
