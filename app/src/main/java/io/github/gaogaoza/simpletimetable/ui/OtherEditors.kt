@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.gaogaoza.simpletimetable.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import io.github.gaogaoza.simpletimetable.TimetableApplication
import io.github.gaogaoza.simpletimetable.data.*
import io.github.gaogaoza.simpletimetable.system.CourseWidget
import io.github.gaogaoza.simpletimetable.system.TimetableWidget
import io.github.gaogaoza.simpletimetable.system.SurfaceUpdater
import java.time.*

@Composable fun TemporaryEditor(app: TimetableApplication, p: Profile, editor: Editor.TemporaryEdit, f: EditForm) {
    val original = remember { p.temporary.firstOrNull { it.id == editor.id } ?: Temporary(startMillis = app.clock.now(), endMillis = Instant.ofEpochMilli(app.clock.now()).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) }
    var name by remember { mutableStateOf(original.name) }; var start by remember { mutableLongStateOf(original.startMillis) }; var end by remember { mutableLongStateOf(original.endMillis) }; var type by remember { mutableStateOf(original.type.name) }; var target by remember { mutableStateOf(original.targetId) }; var inherit by remember { mutableStateOf(original.inheritGroup) }
    Input("名称", name, f) { name = it }; DateTimeControl("生效时间", start, f) { start = it }; DateTimeControl("失效时间", end, f) { end = it }
    if (original.patches.isEmpty()) {
        Choice("安排类型", type, listOf("PLAN" to "临时整张课表", "LAYER" to "临时层（只覆盖相交课程）", "GROUP" to "临时课表群"), f) { type = it; target = "" }
        Choice("覆盖来源", target, if (type == "GROUP") p.groups.map { it.id to it.name } else p.plans.map { it.id to it.name }, f) { target = it }
        if (type == "GROUP") Toggle("保留原课表群一起匹配", inherit, f) { inherit = it }
        if (type == "LAYER") Explanation("仅来源课表中已安排科目的课程时段覆盖常规课程，其他时段保留。")
    } else Explanation("这里只修改有效期，已保存的替换或交换内容保持不变。")
    Explanation("可预定未来时间。到期后恢复常规规则，记录保留以便查看。")
    f.submit = { val item = original.copy(name = requireName(name), startMillis = start, endMillis = end, type = TempType.valueOf(type), targetId = target, inheritGroup = inherit); app.repository.editProfile(p.id) { it.copy(temporary = if (editor.id.isEmpty()) it.temporary + item else it.temporary.map { t -> if (t.id == item.id) item else t }) } }
    if (editor.id.isNotEmpty()) Action("撤销临时安排", true) { f.ask("撤销后立即按其他有效安排和常规规则运行，继续？") { app.repository.editProfile(p.id) { it.copy(temporary = it.temporary.filterNot { t -> t.id == original.id }) }; f.close() } }
}

private fun findLesson(app: TimetableApplication, p: Profile, date: String, plan: String, slot: String, atSecond: Int): Lesson? {
    val local = Instant.ofEpochMilli(app.clock.now()).atZone(ZoneId.systemDefault())
    val second = if (atSecond >= 0) atSecond else if (local.toLocalDate().toString() == date) local.toLocalTime().toSecondOfDay() else -1
    val lessons = Engine.resolve(p, LocalDate.parse(date), app.clock.now()).lessons.filter { it.planId == plan && it.sourceSlotId == slot }
    return lessons.firstOrNull { second >= it.slot.start && second < it.slot.end } ?: lessons.firstOrNull()
}

@Composable fun DetailEditor(app: TimetableApplication, data: AppData, editor: Editor.Detail, f: EditForm) {
    val p = data.profiles.firstOrNull { it.id == editor.profile } ?: return
    val lesson = findLesson(app, p, editor.date, editor.plan, editor.slot, editor.atSecond)
    if (lesson == null) { Explanation("这节课程已不再适用于所选日期，请返回课表重新选择。") ; return }
    Text(lesson.subject?.name ?: "未安排科目", style = MaterialTheme.typography.headlineSmall)
    Explanation("${dateLabel(LocalDate.parse(editor.date))} · ${clockText(lesson.slot.start)} — ${clockText(lesson.slot.end)}\n${lesson.subject?.teacher.orEmpty()}${if (lesson.subject?.outdoor == true) " · 户外课程" else ""}\n${p.name} / ${p.plans.firstOrNull { it.id == lesson.planId }?.name.orEmpty()}")
    Action("仅修改这一次") { f.open(Editor.ChangeLesson(p.id, editor.date, editor.plan, editor.slot, atSecond = editor.atSecond)) }
    Action("编辑常规安排") { f.open(Editor.ChangeLesson(p.id, editor.date, editor.plan, editor.slot, true, editor.atSecond)) }
    Action("与另一节交换") { f.open(Editor.Swap(p.id, editor.date, editor.plan, editor.slot, editor.atSecond)) }
}

