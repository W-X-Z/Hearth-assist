package com.hearthassist.app.overlay

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import com.hearthassist.app.capture.ScanResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

enum class HudTab(val label: String) { DECK("내 덱"), OPPONENT("상대"), ARENA("투기장") }

class OverlayUiState {
    val expanded = MutableStateFlow(false)
    val tab = MutableStateFlow(HudTab.DECK)
    val capturing = MutableStateFlow(false)
    val scanning = MutableStateFlow(false)
    val scan = MutableStateFlow<ScanResult?>(null)
    val message = MutableStateFlow<String?>(null)
    val searchOpen = MutableStateFlow(false)
}

interface HudActions {
    fun setExpanded(expanded: Boolean)
    fun selectTab(tab: HudTab)
    fun startCapture()
    fun stopCapture()
    fun scanNow()
    fun setAutoScan(enabled: Boolean)
    fun setManualClass(cardClass: String?)
    fun openSearch()
    fun closeSearch()
    fun stopService()
}

/** Moves an overlay window with the finger, using raw screen coordinates (no jitter). */
class WindowDragController(
    private val window: () -> OverlayWindow?,
    private val onMoved: (x: Int, y: Int) -> Unit = { _, _ -> },
) {
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    fun begin(rawX: Float, rawY: Float) {
        val p: WindowManager.LayoutParams = window()?.params ?: return
        downX = rawX
        downY = rawY
        startX = p.x
        startY = p.y
        moved = false
    }

    fun drag(rawX: Float, rawY: Float) {
        val w = window() ?: return
        val dx = rawX - downX
        val dy = rawY - downY
        if (!moved && abs(dx) < SLOP && abs(dy) < SLOP) return
        moved = true
        w.params.x = (startX + dx).roundToInt().coerceAtLeast(0)
        w.params.y = (startY + dy).roundToInt().coerceAtLeast(0)
        w.update()
    }

    /** Returns true when the gesture was a drag rather than a tap. */
    fun end(): Boolean {
        if (moved) window()?.params?.let { onMoved(it.x, it.y) }
        return moved
    }

    companion object {
        private const val SLOP = 12f
    }
}

@SuppressLint("ClickableViewAccessibility")
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.windowDrag(controller: WindowDragController, onTap: (() -> Unit)? = null): Modifier =
    pointerInteropFilter { e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                controller.begin(e.rawX, e.rawY)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                controller.drag(e.rawX, e.rawY)
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!controller.end()) onTap?.invoke()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                controller.end()
                true
            }
            else -> false
        }
    }
