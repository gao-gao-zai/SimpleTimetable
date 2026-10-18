@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.gaogaoza.simpletimetable.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.gaogaoza.simpletimetable.TimetableApplication
import io.github.gaogaoza.simpletimetable.data.*
import io.github.gaogaoza.simpletimetable.system.SurfaceUpdater
import kotlinx.coroutines.delay
import java.time.*

sealed interface Editor {
    data class SubjectEdit(val id: String = "") : Editor
    data class LayoutEdit(val id: String = "") : Editor
    data class SlotEdit(val layout: String, val id: String = "") : Editor
    data class PlanEdit(val id: String = "") : Editor
    data class PlanItems(val id: String) : Editor
    data class GroupEdit(val id: String = "") : Editor
    data class ProfileEdit(val id: String = "") : Editor
    data class TemporaryEdit(val id: String = "") : Editor
    data class Detail(val profile: String, val date: String, val plan: String, val slot: String, val atSecond: Int = -1) : Editor
    data class ChangeLesson(val profile: String, val date: String, val plan: String, val slot: String, val permanent: Boolean = false, val atSecond: Int = -1) : Editor
    data class Swap(val profile: String, val date: String, val plan: String, val slot: String, val atSecond: Int = -1) : Editor
    data class DatePick(val initial: String) : Editor
    data object Appearance : Editor
    data object Clock : Editor
    data object Activation : Editor
    data object SurfaceSettings : Editor
    data object Source : Editor
}
class UiSession(activeId: String, date: LocalDate) {
    var page by mutableStateOf("home")
    var profileId by mutableStateOf(activeId)
    var date by mutableStateOf(date)
    var editor by mutableStateOf<Editor?>(null)
    var appearanceDraft by mutableStateOf<Settings?>(null)
    var widgetView by mutableStateOf("card")
    var widgetFull by mutableStateOf(true)
    var surfaceView by mutableStateOf("widget")
    var notificationExpanded by mutableStateOf(true)
    var message by mutableStateOf("")
}
val pageTitles = mapOf("home" to "现在", "schedule" to "课表", "manage" to "管理", "subjects" to "科目", "layouts" to "时间表", "plans" to "课表", "groups" to "课表群与启用", "temporary" to "临时安排", "profiles" to "档案", "settings" to "显示与时间", "external" to "通知与小组件", "import" to "导入课表")
fun dateLabel(date: LocalDate) = "${date.monthValue} 月 ${date.dayOfMonth} 日 · 周${"日一二三四五六"[date.dayOfWeek.value % 7]}"

