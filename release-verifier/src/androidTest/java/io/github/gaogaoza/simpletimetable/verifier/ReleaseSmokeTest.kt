package io.github.gaogaoza.simpletimetable.verifier

import android.app.NotificationManager
import android.appwidget.*
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.core.app.ActivityScenario
import android.os.ParcelFileDescriptor

/** Exercises the actual optimized APK through Android APIs, without Compose
 * test hooks or references to application classes that R8 can rename/remove. */
@RunWith(AndroidJUnit4::class)
class ReleaseSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val target = "io.github.gaogaoza.simpletimetable"
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun launchTarget() { shell("am start -W -S -n $target/.MainActivity") }
    @Before fun launchRelease() {
        assertEquals("Target must be the actual non-debuggable release APK",0,
            context.packageManager.getApplicationInfo(target,0).flags and ApplicationInfo.FLAG_DEBUGGABLE)
        launchTarget()
        waitFor("release home") { node("现在") != null }
    }
    private fun allNodes(root: AccessibilityNodeInfo? = automation.rootInActiveWindow): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return listOf(root) + (0 until root.childCount).flatMap { allNodes(root.getChild(it)) }
    }
    private fun node(label: String) = allNodes().firstOrNull {
        it.text?.toString()?.lineSequence()?.any { line -> line == label } == true ||
            it.contentDescription?.toString()?.lineSequence()?.any { line -> line == label } == true
    }
    private fun waitFor(label: String, timeout: Long = 15000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail("Timed out: $label; visible=${allNodes().mapNotNull { it.text?.toString() }.take(50)}")
    }
    private fun click(label: String) {
        repeat(12) {
            var found = node(label)
            if (found != null) {
                while (found != null && !found.isClickable) found = found.parent
                if (found?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    SystemClock.sleep(350); return
                }
            }
            allNodes().lastOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(200)
        }
        fail("No clickable accessibility node: $label; visible=${allNodes().mapNotNull { it.text?.toString() }.take(50)}")
    }
    @Test fun optimizedReleaseReadsStoredDataAndSavesThemeThroughSystemUi() {
        waitFor("persisted current lesson") { node("正在上课") != null && node("语文") != null }
        captureDevice("release-now-pink")
        click("课表")
        waitFor("timeline") { node("下一天") != null }
        // This process loaded the calibrated fixture before creating its UI,
        // so it already displays today. The date row also says 今天 and opens
        // a picker; avoid confusing that label with the app bar's Today action.
        click("下一天")
        waitFor("next date") { node("10 月 13 日 · 周二") != null }
        click("上一天")
        waitFor("previous date") { node("10 月 12 日 · 周一") != null }
        captureDevice("release-timeline")
        click("管理"); click("时间表"); click("秋季作息"); click("第 1 节"); click("开始时间")
        click("增加分钟"); captureDevice("release-time-picker"); click("确定"); click("保存")
        waitFor("time picker saved both start and retained duration") {
            if (node("08:01 — 08:46") != null) true else {
                allNodes().lastOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(200); false
            }
        }
        click("关闭"); click("返回管理")
        click("显示与时间"); click("主题与阅读"); click("海盐蓝"); click("保存")
        shell("am force-stop $target")
        launchTarget()
        waitFor("stored course survives a fresh process") { node("正在上课") != null && node("语文") != null }
        click("管理"); click("显示与时间"); click("主题与阅读")
        waitFor("theme survives a fresh process") {
            if(node("#3568B0") != null) true else {
                allNodes().lastOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(200); false
            }
        }
        captureDevice("release-settings-blue")
    }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    @Test fun optimizedReleaseRunsSilentNotificationAndBothRealWidgets() {
        waitFor("ongoing notification") { shell("dumpsys notification --noredact").lineSequence().any { it.contains("NotificationRecord(") && it.contains("pkg=$target") } }
        val dump=shell("dumpsys notification --noredact")
        val record=dump.lineSequence().first { it.contains("NotificationRecord(") && it.contains("pkg=$target") }
        val flags=Regex("flags=0x([0-9a-fA-F]+)").find(record)?.groupValues?.get(1)?.toInt(16) ?: 0
        assertTrue(record,flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        val channel=dump.lineSequence().first { it.contains("NotificationChannel{") && it.contains("mId='silent_timetable'") }
        assertTrue(channel,channel.contains("mSound=null"))
        assertTrue(channel,channel.contains("mVibrationEnabled=false"))
        automation.executeShellCommand("cmd statusbar expand-notifications").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        SystemClock.sleep(400);captureDevice("release-notification")
        automation.executeShellCommand("cmd statusbar collapse").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val scenario=ActivityScenario.launch(WidgetHostActivity::class.java)
        val manager=AppWidgetManager.getInstance(context)
        val host=AppWidgetHost(context,9716)
        var original:Throwable?=null
        try {
            instrumentation.runOnMainSync { host.startListening() }
            for ((provider,label) in listOf("CourseWidget" to "course", "TimetableWidget" to "timetable")) {
                var view:AppWidgetHostView?=null
                instrumentation.runOnMainSync {
                    val id=host.allocateAppWidgetId()
                    val options=Bundle().apply {
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,360);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,360)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,300);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,300)
                    }
                    assertTrue(manager.bindAppWidgetIdIfAllowed(id,ComponentName(target,"$target.system.$provider"),options))
                    val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
                    view=host.createView(activity,id,manager.getAppWidgetInfo(id))
                    activity.setContentView(view)
                }
                waitFor("actual optimized $provider",20000) {
                    var ready=false
                    instrumentation.runOnMainSync {
                        val children=views(view!!)
                        ready=if(provider=="CourseWidget") children.filterIsInstance<TextView>().any { it.text.toString()=="语文" } &&
                            children.any { it.contentDescription?.toString()?.startsWith("本节已过去") == true }
                        else children.filterIsInstance<ListView>().any { it.adapter?.count == 6 }
                    }
                    ready
                }
                captureDevice("release-$label-widget")
            }
        } catch(t:Throwable) { original=t;throw t }
        finally {
            runCatching { instrumentation.runOnMainSync { host.stopListening();host.deleteHost() } }
                .onFailure { if(original==null) throw it }
            scenario.close()
        }
    }
}