@Composable fun LessonEditor(app: TimetableApplication, data: AppData, editor: Editor.ChangeLesson, f: EditForm) {
    val p = data.profiles.first { it.id == editor.profile }; val lesson = findLesson(app, p, editor.date, editor.plan, editor.slot, editor.atSecond)
    if (lesson == null) { Explanation("课程已变更，请返回重新选择。") ; return }
    val date = LocalDate.parse(editor.date); val zone = ZoneId.systemDefault()
    var subject by remember { mutableStateOf(lesson.subject?.id.orEmpty()) }; var start by remember { mutableLongStateOf(date.atStartOfDay(zone).toInstant().toEpochMilli()) }; var end by remember { mutableLongStateOf(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()) }
    Choice("科目", subject, p.subjects.map { it.id to it.name }, f) { subject = it }
    if (!editor.permanent) { DateTimeControl("生效时间", start, f) { start = it }; DateTimeControl("失效时间", end, f) { end = it } } else Explanation("常规修改将影响以后所有使用此课表的日期。")
    f.submit = {
        require(p.subjects.any { it.id == subject }) { "请选择科目" }
        if (editor.permanent) app.repository.editProfile(p.id) { it.copy(plans = it.plans.map { q -> if (q.id == editor.plan) q.copy(subjects = q.subjects + (editor.slot to subject)) else q }) }
        else { val from = start; val until = end; val lessonTime = date.atStartOfDay(zone).toInstant().toEpochMilli() + lesson.slot.start * 1000L; require(from <= lessonTime && lessonTime < until) { "有效期需要包含所选课程开始时刻" }; app.repository.editProfile(p.id) { it.copy(temporary = it.temporary + Temporary(name = "${dateLabel(date)} · 单节修改", type = TempType.LESSON, startMillis = from, endMillis = until, patches = listOf(LessonPatch(editor.date, editor.plan, editor.slot, subject)))) } }
    }
}

@Composable fun SwapEditor(app: TimetableApplication, data: AppData, editor: Editor.Swap, f: EditForm) {
    val p = data.profiles.first { it.id == editor.profile }; val a = findLesson(app, p, editor.date, editor.plan, editor.slot, editor.atSecond)
    if (a == null) { Explanation("课程已变更，请重新选择。") ; return }
    var date by remember { mutableStateOf(LocalDate.parse(editor.date)) }; var target by remember { mutableStateOf("") }; var permanent by remember { mutableStateOf(false) }
    DateControl("另一节课的日期", date, f) { date = it; target = "" }
    val choices = runCatching { Engine.resolve(p, date, app.clock.now()).lessons }.getOrDefault(emptyList())
    Choice("交换课程", target, choices.map { "${it.planId}|${it.slot.id}" to "${clockText(it.slot.start)} ${it.subject?.name ?: "未安排"}" }, f) { target = it }
    Toggle("永久交换常规安排", permanent, f) { permanent = it }
    val b = choices.firstOrNull { "${it.planId}|${it.slot.id}" == target }
    if (b != null) {
        Explanation("${editor.date} ${clockText(a.slot.start)}：${a.subject?.name} → ${b.subject?.name}\n$date ${clockText(b.slot.start)}：${b.subject?.name} → ${a.subject?.name}")
        Explanation(if (permanent) "会修改两份常规课表的科目引用。" else "只对所选日期生效，到期后恢复常规安排。")
    }
    f.submit = {
        require(b != null && a.subject != null && b.subject != null) { "请先选择已安排科目的另一节课" }
        require(!(editor.date == date.toString() && a.planId == b.planId && a.sourceSlotId == b.sourceSlotId)) { "不能与同一节课交换" }
        if (permanent) {
            require(!a.temporary && !b.temporary && (a.planId != b.planId || a.sourceSlotId != b.sourceSlotId)) { "临时课程或同一常规课程位置不能永久交换" }
            app.repository.editProfile(p.id) { profile -> profile.copy(plans = profile.plans.map { q -> var assignments = q.subjects; if (q.id == a.planId) assignments = assignments + (a.sourceSlotId to b.subject.id); if (q.id == b.planId) assignments = assignments + (b.sourceSlotId to a.subject.id); q.copy(subjects = assignments) }) }
        } else {
            val dates = listOf(LocalDate.parse(editor.date), date).sorted(); val zone = ZoneId.systemDefault()
            val temporary = Temporary(name = "两节课程交换", type = TempType.SWAP, startMillis = dates.first().atStartOfDay(zone).toInstant().toEpochMilli(), endMillis = dates.last().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), patches = listOf(LessonPatch(editor.date, a.planId, a.sourceSlotId, b.subject.id), LessonPatch(date.toString(), b.planId, b.sourceSlotId, a.subject.id)))
            app.repository.editProfile(p.id) { it.copy(temporary = it.temporary + temporary) }
        }
    }
}

