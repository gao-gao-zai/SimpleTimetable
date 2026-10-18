package io.github.gaogaoza.simpletimetable.data

import java.time.*
import java.time.temporal.ChronoUnit
import kotlin.math.max

data class Lesson(val slot: TimeSlot, val subject: Subject?, val planId: String, val temporary: Boolean = false, val sourceSlotId: String = slot.id)
data class Resolution(val profile: Profile, val date: LocalDate, val plan: ClassPlan?, val slots: List<TimeSlot>, val lessons: List<Lesson>, val reason: String, val conflicts: Boolean = false)
enum class Phase { CLASS, BREAK, BEFORE, EMPTY, ENDED }
data class CourseState(val resolution: Resolution, val phase: Phase, val current: Lesson? = null, val next: Lesson? = null, val remaining: Long = 0, val progress: Double? = null, val status: String = "", val secondOfDay: Int = 0) {
    val title: String get() = when (phase) { Phase.CLASS -> current?.subject?.name ?: "未安排科目"; Phase.BREAK -> status; Phase.BEFORE -> "今日尚未开课"; Phase.EMPTY -> "今天没有课程"; Phase.ENDED -> "今日课程已结束" }
    val targetSecond: Int? get() = if (phase == Phase.CLASS) current?.slot?.end else next?.slot?.start
}

object Engine {
    fun matches(plan: ClassPlan, date: LocalDate): Boolean {
        if (!plan.enabled || plan.layerOnly || date.dayOfWeek.value % 7 !in plan.days) return false
        if (plan.cycle == 1) return true
        val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
        val base = LocalDate.parse(plan.anchor)
        val weeks = ChronoUnit.WEEKS.between(base, monday) + plan.offsetWeeks
        return Math.floorMod(weeks, plan.cycle.toLong()).toInt() + 1 == plan.week
    }

    private fun resolveAt(profile: Profile, date: LocalDate, effectiveMillis: Long, zone: ZoneId): Resolution {
        // Historical/future browsing resolves date-wide arrangements at noon, not at today's clock.
        val effective = Instant.ofEpochMilli(effectiveMillis).atZone(zone)
        val at = if (effective.toLocalDate() == date) effectiveMillis else date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val active = profile.temporary.filter { at >= it.startMillis && at < it.endMillis }
        val groupTemp = active.lastOrNull { it.type == TempType.GROUP }
        val selected = groupTemp?.targetId ?: profile.activeGroup
        val allowed = setOf(selected) + if (groupTemp?.inheritGroup == true) setOf(profile.activeGroup) else emptySet()
        val globals = profile.groups.filter { it.global }.map { it.id }.toSet()
        fun priority(q: ClassPlan): Int = when {
            groupTemp != null && q.groupId == groupTemp.targetId -> 3
            q.groupId == profile.activeGroup || profile.activeGroup.isEmpty() && q.groupId !in globals -> 2
            q.groupId in globals -> 1
            else -> 0
        }
        val candidates = profile.plans.filter { matches(it, date) && (it.groupId in allowed || it.groupId in globals || selected.isEmpty()) }
            .sortedByDescending(::priority)
        var plan = candidates.firstOrNull()
        var reason = plan?.let { "自动匹配 · ${it.name}" } ?: "没有匹配的课表"
        profile.plans.firstOrNull { it.id == profile.manualPlan }?.let { plan = it; reason = "手动启用 · ${it.name}" }
        val tempPlan = active.lastOrNull { it.type == TempType.PLAN }
        profile.plans.firstOrNull { it.id == tempPlan?.targetId }?.let { plan = it; reason = "临时课表 · ${it.name}" }
        var slots = profile.layouts.firstOrNull { it.id == plan?.layoutId }?.slots.orEmpty().sortedBy { it.start }
        fun lessons(q: ClassPlan?, points: List<TimeSlot>, overlay: Boolean = false): List<Lesson> {
            if (q == null) return emptyList()
            val layout = profile.layouts.firstOrNull { it.id == q.layoutId }
            return points.filter { it.type == SlotType.LESSON && it.id !in q.disabledSlots }.map { slot ->
                val subjectId = q.subjects[slot.id]?.ifEmpty { null } ?: slot.defaultSubject.ifEmpty { layout?.defaultSubject.orEmpty() }
                Lesson(slot, profile.subjects.firstOrNull { it.id == subjectId }, q.id, overlay)
            }
        }
        var entries = lessons(plan, slots, tempPlan != null)
        // A layer only replaces intersecting lesson intervals; other base lessons remain.
        for (layer in active.filter { it.type == TempType.LAYER }) {
            val q = profile.plans.firstOrNull { it.id == layer.targetId } ?: continue
            val layout = profile.layouts.firstOrNull { it.id == q.layoutId } ?: continue
            val over = lessons(q, layout.slots, true).filter { it.subject != null }
            val ranges = over.map { it.slot.start until it.slot.end }
            fun covered(s: TimeSlot) = ranges.any { s.start < it.last + 1 && it.first < s.end }
            entries = entries.filterNot { covered(it.slot) } + over
            slots = slots.filterNot { covered(it) } + layout.slots.filter { it.type != SlotType.LESSON && !covered(it) } + over.map { it.slot }
            reason = "临时层 · ${q.name}"
        }
        for (temporary in active.filter { it.type == TempType.LESSON || it.type == TempType.SWAP }) {
            for (patch in temporary.patches.filter { it.date == date.toString() }) {
                entries = entries.map { lesson ->
                    if (lesson.slot.id == patch.slotId && lesson.planId == patch.planId) lesson.copy(subject = profile.subjects.firstOrNull { it.id == patch.subjectId }, temporary = true) else lesson
                }
            }
        }
        return Resolution(profile, date, plan, slots.distinctBy { it.id }.sortedBy { it.start }, entries.sortedBy { it.slot.start }, reason, candidates.count { priority(it) == candidates.firstOrNull()?.let(::priority) } > 1 && tempPlan == null && profile.manualPlan.isEmpty())
    }

