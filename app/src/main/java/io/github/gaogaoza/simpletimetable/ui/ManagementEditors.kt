@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.github.gaogaoza.simpletimetable.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.gaogaoza.simpletimetable.TimetableApplication
import io.github.gaogaoza.simpletimetable.data.*
import java.time.*

@Composable fun SubjectEditor(app: TimetableApplication, p: Profile, editor: Editor.SubjectEdit, f: EditForm) {
    val original = remember { p.subjects.firstOrNull { it.id == editor.id } ?: Subject() }
    var name by remember { mutableStateOf(original.name) }; var short by remember { mutableStateOf(original.shortName) }; var teacher by remember { mutableStateOf(original.teacher) }; var color by remember { mutableStateOf(original.color) }; var outdoor by remember { mutableStateOf(original.outdoor) }
    Input("科目全称", name, f) { name = it }; Input("简称", short, f) { short = it }; Input("教师", teacher, f) { teacher = it }; Input("科目颜色（#RRGGBB）", color, f) { color = it }; Toggle("户外课程", outdoor, f) { outdoor = it }
    f.submit = { require(color.matches(Regex("#[0-9A-Fa-f]{6}"))) { "颜色请使用 #RRGGBB" }; val item = original.copy(name = requireName(name), shortName = short.trim(), teacher = teacher.trim(), color = color, outdoor = outdoor); app.repository.editProfile(p.id) { it.copy(subjects = if (editor.id.isEmpty()) it.subjects + item else it.subjects.map { s -> if (s.id == item.id) item else s }) } }
    if (editor.id.isNotEmpty()) {
        Action("复制科目") { f.run { app.repository.editProfile(p.id) { it.copy(subjects = it.subjects + original.copy(id = newId(), name = original.name + " 副本")) }; f.close() } }
        Action("删除科目", true) { f.ask("删除「${original.name}」？") { require(p.layouts.none { it.defaultSubject == original.id || it.slots.any { slot -> slot.defaultSubject == original.id } } && p.plans.none { original.id in it.subjects.values } && p.temporary.none { it.patches.any { patch -> patch.subjectId == original.id } }) { "科目仍被时间表、课表或临时安排引用，请先解除引用。" }; app.repository.editProfile(p.id) { it.copy(subjects = it.subjects.filterNot { s -> s.id == original.id }) }; f.close() } }
    }
}

@Composable fun LayoutEditor(app: TimetableApplication, p: Profile, editor: Editor.LayoutEdit, f: EditForm) {
    val original = remember { p.layouts.firstOrNull { it.id == editor.id } ?: TimeLayout() }
    val live = p.layouts.firstOrNull { it.id == original.id } ?: original
    var name by remember { mutableStateOf(original.name) }; var minutes by remember { mutableStateOf(original.defaultMinutes.toString()) }; var defaultSubject by remember { mutableStateOf(original.defaultSubject) }
    Input("时间表名称", name, f) { name = it }; Input("默认课程时长（分钟）", minutes, f, true) { minutes = it }; Choice("默认科目", defaultSubject, listOf("" to "不设置默认科目") + p.subjects.map { it.id to it.name }, f) { defaultSubject = it }
    if (editor.id.isNotEmpty()) {
        Explanation("${p.plans.count { it.layoutId == live.id }} 份课表引用此时间表，修改时段会共同生效。")
        live.slots.sortedBy { it.start }.forEach { slot -> SectionRow(slot.name, if (slot.type == SlotType.DIVIDER) "分割线" else "${clockText(slot.start)} — ${clockText(slot.end)}") { f.open(Editor.SlotEdit(live.id, slot.id)) } }
        Action("新增时段") { f.open(Editor.SlotEdit(live.id)) }
        Action("复制为独立时间表") { f.run { app.repository.editProfile(p.id) { it.copy(layouts = it.layouts + live.copy(id = newId(), name = live.name + " 副本")) }; f.close() } }
        Action("删除时间表", true) { f.ask("删除这份时间表？") { require(p.plans.none { it.layoutId == live.id }) { "仍有课表引用此时间表，请先更换引用。" }; app.repository.editProfile(p.id) { it.copy(layouts = it.layouts.filterNot { l -> l.id == live.id }) }; f.close() } }
    } else Explanation("保存时间表后即可添加课程、课间和分割线。")
    f.submit = { val item = live.copy(name = requireName(name), defaultMinutes = intRange(minutes, 1..720), defaultSubject = defaultSubject); app.repository.editProfile(p.id) { it.copy(layouts = if (editor.id.isEmpty()) it.layouts + item else it.layouts.map { l -> if (l.id == item.id) item else l }) } }
    if (editor.id.isEmpty()) f.afterSave = { f.open(Editor.LayoutEdit(original.id)) }
}

