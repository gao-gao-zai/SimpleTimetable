package io.github.gaogaoza.simpletimetable.data

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class Repository(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "timetable.json"))
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    var loadError: String? = null
        private set
    private val mutable = MutableStateFlow(load())
    val data = mutable.asStateFlow()
    val current: AppData get() = mutable.value
    private fun load(): AppData {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return AppData().let { it.copy(activeProfile = it.active().id) }
        return try { file.openRead().bufferedReader().use { gson.fromJson(it, AppData::class.java) }.also { validate(it) } }
        catch (e: Exception) { loadError = "本地课表读取失败，原文件已保留：${e.message}"; AppData() }
    }
    @Synchronized fun update(change: (AppData) -> AppData) {
        check(loadError == null) { "请先导出保留的本地数据，再处理读取错误，避免覆盖原文件。" }
        val next = change(current)
        validate(next)
        val output = file.startWrite()
        try { output.write(gson.toJson(next).toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
        mutable.value = next
    }
    fun editProfile(id: String, change: (Profile) -> Profile) = update { data -> data.copy(profiles = data.profiles.map { if (it.id == id) change(it) else it }) }
    fun settings(change: (Settings) -> Settings) = update { it.copy(settings = change(it.settings)) }
    fun originalBytes(): ByteArray = if (loadError != null) file.openRead().use { it.readBytes() } else gson.toJson(current).toByteArray()
    companion object {
        fun validate(data: AppData) {
            require(data.schema == 1) { "不支持此数据版本" }
            require(data.profiles.isNotEmpty()) { "至少保留一个档案" }
            require(data.profiles.map { it.id }.distinct().size == data.profiles.size) { "档案标识重复" }
            require(data.activeProfile.isEmpty() || data.profiles.any { it.id == data.activeProfile }) { "当前档案不存在" }
            require(data.settings.seed.matches(Regex("#[0-9a-fA-F]{6}"))) { "主题色格式无效" }
            require(data.settings.schoolOffset in -86400..86400) { "学校偏移不得超过一天" }
            data.profiles.forEach { p ->
                require(p.name.isNotBlank()) { "档案名称不能为空" }
                require(p.subjects.map { it.id }.distinct().size == p.subjects.size) { "科目标识重复" }
                require(p.layouts.map { it.id }.distinct().size == p.layouts.size) { "时间表标识重复" }
                require(p.plans.map { it.id }.distinct().size == p.plans.size) { "课表标识重复" }
                require(p.groups.map { it.id }.distinct().size == p.groups.size) { "课表群标识重复" }
                require(p.subjects.all { it.name.isNotBlank() && it.color.matches(Regex("#[0-9a-fA-F]{6}")) }) { "科目名称或颜色无效" }
                require(p.activeGroup.isEmpty() || p.groups.any { it.id == p.activeGroup }) { "启用的课表群不存在" }
                require(p.temporary.map { it.id }.distinct().size == p.temporary.size) { "临时安排标识重复" }
                p.layouts.forEach { l ->
                    require(l.name.isNotBlank() && l.defaultMinutes in 1..720) { "时间表名称或默认时长无效" }
                    require(l.defaultSubject.isEmpty() || p.subjects.any { it.id == l.defaultSubject }) { "默认科目不存在" }
                    require(l.slots.all { slot -> slot.defaultSubject.isEmpty() || p.subjects.any { it.id == slot.defaultSubject } }) { "时段默认科目不存在" }
                    require(l.slots.map { it.id }.distinct().size == l.slots.size) { "时段标识重复" }
                    l.slots.forEach { s -> require(s.start in 0..86399 && (s.type == SlotType.DIVIDER || s.end in 1..86400 && s.end > s.start)) { "时段时间无效" } }
                    val ranges = l.slots.filter { it.type != SlotType.DIVIDER }.sortedBy { it.start }
                    require(ranges.zipWithNext().none { (a, b) -> a.end > b.start }) { "时间表时段不能重叠" }
                }
                p.plans.forEach { q ->
                    require(p.layouts.any { it.id == q.layoutId }) { "课表引用的时间表不存在" }
                    require(q.cycle in 1..52 && q.week in 1..q.cycle && q.days.isNotEmpty() && q.days.all { it in 0..6 }) { "周轮换规则无效" }
                    require(java.time.LocalDate.parse(q.anchor).dayOfWeek == java.time.DayOfWeek.MONDAY) { "周次基准必须是星期一" }
                    require(p.groups.any { it.id == q.groupId }) { "课表群不存在" }
                    val lessonIds = p.layouts.first { it.id == q.layoutId }.slots.filter { it.type == SlotType.LESSON }.map { it.id }.toSet()
                    require(q.subjects.keys.all { it in lessonIds } && q.disabledSlots.all { it in lessonIds }) { "课表引用的课程时段不存在" }
                    require(q.subjects.values.all { s -> s.isEmpty() || p.subjects.any { it.id == s } }) { "课表引用的科目不存在" }
                }
                require(p.manualPlan.isEmpty() || p.plans.any { it.id == p.manualPlan }) { "手动启用的课表不存在" }
                p.temporary.forEach { t ->
                    require(t.endMillis > t.startMillis) { "临时安排失效时间必须晚于生效时间" }
                    if (t.type in listOf(TempType.PLAN, TempType.LAYER)) require(p.plans.any { it.id == t.targetId }) { "临时课表引用缺失" }
                    if (t.type == TempType.GROUP) require(p.groups.any { it.id == t.targetId }) { "临时课表群引用缺失" }
                    t.patches.forEach { patch ->
                        java.time.LocalDate.parse(patch.date)
                        val q = p.plans.firstOrNull { it.id == patch.planId }
                        require(q != null && p.subjects.any { it.id == patch.subjectId } && p.layouts.first { it.id == q.layoutId }.slots.any { it.id == patch.slotId && it.type == SlotType.LESSON }) { "临时课程引用缺失" }
                    }
                }
            }
        }
    }
}
