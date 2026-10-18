package io.github.gaogaoza.simpletimetable

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.gaogaoza.simpletimetable.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Runs the real Compose activity and dialogs; emulator tests remain separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30], application=TimetableApplication::class, qualifiers="w410dp-h502dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TimetableApplication
    @Before fun seed() {
        compose.runOnUiThread {
            android.provider.Settings.Global.putInt(app.contentResolver,android.provider.Settings.Global.BOOT_COUNT,7)
            val p=SampleData.profile()
            app.repository.update { AppData(profiles=listOf(p),activeProfile=p.id,settings=Settings(softwareClock=true,autoSync=false,syncEpoch=java.time.LocalDate.of(2026,10,12).atTime(8,10).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),syncElapsed=android.os.SystemClock.elapsedRealtime(),syncBootCount=7)) }
        }
        compose.waitForIdle()
    }
    @Test fun pageTransitionKeepsBothPagesUntilAnimationEnds() {
        compose.mainClock.autoAdvance=false
        compose.onNodeWithText("课表").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.runOnUiThread { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithText("今日课程 · 6 节").assertExists()
        compose.onNodeWithContentDescription("下一天").assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("今日课程 · 6 节").assertDoesNotExist()
        compose.onNodeWithContentDescription("下一天").assertExists()
        compose.mainClock.autoAdvance=true
        compose.onNode(hasClickAction() and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("今天")))).performClick()
        compose.onNodeWithText("周一课表").assertExists();capture("timeline")
        compose.onNodeWithContentDescription("下一天").performClick()
        compose.onNodeWithText("周二课表").assertExists()
        compose.onNodeWithText("10 月 13 日 · 周二").performClick()
        compose.onNodeWithText("日期（yyyy-MM-dd）").performTextReplacement("2026-10-14")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("周三课表").assertExists()
    }
    private fun capture(name:String) {
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val file=File("build/acceptance/$name.png");file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
    }
    @Test @Config(qualifiers="w411dp-h891dp-mdpi") fun phoneLayoutRenders() { capture("phone") }
    @Test @Config(qualifiers="w410dp-h410dp-mdpi") fun squareLargeThemeRenders() {
        compose.runOnUiThread { app.repository.settings { it.copy(seed="#B34E78",largeText=true,highContrast=true) } }
        compose.waitForIdle(); compose.onNodeWithText("管理",useUnmergedTree=true).assertIsDisplayed(); capture("square-large-pink")
    }
    @Test @Config(qualifiers="w274dp-h335dp-hdpi") fun denseWatchRenders() {
        compose.onNodeWithText("管理",useUnmergedTree=true).assertIsDisplayed(); capture("watch-410px-hdpi")
    }
    @Test fun canCreateSubjectAndSaveThroughNativeDrawer() {
        compose.onNodeWithText("管理",useUnmergedTree=true).performClick()
        compose.onNodeWithText("科目").performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("新建科目"))
        compose.onNodeWithText("新建科目").performClick()
        compose.onNodeWithText("科目全称").performTextInput("信息技术")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("信息技术").assertExists()
        assertTrue(Repository(app).current.active().subjects.any { it.name=="信息技术" })
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("新建科目"))
        compose.onNodeWithText("新建科目").performClick()
        compose.onAllNodes(isRoot()).onLast().performTouchInput { click(androidx.compose.ui.geometry.Offset(5f,5f)) }
        compose.onNodeWithText("科目全称").assertDoesNotExist()
    }
    @Test fun themePreviewDiscardsAndSaveChangesWholePalette() {
        compose.onNodeWithText("管理",useUnmergedTree=true).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("显示与时间"))
        compose.onNodeWithText("显示与时间").performClick()
        compose.onNodeWithText("主题与阅读").performScrollTo().performClick()
        compose.onNodeWithText("玫瑰粉").performScrollTo().performClick()
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithText("放弃未保存的修改？").assertExists()
        compose.onNodeWithText("确认").performClick()
        assertEquals("#355E45",app.repository.current.settings.seed)
        compose.onNodeWithText("主题与阅读").performScrollTo().performClick()
        compose.onNodeWithText("玫瑰粉").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick()
        assertEquals("#B34E78",app.repository.current.settings.seed)
        compose.onNodeWithText("现在",useUnmergedTree=true).performClick(); capture("watch-pink")
    }
}
