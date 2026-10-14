package com.byeonggon.cameramanager.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.sqrt

/** How the picture's tones are spread from black on the left to white on the right, for each colour. */
@Composable
internal fun HistogramView(histogram: Histogram, modifier: Modifier = Modifier, docked: Boolean = false) {
    Canvas(
        modifier
            .padding(12.dp)
            // Docked in a side panel it takes the panel's width; over the picture it stays small.
            .then(if (docked) Modifier.fillMaxWidth().height(84.dp) else Modifier.size(width = 96.dp, height = 52.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .semantics { contentDescription = "히스토그램" },
    ) {
        // The tallest bar, leaving out the ends: a blown sky would flatten everything else otherwise.
        val bins = histogram.red.size
        var tallest = 1
        for (bin in 1 until bins - 1) tallest = max(tallest, max(histogram.red[bin], max(histogram.green[bin], histogram.blue[bin])))
        fun draw(counts: IntArray, color: Color) {
            val path = Path()
            path.moveTo(0f, size.height)
            for (bin in 0 until bins) {
                // The square root keeps the less common tones visible next to the most common ones.
                val share = sqrt((counts[bin].toFloat() / tallest).coerceAtMost(1f))
                path.lineTo(size.width * (bin + 0.5f) / bins, size.height * (1f - share))
            }
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, color, blendMode = BlendMode.Plus)
        }
        draw(histogram.red, Color(0xFFD03030))
        draw(histogram.green, Color(0xFF30B040))
        draw(histogram.blue, Color(0xFF3050E0))
    }
}
