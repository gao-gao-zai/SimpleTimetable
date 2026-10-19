package io.github.gaogaoza.simpletimetable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

@Composable fun ClockPicker(label: String, initial: Int, allowMidnightEnd: Boolean, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var value by remember { mutableIntStateOf(initial) }
    var unit by remember { mutableIntStateOf(0) }
    var keyboard by remember { mutableStateOf(false) }
    val local = remember { EditForm() }
    val compact = LocalConfiguration.current.screenHeightDp < 600 || LocalConfiguration.current.screenWidthDp < 320
    fun part(i: Int) = when(i) { 0 -> value / 3600; 1 -> value % 3600 / 60; else -> value % 60 }
    fun set(i: Int, n: Int) {
        val a = intArrayOf(part(0), part(1), part(2)); a[i] = n
        if (a[0] == 24) { a[1] = 0; a[2] = 0 }
        value = a[0] * 3600 + a[1] * 60 + a[2]
    }
    PickerDialog(label, onDismiss, { onConfirm(value) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            (0..2).forEach { i ->
                if (i > 0) Text(":", fontSize = 22.sp)
                Surface(onClick = { unit = i }, shape = MaterialTheme.shapes.medium,
                    color = if (unit == i) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (unit == i) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "选择${listOf("小时", "分钟", "秒")[i]}"; selected = unit == i }) {
                    Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%02d".format(part(i)), fontSize = if (compact) 25.sp else 32.sp, fontWeight = FontWeight.Medium)
                        Text(listOf("时", "分", "秒")[i], style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (keyboard) {
            NumberControl("小时", part(0), 0..if (allowMidnightEnd) 24 else 23, local, "时") { set(0, it) }
            if (part(0) < 24) {
                NumberControl("分钟", part(1), 0..59, local, "分") { set(1, it) }
                NumberControl("秒", part(2), 0..59, local, "秒") { set(2, it) }
            }
        } else {
            if (compact && unit == 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                FilterChip(selected = part(0) < 12 || part(0) == 24, onClick = { set(0, part(0) % 12) }, label = { Text("上午") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = part(0) in 12..23, onClick = { set(0, part(0) % 12 + 12) }, label = { Text("下午") })
            }
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val diameter = minOf(maxWidth, if (compact) 180.dp else 256.dp)
                val colors = MaterialTheme.colorScheme
                val chosen = part(unit)
                val inner = unit == 0 && !compact && (chosen == 0 || chosen >= 13)
                val radians = (if (unit == 0) chosen % 12 * 30f else chosen * 6f) * PI.toFloat() / 180
                Box(Modifier.size(diameter).background(colors.surfaceContainerHighest, CircleShape)
                    .semantics { contentDescription = "${listOf("小时", "分钟", "秒")[unit]}钟面" }
                    .pointerInput(unit, compact, diameter) {
                        fun choose(p: Offset) {
                            val dx = p.x - size.width / 2f; val dy = p.y - size.height / 2f
                            val degrees = (atan2(dx, -dy) * 180 / PI + 360) % 360
                            val n = if (unit == 0) {
                                val k = (degrees / 30).roundToInt() % 12
                                if (!compact) { if (hypot(dx, dy) < size.width * .335f) if (k == 0) 0 else k + 12 else if (k == 0) 12 else k }
                                else k + if (part(0) in 12..23) 12 else 0
                            } else (degrees / 6).roundToInt() % 60
                            set(unit, n)
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false); choose(down.position); down.consume()
                            var released = false
                            do {
                                val event = awaitPointerEvent(); val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.pressed) choose(change.position) else released = true
                                change.consume()
                            } while (event.changes.any { it.id == down.id && it.pressed })
                            if (released && unit == 0) unit = 1
                        }
                    }) {
                    Canvas(Modifier.matchParentSize()) {
                        val center = Offset(size.width / 2, size.height / 2)
                        val radius = size.width * if (inner) .27f else .4f
                        val end = center + Offset(sin(radians) * radius, -cos(radians) * radius)
                        drawLine(colors.primary, center, end, 2.dp.toPx()); drawCircle(colors.primary, 3.dp.toPx(), center)
                        drawCircle(colors.primary, (if (compact) 15.dp else 19.dp).toPx(), end)
                    }
                    val labels = if (unit == 0) (0..11).map { (if (it == 0) 12 else it) to .4f } + if (!compact) (0..11).map { (if (it == 0) 0 else it + 12) to .27f } else emptyList()
                        else (0..11).map { it * 5 to .4f }
                    labels.forEachIndexed { i, (n, r) ->
                        val actual = if (compact && unit == 0) n % 12 + if (part(0) in 12..23) 12 else 0 else n
                        val selected = actual == chosen || chosen == 24 && actual == 0
                        val angle = i % 12 * PI.toFloat() / 6
                        val x = diameter * (.5f + sin(angle) * r) - 16.dp
                        val y = diameter * (.5f - cos(angle) * r) - 16.dp
                        Box(Modifier.offset(x, y).size(32.dp).semantics {
                            role = Role.Button; contentDescription = "$actual${listOf("时", "分", "秒")[unit]}"; this.selected = selected
                            onClick { set(unit, actual); if (unit == 0) unit = 1; true }
                        }, contentAlignment = Alignment.Center) {
                            Text(if (unit == 0 && n != 0) n.toString() else "%02d".format(n), color = if (selected) colors.onPrimary else colors.onSurface,
                                fontSize = if (r < .3f) 12.sp else if (compact) 14.sp else 16.sp)
                        }
                    }
                    if (unit > 0 && chosen % 5 != 0) {
                        Box(Modifier.offset(diameter * (.5f + sin(radians) * .4f) - 16.dp, diameter * (.5f - cos(radians) * .4f) - 16.dp).size(32.dp), contentAlignment = Alignment.Center) {
                            Text("%02d".format(chosen), color = colors.onPrimary, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { keyboard = !keyboard }) { Icon(if (keyboard) Icons.Default.Schedule else Icons.Default.Keyboard, if (keyboard) "使用钟面" else "使用键盘输入时间") }
            if (allowMidnightEnd) TextButton(onClick = { value = 86400; unit = 0 }) { Text("24:00 · 当天结束", style = MaterialTheme.typography.labelSmall) }
        }
    }
}
