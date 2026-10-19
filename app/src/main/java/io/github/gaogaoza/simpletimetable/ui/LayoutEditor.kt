@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.github.gaogaoza.simpletimetable.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gaogaoza.simpletimetable.TimetableApplication
import io.github.gaogaoza.simpletimetable.data.*
import kotlin.math.max

@Composable fun LayoutEditor(app: TimetableApplication, p: Profile, editor: Editor.LayoutEdit, f: EditForm) {
    val draft = remember { LayoutDraft(p.layouts.firstOrNull { it.id == editor.id } ?: TimeLayout(name = "新时间表")) }
    var mode by remember { mutableStateOf("timeline") }
    var detail by remember { mutableStateOf("") }
    var zoom by remember { mutableFloatStateOf(.75f) }
    var snap by remember { mutableIntStateOf(5) }
    var shift by remember { mutableIntStateOf(0) }
    var addMenu by remember { mutableStateOf(false) }
    val square = LocalConfiguration.current.screenHeightDp <= 440
    val local = remember { EditForm() }
    f.saveLabel = "保存时间表"
    f.submit = { app.repository.editProfile(p.id) { profileWithLayout(it, draft.layout) } }
    BackHandler(detail.isNotBlank()) { detail = "" }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(draft.layout.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!square) Explanation("${draft.layout.slots.count { it.type == SlotType.LESSON }} 节课 · ${draft.layout.slots.count { it.type == SlotType.BREAK }} 个课间")
        }
        if (square) Box {
            IconButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, "添加时段") }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                listOf(SlotType.LESSON to "上课", SlotType.BREAK to "课间", SlotType.DIVIDER to "分割线").forEach { (type, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { addMenu = false; f.run { draft.insert(type); detail = ""; f.changed() } })
                }
            }
        }
        IconButton(onClick = { detail = if (detail == "settings") "" else "settings" }) { Icon(Icons.Default.Settings, "时间表设置") }
    }
    if (!square) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(SlotType.LESSON to "＋ 上课", SlotType.BREAK to "＋ 课间", SlotType.DIVIDER to "分割线").forEach { (type, name) ->
            OutlinedButton(onClick = { f.run { draft.insert(type); detail = ""; f.changed() } }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) { Text(name, fontSize = 12.sp) }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        listOf("timeline" to "时间轴", "list" to "列表").forEach { (key, name) ->
            TextButton(onClick = { mode = key; detail = "" }, modifier = Modifier.weight(1f).semantics { selected = mode == key }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(name, color = if (mode == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        IconButton(onClick = { draft.history(true); f.changed(); detail = "" }, enabled = draft.undo.isNotEmpty(), modifier = Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Filled.Undo, "撤销") }
        IconButton(onClick = { draft.history(false); f.changed(); detail = "" }, enabled = draft.redo.isNotEmpty(), modifier = Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Filled.Redo, "重做") }
    }
    if (detail.isNotBlank()) TextButton(onClick = { detail = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text("返回整张时间表") }
    when (detail) {
        "settings" -> {
            Input("时间表名称", draft.layout.name, f) { draft.update(draft.layout.copy(name = it)) }
            NumberControl("默认课程时长", draft.layout.defaultMinutes, 1..720, f, "分钟", 5) { draft.update(draft.layout.copy(defaultMinutes = it)) }
            NumberControl("默认课间时长", draft.layout.defaultBreakMinutes, 1..720, f, "分钟", 5) { draft.update(draft.layout.copy(defaultBreakMinutes = it)) }
            Choice("默认科目", draft.layout.defaultSubject, listOf("" to "不设置默认科目") + p.subjects.map { it.id to it.name }, f) { draft.update(draft.layout.copy(defaultSubject = it)) }
            NumberControl("整张时间表平移", shift, -720..720, local, "分钟", 5) { shift = it }
            Action("应用平移") { f.run { draft.shift(shift); f.changed() } }
            Explanation("${p.plans.count { it.layoutId == draft.layout.id }} 份课表引用此时间表；保存后共同生效。")
            if (editor.id.isNotBlank()) {
                Action("复制为独立时间表") { f.run {
                    val copy = draft.layout.copy(id = newId(), name = requireName(draft.layout.name) + " 副本")
                    app.repository.editProfile(p.id) { profileWithLayout(it, copy) }; f.close()
                } }
                Action("删除时间表", true) { f.ask("删除这份时间表？") {
                    app.repository.editProfile(p.id) { profile ->
                        require(profile.plans.none { it.layoutId == draft.layout.id }) { "仍有课表引用此时间表，请先更换引用。" }
                        profile.copy(layouts = profile.layouts.filterNot { it.id == draft.layout.id })
                    }; f.close()
                } }
            }
        }
        "slot" -> draft.slot?.let { slot ->
            key(slot.id) {
                Input("时段名称", slot.name, f) { draft.updateSlot(slot.copy(name = it)) }
                Choice("时段类型", slot.type.name, listOf("LESSON" to "上课", "BREAK" to "课间", "DIVIDER" to "分割线"), local) { type -> f.run {
                    val kind = SlotType.valueOf(type)
                    require(kind == SlotType.LESSON || !referencesSlot(p, draft.layout.id, slot.id)) { "临时安排仍引用这节课，不能改为其他类型。" }
                    val end = if (kind == SlotType.DIVIDER) slot.start else if (slot.end > slot.start) slot.end else slot.start + (if (kind == SlotType.BREAK) draft.layout.defaultBreakMinutes else draft.layout.defaultMinutes) * 60
                    draft.updateSlot(slot.copy(type = kind, end = end)); f.changed()
                } }
                if (slot.type == SlotType.LESSON) Choice("未分配课程时的默认科目", slot.defaultSubject, listOf("" to "沿用时间表默认科目") + p.subjects.map { it.id to it.name }, f) { draft.updateSlot(slot.copy(defaultSubject = it)) }
                LayoutSelection(draft, f, false) { }
                Action("复制此时段") { f.run { draft.insert(slot.type, duplicate = true); detail = ""; f.changed() } }
                Action("删除此时段", true) { f.ask("从共享时间表中删除「${slot.name}」？") { draft.delete(p); detail = ""; f.changed() } }
            }
        }
        else -> {
            if (mode == "timeline") {
                LayoutTimeline(draft, f, zoom, snap)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { zoom = (zoom - .25f).coerceAtLeast(.5f) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Remove, "缩小时间轴") }
                    Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    IconButton(onClick = { zoom = (zoom + .25f).coerceAtMost(3f) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Add, "放大时间轴") }
                    Spacer(Modifier.weight(1f))
                    var open by remember { mutableStateOf(false) }
                    Box { TextButton(onClick = { open = true }, contentPadding = PaddingValues(4.dp)) { Text("吸附 $snap 分", fontSize = 11.sp) }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { listOf(1, 5, 10).forEach { n -> DropdownMenuItem(text = { Text("$n 分钟") }, onClick = { snap = n; open = false }) } }
                    }
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                draft.layout.slots.sortedBy { it.start }.forEach { slot ->
                    Surface(onClick = { draft.selected = slot.id }, shape = MaterialTheme.shapes.medium,
                        color = if (draft.selected == slot.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (draft.selected == slot.id) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "选择时段 ${slot.name}"; selected = draft.selected == slot.id }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(slot.name, style = MaterialTheme.typography.titleSmall); Text(if (slot.type == SlotType.DIVIDER) "${clockText(slot.start)} · 分割线" else "${clockText(slot.start)} — ${clockText(slot.end)}", style = MaterialTheme.typography.bodySmall) }
                            if (draft.selected == slot.id) Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                        }
                    }
                }
            }
            key(draft.selected) { LayoutSelection(draft, f, true) { detail = "slot" } }
            if (draft.layout.slots.isEmpty()) Explanation("点添加时段，安排上课、课间或分割线。")
        }
    }
}

