package com.hearthassist.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mirrors the screen into an [ImageReader] and hands out bitmaps on request. The newest frame
 * is kept as an [Image] and only converted when someone asks, so an idle capture costs almost
 * nothing and a static screen still yields a fresh bitmap.
 *
 * Everything touching the reader, the display and the held image runs on [handler]'s thread.
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
    private var heldImage: Image? = null
    private var width = 0
    private var height = 0
    private var request: CompletableDeferred<Bitmap?>? = null

    @Volatile private var released = false
    @Volatile var running = false
        private set

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Called on [handler] (the user stopped sharing, or another app took over).
            teardown(stopProjection = false)
            onStopped()
        }
    }

    /** Must be called once, right after the projection was obtained. */
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
    suspend fun capture(timeoutMs: Long = 800): Bitmap? {
        if (!running) return null
        val deferred = CompletableDeferred<Bitmap?>()
        handler.post {
            if (!running) {
                deferred.complete(null)
                return@post
            }
            ensureSize()
            request?.complete(null)
            request = deferred
            heldImage?.let { deliver(it) }
        }
        val frame = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (frame == null) handler.post { if (request === deferred) request = null }
        return frame
    }

    private fun deliver(image: Image) {
        val pending = request ?: return
        request = null
        pending.complete(runCatching { toBitmap(image) }.getOrNull())
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer.rewind())
        if (rowPadding == 0) return padded
        return Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
    }

    /** Follows rotation: Hearthstone runs in landscape, the phone may have started in portrait. */
    private fun ensureSize() {
        val size = screenSize()
        if (size.x == width && size.y == height) return
        val d = display ?: return
        width = size.x
        height = size.y
        heldImage?.close()
        heldImage = null
        val old = reader
        val r = newReader(width, height)
        reader = r
        d.resize(width, height, context.resources.displayMetrics.densityDpi)
        d.surface = r.surface
        old?.close()
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        // One image is held between frames, so keep room for acquireLatestImage().
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ ir ->
                if (!running || ir !== reader) return@setOnImageAvailableListener
                try {
                    val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                    heldImage?.close()
                    heldImage = image
                    deliver(image)
                } catch (e: IllegalStateException) {
                    // Reader closed or out of buffers; the next frame will retry.
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

    /** Stops capturing and releases the projection. Safe to call more than once. */
    fun stop() {
        running = false
        handler.post { teardown(stopProjection = true) }
    }

    private fun teardown(stopProjection: Boolean) {
        if (released) return
        released = true
        running = false
        runCatching { projection.unregisterCallback(callback) }
        runCatching { display?.release() }
        display = null
        runCatching { heldImage?.close() }
        heldImage = null
        runCatching { reader?.close() }
        reader = null
        request?.complete(null)
        request = null
        if (stopProjection) runCatching { projection.stop() }
        thread.quitSafely()
    }
}
