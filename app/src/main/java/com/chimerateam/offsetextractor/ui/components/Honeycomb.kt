package com.chimerateam.offsetextractor.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a single honeycomb cell outline centred in the available space.
 *
 * @param size outer diameter of the cell.
 * @param color stroke colour.
 * @param strokeWidth outline thickness.
 * @param fillAlpha alpha applied to the cell interior.
 */
@Composable
fun HoneycombCell(
    size: Dp,
    color: Color,
    strokeWidth: Dp = 3.dp,
    fillAlpha: Float = 0f,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val path = hexagonPath(center, radius)
        if (fillAlpha > 0f) {
            drawPath(path, color = color.copy(alpha = fillAlpha))
        }
        drawPath(path, color = color, style = Stroke(width = strokeWidth.toPx()))
    }
}

/**
 * Animated honeycomb used while extraction or scanning is running.
 *
 * The glow pulses continuously; the caller controls visibility by adding or
 * removing the composable.
 *
 * @param modifier layout modifier for the enclosing box.
 * @param cellSize diameter of each drawn cell.
 */
@Composable
fun HoneycombSpinner(modifier: Modifier = Modifier, cellSize: Dp = 120.dp) {
    val transition = rememberInfiniteTransition(label = "honeycomb")
    val pulse by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(cellSize * 2f)) {
            val radius = cellSize.toPx() / 2f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val satellites = listOf(0f, 60f, 120f, 180f, 240f, 300f)
            satellites.forEachIndexed { index, degrees ->
                val angle = Math.toRadians((degrees + rotation).toDouble())
                val distance = radius * 1.15f
                val offset = Offset(
                    x = center.x + (distance * kotlin.math.cos(angle)).toFloat(),
                    y = center.y + (distance * kotlin.math.sin(angle)).toFloat()
                )
                val alpha = 0.15f + (pulse * (0.25f + (index % 3) * 0.25f))
                drawPath(
                    path = hexagonPath(offset, radius * 0.55f),
                    color = Color(0xFFFFB300).copy(alpha = alpha.coerceIn(0f, 1f)),
                    style = Stroke(width = 3f)
                )
            }
            drawPath(
                path = hexagonPath(center, radius),
                color = Color(0xFFFFB300).copy(alpha = pulse.coerceIn(0f, 1f)),
                style = Stroke(width = 5f)
            )
        }
    }
}

/** Builds a flat-top hexagon path. */
private fun hexagonPath(center: Offset, radius: Float): Path {
    val path = Path()
    repeat(6) { corner ->
        val angle = Math.toRadians((60.0 * corner - 30.0))
        val x = center.x + (radius * kotlin.math.cos(angle)).toFloat()
        val y = center.y + (radius * kotlin.math.sin(angle)).toFloat()
        if (corner == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

@Preview
@Composable
private fun HoneycombSpinnerPreview() {
    HoneycombSpinner()
}
