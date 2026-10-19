@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.github.gaogaoza.simpletimetable.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.gaogaoza.simpletimetable.data.*
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** A bounded numeric value with visible units. Tapping the value allows keyboard entry. */
@Composable fun NumberControl(label: String, value: Int, range: IntRange, form: EditForm, unit: String = "", step: Int = 1, onChange: (Int) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { form.changed(); onChange((value - step).coerceIn(range)) }, enabled = value > range.first) { Icon(Icons.Default.Remove, "减少$label") }
                TextButton(onClick = { editing = true }, modifier = Modifier.weight(1f)) { Text("$value${if (unit.isBlank()) "" else " $unit"}", color = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = { form.changed(); onChange((value + step).coerceIn(range)) }, enabled = value < range.last) { Icon(Icons.Default.Add, "增加$label") }
            }
        }
    }
    if (editing) {
        var text by remember { mutableStateOf(value.toString()) }; var error by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(label) }, text = {
            OutlinedTextField(text, { text = it; error = false }, singleLine = true, label = { Text(if (unit.isBlank()) label else unit) }, supportingText = { Text("${range.first} — ${range.last}${if (error) "，请输入范围内的整数" else ""}") }, isError = error, keyboardOptions = KeyboardOptions(keyboardType = if (range.first < 0) KeyboardType.Text else KeyboardType.Number))
        }, confirmButton = { TextButton(onClick = { val n = text.toIntOrNull(); if (n != null && n in range) { form.changed(); onChange(n); editing = false } else error = true }) { Text("确定") } }, dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } })
    }
}

@Composable fun PickerDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp).widthIn(max = 480.dp).heightIn(max = (LocalConfiguration.current.screenHeightDp * .9f).dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column {
                Text(title, Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text("取消") }; TextButton(onClick = onConfirm) { Text("确定") } }
            }
        }
    }
}

@Composable fun PickerRow(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Icon(icon, null, Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
        }
        Icon(Icons.Default.ArrowDropDown, null)
    }
}

@Composable fun TimeControl(label: String, value: Int, form: EditForm, allowMidnightEnd: Boolean = false, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    PickerRow(label, clockText(value), Icons.Default.Schedule) { open = true }
    if (open) ClockPicker(label, value, allowMidnightEnd, { open = false }) {
        form.changed(); onChange(it); open = false
    }
}

