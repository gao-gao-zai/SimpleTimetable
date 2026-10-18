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
    if (open) {
        var hour by remember { mutableIntStateOf(value / 3600) }; var minute by remember { mutableIntStateOf(value % 3600 / 60) }; var second by remember { mutableIntStateOf(value % 60) }
        val local = remember { EditForm() }
        PickerDialog(label, { open = false }, { form.changed(); onChange(hour * 3600 + minute * 60 + second); open = false }) {
            Text(clockText(hour * 3600 + minute * 60 + second), style = MaterialTheme.typography.headlineMedium)
            NumberControl("小时", hour, 0..if (allowMidnightEnd) 24 else 23, local, "时") { hour = it; if (it == 24) { minute = 0; second = 0 } }
            if (hour < 24) {
                NumberControl("分钟", minute, 0..59, local, "分") { minute = it }
                NumberControl("秒", second, 0..59, local, "秒") { second = it }
            } else Explanation("24:00 表示当天结束。")
        }
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
        fun set(index: Int, n: Float) { val next = hsv.copyOf(); next[index] = n; hsv = next; emitted = hsvColor(next[0], next[1], next[2]); form.changed(); onChange(emitted) }
        Text("色相", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().height(12.dp)) { (0 until 36).forEach { h -> Box(Modifier.weight(1f).fillMaxHeight().background(Color(android.graphics.Color.HSVToColor(floatArrayOf(h * 10f, .8f, .8f))))) } }
        Slider(hsv[0], { set(0, it) }, valueRange = 0f..359.9f, modifier = Modifier.semantics { contentDescription = "色相" })
        Text("饱和度", style = MaterialTheme.typography.labelLarge); Slider(hsv[1], { set(1, it) }, modifier = Modifier.semantics { contentDescription = "饱和度" })
        Text("明亮度", style = MaterialTheme.typography.labelLarge); Slider(hsv[2], { set(2, it) }, modifier = Modifier.semantics { contentDescription = "明亮度" })
        TextButton(onClick = { code = true }) { Text("输入颜色代码") }
    }
    if (code) {
        var hex by remember { mutableStateOf(value) }; var error by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { code = false }, title = { Text("颜色代码") }, text = { OutlinedTextField(hex, { hex = it; error = false }, label = { Text("#RRGGBB") }, singleLine = true, isError = error, supportingText = { if (error) Text("例如 #B34E78") }) }, confirmButton = { TextButton(onClick = { if (hex.matches(Regex("#[0-9a-fA-F]{6}"))) { form.changed(); onChange(hex.uppercase(Locale.ROOT)); code = false } else error = true }) { Text("确定") } }, dismissButton = { TextButton(onClick = { code = false }) { Text("取消") } })
    }
}