@Composable fun TimetableApp(app: TimetableApplication, launch: Intent) {
    val db by app.repository.data.collectAsState()
    val session = remember { UiSession(db.active().id, Instant.ofEpochMilli(app.clock.now()).atZone(ZoneId.systemDefault()).toLocalDate()) }
    LaunchedEffect(launch) {
        if (launch.getStringExtra("page") == "schedule") {
            session.page = "schedule"
            session.profileId = db.profiles.firstOrNull { it.id == launch.getStringExtra("profile") }?.id ?: db.active().id
            runCatching { LocalDate.parse(launch.getStringExtra("date")) }.getOrNull()?.let { session.date = it }
            val plan = launch.getStringExtra("plan"); val slot = launch.getStringExtra("slot")
            if (plan != null && slot != null) session.editor = Editor.Detail(session.profileId, session.date.toString(), plan, slot)
        }
        app.clock.autoSync()
    }
    val settings = session.appearanceDraft ?: db.settings
    TimetableTheme(settings) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var now by remember { mutableLongStateOf(app.clock.now()) }
        LaunchedEffect(lifecycle, db.settings) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { now = app.clock.now(); if (now / 1000 % 60 == 0L) app.clock.autoSync(); delay(1000) } } }
        val config = LocalConfiguration.current
        val compact = config.screenHeightDp < 600
        val reduce = settings.reducedMotion || AndroidSettings.Global.getFloat(LocalContext.current.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        val profile = db.profiles.firstOrNull { it.id == session.profileId } ?: db.active()
        val state = remember(db, now) { Engine.state(db.active(), now) }
        val activeDate = state.resolution.date
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(session.message) { if (session.message.isNotBlank()) { snack.showSnackbar(session.message); session.message = "" } }
        LaunchedEffect(db) { SurfaceUpdater.refresh(app) }
        BackHandler(enabled = session.editor == null && session.page != "home") { session.page = if (session.page in listOf("schedule", "manage")) "home" else "manage" }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(title = { Column { Text(pageTitles[session.page] ?: "简单课表", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); if (session.page == "home") Text(dateLabel(activeDate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    navigationIcon = { if (session.page !in listOf("home", "schedule", "manage")) IconButton(onClick = { session.page = "manage" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回管理") } },
                    actions = { if (session.page == "schedule") TextButton(onClick = { session.date = activeDate }) { Text("今天") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
            },
            bottomBar = { MainNavigation(session, compact) }, snackbarHost = { SnackbarHost(snack) }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                app.repository.loadError?.let { RecoveryBanner(app, it) }
                AnimatedContent(targetState = session.page, modifier = Modifier.weight(1f), label = "页面切换", transitionSpec = {
                    val direction = if (listOf("home", "schedule", "manage").indexOf(targetState) >= listOf("home", "schedule", "manage").indexOf(initialState)) 1 else -1
                    if (reduce) fadeIn(snap()) togetherWith fadeOut(snap()) else (slideInHorizontally(tween(230)) { it / 5 * direction } + fadeIn(tween(230))) togetherWith (slideOutHorizontally(tween(230)) { -it / 5 * direction } + fadeOut(tween(180)))
                }) { page -> when (page) {
                    "home" -> NowPage(state, session, compact, db)
                    "schedule" -> SchedulePage(profile, now, session, reduce)
                    "manage" -> ManagePage(profile, db, session)
                    "subjects", "layouts", "plans", "groups", "temporary", "profiles" -> ManageList(page, profile, db, now, session)
                    "settings" -> SettingsPage(app, db, session)
                    "external" -> SystemPreviewPage(app, db, state, session, compact)
                    "import" -> ImportPage(app, profile, session)
                } }
            }
        }
        session.editor?.let { editor -> EditorHost(app, db, profile, session, editor, reduce) }
    }
}

@Composable private fun MainNavigation(session: UiSession, compact: Boolean) {
    val entries = listOf(Triple("home", "现在", Icons.Default.Home), Triple("schedule", "课表", Icons.Default.DateRange), Triple("manage", "管理", Icons.Default.GridView))
    val selected = when (session.page) { "home", "schedule" -> session.page; else -> "manage" }
    if (compact) Surface(color = MaterialTheme.colorScheme.surfaceContainer) { Row(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = 52.dp)) { entries.forEach { (key, text, icon) ->
        TextButton(onClick = { session.page = key }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(4.dp), colors = ButtonDefaults.textButtonColors(contentColor = if (key == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(text, fontSize = 12.sp) }
    } } } else NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) { entries.forEach { (key, text, icon) -> NavigationBarItem(selected = key == selected, onClick = { session.page = key }, icon = { Icon(icon, null) }, label = { Text(text) }) } }
}

@Composable fun SectionRow(title: String, subtitle: String, icon: ImageVector = Icons.Default.ChevronRight, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp).heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) { Icon(icon, null, Modifier.padding(8.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable fun InactiveProfile(profile: Profile, db: AppData) { if (profile.id != db.active().id) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) { Text("正在编辑「${profile.name}」· 未启用", Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall) } }
@Composable fun ProgressBar(progress: Double?, modifier: Modifier = Modifier) { LinearProgressIndicator(progress = { (progress ?: 0.0).toFloat().coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth().height(6.dp), trackColor = MaterialTheme.colorScheme.surfaceContainerHighest) }
fun openLesson(session: UiSession, profile: Profile, date: LocalDate, lesson: Lesson) { session.editor = Editor.Detail(profile.id, date.toString(), lesson.planId, lesson.sourceSlotId, lesson.slot.start) }

@Composable private fun NowPage(state: CourseState, session: UiSession, compact: Boolean, db: AppData) {
    val active = db.active(); val idle = state.phase in listOf(Phase.EMPTY, Phase.ENDED)
    val subject = if (state.phase == Phase.CLASS) state.current?.subject else state.next?.subject
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = if (compact) 16.dp else 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!compact) item { TextButton(onClick = { session.editor = Editor.Source }, contentPadding = PaddingValues(0.dp)) { Text(active.name, style = MaterialTheme.typography.labelMedium); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp)) } }
        item {
            if (compact) CompactNowHero(state, active, session)
            else Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.primaryContainer, onClick = { (state.current ?: state.next)?.let { openLesson(session, active, state.resolution.date, it) } }) {
                Column(Modifier.fillMaxWidth().padding(if (compact) 18.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp)) {
                    Text(state.headingLabel(), style = MaterialTheme.typography.labelMedium)
                    val title = subject?.name?.takeIf { !idle } ?: state.title
                    Text(title, fontSize = if (title.length > 14) 24.sp else if (compact) 28.sp else 32.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
                    if (!compact && !idle && !subject?.teacher.isNullOrBlank()) Text(subject!!.teacher, style = MaterialTheme.typography.bodySmall)
                    if (!idle) {
                        Text(durationText(state.remaining), fontSize = if (compact) 43.sp else 54.sp, lineHeight = if (compact) 48.sp else 60.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clearAndSetSemantics { contentDescription = (if (state.phase == Phase.CLASS) "距下课 " else "距上课 ") + durationText(state.remaining) })
                        Text(if (state.phase == Phase.CLASS) "距下课" else "距上课", style = MaterialTheme.typography.bodySmall)
                    }
                    LinearProgressIndicator(progress = { (state.progress ?: 0.0).toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f))
                    if (!compact) Text(state.progressLabel(), style = MaterialTheme.typography.labelSmall)
                    if (state.next != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .15f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${if (state.phase == Phase.BEFORE) "第一节" else "下一节"}  ${state.next.subject?.name ?: "未安排科目"}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text(clockText(state.next.slot.start) + " 上课", style = MaterialTheme.typography.bodySmall) }
                    } else if (state.phase == Phase.CLASS) Text("当前是今日最后一节", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("今日课程 · ${state.resolution.lessons.size} 节", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); TextButton(onClick = { session.profileId = active.id; session.date = state.resolution.date; session.page = "schedule" }) { Text("查看全部") } } }
        if (state.resolution.lessons.isEmpty()) item { Text("在管理中添加科目、时间表和课表，即可开始使用。", color = MaterialTheme.colorScheme.onSurfaceVariant); Button(onClick = { session.profileId = active.id; session.page = "manage" }) { Text("管理课表") } }
        items(state.resolution.lessons, key = { it.planId + it.slot.id }) { LessonRow(it, state.secondOfDay, true) { openLesson(session, active, state.resolution.date, it) } }
    }
}

@Composable private fun CompactNowHero(state: CourseState, active: Profile, session: UiSession) {
    val idle = state.phase in listOf(Phase.EMPTY, Phase.ENDED)
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, onClick = { (state.current ?: state.next)?.let { openLesson(session, active, state.resolution.date, it) } }) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            val narrow = maxWidth < 300.dp
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(state.headingLabel(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall); Text(active.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.title, Modifier.weight(1f), fontSize = if (narrow || state.title.length > 12) 22.sp else 28.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)
                    if (!idle) { Spacer(Modifier.width(8.dp)); Column(horizontalAlignment = Alignment.End) { Text(durationText(state.remaining), fontSize = if (narrow) 31.sp else 43.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clearAndSetSemantics { contentDescription = "${if (state.phase == Phase.CLASS) "距下课" else "距上课"} ${durationText(state.remaining)}" }); Text(if (state.phase == Phase.CLASS) "距下课" else "距上课", style = MaterialTheme.typography.labelSmall) } }
                }
                LinearProgressIndicator(progress = { (state.progress ?: 0.0).toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .15f))
                if (state.phase != Phase.EMPTY) Text(state.next?.let { "${if (state.phase == Phase.BEFORE) "第一节" else "下一节"} ${it.subject?.name ?: "未安排科目"} · ${clockText(it.slot.start)} 上课" } ?: if (state.phase == Phase.CLASS) "当前是今日最后一节" else state.progressLabel(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable fun LessonRow(lesson: Lesson, second: Int, live: Boolean, onClick: () -> Unit) {
    val current = live && second >= lesson.slot.start && second < lesson.slot.end
    val ended = live && second >= lesson.slot.end
    val subject = lesson.subject
    val mainInk = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val mutedInk = if (current) mainInk else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, border = if (current) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            val color = subject?.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: MaterialTheme.colorScheme.primary
            val badge = color.copy(alpha = .12f).compositeOver(if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            val ink = if (androidx.core.graphics.ColorUtils.calculateContrast(color.toArgb(), badge.toArgb()) >= 4.5) color else mainInk
            Surface(color = badge, shape = RoundedCornerShape(10.dp)) { Text(subject?.shortName?.ifBlank { subject.name.take(1) } ?: "空", Modifier.padding(11.dp), color = ink, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(subject?.name ?: "未安排科目", fontWeight = if (ended) FontWeight.Normal else FontWeight.SemiBold, color = if (ended) mutedInk else mainInk); Text("${clockText(lesson.slot.start)} — ${clockText(lesson.slot.end)}" + (if (subject?.teacher.isNullOrBlank()) "" else " · ${subject!!.teacher}"), style = MaterialTheme.typography.labelSmall, color = mutedInk); if (lesson.temporary) Text("临时安排", style = MaterialTheme.typography.labelSmall, color = if (current) mainInk else MaterialTheme.colorScheme.tertiary) }
            if (current || ended) Text(if (current) "进行中" else "已结束", fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp)) else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
        }
    }
}

@Composable private fun SchedulePage(profile: Profile, millis: Long, session: UiSession, reduce: Boolean) {
    val date = session.date
    val today = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    var drag by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(profile.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { session.date = date.minusDays(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一天") }
            TextButton(onClick = { session.editor = Editor.DatePick(date.toString()) }, modifier = Modifier.weight(1f)) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(dateLabel(date)); if (date == today) Text("今天", style = MaterialTheme.typography.labelSmall) } }
            IconButton(onClick = { session.date = date.plusDays(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一天") }
        }
        AnimatedContent(date, label = "翻页", modifier = Modifier.weight(1f).pointerInput(date) { detectHorizontalDragGestures(onDragStart = { drag = 0f }, onHorizontalDrag = { _, amount -> drag += amount }, onDragEnd = { if (drag > 70) session.date = date.minusDays(1); if (drag < -70) session.date = date.plusDays(1) }) }, transitionSpec = { if (reduce) fadeIn(snap()) togetherWith fadeOut(snap()) else (slideInHorizontally(tween(230)) { if (targetState > initialState) it / 4 else -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally(tween(230)) { if (targetState > initialState) -it / 4 else it / 4 } + fadeOut()) }) { selected ->
            val result = remember(profile, selected) { Engine.resolve(profile, selected, millis) }; val live = selected == today
            val second = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().toSecondOfDay()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { Text(result.plan?.name ?: if (result.lessons.isNotEmpty()) "当日课程安排" else "没有匹配的课表", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (result.conflicts) item { Text("多个课表适用于这一天，请检查启用规则。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (result.lessons.isEmpty()) item { Text("这一天没有课程", Modifier.padding(vertical = 24.dp)) }
                items(result.slots, key = { it.id }) { slot ->
                    val lesson = result.lessons.firstOrNull { it.slot.id == slot.id }
                    if (lesson != null) Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.width(12.dp).fillMaxHeight(), contentAlignment = Alignment.Center) { Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant)); Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))) }; Spacer(Modifier.width(8.dp)); Box(Modifier.weight(1f)) { LessonRow(lesson, second, live) { openLesson(session, profile, selected, lesson) } } }
                    else if (slot.type != SlotType.LESSON) Text((if (slot.type == SlotType.DIVIDER) "" else "${clockText(slot.start)} — ${clockText(slot.end)} · ") + slot.name, Modifier.padding(start = 22.dp, top = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun ManagePage(profile: Profile, db: AppData, session: UiSession) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { InactiveProfile(profile, db) }
        val entries = listOf(
            Triple("profiles", "档案", profile.name + if (profile.id == db.active().id) " · 当前启用" else " · 当前运行 ${db.active().name}"),
            Triple("subjects", "科目", "${profile.subjects.size} 个 · 名称、教师与颜色"), Triple("layouts", "时间表", "${profile.layouts.size} 份 · 时段与共享引用"),
            Triple("plans", "课表", "${profile.plans.size} 份 · 日期与多周规则"), Triple("groups", "课表群与启用", "自动、手动与临时覆盖"), Triple("temporary", "临时安排", "${profile.temporary.size} 项 · 有效期与调课"),
            Triple("import", "导入课表", "ClassIsland、Excel 和 CSV"), Triple("settings", "显示与时间", "主题、字号与时间校准"), Triple("external", "通知与小组件", "课程卡片与今日课表")
        )
        items(entries) { (page, title, sub) -> SectionRow(title, sub, when (page) { "profiles" -> Icons.Default.Folder; "subjects" -> Icons.Default.MenuBook; "layouts" -> Icons.Default.Schedule; "plans" -> Icons.Default.DateRange; "groups" -> Icons.Default.Layers; "temporary" -> Icons.Default.SwapHoriz; "import" -> Icons.Default.FileDownload; "settings" -> Icons.Default.Settings; else -> Icons.Default.Widgets }) { session.page = page } }
    }
}

@Composable private fun ManageList(page: String, profile: Profile, db: AppData, now: Long, session: UiSession) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { InactiveProfile(profile, db) }
        when (page) {
            "subjects" -> { items(profile.subjects) { s -> SectionRow(s.name, s.teacher + if (s.outdoor) " · 户外" else "", Icons.Default.MenuBook) { session.editor = Editor.SubjectEdit(s.id) } }; item { AddButton("新建科目") { session.editor = Editor.SubjectEdit() } } }
            "layouts" -> { items(profile.layouts) { l -> SectionRow(l.name, "${l.slots.size} 个条目 · ${profile.plans.count { it.layoutId == l.id }} 份课表引用", Icons.Default.Schedule) { session.editor = Editor.LayoutEdit(l.id) } }; item { AddButton("新建时间表") { session.editor = Editor.LayoutEdit() } } }
            "plans" -> { items(profile.plans) { q -> SectionRow(q.name, "${q.cycle} 周轮换 · 第 ${q.week} 周 · " + q.days.sorted().joinToString("、") { "周${"日一二三四五六"[it]}" } + if (!q.enabled) " · 未启用" else "", Icons.Default.DateRange) { session.editor = Editor.PlanEdit(q.id) } }; item { AddButton("新建课表") { if (profile.layouts.isEmpty()) { session.message = "请先创建时间表"; session.editor = Editor.LayoutEdit() } else session.editor = Editor.PlanEdit() } } }
            "groups" -> { items(profile.groups) { g -> SectionRow(g.name, "${profile.plans.count { it.groupId == g.id }} 份课表" + if (profile.activeGroup == g.id) " · 自动匹配范围" else "", Icons.Default.Layers) { session.editor = Editor.GroupEdit(g.id) } }; item { AddButton("新建课表群") { session.editor = Editor.GroupEdit() }; OutlinedButton(onClick = { session.editor = Editor.Activation }, modifier = Modifier.fillMaxWidth()) { Text("自动 / 手动启用") } } }
            "temporary" -> { items(profile.temporary) { t -> val phase = if (now < t.startMillis) "未开始" else if (now >= t.endMillis) "已到期" else "生效中"; SectionRow(t.name, "$phase · ${localDateTime(t.startMillis)} 至 ${localDateTime(t.endMillis)}", Icons.Default.SwapHoriz) { session.editor = Editor.TemporaryEdit(t.id) } }; item { if (profile.temporary.isEmpty()) Text("暂无临时安排，可从课程详情替换或交换课程。", color = MaterialTheme.colorScheme.onSurfaceVariant); AddButton("新建临时安排") { session.editor = Editor.TemporaryEdit() } } }
            "profiles" -> { items(db.profiles) { p -> SectionRow(p.name, (if (p.id == db.active().id) "当前启用" else "未启用") + if (p.id == profile.id) " · 正在浏览" else "", Icons.Default.Folder) { session.editor = Editor.ProfileEdit(p.id) } }; item { AddButton("新建档案") { session.editor = Editor.ProfileEdit() } } }
        }
    }
}
@Composable fun AddButton(text: String, onClick: () -> Unit) { Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(text) } }
fun localDateTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
fun parseDateTime(text: String): Long = LocalDateTime.parse(text.trim().replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

@Composable private fun SettingsPage(app: TimetableApplication, db: AppData, session: UiSession) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionRow("主题与阅读", "种子色、预设主题、字号与动画", Icons.Default.Palette) { session.editor = Editor.Appearance }
        SectionRow("课表时间", app.clock.status() + " · 学校偏移 ${db.settings.schoolOffset} 秒", Icons.Default.Schedule) { session.editor = Editor.Clock }
        SectionRow("通知与小组件", "显示设置与系统入口", Icons.Default.Widgets) { session.page = "external" }
        Text("简单课表 ${io.github.gaogaoza.simpletimetable.BuildConfig.VERSION_NAME} · GPL v3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
