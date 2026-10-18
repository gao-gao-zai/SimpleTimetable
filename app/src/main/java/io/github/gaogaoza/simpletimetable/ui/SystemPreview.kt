package io.github.gaogaoza.simpletimetable.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gaogaoza.simpletimetable.TimetableApplication
import io.github.gaogaoza.simpletimetable.data.*

@Composable fun SystemPreviewPage(app: TimetableApplication, db: AppData, state: CourseState, session: UiSession, compact: Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = session.surfaceView == "widget", onClick = { session.surfaceView = "widget" }, label = { Text("桌面小组件") }); FilterChip(selected = session.surfaceView == "notification", onClick = { session.surfaceView = "notification" }, label = { Text("常驻通知") }) }
        if (session.surfaceView == "widget") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = session.widgetView == "card", onClick = { session.widgetView = "card" }, label = { Text("课程卡片") }); FilterChip(selected = session.widgetView == "timetable", onClick = { session.widgetView = "timetable" }, label = { Text("今日课表") }) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = session.widgetFull, onClick = { session.widgetFull = !session.widgetFull; session.notificationExpanded = session.widgetFull }, label = { Text(if (session.widgetFull) "完整布局" else "紧凑布局") })
            Spacer(Modifier.weight(1f)); TextButton(onClick = { session.editor = Editor.SurfaceSettings }) { Text("设置") }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(26.dp)) {
            Column(Modifier.padding(14.dp).fillMaxWidth()) {
                if (session.surfaceView == "widget" && session.widgetView == "timetable") TimetableCardPreview(state, session.widgetFull, onCourse = { openLesson(session, db.active(), state.resolution.date, it) }, onOpen = { session.date = state.resolution.date; session.profileId = db.active().id; session.page = "schedule" })
                else CourseCardPreview(state, session.widgetFull, onOpen = { session.page = "home" }, onSchedule = { session.date = state.resolution.date; session.profileId = db.active().id; session.page = "schedule" })
            }
        }
        if (session.surfaceView == "notification" && !db.settings.notification) Explanation("常驻通知已关闭，可在设置中开启。")
    }
}

@Composable private fun CourseCardPreview(state: CourseState, full: Boolean, onOpen: () -> Unit, onSchedule: () -> Unit) {
    val colors = MaterialTheme.colorScheme; val idle = state.phase in listOf(Phase.EMPTY, Phase.ENDED)
    Surface(shape = RoundedCornerShape(26.dp), color = colors.surfaceContainerHigh, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CalendarMonth, null, Modifier.size(17.dp), tint = colors.primary); Spacer(Modifier.width(7.dp)); Text("简单课表", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall); Text("${state.resolution.date.monthValue} / ${state.resolution.date.dayOfMonth}", style = MaterialTheme.typography.labelSmall) }
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(state.title, fontSize = if (state.title.length > 14) 21.sp else 25.sp, fontWeight = FontWeight.SemiBold); if (full) Text(if (state.phase == Phase.CLASS) "${clockText(state.current!!.slot.start)} — ${clockText(state.current.slot.end)} · ${state.current.subject?.teacher.orEmpty()}" else state.status, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
                if (!idle) Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 10.dp)) { Text(durationText(state.remaining), fontSize = 29.sp, fontWeight = FontWeight.SemiBold, color = colors.primary); Text(if (state.phase == Phase.CLASS) "距下课" else "距上课", style = MaterialTheme.typography.labelSmall) }
            }
            ProgressBar(state.progress)
            if (full) Text(state.progressLabel(), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            if (state.next != null) Surface(shape = RoundedCornerShape(14.dp), color = if (full) colors.secondaryContainer else colors.surfaceContainerHigh) { Row(Modifier.fillMaxWidth().padding(if (full) 12.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (state.phase == Phase.BEFORE) "第一节" else "下一节", style = MaterialTheme.typography.labelSmall); Spacer(Modifier.width(8.dp)); Text(state.next.subject?.name ?: "未安排科目", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold); Text(clockText(state.next.slot.start) + " 上课", style = MaterialTheme.typography.labelSmall) } }
            else if (state.phase == Phase.CLASS) Text("当前是今日最后一节 · ${clockText(state.current!!.slot.end)} 下课", style = MaterialTheme.typography.labelSmall)
            if (full) TextButton(onClick = onSchedule, modifier = Modifier.align(Alignment.End)) { Text("查看今日课表", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable private fun TimetableCardPreview(state: CourseState, full: Boolean, onCourse: (Lesson) -> Unit, onOpen: () -> Unit) {
    val lessons = state.resolution.lessons; val done = lessons.count { it.slot.end <= state.secondOfDay }; val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(26.dp), color = colors.surfaceContainerHigh, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth()) { Text("今日课表", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text("${lessons.size} 节课", style = MaterialTheme.typography.labelSmall) }
            Text("${state.status} · 已结束 $done / ${lessons.size} 节", style = MaterialTheme.typography.labelSmall)
            ProgressBar(if (lessons.isEmpty()) 0.0 else done.toDouble() / lessons.size)
            LazyColumn(Modifier.height(if (full) 285.dp else 174.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (lessons.isEmpty()) item { Text("今天没有课程", Modifier.padding(vertical = 30.dp)) }
                items(lessons) { lesson -> val current = lesson.slot.start <= state.secondOfDay && state.secondOfDay < lesson.slot.end; Surface(onClick = { onCourse(lesson) }, shape = RoundedCornerShape(13.dp), color = if (current) colors.secondaryContainer else colors.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) { Column { Text(clockText(lesson.slot.start), fontSize = 10.sp); Text(clockText(lesson.slot.end), fontSize = 9.sp, color = if (current) colors.onSecondaryContainer else colors.onSurfaceVariant) }; Spacer(Modifier.width(13.dp)); Column(Modifier.weight(1f)) { Text(lesson.subject?.name ?: "未安排", fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(lesson.subject?.teacher.orEmpty(), fontSize = 10.sp) }; Text(if (current) "正在上课" else if (lesson.slot.end <= state.secondOfDay) "已结束" else "未开始", fontSize = 9.sp) }
                } }
            }
            TextButton(onClick = onOpen, modifier = Modifier.align(Alignment.End)) { Text("查看完整课表", style = MaterialTheme.typography.labelMedium) }
        }
    }
}