@Composable fun CalendarControl(value: LocalDate, onChange: (LocalDate) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(value)) }; var jump by remember { mutableStateOf(false) }
    val local = remember { EditForm() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, "上个月") }
        TextButton(onClick = { jump = !jump }, modifier = Modifier.weight(1f)) { Text("${month.year} 年 ${month.monthValue} 月") }
        IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Default.ChevronRight, "下个月") }
    }
    if (jump) {
        NumberControl("年份", month.year, 1900..2200, local, "年") { month = month.withYear(it) }
        NumberControl("月份", month.monthValue, 1..12, local, "月") { month = month.withMonth(it) }
    }
    Row(Modifier.fillMaxWidth()) { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium) } }
    val first = month.atDay(1).dayOfWeek.value - 1
    val cells = (first + month.lengthOfMonth() + 6) / 7 * 7
    (0 until cells step 7).forEach { row ->
        Row(Modifier.fillMaxWidth()) {
            (0..6).forEach { column ->
                val day = row + column - first + 1
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (day in 1..month.lengthOfMonth()) {
                        val date = month.atDay(day); val selected = date == value
                        Surface(onClick = { onChange(date) }, shape = CircleShape,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics { contentDescription = "选择日期 $date" }) {
                            Box(contentAlignment = Alignment.Center) { Text(day.toString(), style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
    }
    Text(dateLabel(value), style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = { val today = LocalDate.now(); month = YearMonth.from(today); onChange(today) }) { Text("回到今天") }
}

@Composable fun DateControl(label: String, value: LocalDate, form: EditForm, monday: Boolean = false, onChange: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    PickerRow(label, "${value.year} 年 ${value.monthValue} 月 ${value.dayOfMonth} 日 · 周${"一二三四五六日"[value.dayOfWeek.value - 1]}", Icons.Default.CalendarMonth) { open = true }
    if (open) {
        var selected by remember { mutableStateOf(value) }
        PickerDialog(label, { open = false }, { form.changed(); onChange(if (monday) selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else selected); open = false }) {
            CalendarControl(selected) { selected = it }
            if (monday) Explanation("选择任意一天，将使用所在周的星期一作为第 1 周基准。")
        }
    }
}

@Composable fun DateTimeControl(label: String, value: Long, form: EditForm, onChange: (Long) -> Unit) {
    val zone = ZoneId.systemDefault(); val local = Instant.ofEpochMilli(value).atZone(zone)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        DateControl("${label}日期", local.toLocalDate(), form) { onChange(it.atTime(local.toLocalTime()).atZone(zone).toInstant().toEpochMilli()) }
        TimeControl("${label}时刻", local.toLocalTime().toSecondOfDay(), form) { onChange(local.toLocalDate().atStartOfDay().plusSeconds(it.toLong()).atZone(zone).toInstant().toEpochMilli()) }
    }
}

@Composable fun WeekdaysControl(selected: Set<Int>, form: EditForm, onChange: (Set<Int>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(1, 2, 3, 4, 5, 6, 0).forEach { day -> FilterChip(selected = day in selected, onClick = { form.changed(); onChange(if (day in selected) selected - day else selected + day) }, label = { Text("周${"日一二三四五六"[day]}") }) }
    }
}

fun hsvColor(hue: Float, saturation: Float, brightness: Float): String = String.format(Locale.ROOT, "#%06X", android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)) and 0xffffff)

@Composable fun ColorControl(label: String, value: String, form: EditForm, onChange: (String) -> Unit) {
    var custom by remember { mutableStateOf(false) }; var code by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.titleSmall)
    seedPresets.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { (name, color) ->
                val selected = color.equals(value, true)
                OutlinedButton(onClick = { form.changed(); onChange(color) }, modifier = Modifier.weight(1f).semantics { this.selected = selected; stateDescription = if (selected) "已选中" else "未选中" }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp), border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
                    Box(Modifier.size(16.dp).background(Color(android.graphics.Color.parseColor(color)), CircleShape)); Spacer(Modifier.width(6.dp)); Text(name, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).background(Color(android.graphics.Color.parseColor(value)), CircleShape)); Spacer(Modifier.width(8.dp))
        Text(value.uppercase(Locale.ROOT), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        TextButton(onClick = { custom = !custom }) { Text("自选颜色") }
    }
    if (custom) {
        var hsv by remember { mutableStateOf(FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(value), it) }) }
        var emitted by remember { mutableStateOf(value) }
        LaunchedEffect(value) { if (!value.equals(emitted, true)) hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(value), it) } }
        ColorPanel(hsv) { next ->
            hsv = next; emitted = hsvColor(next[0], next[1], next[2]); form.changed(); onChange(emitted)
        }
        TextButton(onClick = { code = true }) { Text("HEX / RGB 数值") }
    }

    if (code) {
        var hex by remember { mutableStateOf(value) }; var error by remember { mutableStateOf(false) }; var rgb by remember { mutableStateOf(false) }
        val initial = remember { android.graphics.Color.parseColor(value) }
        var red by remember { mutableStateOf(android.graphics.Color.red(initial).toString()) }; var green by remember { mutableStateOf(android.graphics.Color.green(initial).toString()) }; var blue by remember { mutableStateOf(android.graphics.Color.blue(initial).toString()) }
        AlertDialog(onDismissRequest = { code = false }, title = { Text("颜色数值") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row { FilterChip(selected = !rgb, onClick = { rgb = false; error = false }, label = { Text("HEX") }); Spacer(Modifier.width(8.dp)); FilterChip(selected = rgb, onClick = { rgb = true; error = false }, label = { Text("RGB") }) }
                if (!rgb) OutlinedTextField(hex, { hex = it; error = false }, label = { Text("#RRGGBB") }, singleLine = true, isError = error)
                else listOf(Triple("红 R", red, { v: String -> red = v }), Triple("绿 G", green, { v: String -> green = v }), Triple("蓝 B", blue, { v: String -> blue = v })).forEach { (label, channel, change) ->
                    OutlinedTextField(channel, { change(it); error = false }, label = { Text(label) }, singleLine = true, isError = error, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (error) Text(if (rgb) "每个通道请输入 0 — 255 的整数" else "请输入 #RRGGBB，例如 #B34E78", color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = {
            val channels = listOf(red, green, blue).map { it.toIntOrNull() }
            val next = if (rgb) if (channels.all { it != null && it in 0..255 }) "#%02X%02X%02X".format(channels[0], channels[1], channels[2]) else null else hex.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }
            if (next != null) { form.changed(); onChange(next.uppercase(Locale.ROOT)); code = false } else error = true
        }) { Text("确定") } }, dismissButton = { TextButton(onClick = { code = false }) { Text("取消") } })
    }
}
