package io.github.gaogaoza.simpletimetable

import android.app.NotificationManager
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gaogaoza.simpletimetable.data.*
import io.github.gaogaoza.simpletimetable.system.SurfaceUpdater
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TimetableApplication
    private val date = LocalDate.of(2026,10,12)
    @Before fun seed() {
        if (BuildConfig.BUILD_TYPE == "release") {
            assertEquals("Release APK must not be debuggable", 0,
                app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)
        }
        compose.runOnUiThread {
            val p = SampleData.profile("验收课表")
            app.repository.update { AppData(profiles = listOf(p), activeProfile = p.id, settings = Settings(seed = "#B34E78", softwareClock = true, autoSync = false, syncEpoch = date.atTime(8,10).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), syncElapsed = SystemClock.elapsedRealtime(), syncBootCount = AndroidSettings.Global.getInt(app.contentResolver, AndroidSettings.Global.BOOT_COUNT,-1))) }
        }
        compose.waitForIdle()
    }
    private fun click(text: String) {
        if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performScrollTo().performClick(); compose.waitForIdle()
    }
    private fun shot(name: String) = captureDevice(name)
    @Test fun navigationTimelinePagingThemeAndDismiss() {
        compose.onNodeWithText("正在上课").assertExists(); shot("now-pink")
        compose.onNodeWithText("课表", useUnmergedTree = true).performClick(); compose.waitForIdle()
        compose.onNode(hasClickAction() and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("今天")))).performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("下一天").performClick(); compose.waitForIdle()
        compose.onNodeWithText(dateLabelForTest(date.plusDays(1))).assertExists(); shot("timeline")
        compose.onNodeWithContentDescription("上一天").performClick(); compose.waitForIdle()
        compose.onNodeWithText("管理", useUnmergedTree = true).performClick(); compose.waitForIdle()
        click("显示与时间"); click("主题与阅读"); click("海盐蓝")
        compose.onNodeWithText("保存").performClick(); compose.waitForIdle()
        assertEquals("#3568B0",app.repository.current.settings.seed)
        click("主题与阅读"); click("玫瑰粉")
        compose.onNodeWithContentDescription("关闭").performClick(); compose.waitForIdle()
        compose.onNodeWithText("放弃未保存的修改？").assertExists()
        compose.onNodeWithText("确认").performClick(); compose.waitForIdle()
        assertEquals("#3568B0",app.repository.current.settings.seed)
        shot("settings-blue")
    }
    @Test fun createSubjectPersistsAcrossRepositoryReload() {
        compose.onNodeWithText("管理",useUnmergedTree=true).performClick(); compose.waitForIdle()
        click("科目"); click("新建科目")
        compose.onNodeWithText("科目全称").performTextInput("信息技术")
        compose.onNodeWithText("保存").performClick(); compose.waitForIdle()
        compose.onNodeWithText("信息技术").assertExists()
        assertTrue(Repository(app).current.active().subjects.any { it.name == "信息技术" })
        shot("subjects")
    }
    @Test fun widgetViewsAndSilentForegroundNotificationRunNatively() {
        compose.runOnUiThread {
            val p = app.repository.current.active()
            for ((index,hour) in listOf(7 to 59,8 to 10,8 to 50,9 to 46,16 to 0).withIndex()) {
                val millis = date.atTime(hour.first,hour.second).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val state = Engine.state(p,millis)
                for (full in listOf(false,true)) for (notification in listOf(false,true)) {
                    val view = SurfaceUpdater.courseViews(app,state,full,notification).apply(compose.activity,FrameLayout(compose.activity))
                    view.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.AT_MOST)); view.layout(0,0,view.measuredWidth,view.measuredHeight)
                    assertTrue(view.findViewById<TextView>(R.id.course_title).text.isNotBlank())
                    assertNotNull(view.findViewById<View>(R.id.progress_image).contentDescription)
                }
            }
            app.repository.settings { it.copy(notification=true) }
            SurfaceUpdater.refresh(app)
        }
        compose.waitUntil(10000) { app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == SurfaceUpdater.NOTIFICATION } }
        val notice = app.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id==SurfaceUpdater.NOTIFICATION }.notification
        assertTrue(notice.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(SurfaceUpdater.CHANNEL)
        assertNull(channel.sound); assertFalse(channel.shouldVibrate())
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("cmd statusbar expand-notifications").close()
        SystemClock.sleep(600);shot("notification-shade")
        automation.executeShellCommand("cmd statusbar collapse").close()
        compose.runOnUiThread { app.repository.settings { it.copy(notification=false) }; SurfaceUpdater.refresh(app) }
    }
    private fun dateLabelForTest(d:LocalDate)="${d.monthValue} 月 ${d.dayOfMonth} 日 · 周${"日一二三四五六"[d.dayOfWeek.value%7]}"
}
