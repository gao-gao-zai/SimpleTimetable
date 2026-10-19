package io.github.gaogaoza.simpletimetable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Classic saturation/value square and hue strip; no network or color-picker service. */
@Composable fun ColorPanel(hsv: FloatArray, onChange: (FloatArray) -> Unit) {
    val current by rememberUpdatedState(hsv)
    val change by rememberUpdatedState(onChange)
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(152.dp).clip(MaterialTheme.shapes.medium).border(1.dp, colors.outline, MaterialTheme.shapes.medium)
        .semantics {
            contentDescription = "二维取色板"; stateDescription = "饱和度 ${(hsv[1] * 100).roundToInt()}%，明亮度 ${(hsv[2] * 100).roundToInt()}%"
            customActions = listOf(
                CustomAccessibilityAction("增加饱和度") { change(current.copyOf().also { it[1] = (it[1] + .05f).coerceAtMost(1f) }); true },
                CustomAccessibilityAction("减少饱和度") { change(current.copyOf().also { it[1] = (it[1] - .05f).coerceAtLeast(0f) }); true },
                CustomAccessibilityAction("提高明亮度") { change(current.copyOf().also { it[2] = (it[2] + .05f).coerceAtMost(1f) }); true },
                CustomAccessibilityAction("降低明亮度") { change(current.copyOf().also { it[2] = (it[2] - .05f).coerceAtLeast(0f) }); true })
        }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(); down.consume()
                fun choose(p: Offset) = change(current.copyOf().also { it[1] = (p.x / size.width).coerceIn(0f, 1f); it[2] = (1 - p.y / size.height).coerceIn(0f, 1f) })
                choose(down.position)
                do { val event = awaitPointerEvent(); val c = event.changes.firstOrNull { it.id == down.id } ?: break; if (c.pressed) choose(c.position); c.consume() } while (event.changes.any { it.id == down.id && it.pressed })
            }
        }) {
        val hue = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)))
        drawRect(Brush.horizontalGradient(listOf(Color.White, hue)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val point = Offset(hsv[1] * size.width, (1 - hsv[2]) * size.height)
        drawCircle(Color.Black, 9.dp.toPx(), point, style = Stroke(4.dp.toPx()))
        drawCircle(Color.White, 9.dp.toPx(), point, style = Stroke(2.dp.toPx()))
    }
    Canvas(Modifier.fillMaxWidth().height(32.dp).clip(MaterialTheme.shapes.large).semantics {
        contentDescription = "色相"; progressBarRangeInfo = ProgressBarRangeInfo(hsv[0], 0f..360f)
        setProgress { change(current.copyOf().also { a -> a[0] = it.coerceIn(0f, 359.9f) }); true }
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(); down.consume()
            fun choose(p: Offset) = change(current.copyOf().also { it[0] = (p.x / size.width * 360).coerceIn(0f, 359.9f) })
            choose(down.position)
            do { val event = awaitPointerEvent(); val c = event.changes.firstOrNull { it.id == down.id } ?: break; if (c.pressed) choose(c.position); c.consume() } while (event.changes.any { it.id == down.id && it.pressed })
        }
    }) {
        drawRect(Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)), topLeft = Offset(0f, 7.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, size.height - 14.dp.toPx()))
        val point = Offset(hsv[0] / 360 * size.width, size.height / 2)
        drawCircle(colors.surface, 12.dp.toPx(), point)
        drawCircle(Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))), 9.dp.toPx(), point)
        drawCircle(colors.outline, 12.dp.toPx(), point, style = Stroke(1.dp.toPx()))
    }
}