@Composable fun SlotEditor(app: TimetableApplication, p: Profile, editor: Editor.SlotEdit, f: EditForm) {
    val layout = p.layouts.first { it.id == editor.layout }; val end = layout.slots.filter { it.type != SlotType.DIVIDER }.maxOfOrNull { it.end } ?: 8 * 3600
    val original = remember { layout.slots.firstOrNull { it.id == editor.id } ?: TimeSlot(name = "新时段", start = end.coerceAtMost(23 * 3600), end = (end + layout.defaultMinutes * 60).coerceAtMost(86400)) }
    var name by remember { mutableStateOf(original.name) }; var start by remember { mutableStateOf(clockText(original.start)) }; var finish by remember { mutableStateOf(clockText(original.end)) }; var type by remember { mutableStateOf(original.type.name) }; var default by remember { mutableStateOf(original.defaultSubject) }
    Input("时段名称", name, f) { name = it }; Choice("类型", type, listOf("LESSON" to "上课", "BREAK" to "课间", "DIVIDER" to "分割线"), f) { type = it }; Input("开始时间", start, f) { start = it }; if (type != "DIVIDER") Input("结束时间", finish, f) { finish = it }
    if (type == "LESSON") Choice("默认科目", default, listOf("" to "使用时间表默认科目") + p.subjects.map { it.id to it.name }, f) { default = it }
    f.submit = {
        val kind = SlotType.valueOf(type); val item = original.copy(name = requireName(name), start = parseClock(start), end = if (kind == SlotType.DIVIDER) parseClock(start) else parseClock(finish), type = kind, defaultSubject = default)
        app.repository.editProfile(p.id) { profile -> profile.copy(layouts = profile.layouts.map { if (it.id == layout.id) it.copy(slots = (it.slots.filterNot { s -> s.id == item.id } + item).sortedBy { s -> s.start }) else it }, plans = profile.plans.map { q -> if (q.layoutId == layout.id && kind != SlotType.LESSON) q.copy(subjects = q.subjects - item.id, disabledSlots = q.disabledSlots - item.id) else q }) }
    }
    if (editor.id.isNotEmpty()) Action("删除共享时段", true) { f.ask("所有引用此时间表的课表都会移除此时段，继续？") {
        require(p.temporary.none { t -> t.patches.any { patch -> patch.slotId == original.id && p.plans.any { it.id == patch.planId && it.layoutId == layout.id } } }) { "临时安排仍引用此时段，请先撤销对应安排。" }
        app.repository.editProfile(p.id) { it.copy(layouts = it.layouts.map { l -> if (l.id == layout.id) l.copy(slots = l.slots.filterNot { s -> s.id == original.id }) else l }, plans = it.plans.map { q -> if (q.layoutId == layout.id) q.copy(subjects = q.subjects - original.id, disabledSlots = q.disabledSlots - original.id) else q }) }; f.close()
    } }
}