    /** Date browsing includes arrangements during the whole day, including partial-day changes. */
    fun resolve(profile: Profile, date: LocalDate, effectiveMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Resolution {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val relevant = profile.temporary.filter { it.startMillis < end && it.endMillis > start }
        if (relevant.isEmpty()) return resolveAt(profile, date, effectiveMillis, zone)
        fun epoch(second: Int) = if (second == 86400) end else date.atTime(LocalTime.ofSecondOfDay(second.toLong())).atZone(zone).toInstant().toEpochMilli()
        val boundaries = (listOf(start, end) + profile.layouts.flatMap { it.slots }.flatMap { listOf(epoch(it.start), epoch(it.end)) } + relevant.flatMap { listOf(it.startMillis.coerceAtLeast(start), it.endMillis.coerceAtMost(end)) }).distinct().sorted()
        val pieces = mutableListOf<Lesson>(); val otherSlots = mutableListOf<TimeSlot>()
        var conflicts = false
        for ((from, until) in boundaries.zipWithNext()) {
            val at = from + (until - from) / 2
            val r = resolveAt(profile, date, at, zone); conflicts = conflicts || r.conflicts
            val lo = Instant.ofEpochMilli(from).atZone(zone).toLocalTime().toSecondOfDay()
            val hi = if (until == end) 86400 else Instant.ofEpochMilli(until).atZone(zone).toLocalTime().toSecondOfDay()
            r.lessons.filter { it.slot.start < hi && it.slot.end > lo }.forEach { lesson ->
                val clipped = lesson.copy(slot = lesson.slot.copy(start = maxOf(lo, lesson.slot.start), end = minOf(hi, lesson.slot.end)))
                val last = pieces.lastOrNull()
                if (last != null && last.planId == clipped.planId && last.sourceSlotId == clipped.sourceSlotId && last.subject == clipped.subject && last.temporary == clipped.temporary && last.slot.end == clipped.slot.start) pieces[pieces.lastIndex] = last.copy(slot = last.slot.copy(end = clipped.slot.end))
                else pieces.add(clipped)
            }
            r.slots.filter { it.type != SlotType.LESSON && (if (it.type == SlotType.DIVIDER) it.start >= lo && it.start < hi else it.start < hi && it.end > lo) }.forEach { slot ->
                val clipped = slot.copy(id = "${slot.id}@$lo", start = maxOf(lo, slot.start), end = minOf(hi, slot.end))
                otherSlots.add(clipped)
            }
        }
        val shown = pieces.map { it.copy(slot = it.slot.copy(id = "${it.planId}:${it.sourceSlotId}@${it.slot.start}")) }
        val base = resolveAt(profile, date, effectiveMillis, zone)
        return base.copy(slots = (otherSlots.distinctBy { it.id } + shown.map { it.slot }).sortedBy { it.start }, lessons = shown, conflicts = conflicts)
    }

    fun state(profile: Profile, millis: Long, zone: ZoneId = ZoneId.systemDefault()): CourseState {
        val local = Instant.ofEpochMilli(millis).atZone(zone)
        val r = resolveAt(profile, local.toLocalDate(), millis, zone)
        val second = local.toLocalTime().toSecondOfDay()
        val items = r.lessons
        items.forEachIndexed { index, lesson ->
            val slot = lesson.slot
            if (second >= slot.start && second < slot.end) return CourseState(r, Phase.CLASS, lesson, items.getOrNull(index + 1), (slot.end - second).toLong(), (second - slot.start).toDouble() / (slot.end - slot.start), "正在上课", second)
            if (second < slot.start) {
                val explicit = r.slots.firstOrNull { it.type == SlotType.BREAK && second >= it.start && second < it.end }
                val start = explicit?.start ?: items.getOrNull(index - 1)?.slot?.end
                val progress = start?.let { if (slot.start > it) ((second - it).toDouble() / (slot.start - it)).coerceIn(0.0, 1.0) else null }
                val before = index == 0
                return CourseState(r, if (before) Phase.BEFORE else Phase.BREAK, next = lesson, remaining = (slot.start - second).toLong(), progress = progress, status = if (before) "今日尚未开课" else explicit?.name?.ifEmpty { "课间休息" } ?: "课程间隔", secondOfDay = second)
            }
        }
        return CourseState(r, if (items.isEmpty()) Phase.EMPTY else Phase.ENDED, progress = if (items.isEmpty()) 0.0 else 1.0, status = if (items.isEmpty()) "今天没有课程" else "今日课程已结束", secondOfDay = second)
    }
    fun nextBoundary(state: CourseState): Int? = state.resolution.slots.flatMap { listOf(it.start, it.end) }.filter { it > state.secondOfDay }.minOrNull()
}

fun clockText(second: Int): String = "%02d:%02d".format(second / 3600, second % 3600 / 60) + if (second % 60 == 0) "" else ":%02d".format(second % 60)
fun durationText(seconds: Long): String { val n = max(0, seconds); return if (n >= 3600) "%d:%02d:%02d".format(n / 3600, n % 3600 / 60, n % 60) else "%02d:%02d".format(n / 60, n % 60) }
fun parseClock(text: String): Int {
    val parts = text.trim().split(':').map { it.toIntOrNull() ?: error("时间格式应为 HH:mm 或 HH:mm:ss") }
    require(parts.size in 2..3 && parts[0] in 0..24 && parts[1] in 0..59 && (parts.getOrNull(2) ?: 0) in 0..59 && (parts[0] != 24 || parts.drop(1).all { it == 0 })) { "时间范围无效" }
    return parts[0] * 3600 + parts[1] * 60 + (parts.getOrNull(2) ?: 0)
}

fun CourseState.progressLabel(): String = when (phase) {
    Phase.CLASS -> "本节已过去 ${((progress ?: 0.0) * 100).toInt()}%"
    Phase.BREAK -> "$status 已过 ${((progress ?: 0.0) * 100).toInt()}%"
    Phase.BEFORE -> "首课 ${next?.slot?.start?.let(::clockText).orEmpty()} 上课"
    Phase.EMPTY -> "今天没有课程"
    Phase.ENDED -> "全部 ${resolution.lessons.size} 节课已结束"
}

fun CourseState.headingLabel(): String = when(phase) { Phase.EMPTY -> "今日安排"; Phase.BEFORE -> "首课前"; Phase.ENDED -> "今日进度"; else -> status }