val seedPresets = listOf("松柏绿" to "#355E45", "海盐蓝" to "#3568B0", "湖水青" to "#087F8C", "鸢尾紫" to "#7856A6", "玫瑰粉" to "#B34E78", "琥珀橙" to "#B86B18", "石墨灰" to "#66717E")
@Composable fun AppearanceEditor(app: TimetableApplication, data: AppData, session: UiSession, f: EditForm) {
    val original = remember { data.settings }; var draft by remember { mutableStateOf(original) }; var seed by remember { mutableStateOf(original.seed) }
    fun change(next: Settings) { draft = next; session.appearanceDraft = next; f.changed() }
    ColorControl("主题种子色", seed, f) { seed = it; change(draft.copy(seed = it)) }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("文字与控件预览", style = MaterialTheme.typography.titleMedium)
            Text("课程名称、时间与正文会保持清晰可读。")
            LinearProgressIndicator(progress = { .6f }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onPrimaryContainer, trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .15f))
        }
    }
    OutlinedTextField(value = "08:00 — 08:45", onValueChange = {}, readOnly = true, label = { Text("输入框预览") }, modifier = Modifier.fillMaxWidth())
    Choice("显示模式", draft.theme, listOf("light" to "浅色", "dark" to "深色", "system" to "跟随系统"), f) { change(draft.copy(theme = it)) }
    Toggle("大字号", draft.largeText, f) { change(draft.copy(largeText = it)) }; Toggle("高对比度", draft.highContrast, f) { change(draft.copy(highContrast = it)) }; Toggle("减少动画", draft.reducedMotion, f) { change(draft.copy(reducedMotion = it)) }
    Explanation("保存后应用，取消恢复原主题。科目颜色可单独设置。")
    f.submit = { require(seed.matches(Regex("#[0-9a-fA-F]{6}"))) { "请填写有效颜色" }; app.repository.settings { it.copy(seed = seed, theme = draft.theme, largeText = draft.largeText, highContrast = draft.highContrast, reducedMotion = draft.reducedMotion) } }
}