@Composable fun PlanEditor(app: TimetableApplication, p: Profile, editor: Editor.PlanEdit, f: EditForm) {
    if (p.layouts.isEmpty()) { Explanation("请先创建时间表。") ; return }
    val original = remember { p.plans.firstOrNull { it.id == editor.id } ?: ClassPlan(layoutId = p.layouts.first().id, groupId = p.groups.first().id) }
    var name by remember { mutableStateOf(original.name) }; var layoutId by remember { mutableStateOf(original.layoutId) }; var days by remember { mutableStateOf(original.days) }; var cycle by remember { mutableStateOf(original.cycle.toString()) }; var week by remember { mutableStateOf(original.week.toString()) }; var anchor by remember { mutableStateOf(original.anchor) }; var offset by remember { mutableStateOf(original.offsetWeeks.toString()) }; var enabled by remember { mutableStateOf(original.enabled) }; var group by remember { mutableStateOf(original.groupId) }; var layer by remember { mutableStateOf(original.layerOnly) }
    Input("课表名称", name, f) { name = it }; Choice("时间表", layoutId, p.layouts.map { it.id to it.name }, f) { layoutId = it }; Choice("所属课表群", group, p.groups.map { it.id to it.name }, f) { group = it }
    Text("适用星期", style = MaterialTheme.typography.labelLarge)
    FlowRow { listOf(1, 2, 3, 4, 5, 6, 0).forEach { day -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(day in days, onCheckedChange = { f.changed(); days = if (it) days + day else days - day }); Text("周${"日一二三四五六"[day]}") } } }
    Input("轮换总周数（1 为每周）", cycle, f, true) { cycle = it }; Input("适用第几周", week, f, true) { week = it }; Input("第 1 周周一基准（yyyy-MM-dd）", anchor, f) { anchor = it }; Input("周偏移", offset, f) { offset = it }; Toggle("参与自动匹配", enabled, f) { enabled = it }; Toggle("仅作为临时层来源", layer, f) { layer = it }
    if (editor.id.isNotEmpty()) {
        Action("安排课程") { f.open(Editor.PlanItems(original.id)) }
        Action("复制课表") { f.run { app.repository.editProfile(p.id) { it.copy(plans = it.plans + original.copy(id = newId(), name = original.name + " 副本", enabled = false)) }; f.close() } }
        Action("删除课表", true) { f.ask("删除课表？科目和时间表会保留。") { require(p.manualPlan != original.id && p.temporary.none { it.targetId == original.id || it.patches.any { patch -> patch.planId == original.id } }) { "课表仍被手动启用或临时安排引用，请先解除。" }; app.repository.editProfile(p.id) { it.copy(plans = it.plans.filterNot { q -> q.id == original.id }) }; f.close() } }
    } else Explanation("保存规则后进入安排课程。")
    f.submit = {
        val newLayout = p.layouts.first { it.id == layoutId }; val oldSlots = p.layouts.firstOrNull { it.id == original.layoutId }?.slots?.filter { it.type == SlotType.LESSON }?.sortedBy { it.start }.orEmpty()
        val subjects = if (layoutId == original.layoutId) original.subjects else newLayout.slots.filter { it.type == SlotType.LESSON }.sortedBy { it.start }.mapIndexed { i, s -> s.id to original.subjects[oldSlots.getOrNull(i)?.id].orEmpty() }.toMap()
        val q = original.copy(name = requireName(name), layoutId = layoutId, groupId = group, days = days, cycle = intRange(cycle, 1..52), week = intRange(week, 1..52), anchor = LocalDate.parse(anchor).toString(), offsetWeeks = intRange(offset, -5200..5200), enabled = enabled, layerOnly = layer, subjects = subjects, disabledSlots = if (layoutId == original.layoutId) original.disabledSlots else emptySet())
        app.repository.editProfile(p.id) { it.copy(plans = if (editor.id.isEmpty()) it.plans + q else it.plans.map { old -> if (old.id == q.id) q else old }) }
    }
    if (editor.id.isEmpty()) f.afterSave = { f.open(Editor.PlanItems(original.id)) }
}

@Composable fun PlanItemsEditor(app: TimetableApplication, p: Profile, editor: Editor.PlanItems, f: EditForm) {
    val plan = p.plans.first { it.id == editor.id }; val layout = p.layouts.first { it.id == plan.layoutId }; var assignments by remember { mutableStateOf(plan.subjects) }; var disabled by remember { mutableStateOf(plan.disabledSlots) }
    Explanation("${layout.name} · 保存后影响此课表适用的日期。")
    layout.slots.filter { it.type == SlotType.LESSON }.sortedBy { it.start }.forEach { slot ->
        Choice("${slot.name} · ${clockText(slot.start)}", assignments[slot.id].orEmpty(), listOf("" to "使用时间表默认科目") + p.subjects.map { it.id to it.name }, f) { assignments = assignments + (slot.id to it) }
        Toggle("启用此课程", slot.id !in disabled, f) { disabled = if (it) disabled - slot.id else disabled + slot.id }
    }
    f.submit = { app.repository.editProfile(p.id) { it.copy(plans = it.plans.map { q -> if (q.id == plan.id) q.copy(subjects = assignments, disabledSlots = disabled) else q }) } }
}

