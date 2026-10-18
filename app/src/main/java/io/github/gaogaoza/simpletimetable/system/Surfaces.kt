@file:Suppress("DEPRECATION")
package io.github.gaogaoza.simpletimetable.system

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.*
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.gaogaoza.simpletimetable.MainActivity
import io.github.gaogaoza.simpletimetable.R
import io.github.gaogaoza.simpletimetable.TimetableApplication
import io.github.gaogaoza.simpletimetable.data.*
import io.github.gaogaoza.simpletimetable.ui.ThemeColors
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

object SurfaceUpdater {
    const val CHANNEL = "silent_timetable"
    const val NOTIFICATION = 1042
    private const val UPDATE = "io.github.gaogaoza.simpletimetable.UPDATE"
    private const val STOP = "io.github.gaogaoza.simpletimetable.STOP"
    @Volatile var lastProblem: String? = null
    private fun app(context: Context) = context.applicationContext as TimetableApplication
    fun notificationsAllowed(context: Context) = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun exactAllowed(context: Context) = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun refresh(context: Context) {
        update(context)
        val enabled = app(context).repository.current.settings.notification
        if (enabled && notificationsAllowed(context)) runCatching { ContextCompat.startForegroundService(context, Intent(context, DisplayService::class.java)) }.onFailure { lastProblem = "系统未允许启动常驻显示，请重新打开应用或检查省电设置。"; Log.w("SimpleTimetable", "Display service start denied", it) }
        else { context.stopService(Intent(context, DisplayService::class.java)); context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION) }
    }
    @Synchronized fun update(context: Context) {
        val application = app(context); application.clock.autoSync(); val data = application.repository.current
        val state = Engine.state(data.active(), application.clock.now())
        val manager = AppWidgetManager.getInstance(context)
        val courseIds = manager.getAppWidgetIds(ComponentName(context, CourseWidget::class.java))
        val tableIds = manager.getAppWidgetIds(ComponentName(context, TimetableWidget::class.java))
        courseIds.forEach { id ->
            val options = manager.getAppWidgetOptions(id); val small = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 220) < 260
            manager.updateAppWidget(id, courseViews(context, state, !small, false, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300) < 260 || data.settings.largeText))
        }
        tableIds.forEach { id -> manager.updateAppWidget(id, timetableViews(context, state, id)) }
        if (tableIds.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(tableIds, R.id.widget_list)
        if (data.settings.notification && notificationsAllowed(context)) context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(context, state))
        else context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        schedule(context, state, courseIds.isNotEmpty() || tableIds.isNotEmpty() || data.settings.notification)
    }
    private fun colors(context: Context): androidx.compose.material3.ColorScheme {
        val settings = app(context).repository.current.settings
        val dark = settings.theme == "dark" || settings.theme == "system" && context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return ThemeColors.colors(settings, dark)
    }
    private fun open(context: Context, page: String = "home", request: Int = 1): PendingIntent = PendingIntent.getActivity(context, request, Intent(context, MainActivity::class.java).setAction("open_$page").putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun progressBitmap(value: Double?, color: Int, track: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 12, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = track; canvas.drawRoundRect(0f, 0f, 600f, 12f, 6f, 6f, paint)
        paint.color = color; val width = ((value ?: 0.0).coerceIn(0.0, 1.0) * 600).toFloat()
        if (width > 0) canvas.drawRoundRect(0f, 0f, width, 12f, 6f, 6f, paint)
        return bitmap
    }
    fun courseViews(context: Context, state: CourseState, full: Boolean, notification: Boolean, stacked: Boolean = false): RemoteViews {
        val s = app(context).repository.current.settings; val palette = colors(context); val idle = state.phase in listOf(Phase.EMPTY, Phase.ENDED)
        val compactNotification = notification && !full
        val views = RemoteViews(context.packageName, if (compactNotification) R.layout.notification_compact else if (!full && !notification) R.layout.course_widget_compact else if (stacked && !notification) R.layout.course_widget_stacked else R.layout.course_widget)
        val title = if (state.phase == Phase.CLASS) state.current?.subject?.name ?: "未安排科目" else state.title
        val target = state.targetSecond
        val label = when (state.phase) { Phase.CLASS -> "本节已过去 ${((state.progress ?: 0.0) * 100).toInt()}%"; Phase.BREAK -> "${state.status}已过 ${((state.progress ?: 0.0) * 100).toInt()}%"; Phase.BEFORE -> "首课 ${target?.let(::clockText).orEmpty()} 上课"; Phase.EMPTY -> "今天没有课程"; Phase.ENDED -> "全部 ${state.resolution.lessons.size} 节课已结束" }
        val next = state.next?.let { (if (state.phase == Phase.BEFORE) "第一节  " else "下一节  ") + (it.subject?.name ?: "未安排科目") + "   ${clockText(it.slot.start)} 上课" } ?: if (state.phase == Phase.CLASS) "当前是今日最后一节 · ${clockText(state.current!!.slot.end)} 下课" else ""
        views.setTextViewText(R.id.course_title, title); views.setTextColor(R.id.course_title, palette.onSurface.toArgb())
        views.setTextColor(R.id.countdown, palette.primary.toArgb()); views.setTextColor(R.id.countdown_label, palette.onSurfaceVariant.toArgb())
        views.setViewVisibility(R.id.countdown, if (idle) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.countdown_label, if (idle) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.countdown_label, if (state.phase == Phase.CLASS) "距下课" else "距上课")
        views.setChronometer(R.id.countdown, SystemClock.elapsedRealtime() + state.remaining * 1000, null, !idle)
        views.setChronometerCountDown(R.id.countdown, true)
        views.setContentDescription(R.id.countdown, if (idle) state.status else "${if (state.phase == Phase.CLASS) "下课" else "上课"}时刻 ${target?.let(::clockText)}")
        views.setImageViewBitmap(R.id.progress_image, progressBitmap(state.progress, palette.primary.toArgb(), palette.outlineVariant.toArgb()))
        views.setContentDescription(R.id.progress_image, label)
        views.setTextViewText(R.id.next_text, if (compactNotification) listOf(label, next).filter { it.isNotBlank() }.joinToString(" · ") else next)
        views.setTextColor(R.id.next_text, if (compactNotification) palette.onSurfaceVariant.toArgb() else palette.onSecondaryContainer.toArgb())
        if (compactNotification) {
            views.setInt(R.id.compact_root, "setBackgroundColor", palette.surfaceContainerHigh.toArgb())
        } else {
            views.setInt(R.id.card_bg, "setColorFilter", palette.surfaceContainerHigh.toArgb())
            views.setTextViewText(R.id.app_label, "简单课表" + if (state.phase == Phase.CLASS) " · 正在上课" else "")
            views.setTextViewText(R.id.widget_date, "${state.resolution.date.monthValue} / ${state.resolution.date.dayOfMonth}")
            views.setTextColor(R.id.app_label, palette.onSurfaceVariant.toArgb()); views.setTextColor(R.id.widget_date, palette.onSurfaceVariant.toArgb())
            val meta = when (state.phase) {
                Phase.CLASS -> "${clockText(state.current!!.slot.start)} — ${clockText(state.current.slot.end)} · ${state.current.subject?.teacher.orEmpty()}"
                Phase.BREAK -> state.resolution.lessons.lastOrNull { it.slot.end <= state.secondOfDay }?.let { "上一节 ${it.subject?.name.orEmpty()} 已于 ${clockText(it.slot.end)} 下课" }.orEmpty()
                Phase.BEFORE -> state.next?.let { "第一节 ${it.subject?.name.orEmpty()}，${clockText(it.slot.start)} 上课" }.orEmpty()
                Phase.ENDED -> state.resolution.lessons.lastOrNull()?.let { "最后一节 ${it.subject?.name.orEmpty()} 已于 ${clockText(it.slot.end)} 下课" }.orEmpty()
                Phase.EMPTY -> "${state.resolution.date.monthValue} 月 ${state.resolution.date.dayOfMonth} 日未安排课程"
            }
            views.setTextViewText(R.id.course_meta, meta); views.setTextColor(R.id.course_meta, palette.onSurfaceVariant.toArgb()); views.setViewVisibility(R.id.course_meta, if (full) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.progress_label, label); views.setTextColor(R.id.progress_label, palette.onSurfaceVariant.toArgb()); views.setViewVisibility(R.id.progress_label, if (full) View.VISIBLE else View.GONE)
            views.setInt(R.id.next_bg, "setColorFilter", palette.secondaryContainer.toArgb()); views.setViewVisibility(R.id.next_box, if (next.isBlank()) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.open_schedule, if (full && !notification) View.VISIBLE else View.GONE); views.setTextColor(R.id.open_schedule, palette.primary.toArgb())
            views.setViewVisibility(R.id.widget_header, if (notification) View.GONE else View.VISIBLE)
            views.setOnClickPendingIntent(R.id.card_root, open(context)); views.setOnClickPendingIntent(R.id.course_main, open(context)); views.setOnClickPendingIntent(R.id.open_schedule, open(context, "schedule", 2))
        }
        val scale = if (s.largeText) 1.2f else 1f
        views.setTextViewTextSize(R.id.course_title, TypedValue.COMPLEX_UNIT_SP, (if (compactNotification) 16f else if (title.length > 16) 19f else 24f) * scale)
        if (s.largeText && !compactNotification) {
            listOf(R.id.course_meta, R.id.progress_label, R.id.next_text, R.id.open_schedule, R.id.countdown_label, R.id.app_label, R.id.widget_date).forEach { views.setTextViewTextSize(it, TypedValue.COMPLEX_UNIT_SP, 13f) }
            views.setTextViewTextSize(R.id.countdown, TypedValue.COMPLEX_UNIT_SP, 34f)
        }
        return views
    }
    fun timetableViews(context: Context, state: CourseState, id: Int): RemoteViews {
        val palette = colors(context); val views = RemoteViews(context.packageName, R.layout.timetable_widget); val lessons = state.resolution.lessons; val done = lessons.count { it.slot.end <= state.secondOfDay }
        views.setInt(R.id.card_bg, "setColorFilter", palette.surfaceContainerHigh.toArgb()); views.setTextColor(R.id.table_heading, palette.onSurface.toArgb()); views.setTextColor(R.id.widget_date, palette.onSurfaceVariant.toArgb())
        views.setTextViewText(R.id.widget_date, "${state.resolution.date.monthValue} / ${state.resolution.date.dayOfMonth}")
        views.setTextViewText(R.id.progress_label, if (lessons.isEmpty()) "今天没有安排课程" else "${state.status} · 已结束 $done / ${lessons.size} 节"); views.setTextColor(R.id.progress_label, palette.onSurfaceVariant.toArgb())
        views.setImageViewBitmap(R.id.progress_image, progressBitmap(if (lessons.isEmpty()) 0.0 else done.toDouble() / lessons.size, palette.primary.toArgb(), palette.outlineVariant.toArgb()))
        views.setContentDescription(R.id.progress_image, "今日已结束 $done / ${lessons.size} 节")
        val service = Intent(context, TimetableListService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).setData(android.net.Uri.parse("simpletimetable://widget/$id"))
        views.setRemoteAdapter(R.id.widget_list, service); views.setEmptyView(R.id.widget_list, R.id.empty_list); views.setTextColor(R.id.empty_list, palette.onSurfaceVariant.toArgb())
        val template = PendingIntent.getActivity(context, 1000 + id, Intent(context, MainActivity::class.java).setAction("widget_lesson_$id").putExtra("page", "schedule").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        views.setPendingIntentTemplate(R.id.widget_list, template); views.setOnClickPendingIntent(R.id.open_schedule, open(context, "schedule", 2)); views.setTextColor(R.id.open_schedule, palette.primary.toArgb())
        if (app(context).repository.current.settings.largeText) { views.setTextViewTextSize(R.id.table_heading, TypedValue.COMPLEX_UNIT_SP, 24f); views.setTextViewTextSize(R.id.progress_label, TypedValue.COMPLEX_UNIT_SP, 13f) }
        return views
    }
    fun notification(context: Context, state: CourseState = Engine.state(app(context).repository.current.active(), app(context).clock.now())): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "静默课表", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false); setShowBadge(false); description = "仅显示课程信息，不发送上下课提醒" })
        val stop = PendingIntent.getBroadcast(context, 4, Intent(context, UpdateReceiver::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_calendar).setContentTitle(state.title).setContentText(state.status).setSubText(state.status).setContentIntent(open(context)).setOngoing(true).setSilent(true).setOnlyAlertOnce(true).setShowWhen(false).setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(colors(context).primary.toArgb()).setCustomContentView(courseViews(context, state, false, true)).setCustomBigContentView(courseViews(context, state, true, true)).setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setProgress(1000, ((state.progress ?: 0.0) * 1000).toInt(), false).addAction(0, "今日课表", open(context, "schedule", 2)).addAction(0, "关闭常驻", stop).build()
    }
    private fun alarm(context: Context, request: Int): PendingIntent = PendingIntent.getBroadcast(context, request, Intent(context, UpdateReceiver::class.java).setAction(UPDATE + request), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(context: Context, state: CourseState, enabled: Boolean) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (!enabled) { alarms.cancel(alarm(context, 10)); alarms.cancel(alarm(context, 11)); return }
        val application = app(context); val now = application.clock.now()
        val boundary = Engine.nextBoundary(state)?.let { now + (it - state.secondOfDay) * 1000L }
        val tempBoundary = application.repository.current.active().temporary.flatMap { listOf(it.startMillis, it.endMillis) }.filter { it > now }.minOrNull()
        val midnight = now + (86400 - state.secondOfDay) * 1000L
        val next = listOfNotNull(boundary, tempBoundary, midnight).minOrNull() ?: midnight
        val elapsed = SystemClock.elapsedRealtime(); val target = elapsed + max(1000, next - now + 100)
        if (exactAllowed(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, target, alarm(context, 10)) else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, target, alarm(context, 10))
        alarms.set(AlarmManager.ELAPSED_REALTIME, elapsed + 60000, alarm(context, 11))
    }
    fun stopAction() = STOP
    fun row(context: Context, state: CourseState, lesson: Lesson): RemoteViews {
        val palette = colors(context); val active = lesson.slot.start <= state.secondOfDay && state.secondOfDay < lesson.slot.end; val ended = lesson.slot.end <= state.secondOfDay
        val views = RemoteViews(context.packageName, R.layout.timetable_row)
        views.setInt(R.id.row_bg, "setColorFilter", if (active) palette.secondaryContainer.toArgb() else palette.surfaceContainerHigh.toArgb())
        val ink = if (active) palette.onSecondaryContainer.toArgb() else palette.onSurface.toArgb(); val sub = if (active) ink else palette.onSurfaceVariant.toArgb()
        views.setTextViewText(R.id.row_start, clockText(lesson.slot.start)); views.setTextViewText(R.id.row_end, clockText(lesson.slot.end)); views.setTextViewText(R.id.row_subject, lesson.subject?.name ?: "未安排科目"); views.setTextViewText(R.id.row_teacher, lesson.subject?.teacher.orEmpty()); views.setTextViewText(R.id.row_state, if (active) "正在上课" else if (ended) "已结束" else "未开始")
        listOf(R.id.row_start, R.id.row_subject).forEach { views.setTextColor(it, if (ended) sub else ink) }; listOf(R.id.row_end, R.id.row_teacher, R.id.row_state).forEach { views.setTextColor(it, sub) }
        if (app(context).repository.current.settings.largeText) { views.setTextViewTextSize(R.id.row_subject, TypedValue.COMPLEX_UNIT_SP, 16f); views.setTextViewTextSize(R.id.row_teacher, TypedValue.COMPLEX_UNIT_SP, 12f) }
        views.setOnClickFillInIntent(R.id.row_root, Intent().putExtra("page", "schedule").putExtra("profile", state.resolution.profile.id).putExtra("date", state.resolution.date.toString()).putExtra("plan", lesson.planId).putExtra("slot", lesson.sourceSlotId))
        return views
    }
}

class CourseWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { SurfaceUpdater.update(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) { SurfaceUpdater.update(context) }
    override fun onDisabled(context: Context) { SurfaceUpdater.update(context) }
}
class TimetableWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { SurfaceUpdater.update(context) }
    override fun onDisabled(context: Context) { SurfaceUpdater.update(context) }
}
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SurfaceUpdater.stopAction()) { (context.applicationContext as TimetableApplication).repository.settings { it.copy(notification = false) }; SurfaceUpdater.refresh(context) }
        else if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) SurfaceUpdater.refresh(context)
        else SurfaceUpdater.update(context)
    }
}
class DisplayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() {
        val app = application as TimetableApplication
        if (!app.repository.current.settings.notification) { stopSelf(); return }
        SurfaceUpdater.update(this@DisplayService)
        val state = Engine.state(app.repository.current.active(), app.clock.now())
        val boundary = Engine.nextBoundary(state)?.let { (it - state.secondOfDay) * 1000L } ?: 30000L
        if (getSystemService(PowerManager::class.java).isInteractive) handler.postDelayed(this, min(30000L, max(1000L, boundary + 80)))
    } }
    private val screens = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { handler.removeCallbacks(tick); if (intent.action == Intent.ACTION_SCREEN_ON) handler.post(tick) } }
    override fun onCreate() { super.onCreate(); ContextCompat.registerReceiver(this, screens, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }, ContextCompat.RECEIVER_NOT_EXPORTED) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as TimetableApplication
        if (!app.repository.current.settings.notification || !SurfaceUpdater.notificationsAllowed(this)) { stopSelf(); return START_NOT_STICKY }
        startForeground(SurfaceUpdater.NOTIFICATION, SurfaceUpdater.notification(this)); handler.removeCallbacks(tick); handler.post(tick); return START_STICKY
    }
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); unregisterReceiver(screens); super.onDestroy() }
}
class TimetableListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var state = Engine.state((application as TimetableApplication).repository.current.active(), (application as TimetableApplication).clock.now())
        override fun onCreate() { onDataSetChanged() }
        override fun onDataSetChanged() { val app = application as TimetableApplication; state = Engine.state(app.repository.current.active(), app.clock.now()) }
        override fun onDestroy() {}
        override fun getCount() = state.resolution.lessons.size
        override fun getViewAt(position: Int): RemoteViews? = state.resolution.lessons.getOrNull(position)?.let { SurfaceUpdater.row(this@TimetableListService, state, it) }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = state.resolution.lessons.getOrNull(position)?.let { (it.planId + it.slot.id).hashCode().toLong() } ?: position.toLong()
        override fun hasStableIds() = true
    }
}
