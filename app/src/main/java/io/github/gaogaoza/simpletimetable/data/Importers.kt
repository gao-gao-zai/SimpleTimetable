package io.github.gaogaoza.simpletimetable.data

import android.util.Xml
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

data class ImportTable(val name: String, val rows: List<List<String>>)
data class ImportResult(val profile: Profile, val warnings: List<String> = emptyList())

object Importers {
    fun csv(bytes: ByteArray, encoding: String = "UTF-8"): ImportTable {
        val text = Charset.forName(encoding).newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '"') { if (quoted && text.getOrNull(i + 1) == '"') { cell.append('"'); i++ } else quoted = !quoted }
            else if (ch == ',' && !quoted) { row.add(cell.toString()); cell.clear() }
            else if ((ch == '\n' || ch == '\r') && !quoted) { if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++; row.add(cell.toString()); cell.clear(); if (row.any { it.isNotBlank() }) rows.add(row); row = mutableListOf() }
            else cell.append(ch)
            require(cell.length <= 10000 && rows.size <= 2000 && row.size <= 128) { "表格内容过大" }; i++
        }
        require(!quoted) { "CSV 引号未闭合" }
        row.add(cell.toString()); if (row.any { it.isNotBlank() }) rows.add(row)
        require(rows.isNotEmpty()) { "表格为空" }; return ImportTable("CSV", rows)
    }

    fun records(table: ImportTable, name: String, mapping: List<Int>, firstRow: Int): ImportResult {
        require(mapping.size == 5 && mapping.take(4).all { it >= 0 } && mapping.take(4).distinct().size == 4) { "请为星期、开始、结束、科目选择不同的列" }
        val records = table.rows.drop(firstRow).mapIndexedNotNull { index, row ->
            if (row.all { it.isBlank() }) return@mapIndexedNotNull null
            fun col(i: Int) = row.getOrNull(mapping[i])?.trim().orEmpty()
            val day = parseDay(col(0)); require(day in 0..6) { "第 ${firstRow + index + 1} 行：星期无效" }
            val start = tableTime(col(1)); val end = tableTime(col(2)); val subject = col(3)
            require(subject.isNotBlank() && end > start && start < 86400) { "第 ${firstRow + index + 1} 行：科目或时间无效" }
            RowLesson(day, start, end, subject, col(4))
        }
        require(records.isNotEmpty()) { "没有可导入课程" }
        return ImportResult(fromRows(name, records))
    }
    private data class RowLesson(val day: Int, val start: Int, val end: Int, val subject: String, val teacher: String = "")
    private fun fromRows(name: String, rows: List<RowLesson>): Profile {
        val subjects = rows.distinctBy { it.subject to it.teacher }.map { Subject(name = it.subject, shortName = it.subject.take(1), teacher = it.teacher) }
        val layouts = mutableListOf<TimeLayout>(); val plans = mutableListOf<ClassPlan>()
        rows.groupBy { it.day }.forEach { (day, dayRows) ->
            val sorted = dayRows.sortedBy { it.start }
            require(sorted.zipWithNext().none { (a, b) -> a.end > b.start }) { "周${"日一二三四五六"[day]}存在课程重叠" }
            val slots = sorted.mapIndexed { i, r -> TimeSlot(name = "第 ${i + 1} 节", start = r.start, end = r.end) }
            val layout = TimeLayout(name = "周${"日一二三四五六"[day]}作息", slots = slots)
            layouts.add(layout); plans.add(ClassPlan(name = "周${"日一二三四五六"[day]}课表", layoutId = layout.id, days = setOf(day), subjects = sorted.mapIndexed { i, r -> slots[i].id to subjects.first { it.name == r.subject && it.teacher == r.teacher }.id }.toMap()))
        }
        return Profile(name = name.ifBlank { "导入课表" }, subjects = subjects, layouts = layouts, plans = plans)
    }
    fun grid(table: ImportTable, name: String, range: String, days: List<Int>, layout: TimeLayout, transpose: Boolean): ImportResult {
        val corners = range.uppercase().split(':'); require(corners.size == 2) { "范围请使用 B2:F7 形式" }
        val (r0, c0) = cellPosition(corners[0]); val (r1, c1) = cellPosition(corners[1]); require(r1 >= r0 && c1 >= c0 && r1 < table.rows.size) { "表格范围无效" }
        val axis = if (transpose) r1 - r0 + 1 else c1 - c0 + 1
        require(days.size == axis && days.all { it in 0..6 } && days.distinct().size == days.size) { "星期数量应与课程区域的天数一致，且不能重复" }
        val slots = layout.slots.filter { it.type == SlotType.LESSON }.sortedBy { it.start }
        val count = if (transpose) c1 - c0 + 1 else r1 - r0 + 1
        require(count <= slots.size) { "区域含 $count 节课，所选作息只有 ${slots.size} 个课程时段" }
        val rows = mutableListOf<RowLesson>()
        days.forEachIndexed { dayIndex, day -> (0 until count).forEach { lessonIndex ->
            val r = if (transpose) r0 + dayIndex else r0 + lessonIndex; val c = if (transpose) c0 + lessonIndex else c0 + dayIndex
            val value = table.rows.getOrNull(r)?.getOrNull(c)?.trim().orEmpty()
            if (value.isNotBlank()) rows.add(RowLesson(day, slots[lessonIndex].start, slots[lessonIndex].end, value))
        } }
        require(rows.isNotEmpty()) { "选定区域没有课程" }; return ImportResult(fromRows(name, rows))
    }
    fun parseDay(text: String): Int {
        val v = text.trim().replace("星期", "").replace("周", "").replace("礼拜", "")
        return when (v) { "日", "天", "7", "0" -> 0; "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6; else -> v.toDoubleOrNull()?.toInt()?.takeIf { it in 1..6 } ?: error("无法识别星期：$text") }
    }
    private fun tableTime(text: String): Int = text.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }?.let { (it * 86400).roundToInt() } ?: parseClock(text.substringAfter('T').substringBefore('.'))

    fun classIsland(bytes: ByteArray): ImportResult {
        val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")).asJsonObject
        require(root.has("Subjects") && root.has("TimeLayouts") && root.has("ClassPlans")) { "不是受支持的 ClassIsland JSON 档案（需要 Subjects、TimeLayouts、ClassPlans）" }
        fun JsonObject.text(key: String, fallback: String = "") = get(key)?.takeUnless { it.isJsonNull }?.asString ?: fallback
        fun JsonObject.flag(key: String, fallback: Boolean = false) = get(key)?.takeUnless { it.isJsonNull }?.asBoolean ?: fallback
        fun JsonObject.number(key: String, fallback: Int = 0) = get(key)?.takeUnless { it.isJsonNull }?.asInt ?: fallback
        val emptyGuid = "00000000-0000-0000-0000-000000000000"
        fun cleanId(id: String) = if (id == emptyGuid) "" else id
        val warnings = mutableListOf("自动化行动、提醒、附加设置及原平台运行时状态不导入。", "轮换基准采用本周周一；请在导入后核对学校周次。")
        val subjects = root.getAsJsonObject("Subjects").entrySet().map { (id, value) -> val v = value.asJsonObject; Subject(id = id, name = v.text("Name", "未命名科目"), shortName = v.text("Initial"), teacher = v.text("TeacherName"), outdoor = v.flag("IsOutDoor")) }
        val layouts = root.getAsJsonObject("TimeLayouts").entrySet().map { (id, value) ->
            val v = value.asJsonObject
            val slots = v.getAsJsonArray("Layouts").mapIndexedNotNull { i, point ->
                val t = point.asJsonObject; val type = t.number("TimeType")
                if (type == 3) return@mapIndexedNotNull null
                require(type in 0..2) { "不支持的时间点类型：$type" }
                val start = tableTime(t.text("StartTime", t.text("StartSecond")))
                val end = if (type == 2) start else tableTime(t.text("EndTime", t.text("EndSecond")))
                TimeSlot(id = newId(), name = if (type == 1) t.text("BreakName").ifEmpty { "课间休息" } else if (type == 2) "分割线" else "第 ${i + 1} 节", start = start, end = end, type = SlotType.entries[type], defaultSubject = cleanId(t.text("DefaultClassId")))
            }
            TimeLayout(id = id, name = v.text("Name", "导入作息"), slots = slots)
        }
        val sourceGroups = root.getAsJsonObject("ClassPlanGroups")?.entrySet()?.map { (id, value) -> val v = value.asJsonObject; PlanGroup(id, v.text("Name", "导入课表群"), id == emptyGuid || v.flag("IsGlobal")) }.orEmpty()
        val groups = sourceGroups.toMutableList()
        fun groupId(source: String): String {
            val key = source.ifEmpty { "default" }
            if (groups.none { it.id == key }) groups.add(PlanGroup(key, if (key == emptyGuid) "全局课表" else "常规课表", key == emptyGuid))
            return key
        }
        val plans = root.getAsJsonObject("ClassPlans").entrySet().map { (id, value) ->
            val v = value.asJsonObject; val layout = layouts.firstOrNull { it.id == v.text("TimeLayoutId") } ?: error("课表 ${v.text("Name")} 的时间表不存在")
            val rule = v.getAsJsonObject("TimeRule") ?: JsonObject(); val rotationWeek = rule.number("WeekCountDiv"); val cycle = if (rotationWeek == 0) 1 else rule.number("WeekCountDivTotal", 2)
            val times = layout.slots.filter { it.type == SlotType.LESSON }; val classes = v.getAsJsonArray("Classes")?.toList().orEmpty()
            val map = times.mapIndexed { i, t -> t.id to cleanId(classes.getOrNull(i)?.asJsonObject?.text("SubjectId").orEmpty()) }.toMap()
            val disabled = times.mapIndexedNotNull { i, t -> if (classes.getOrNull(i)?.asJsonObject?.flag("IsEnabled", true) == false) t.id else null }.toSet()
            ClassPlan(id, v.text("Name", "导入课表"), layout.id, map, setOf(rule.number("WeekDay")), cycle, if (rotationWeek == 0) 1 else rotationWeek, enabled = v.flag("IsEnabled", true), groupId = groupId(v.text("AssociatedGroup")), layerOnly = v.flag("IsOverlay"), disabledSlots = disabled)
        }
        if (groups.isEmpty()) groups.add(PlanGroup("default", "常规课表"))
        val selected = root.text("SelectedClassPlanGroupId").takeIf { id -> groups.any { it.id == id } } ?: groups.first().id
        val ordered = root.getAsJsonObject("OrderedSchedules")?.entrySet()?.mapNotNull { (day, value) ->
            val target = plans.firstOrNull { it.id == value.asJsonObject.text("ClassPlanId") } ?: error("预定课表引用缺失：$day")
            if (target.layerOnly && !root.flag("IsOverlayClassPlanEnabled")) {
                warnings.add("$day 的临时层在原档案中未启用，未导入其日期启用安排。")
                return@mapNotNull null
            }
            val date = LocalDate.parse(day.substringBefore('T')); val zone = ZoneId.systemDefault()
            Temporary(name = "ClassIsland 预定 · $date", type = TempType.PLAN, startMillis = date.atStartOfDay(zone).toInstant().toEpochMilli(), endMillis = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), targetId = target.id)
        }.orEmpty()
        val profile = Profile(name = root.text("Name").ifBlank { "ClassIsland 导入" }, subjects = subjects, layouts = layouts, plans = plans, groups = groups, activeGroup = selected, temporary = ordered)
        Repository.validate(AppData(profiles = listOf(profile), activeProfile = profile.id))
        return ImportResult(profile, warnings)
    }

    fun xlsx(bytes: ByteArray): List<ImportTable> {
        val files = mutableMapOf<String, ByteArray>(); var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry; var count = 0
            while (entry != null) {
                require(++count < 5000) { "工作簿条目过多" }
                if (!entry.isDirectory) {
                    val keep = entry.name.startsWith("xl/") && (entry.name.endsWith(".xml") || entry.name.endsWith(".rels"))
                    val out = if (keep) java.io.ByteArrayOutputStream() else null
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer); if (n < 0) break
                        total += n; require(total <= 20 * 1024 * 1024) { "工作簿展开后超过 20 MB" }
                        out?.write(buffer, 0, n)
                    }
                    if (out != null) files[entry.name] = out.toByteArray()
                }
                zip.closeEntry(); entry = zip.nextEntry
            }
        }
        require(files.containsKey("xl/workbook.xml")) { "不是有效的 XLSX 工作簿" }
        val strings = mutableListOf<String>(); var shared = StringBuilder()
        files["xl/sharedStrings.xml"]?.let { xml -> parseXml(xml) { parser -> when { parser.eventType == XmlPullParser.START_TAG && parser.name == "si" -> shared = StringBuilder(); parser.eventType == XmlPullParser.START_TAG && parser.name == "t" -> shared.append(parser.nextText()); parser.eventType == XmlPullParser.END_TAG && parser.name == "si" -> strings.add(shared.toString()) } } }
        val sheets = mutableListOf<Pair<String, String>>()
        parseXml(files.getValue("xl/workbook.xml")) { parser -> if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") sheets.add((parser.getAttributeValue(null, "name") ?: "工作表") to (parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id") ?: "")) }
        val relations = mutableMapOf<String, String>()
        files["xl/_rels/workbook.xml.rels"]?.let { xml -> parseXml(xml) { parser -> if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship" && parser.getAttributeValue(null, "TargetMode") != "External") { val path = parser.getAttributeValue(null, "Target") ?: ""; relations[parser.getAttributeValue(null, "Id")] = if (path.startsWith('/')) path.removePrefix("/") else "xl/$path" } } }
        return sheets.mapNotNull { (name, id) ->
            val path = relations[id] ?: "xl/worksheets/sheet${sheets.indexOfFirst { it.second == id } + 1}.xml"; val xml = files[path] ?: return@mapNotNull null
            val cells = mutableMapOf<Pair<Int, Int>, String>(); val merged = mutableListOf<String>(); var position = 0 to 0; var type = ""; var value = StringBuilder(); var cell = false
            parseXml(xml) { parser ->
                if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                    "c" -> { position = cellPosition(parser.getAttributeValue(null, "r") ?: "A1"); type = parser.getAttributeValue(null, "t") ?: ""; value = StringBuilder(); cell = true }
                    "v", "t" -> if (cell) value.append(parser.nextText())
                    "mergeCell" -> merged.add(parser.getAttributeValue(null, "ref") ?: "")
                }
                if (parser.eventType == XmlPullParser.END_TAG && parser.name == "c") { cells[position] = if (type == "s") strings.getOrNull(value.toString().toIntOrNull() ?: -1).orEmpty() else value.toString(); cell = false }
                require(cells.size < 100000) { "工作表过大" }
            }
            merged.forEach { area -> val ends = area.split(':'); if (ends.size == 2) { val a = cellPosition(ends[0]); val z = cellPosition(ends[1]); val text = cells[a].orEmpty(); require((z.first - a.first + 1) * (z.second - a.second + 1) < 10000) { "合并区域过大" }; for (r in a.first..z.first) for (c in a.second..z.second) cells.putIfAbsent(r to c, text) } }
            val maxRow = cells.keys.maxOfOrNull { it.first } ?: 0; val maxCol = cells.keys.maxOfOrNull { it.second } ?: 0
            ImportTable(name, (0..maxRow).map { r -> (0..maxCol).map { c -> cells[r to c].orEmpty() } })
        }.also { require(it.isNotEmpty()) { "工作簿没有可读取工作表" } }
    }
    private fun parseXml(bytes: ByteArray, visit: (XmlPullParser) -> Unit) {
        val parser = Xml.newPullParser(); parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        while (parser.eventType != XmlPullParser.END_DOCUMENT) { require(parser.eventType != XmlPullParser.DOCDECL) { "不支持 XML 文档实体" }; visit(parser); parser.next() }
    }
    private fun cellPosition(text: String): Pair<Int, Int> {
        val match = Regex("([A-Z]+)([0-9]+)").matchEntire(text.uppercase().replace("$", "")) ?: error("单元格地址无效：$text")
        val column = match.groupValues[1].fold(0) { n, ch -> n * 26 + (ch - 'A' + 1) } - 1; val row = match.groupValues[2].toInt() - 1
        require(row in 0..1999 && column in 0..127) { "支持的工作表范围为 2000 行、128 列以内" }; return row to column
    }
}