@Composable fun GroupEditor(app: TimetableApplication, p: Profile, editor: Editor.GroupEdit, f: EditForm) {
    val original = remember { p.groups.firstOrNull { it.id == editor.id } ?: PlanGroup() }; var name by remember { mutableStateOf(original.name) }; var global by remember { mutableStateOf(original.global) }; var members by remember { mutableStateOf(p.plans.filter { it.groupId == original.id }.map { it.id }.toSet()) }
    Input("课表群名称", name, f) { name = it }; Toggle("全局课表群", global, f) { global = it }
    Explanation("每份课表属于一个课表群；勾选后会从原群移动至这里。全局课表参与所有日期的规则匹配。")
    p.plans.forEach { q -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(q.id in members, onCheckedChange = { f.changed(); members = if (it) members + q.id else members - q.id }); Text(q.name) } }
    val fallback = p.groups.firstOrNull { it.id != original.id && !it.global }?.id ?: p.groups.firstOrNull { it.id != original.id }?.id
    f.submit = { app.repository.editProfile(p.id) { it.copy(groups = if (editor.id.isEmpty()) it.groups + original.copy(name = requireName(name), global = global) else it.groups.map { g -> if (g.id == original.id) g.copy(name = requireName(name), global = global) else g }, plans = it.plans.map { q -> if (q.id in members) q.copy(groupId = original.id) else if (q.groupId == original.id && fallback != null) q.copy(groupId = fallback) else q }) } }
    if (editor.id.isNotEmpty()) Action("解散课表群，保留课表", true) { f.ask("解散这个课表群？课表会移到其他群。") { require(fallback != null && p.activeGroup != original.id && p.temporary.none { it.type == TempType.GROUP && it.targetId == original.id }) { "课表群正在使用，或没有其他可接收的群，请先切换。" }; app.repository.editProfile(p.id) { it.copy(groups = it.groups.filterNot { g -> g.id == original.id }, plans = it.plans.map { q -> if (q.groupId == original.id) q.copy(groupId = fallback) else q }) }; f.close() } }
}

@Composable fun ProfileEditor(app: TimetableApplication, data: AppData, session: UiSession, editor: Editor.ProfileEdit, f: EditForm) {
    val original = remember { data.profiles.firstOrNull { it.id == editor.id } ?: Profile() }; var name by remember { mutableStateOf(if (editor.id.isEmpty()) "" else original.name) }; var sample by remember { mutableStateOf(false) }
    Input("档案名称", name, f) { name = it }
    if (editor.id.isEmpty()) {
        Toggle("包含示例科目和作息", sample, f) { sample = it }
        f.submit = { val p = if (sample) SampleData.profile(requireName(name)) else original.copy(name = requireName(name)); app.repository.update { it.copy(profiles = it.profiles + p) }; session.profileId = p.id }
    } else {
        Explanation("${original.subjects.size} 个科目 · ${original.layouts.size} 份时间表 · ${original.plans.size} 份课表")
        Action("浏览与编辑此档案") { f.run { session.profileId = original.id; session.page = "manage"; f.close() } }
        if (data.active().id != original.id) Action("设为当前运行档案") { f.ask("切换后现在页、通知和小组件会立即使用此档案，继续？") { app.repository.update { it.copy(activeProfile = original.id) }; session.profileId = original.id; f.close() } } else Explanation("当前已启用")
        Action("复制完整档案") { f.run { app.repository.update { it.copy(profiles = it.profiles + original.copy(id = newId(), name = original.name + " 副本")) }; f.close() } }
        Action("删除档案", true) { f.ask("删除档案及其全部课程数据？") { require(original.id != app.repository.current.active().id) { "请先启用其他档案再删除。" }; app.repository.update { it.copy(profiles = it.profiles.filterNot { p -> p.id == original.id }) }; if (session.profileId == original.id) session.profileId = app.repository.current.active().id; f.close() } }
        f.submit = { app.repository.editProfile(original.id) { it.copy(name = requireName(name)) } }
    }
}

@Composable fun ActivationEditor(app: TimetableApplication, p: Profile, data: AppData, f: EditForm) {
    var group by remember { mutableStateOf(p.activeGroup) }; var manual by remember { mutableStateOf(p.manualPlan) }
    Choice("自动匹配的课表群", group, listOf("" to "所有课表群") + p.groups.map { it.id to it.name }, f) { group = it }
    Choice("启用方式", manual, listOf("" to "按规则自动匹配") + p.plans.map { it.id to "手动固定：${it.name}" }, f) { manual = it }
    Explanation(if (p.id == data.active().id) "保存会立即更新当前运行结果。" else "此档案尚未启用，保存不会切换当前运行档案。")
    f.submit = { app.repository.editProfile(p.id) { it.copy(activeGroup = group, manualPlan = manual) } }
}
