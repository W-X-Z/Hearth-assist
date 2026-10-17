package com.hearthassist.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Lifecycle owner for Compose content living in a window outside any Activity. */
private class OverlayLifecycleOwner : SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun create() {
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}

/** A [ComposeView] shown with TYPE_APPLICATION_OVERLAY. */
class OverlayWindow(
    context: Context,
    val params: WindowManager.LayoutParams,
    content: @Composable () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayLifecycleOwner()
    private val view = ComposeView(context).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setContent(content)
    }
    var isShown = false
        private set

    fun show() {
        if (isShown) return
        owner.create()
        wm.addView(view, params)
        isShown = true
    }

    fun update() {
        if (isShown) runCatching { wm.updateViewLayout(view, params) }
    }

    /** Keeps the window fully on screen (after drags, restores and rotation). */
    fun clampToScreen() {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            val dm = view.resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
        // Before the first layout the size is unknown; assume a small bubble.
        val minSize = (48 * view.resources.displayMetrics.density).toInt()
        val w = if (view.width > 0) view.width else minSize
        val h = if (view.height > 0) view.height else minSize
        params.x = params.x.coerceIn(0, (bounds.width() - w).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (bounds.height() - h).coerceAtLeast(0))
        update()
    }

    fun setFocusable(focusable: Boolean) {
        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        update()
    }

    /** Window bounds on screen (for excluding our own text from OCR). */
    fun screenRect(): Rect? {
        if (!isShown || view.width == 0) return null
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
    }

    fun remove() {
        if (!isShown) return
        runCatching { wm.removeView(view) }
        owner.destroy()
        isShown = false
    }

    companion object {
        fun params(
            touchable: Boolean,
            focusable: Boolean = false,
            fullScreen: Boolean = false,
            gravity: Int = Gravity.TOP or Gravity.START,
        ): WindowManager.LayoutParams {
            var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (fullScreen) flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            val size = if (fullScreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT
            return WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                this.gravity = gravity
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                if (!touchable) {
                    // Android 12+ only lets touches pass through overlays at <= 80% opacity.
                    alpha = 0.8f
                }
                if (focusable) {
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                }
            }
        }
    }
}
