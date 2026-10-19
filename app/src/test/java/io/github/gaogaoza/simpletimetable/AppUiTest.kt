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
        compose.onNodeWithContentDescription("选择日期 2026-10-14").performScrollTo()
        compose.onNodeWithContentDescription("选择日期 2026-10-14").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("周三课表").assertExists()
    }
    @Test fun timetableTimePickerPreservesDurationAndSaves() {
        compose.onNodeWithText("管理",useUnmergedTree=true).performClick()
        compose.onNodeWithText("时间表").performScrollTo().performClick()
        compose.onNodeWithText("秋季作息").performClick()
        compose.onNodeWithContentDescription("选择时段 第 1 节").performScrollTo().performClick()
        compose.onNodeWithText("开始时间").performScrollTo().performClick()
        compose.onNodeWithContentDescription("选择分钟").performScrollTo().performClick()
        compose.onNodeWithContentDescription("使用键盘输入时间").performScrollTo().performClick()
        compose.onNodeWithContentDescription("增加分钟").performScrollTo().performClick()
        capture("time-picker", dialog = true)
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("保存时间表").performClick()
        val slot = Repository(app).current.active().layouts.first().slots.first()
        assertEquals(8 * 3600 + 60, slot.start)
        assertEquals(8 * 3600 + 46 * 60, slot.end)
        compose.onNodeWithText("秋季作息").assertExists()
    }
    @Test @Config(qualifiers="w274dp-h335dp-hdpi") fun smallWatchCalendarHasSelectableDates() {
        compose.onNodeWithText("课表",useUnmergedTree=true).performClick()
        compose.onNode(hasClickAction() and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("今天")))).performClick()
        compose.onNodeWithText("10 月 12 日 · 周一").performClick()
        compose.onNodeWithContentDescription("选择日期 2026-10-14").performScrollTo()
        compose.onNodeWithContentDescription("选择日期 2026-10-14").performClick()
        capture("watch-calendar", dialog = true)
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("周三课表").assertExists()
    }
    @Test fun temporaryExpiryUsesDateAndTimePickers() {
        compose.onNodeWithText("管理",useUnmergedTree=true).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("临时安排"))
        compose.onNodeWithText("临时安排").performClick()
        compose.onNodeWithText("新建临时安排").performClick()
        compose.onNodeWithText("名称").performTextInput("运动会")
        compose.onNodeWithText("失效时间日期").performScrollTo().performClick()
        compose.onNodeWithContentDescription("选择日期 2026-10-14").performScrollTo()
        compose.onNodeWithContentDescription("选择日期 2026-10-14").performClick()
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("覆盖来源").performScrollTo()
        compose.onNodeWithText("覆盖来源").performClick()
        compose.onNodeWithText("周一课表").performClick()
        compose.onNodeWithText("保存").performClick()
        val temporary = Repository(app).current.active().temporary.single()
        assertEquals("运动会", temporary.name)
        assertEquals(java.time.LocalDate.of(2026,10,14), java.time.Instant.ofEpochMilli(temporary.endMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate())
    }
    @Test fun cycleWeekIsBoundedAndAnchorChosenFromCalendar() {
        compose.onNodeWithText("管理",useUnmergedTree=true).performClick()
        compose.onNode(hasText("课表") and hasText("5 份 · 日期与多周规则")).performScrollTo().performClick()
        compose.onNodeWithText("周一课表").performClick()
        compose.onNodeWithContentDescription("增加轮换总周数").performScrollTo()
        compose.onNodeWithContentDescription("增加轮换总周数").performClick()
        compose.onNodeWithText("第 1 周基准").performScrollTo().performClick()
        compose.onNodeWithContentDescription("选择日期 2026-10-14").performScrollTo()
        compose.onNodeWithContentDescription("选择日期 2026-10-14").performClick()
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("保存").performClick()
        val plan = Repository(app).current.active().plans.first()
        assertEquals(2, plan.cycle)
        assertEquals("2026-10-12", plan.anchor)
    }
    private fun openLayout() {
        compose.onNodeWithText("管理", useUnmergedTree = true).performClick()
        compose.onNodeWithText("时间表").performScrollTo().performClick()
        compose.onNodeWithText("秋季作息").performClick()
    }
    private fun checkSelectionAndDraft() {
        openLayout()
        capture("layout-timeline", dialog = true)
        compose.onNodeWithContentDescription("时间表时间轴").performScrollTo()
        compose.onNodeWithContentDescription("选择时段 第 2 节").performScrollTo().performClick()
        compose.onNode(hasText("开始时间") and hasText("09:00")).performScrollTo().assertExists()
        compose.onNodeWithText("时段名称").assertDoesNotExist()
        compose.onNodeWithText("详细编辑").performScrollTo().performClick()
        compose.onNodeWithText("时段名称").performScrollTo().performTextReplacement("第二节新名称")
        assertEquals("第 2 节", Repository(app).current.active().layouts.first().slots[1].name)
        compose.onNodeWithText("返回整张时间表").performScrollTo().performClick()
        compose.onNodeWithContentDescription("时间表时间轴").assertExists()
        compose.onNodeWithText("列表", substring = false).performScrollTo().performClick()
        compose.onNodeWithContentDescription("选择时段 第 1 节").performScrollTo().performClick()
        compose.onNodeWithText("时段名称").assertDoesNotExist()
        compose.onNode(hasText("开始时间") and hasText("08:00")).performScrollTo().assertExists()
        capture("layout-selection", dialog = true)
        compose.onNodeWithText("保存时间表").performClick()
        assertEquals("第二节新名称", Repository(app).current.active().layouts.first().slots[1].name)
    }
    @Test @Config(qualifiers="w411dp-h891dp-mdpi") fun phoneSelectionOnlyUpdatesLowerEditor() = checkSelectionAndDraft()
    @Test @Config(qualifiers="w274dp-h335dp-hdpi") fun watchSelectionOnlyUpdatesLowerEditor() = checkSelectionAndDraft()
    @Test @Config(qualifiers="w410dp-h410dp-mdpi") fun squareSelectionOnlyUpdatesLowerEditor() = checkSelectionAndDraft()
    @Test fun clockDialSecondsAndDraftSaveAreNative() {
        openLayout()
        compose.onNodeWithText("开始时间").performScrollTo().performClick()
        compose.onNodeWithContentDescription("8时").performClick()
        compose.onNodeWithContentDescription("0分").performClick()
        compose.onNodeWithContentDescription("选择秒").performClick()
        compose.onNodeWithContentDescription("秒钟面").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .5f, height * .9f)) }
        capture("clock-dial", dialog = true)
        compose.onNodeWithText("确定").performClick()
        compose.onNode(hasText("开始时间") and hasText("08:00:30")).performScrollTo().assertExists()
        assertEquals(8 * 3600, Repository(app).current.active().layouts.first().slots.first().start)
        compose.onNodeWithText("保存时间表").performClick()
        val saved = Repository(app).current.active().layouts.first().slots.first()
        assertEquals(8 * 3600 + 30, saved.start); assertEquals(8 * 3600 + 45 * 60 + 30, saved.end)
    }
    @Test fun colorSquareUsesTwoDimensionsAndSavesOnlyExplicitly() {
        compose.onNodeWithText("管理", useUnmergedTree = true).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("显示与时间"))
        compose.onNodeWithText("显示与时间").performClick()
        compose.onNodeWithText("主题与阅读").performScrollTo().performClick()
        compose.onNodeWithText("自选颜色").performScrollTo().performClick()
        compose.onNodeWithContentDescription("二维取色板").performScrollTo().performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width * .2f, height * .2f), androidx.compose.ui.geometry.Offset(width * .8f, height * .5f)) }
        compose.onNodeWithContentDescription("色相").performScrollTo().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .9f, height / 2f)) }
        assertEquals("#355E45", Repository(app).current.settings.seed)
        capture("color-panel", dialog = true)
        compose.onNodeWithText("保存").performClick()
        assertNotEquals("#355E45", Repository(app).current.settings.seed)
    }
    private fun capture(name:String, dialog:Boolean = false) {
        compose.runOnIdle {
            val view=if (dialog) requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView else compose.activity.window.decorView
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