@Composable fun ClockEditor(app: TimetableApplication, data: AppData, session: UiSession, f: EditForm) {
    val s = data.settings; var software by remember { mutableStateOf(s.softwareClock) }; var offset by remember { mutableIntStateOf(s.schoolOffset) }; var slow by remember { mutableStateOf(s.schoolOffset < 0) }; var auto by remember { mutableStateOf(s.autoSync) }; var server by remember { mutableStateOf(s.ntpHost) }
    Choice("时间来源", if (software) "software" else "system", listOf("system" to "系统时间", "software" to "软件时间"), f) { software = it == "software" }; Choice("学校时钟方向", if (slow) "slow" else "fast", listOf("fast" to "比设备时间快", "slow" to "比设备时间慢"), f) { slow = it == "slow"; offset = if (slow) -kotlin.math.abs(offset) else kotlin.math.abs(offset) }
    NumberControl("偏移分钟", kotlin.math.abs(offset) / 60, 0..1440, f, "分钟") { val n = (it * 60 + kotlin.math.abs(offset) % 60).coerceAtMost(86400); offset = if (slow) -n else n }
    NumberControl("偏移秒", kotlin.math.abs(offset) % 60, 0..if (kotlin.math.abs(offset) / 60 == 1440) 0 else 59, f, "秒") { val n = kotlin.math.abs(offset) / 60 * 60 + it; offset = if (slow) -n else n }
    Explanation(if (offset == 0) "与设备时间一致" else "学校时间比设备时间${if (offset > 0) "快" else "慢"} ${kotlin.math.abs(offset) / 60} 分 ${kotlin.math.abs(offset) % 60} 秒"); Toggle("自动联网校时", auto, f) { auto = it }; Input("NTP 服务器", server, f) { server = it }
    Explanation("设备校准：${s.calibrationMillis} 毫秒\n状态：${app.clock.status()}\n最近校时：${if (s.syncEpoch > 0) localDateTime(s.syncEpoch) else "尚无"}")
    Explanation("学校偏移独立于设备校准，用于对齐学校作息。软件校时失败时保留可用校准；设备重启后会重新校时。")
    Action("立即校时") { f.run { require(server == s.ntpHost) { "请先保存新的服务器地址" }; app.clock.sync { result -> Handler(Looper.getMainLooper()).post { session.message = if (result.isSuccess) "校时成功" else "校时失败，请检查网络或服务器"; SurfaceUpdater.refresh(app) } } } }
    f.submit = { require(server.isNotBlank() && server.length <= 253 && !server.contains('/')) { "请填写 NTP 主机名" }; app.repository.settings { it.copy(softwareClock = software, schoolOffset = offset, autoSync = auto, ntpHost = server.trim()) }; app.clock.autoSync() }
}

@Composable fun SurfaceSettingsEditor(app: TimetableApplication, data: AppData, session: UiSession, f: EditForm) {
    var notice by remember { mutableStateOf(data.settings.notification) }; val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> notice = allowed; f.changed(); if (!allowed) session.message = "未获得通知权限，可在系统设置中开启" }
    Toggle("显示静默常驻通知", notice, f) { wanted -> if (wanted && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else notice = wanted }
    Explanation("通知保持静默，不发声音或振动。数字由系统计时，进度及课程切换仍受设备省电策略影响。")
    if (Build.VERSION.SDK_INT >= 31 && !SurfaceUpdater.exactAllowed(context)) {
        Explanation("允许精确课程切换后，熄屏时可按课程边界刷新。未允许时由系统安排刷新，可能延迟。此权限不会产生声音或提醒。")
        Action("允许按课程时间准确切换") { f.run { context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}"))) } }
    }
    SurfaceUpdater.lastProblem?.let { Explanation(it) }
    Action("打开系统通知设置") { context.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)) }
    fun pin(provider: Class<*>) { f.run { val manager = AppWidgetManager.getInstance(context); if (manager.isRequestPinAppWidgetSupported) { require(manager.requestPinAppWidget(ComponentName(context, provider), null, null)) { "桌面没有接受添加请求" }; session.message = "请在桌面确认添加" } else session.message = "请长按桌面，在小组件列表中选择简单课表" } }
    Action("添加课程卡片到桌面") { pin(CourseWidget::class.java) }; Action("添加今日课表到桌面") { pin(TimetableWidget::class.java) }
    f.submit = { app.repository.settings { it.copy(notification = notice) } }
}

@Composable fun SourceEditor(app: TimetableApplication, data: AppData, f: EditForm) {
    val p = data.active(); val state = Engine.state(p, app.clock.now()); val r = state.resolution
    Explanation("档案：${p.name}\n日期：${dateLabel(r.date)}\n来源：${r.reason}\n课表：${r.plan?.name ?: "无"}\n${app.clock.status()}\n学校偏移：${data.settings.schoolOffset} 秒")
    if (r.conflicts) Text("有多份课表同时匹配，请在管理中检查启用规则。", color = MaterialTheme.colorScheme.error)
}

@Composable fun DateEditor(session: UiSession, editor: Editor.DatePick, f: EditForm) {
    var date by remember { mutableStateOf(LocalDate.parse(editor.initial)) }
    CalendarControl(date) { date = it; f.changed() }
    f.submit = { session.date = date }
}
