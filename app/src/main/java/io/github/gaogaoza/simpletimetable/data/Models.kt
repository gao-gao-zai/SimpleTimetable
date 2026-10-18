package io.github.gaogaoza.simpletimetable.data

import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
enum class SlotType { LESSON, BREAK, DIVIDER }
data class Subject(val id: String = newId(), val name: String = "", val shortName: String = "", val teacher: String = "", val color: String = "#657E9C", val outdoor: Boolean = false)
data class TimeSlot(val id: String = newId(), val name: String = "", val start: Int = 8 * 3600, val end: Int = 8 * 3600 + 2700, val type: SlotType = SlotType.LESSON, val defaultSubject: String = "")
data class TimeLayout(val id: String = newId(), val name: String = "", val defaultMinutes: Int = 45, val defaultSubject: String = "", val slots: List<TimeSlot> = emptyList())
data class ClassPlan(
    val id: String = newId(), val name: String = "", val layoutId: String = "",
    val subjects: Map<String, String> = emptyMap(), val days: Set<Int> = setOf(1),
    val cycle: Int = 1, val week: Int = 1,
    val anchor: String = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
    val offsetWeeks: Int = 0, val enabled: Boolean = true, val groupId: String = "default", val layerOnly: Boolean = false,
    val disabledSlots: Set<String> = emptySet()
)
data class PlanGroup(val id: String = newId(), val name: String = "", val global: Boolean = false)
enum class TempType { PLAN, LAYER, GROUP, LESSON, SWAP }
data class LessonPatch(val date: String = "", val planId: String = "", val slotId: String = "", val subjectId: String = "")
data class Temporary(
    val id: String = newId(), val name: String = "", val type: TempType = TempType.PLAN,
    val startMillis: Long = 0, val endMillis: Long = 0, val targetId: String = "",
    val inheritGroup: Boolean = false, val patches: List<LessonPatch> = emptyList()
)
data class Profile(
    val id: String = newId(), val name: String = "我的课表", val subjects: List<Subject> = emptyList(),
    val layouts: List<TimeLayout> = emptyList(), val plans: List<ClassPlan> = emptyList(),
    val groups: List<PlanGroup> = listOf(PlanGroup("default", "常规课表"), PlanGroup("global", "全局课表", true)),
    val activeGroup: String = "default", val manualPlan: String = "", val temporary: List<Temporary> = emptyList()
)
data class Settings(
    val seed: String = "#355E45", val theme: String = "system", val largeText: Boolean = false,
    val highContrast: Boolean = false, val reducedMotion: Boolean = false, val notification: Boolean = false,
    val softwareClock: Boolean = false, val autoSync: Boolean = true, val schoolOffset: Int = 0,
    val calibrationMillis: Long = 0, val syncEpoch: Long = 0, val syncElapsed: Long = 0,
    val syncBootCount: Int = -1, val syncStatus: String = "尚未校时", val ntpHost: String = "time.android.com"
)
data class AppData(val schema: Int = 1, val profiles: List<Profile> = listOf(Profile()), val activeProfile: String = "", val settings: Settings = Settings()) {
    fun active(): Profile = profiles.firstOrNull { it.id == activeProfile } ?: profiles.first()
}

object SampleData {
    fun profile(name: String = "示例课表"): Profile {
        val names = listOf("语文", "数学", "英语", "物理", "地理", "体育")
        val colors = listOf("#9A633F", "#4A7257", "#657E9C", "#887099", "#7F8C47", "#AE6E66")
        val subjects = names.mapIndexed { i, n -> Subject(name = n, shortName = n.take(1), teacher = listOf("李老师", "陈老师", "王老师", "张老师", "刘老师", "赵老师")[i], color = colors[i], outdoor = i == 5) }
        val times = listOf(480 to 525, 540 to 585, 595 to 640, 650 to 695, 840 to 885, 895 to 940)
        val lessons = times.mapIndexed { i, t -> TimeSlot(name = "第 ${i + 1} 节", start = t.first * 60, end = t.second * 60) }
        val layout = TimeLayout(name = "秋季作息", defaultSubject = subjects.first().id, slots = (lessons + TimeSlot(name = "课间休息", start = 585 * 60, end = 595 * 60, type = SlotType.BREAK)).sortedBy { it.start })
        return Profile(name = name, subjects = subjects, layouts = listOf(layout), plans = (1..5).map { day ->
            ClassPlan(name = "周${"日一二三四五六"[day]}课表", layoutId = layout.id, days = setOf(day), subjects = lessons.mapIndexed { i, slot -> slot.id to subjects[(i + day - 1) % 6].id }.toMap())
        })
    }
}