@Composable private fun LayoutSelection(draft: LayoutDraft, f: EditForm, showDetail: Boolean, onDetail: () -> Unit) {
    val slot = draft.slot ?: return
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(slot.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (showDetail) TextButton(onClick = onDetail) { Text("详细编辑", fontSize = 12.sp) }
            }
            TimeControl(if (slot.type == SlotType.DIVIDER) "分割线位置" else "开始时间", slot.start, f) { n -> f.run {
                val end = if (slot.type == SlotType.DIVIDER) n else n + slot.end - slot.start
                draft.updateSlot(slot.copy(start = n, end = end))
            } }
            if (slot.type != SlotType.DIVIDER) {
                TimeControl("结束时间", slot.end, f, allowMidnightEnd = true) { n -> f.run { draft.updateSlot(slot.copy(end = n)) } }
                Explanation("时长 ${(slot.end - slot.start) / 60} 分 ${(slot.end - slot.start) % 60} 秒")
            }
        }
    }
}

@Composable private fun LayoutTimeline(draft: LayoutDraft, f: EditForm, zoom: Float, snap: Int) {
    val config = LocalConfiguration.current
    val height = if (config.screenHeightDp <= 440) 128.dp else if (config.screenHeightDp < 600) 160.dp else 224.dp
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(zoom) { scroll.scrollTo(with(density) { ((draft.slot?.start ?: 28800) / 60f * 2 * zoom - 12).dp.roundToPx() }.coerceAtLeast(0)) }
    Box(Modifier.fillMaxWidth().height(height).verticalScroll(scroll).semantics { contentDescription = "时间表时间轴" }) {
        Box(Modifier.fillMaxWidth().height((1440 * 2 * zoom + 24).dp)) {
            Canvas(Modifier.matchParentSize()) {
                val x = 65.dp.toPx()
                drawLine(colors.outlineVariant, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                (0..24).forEach { hour -> val y = (hour * 60 * 2 * zoom).dp.toPx(); drawLine(colors.outline, Offset(x - 4.dp.toPx(), y), Offset(x + 4.dp.toPx(), y), 1.dp.toPx()) }
            }
            draft.layout.slots.forEach { slot ->
                val selected = slot.id == draft.selected
                val divider = slot.type == SlotType.DIVIDER
                val slotHeight = if (divider) 20f else max(20f, (slot.end - slot.start) / 60f * 2 * zoom)
                Box(Modifier.offset(y = (slot.start / 60f * 2 * zoom).dp).fillMaxWidth().height(slotHeight.dp)
                    .semantics { contentDescription = "选择时段 ${slot.name}"; this.selected = selected }
                    .clickable(role = Role.Button) { draft.selected = slot.id }) {
                    Text(clockText(slot.start), Modifier.width(53.dp).padding(top = 2.dp), fontSize = 10.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End, color = if (selected) colors.primary else colors.onSurfaceVariant)
                    Canvas(Modifier.matchParentSize()) {
                        val x = 65.dp.toPx()
                        if (divider) drawLine(colors.outline, Offset(x - 6.dp.toPx(), size.height / 2), Offset(x + 6.dp.toPx(), size.height / 2), 2.dp.toPx())
                        else if (slot.type == SlotType.BREAK) drawCircle(if (selected) colors.primary else colors.outline, 3.dp.toPx(), Offset(x, size.height / 2))
                        else drawLine(if (selected) colors.primary else colors.outline, Offset(x, 3.dp.toPx()), Offset(x, size.height - 3.dp.toPx()), (if (selected) 5.dp else 3.dp).toPx())
                    }
                    Column(Modifier.fillMaxSize().padding(start = 88.dp, end = 2.dp), verticalArrangement = Arrangement.Center) {
                        Text(slot.name, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = if (slotHeight < 40) 11.sp else 13.sp,
                            color = if (selected) colors.primary else colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (slotHeight >= 42 && !divider) Text("${clockText(slot.start)} — ${clockText(slot.end)}", fontSize = 10.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (selected && !divider) listOf(true, false).forEach { startEdge ->
                        var before by remember(slot.id, startEdge) { mutableStateOf(draft.snapshot()) }
                        var delta by remember(slot.id, startEdge) { mutableFloatStateOf(0f) }
                        var wasDirty by remember(slot.id, startEdge) { mutableStateOf(false) }
                        Box(Modifier.align(if (startEdge) Alignment.TopStart else Alignment.BottomStart).offset(x = 43.dp).width(44.dp).height(20.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = if (startEdge) "调整开始时间" else "调整结束时间"
                                customActions = listOf(-1 to "提前 $snap 分钟", 1 to "延后 $snap 分钟").map { (direction, label) -> CustomAccessibilityAction(label) {
                                    val snapshot = draft.snapshot(); draft.resize(snapshot, startEdge, direction * snap * 60f, snap); draft.finishResize(snapshot); f.changed(); true
                                } }
                            }.pointerInput(slot.id, startEdge, zoom, snap) {
                                detectVerticalDragGestures(onDragStart = { before = draft.snapshot(); delta = 0f; wasDirty = f.dirty },
                                    onDragCancel = { draft.restore(before); f.dirty = wasDirty },
                                    onDragEnd = { if (draft.snapshot() != before) { draft.finishResize(before); f.changed() } },
                                    onVerticalDrag = { change, amount -> change.consume(); delta += amount; draft.resize(before, startEdge, with(density) { delta.toDp().value } / (2 * zoom) * 60, snap) })
                            }, contentAlignment = if (startEdge) Alignment.TopCenter else Alignment.BottomCenter) {
                            Canvas(Modifier.size(12.dp)) { drawCircle(colors.surface); drawCircle(colors.primary, style = Stroke(2.dp.toPx())) }
                        }
                    }
                }
            }
        }
    }
}
