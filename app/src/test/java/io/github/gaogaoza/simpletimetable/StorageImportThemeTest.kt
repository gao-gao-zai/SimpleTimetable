package io.github.gaogaoza.simpletimetable

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import io.github.gaogaoza.simpletimetable.data.*
import io.github.gaogaoza.simpletimetable.system.SurfaceUpdater
import io.github.gaogaoza.simpletimetable.ui.ThemeColors
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.*
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = TimetableApplication::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class StorageImportThemeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext<TimetableApplication>()
    @Before fun clear() { context.filesDir.resolve("timetable.json").delete(); context.filesDir.resolve("timetable.json.bak").delete() }
    @Test fun atomicRepositorySurvivesRestartAndExpiredChangesStayInactive() {
        val repo = Repository(context); val p = SampleData.profile()
        val date = LocalDate.of(2026, 10, 12); val zone = ZoneId.systemDefault()
        val now = date.atTime(8, 10).atZone(zone).toInstant().toEpochMilli()
        val plan = p.plans.first(); val slot = p.layouts.first().slots.first()
        val t = Temporary(name = "单节", type = TempType.LESSON, startMillis = now - 1, endMillis = now + 1000, patches = listOf(LessonPatch(date.toString(), plan.id, slot.id, p.subjects.last().id)))
        repo.update { AppData(profiles = listOf(p.copy(temporary = listOf(t))), activeProfile = p.id) }
        val restored = Repository(context)
        assertEquals(p.subjects.last().id, Engine.state(restored.current.active(), now).current!!.subject!!.id)
        assertFalse(Engine.state(restored.current.active(), now + 1000).current!!.temporary)
        val bytes = restored.originalBytes()
        assertTrue(runCatching { restored.update { it.copy(profiles = emptyList()) } }.isFailure)
        assertArrayEquals(bytes, restored.originalBytes())
    }
    @Test fun brokenStorageIsPreservedAndCannotBeOverwritten() {
        val bytes = "{broken".toByteArray(); context.filesDir.resolve("timetable.json").writeBytes(bytes)
        val repo = Repository(context); assertNotNull(repo.loadError)
        assertTrue(runCatching { repo.settings { it.copy(seed = "#FF00FF") } }.isFailure)
        assertArrayEquals(bytes, repo.originalBytes())
    }
    @Test fun csvSupportsQuotedNewlinesEscapesAndMappedOrder() {
        val table = Importers.csv("课程,末,周,始,教师\r\n\"语文,阅读\",08:45,周一,08:00,\"李\"\"老师\"\r\n英语,09:45,1,09:00,\"王\n老师\"".toByteArray())
        assertEquals("王\n老师", table.rows[2][4])
        val p = Importers.records(table, "CSV", listOf(2, 3, 1, 0, 4), 1).profile
        Repository.validate(AppData(profiles = listOf(p)))
        assertEquals("语文,阅读", p.subjects.first().name); assertEquals("李\"老师", p.subjects.first().teacher)
        assertTrue(runCatching { Importers.csv("\"not closed".toByteArray()) }.isFailure)
    }
    @Test fun csvGBKAndExcelTimeNumbers() {
        val text = "周,始,末,科目\n周一,0.3333333333,0.3645833333,数学"
        val table = Importers.csv(text.toByteArray(charset("GB18030")), "GB18030")
        val p = Importers.records(table, "CSV", listOf(0, 1, 2, 3, -1), 1).profile
        assertEquals(8 * 3600, p.layouts.first().slots.first().start)
    }
    @Test fun gridSupportsTranspositionAndExplicitTimeLayout() {
        val layout = TimeLayout(name = "作息", slots = listOf(TimeSlot(start = 28800, end = 31500), TimeSlot(start = 32400, end = 35100)))
        val table = ImportTable("横向", listOf(listOf("数学", "英语"), listOf("语文", "物理")))
        val p = Importers.grid(table, "网格", "A1:B2", listOf(1, 2), layout, true).profile
        assertEquals(2, p.plans.size); assertEquals("英语", p.subjects[1].name)
        assertTrue(runCatching { Importers.grid(table, "bad", "A1:B2", listOf(1), layout, false) }.isFailure)
    }
    @Test fun overlappingRecordsAreRejectedBeforeWrite() {
        val t = ImportTable("bad", listOf(listOf("1", "08:00", "09:00", "数学"), listOf("1", "08:30", "09:30", "英语")))
        assertTrue(runCatching { Importers.records(t, "bad", listOf(0, 1, 2, 3, -1), 0) }.isFailure)
    }
    @Test fun identicalSubjectNamesKeepDistinctTeachers() {
        val t=ImportTable("课程",listOf(listOf("1","08:00","08:45","数学","李老师"),listOf("2","08:00","08:45","数学","陈老师")))
        val p=Importers.records(t,"测试",listOf(0,1,2,3,4),0).profile
        assertEquals(setOf("李老师","陈老师"),p.subjects.map { it.teacher }.toSet())
        assertNotEquals(p.plans[0].subjects.values.first(),p.plans[1].subjects.values.first())
    }
    @Test fun workbookExpansionLimitAlsoCoversImages() {
        val zip=ByteArrayOutputStream()
        ZipOutputStream(zip).use { out -> out.putNextEntry(ZipEntry("xl/media/image.png")); val block=ByteArray(1024*1024);repeat(21) { out.write(block) };out.closeEntry() }
        val result=runCatching { Importers.xlsx(zip.toByteArray()) }
        assertTrue(result.isFailure);assertTrue(result.exceptionOrNull()!!.message!!.contains("20 MB"))
    }
    @Test fun xlsxReadsRelationsMultipleSheetsMergedAndCachedFormula() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            fun file(name: String, text: String) { out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry() }
            file("xl/workbook.xml", """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="课表" r:id="rA"/><sheet name="第二张" r:id="rB"/></sheets></workbook>""")
            file("xl/_rels/workbook.xml.rels", """<Relationships><Relationship Id="rA" Target="worksheets/custom.xml"/><Relationship Id="rB" Target="worksheets/other.xml"/></Relationships>""")
            file("xl/sharedStrings.xml", """<sst><si><t>数学</t></si></sst>""")
            file("xl/worksheets/custom.xml", """<worksheet><sheetData><row><c r="A1" t="s"><v>0</v></c><c r="C1"><f>1/3</f><v>0.3333333333</v></c></row></sheetData><mergeCells><mergeCell ref="A1:B1"/></mergeCells></worksheet>""")
            file("xl/worksheets/other.xml", """<worksheet><sheetData><row><c r="A1" t="inlineStr"><is><t>英语</t></is></c></row></sheetData></worksheet>""")
        }
        val tables = Importers.xlsx(zip.toByteArray())
        assertEquals(listOf("课表", "第二张"), tables.map { it.name })
        assertEquals(listOf("数学", "数学", "0.3333333333"), tables.first().rows.first())
        assertEquals("英语", tables.last().rows.first().first())
    }
    @Test fun actualClassIslandSchemaSkipsActionsAndAlignsLessonSlots() {
        val json = """{"Subjects":{"s":{"Name":"数学","Initial":"数","TeacherName":"陈老师","IsOutDoor":true}},"TimeLayouts":{"t":{"Name":"共享作息","Layouts":[{"TimeType":0,"StartTime":"2026-10-12T08:00:00","EndTime":"2026-10-12T08:45:00","DefaultClassId":"s"},{"TimeType":3,"StartTime":"2026-10-12T08:45:00","Actions":[{"arbitrary":"do not run"}]},{"TimeType":1,"StartTime":"2026-10-12T08:45:00","EndTime":"2026-10-12T09:00:00","BreakName":"课间"},{"TimeType":0,"StartTime":"2026-10-12T09:00:00","EndTime":"2026-10-12T09:45:00"}]}},"ClassPlanGroups":{"g":{"Name":"常规"}},"ClassPlans":{"p":{"Name":"周一","TimeLayoutId":"t","AssociatedGroup":"g","TimeRule":{"WeekDay":1,"WeekCountDiv":0},"Classes":[{"SubjectId":"s","IsEnabled":true},{"SubjectId":"s","IsEnabled":false}]}}} """
        val result = Importers.classIsland(json.toByteArray()); val p = result.profile
        val withOrder = json.replace("\"ClassPlans\":", "\"OrderedSchedules\":{\"2026-10-13T00:00:00\":{\"ClassPlanId\":\"p\"}},\"ClassPlans\":")
        val ordered = Importers.classIsland(withOrder.toByteArray()).profile
        val orderedDate = LocalDate.of(2026,10,13)
        assertEquals("p",Engine.resolve(ordered,orderedDate,orderedDate.atTime(8,10).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()).plan!!.id)
        assertEquals(3, p.layouts.first().slots.size); assertEquals(1, p.plans.first().disabledSlots.size)
        assertTrue(p.subjects.first().outdoor); assertTrue(result.warnings.any { it.contains("自动化") })
        val date = LocalDate.of(2026, 10, 12)
        assertEquals(1, Engine.resolve(p, date, date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()).lessons.size)
        assertTrue(runCatching { Importers.classIsland(json.replace("\"SubjectId\":\"s\"", "\"SubjectId\":\"missing\"").toByteArray()) }.isFailure)
    }
    @Test fun everyThemeRoleRespondsToSeedAndKeepsReadableContrast() {
        for (dark in listOf(false, true)) for (high in listOf(false, true)) {
            val pink = ThemeColors.colors(Settings(seed = "#B34E78", highContrast = high), dark)
            val green = ThemeColors.colors(Settings(seed = "#355E45", highContrast = high), dark)
            assertNotEquals(pink.background, green.background); assertNotEquals(pink.primaryContainer, green.primaryContainer)
            assertNotEquals(pink.surfaceContainer, green.surfaceContainer); if (!high) assertNotEquals(pink.onSurface, green.onSurface)
            listOf(pink.onSurface to pink.background, pink.onPrimaryContainer to pink.primaryContainer, pink.onPrimary to pink.primary, pink.onSecondaryContainer to pink.secondaryContainer).forEach { (fg,bg) -> assertTrue(ColorUtils.calculateContrast(fg.toArgb(), bg.toArgb()) >= 4.45) }
        }
    }
    @Test fun readableTextAndControlBordersAcrossSeedsAndModes() {
        val seeds = listOf("#FFFFFF", "#000000", "#FFFF00", "#00FFFF", "#FF00FF", "#FFDDDD", "#B34E78", "#355E45", "#3568B0", "#7856A6") +
            (0 until 24).map { "#%06X".format(java.util.Random(it.toLong()).nextInt(0x1000000)) }
        for (seed in seeds) for (dark in listOf(false, true)) for (high in listOf(false, true)) {
            val c = ThemeColors.colors(Settings(seed = seed, highContrast = high), dark)
            val backgrounds = listOf(c.background,c.surface,c.surfaceContainerLowest,c.surfaceContainerLow,c.surfaceContainer,c.surfaceContainerHigh,c.surfaceContainerHighest)
            fun contrast(fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color, minimum: Double) {
                val ratio = ColorUtils.calculateContrast(fg.toArgb(), bg.toArgb())
                assertTrue("$seed dark=$dark high=$high ratio=$ratio minimum=$minimum", ratio >= minimum)
            }
            backgrounds.forEach { bg -> contrast(c.onSurface,bg,7.0); contrast(c.onSurfaceVariant,bg,4.5); contrast(c.primary,bg,4.5); contrast(c.tertiary,bg,4.5); contrast(c.error,bg,4.5); contrast(c.outline,bg,3.0) }
            listOf(c.onPrimaryContainer to c.primaryContainer,c.onSecondaryContainer to c.secondaryContainer,c.onTertiaryContainer to c.tertiaryContainer).forEach { (fg,bg) -> contrast(fg,bg,7.0) }
            listOf(c.onPrimary to c.primary,c.onSecondary to c.secondary,c.onTertiary to c.tertiary,c.onError to c.error).forEach { (fg,bg) -> contrast(fg,bg,4.5) }
        }
    }
    @Test fun softwareCalibrationAndSchoolOffsetStaySeparate() {
        android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 8)
        val repo = Repository(context); val clock = AppClock(context,repo)
        val settings = Settings(softwareClock=true, syncEpoch=1_800_000_000_000L, syncElapsed=android.os.SystemClock.elapsedRealtime(), syncBootCount=8, calibrationMillis=1234, schoolOffset=17)
        assertTrue(clock.reliable(settings))
        assertEquals(17000L, clock.now(settings)-clock.now(settings.copy(schoolOffset=0)))
        android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 9)
        assertFalse(clock.reliable(settings)); assertTrue(clock.status(settings).contains("重启"))
        assertTrue(kotlin.math.abs(clock.now(settings)-System.currentTimeMillis()-17000) < 1000)
    }
    @Test fun ntpTimestampRoundtripHandles2036EraRollover() {
        for (year in listOf(2026,2036,2040)) {
            val epoch=LocalDate.of(year,10,12).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()+567
            val bytes=ByteArray(48); AppClock.writeTimestamp(bytes,40,epoch)
            assertTrue(kotlin.math.abs(epoch-AppClock.readTimestamp(bytes,40))<=1)
        }
    }
    @Test fun remoteViewsApplyWithoutUnsupportedActions() {
        val app = context as TimetableApplication
        val p = SampleData.profile(); app.repository.update { AppData(profiles = listOf(p), activeProfile = p.id, settings = Settings(seed = "#B34E78")) }
        val now = LocalDate.of(2026, 10, 12).atTime(8, 10).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val state = Engine.state(p, now)
        for (full in listOf(false,true)) for (notification in listOf(false,true)) {
            val view = SurfaceUpdater.courseViews(context, state, full, notification).apply(context, FrameLayout(context))
            assertEquals("语文", view.findViewById<TextView>(R.id.course_title).text.toString())
            assertNotNull(view.findViewById<View>(R.id.progress_image).contentDescription)
            val width=if(notification || full) 360 else 240
            val height=if(notification && !full) 64 else if(full) 300 else 160
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
            val bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(bitmap))
            val output=java.io.File("build/acceptance/surface-$full-$notification.png");output.parentFile!!.mkdirs();output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
        val s = SurfaceUpdater.notification(context, state)
        assertNotNull(s.contentView); assertNotNull(s.bigContentView)
    }
}
