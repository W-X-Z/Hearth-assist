package com.hearthassist.app.overlay

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import com.hearthassist.app.AppGraph
import com.hearthassist.app.ui.ScoreBadge
import kotlin.math.roundToInt

/**
 * Full-screen, touch-through layer that draws a score badge right above each offered card's
 * name, at the position where OCR found it.
 */
@Composable
fun BadgesContent(ui: OverlayUiState) {
    val scan by ui.scan.collectAsState()
    val settings by AppGraph.settings.state.collectAsState()
    val result = scan ?: return
    if (result.cards.isEmpty()) return
    val best = result.best

    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            result.cards.forEach { d -> ScoreBadge(d.score, best = d === best, scale = settings.overlayScale) }
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val sx = w.toFloat() / result.imageWidth.coerceAtLeast(1)
        val sy = h.toFloat() / result.imageHeight.coerceAtLeast(1)
        val loose = Constraints(maxWidth = w, maxHeight = h)
        val placeables = measurables.map { it.measure(loose) }
        layout(w, h) {
            placeables.forEachIndexed { i, p ->
                val d = result.cards[i].detected
                val margin = (8 * density).roundToInt()
                val anchorX = d.centerX * sx
                var y = (d.top * sy).roundToInt() - p.height - margin
                if (y < 0) y = (d.bottom * sy).roundToInt() + margin
                val x = (anchorX - p.width / 2f).roundToInt().coerceIn(0, (w - p.width).coerceAtLeast(0))
                p.place(x, y.coerceIn(0, (h - p.height).coerceAtLeast(0)))
            }
        }
    }
}
