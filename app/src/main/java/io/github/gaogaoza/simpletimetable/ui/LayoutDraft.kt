package io.github.gaogaoza.simpletimetable.ui

import androidx.compose.runtime.*
import io.github.gaogaoza.simpletimetable.data.*
import kotlin.math.roundToInt

data class LayoutSnapshot(val layout: TimeLayout, val selected: String)

/** Immutable data in a local draft; only the outer Save writes to Repository. */
class LayoutDraft(initial: TimeLayout) {
    var layout by mutableStateOf(initial)
        private set
    var selected by mutableStateOf(initial.slots.minByOrNull { it.start }?.id.orEmpty())
    val undo = mutableStateListOf<LayoutSnapshot>()
    val redo = mutableStateListOf<LayoutSnapshot>()
    val slot get() = layout.slots.firstOrNull { it.id == selected }
    fun snapshot() = LayoutSnapshot(layout, selected)
    fun restore(value: LayoutSnapshot) { layout = value.layout; selected = value.selected }
    private fun remember(before: LayoutSnapshot) {
        if (before == snapshot()) return
        undo.add(before); if (undo.size > 60) undo.removeAt(0); redo.clear()
    }
    fun update(next: TimeLayout, select: String = selected) {
        val before = snapshot(); layout = next; selected = select; remember(before)
    }
    fun updateSlot(next: TimeSlot) {
        val nextLayout = layout.copy(slots = layout.slots.map { if (it.id == next.id) next else it })
        validateTimes(nextLayout); update(nextLayout)
    }
    fun history(back: Boolean) {
        val source = if (back) undo else redo; val target = if (back) redo else undo
        if (source.isNotEmpty()) { target.add(snapshot()); restore(source.removeAt(source.lastIndex)) }
    }
    fun insert(type: SlotType, duplicate: Boolean = false) {
        val previous = slot
        val duration = if (type == SlotType.DIVIDER) 0 else if (duplicate && previous != null) previous.end - previous.start
            else (if (type == SlotType.BREAK) layout.defaultBreakMinutes else layout.defaultMinutes) * 60
        var start = previous?.let { if (it.type == SlotType.DIVIDER) it.start else it.end } ?: 8 * 3600
        if (type != SlotType.DIVIDER) for (range in layout.slots.filter { it.type != SlotType.DIVIDER }.sortedBy { it.start }) {
            if (start + duration <= range.start) break
            if (start < range.end) start = range.end
        }
        require(start < 86400 && start + duration <= 86400) { "当天没有足够的空档，请调整时长或插入位置。" }
        val item = if (duplicate && previous != null) previous.copy(id = newId(), name = previous.name + " 副本", start = start, end = start + duration)
            else TimeSlot(name = when (type) { SlotType.LESSON -> "第 ${layout.slots.count { it.type == type } + 1} 节"; SlotType.BREAK -> "课间休息"; SlotType.DIVIDER -> "分割线" }, start = start, end = start + duration, type = type)
        val next = layout.copy(slots = (layout.slots + item).sortedBy { it.start })
        validateTimes(next); update(next, item.id)
    }
    fun delete(profile: Profile) {
        val item = slot ?: return
        require(!referencesSlot(profile, layout.id, item.id)) { "临时安排仍引用此时段，请先撤销对应安排。" }
        val remaining = layout.slots.filterNot { it.id == item.id }
        update(layout.copy(slots = remaining), remaining.minByOrNull { it.start }?.id.orEmpty())
    }
    fun shift(minutes: Int) {
        val next = layout.copy(slots = layout.slots.map { it.copy(start = it.start + minutes * 60, end = it.end + minutes * 60) })
        validateTimes(next); update(next)
    }
    /** One drag produces one undo entry; callers can restore before on cancellation. */
    fun resize(before: LayoutSnapshot, startEdge: Boolean, deltaSeconds: Float, stepMinutes: Int) {
        val original = before.layout.slots.first { it.id == before.selected }
        val ranges = before.layout.slots.filter { it.id != original.id && it.type != SlotType.DIVIDER }
        val lower = ranges.filter { it.end <= original.start }.maxOfOrNull { it.end } ?: 0
        val upper = ranges.filter { it.start >= original.end }.minOfOrNull { it.start } ?: 86400
        val step = stepMinutes * 60
        val value = (((if (startEdge) original.start else original.end) + deltaSeconds) / step).roundToInt() * step
        val item = if (startEdge) original.copy(start = value.coerceIn(lower, original.end - 1))
            else original.copy(end = value.coerceIn(original.start + 1, upper))
        layout = before.layout.copy(slots = before.layout.slots.map { if (it.id == item.id) item else it })
    }
    fun finishResize(before: LayoutSnapshot) = remember(before)
}

fun validateTimes(layout: TimeLayout) {
    require(layout.slots.all { it.start in 0..86399 && (it.type == SlotType.DIVIDER || it.end in (it.start + 1)..86400) }) { "起止时间必须位于当天，且结束晚于开始。" }
    val ranges = layout.slots.filter { it.type != SlotType.DIVIDER }.sortedBy { it.start }
    require(ranges.zipWithNext().none { (a, b) -> a.end > b.start }) { "时段不能重叠，请调整起止时间。" }
}
fun referencesSlot(profile: Profile, layoutId: String, slotId: String) = profile.temporary.any { t ->
    t.patches.any { patch -> patch.slotId == slotId && profile.plans.any { it.id == patch.planId && it.layoutId == layoutId } }
}

/** Validate shared references and clean removed class assignments in the same atomic write. */
fun profileWithLayout(profile: Profile, draft: TimeLayout): Profile {
    validateTimes(draft)
    val item = draft.copy(name = requireName(draft.name), slots = draft.slots.map { it.copy(name = requireName(it.name)) }.sortedBy { it.start })
    val lessons = item.slots.filter { it.type == SlotType.LESSON }.map { it.id }.toSet()
    val removed = profile.layouts.firstOrNull { it.id == item.id }?.slots.orEmpty().filter { it.id !in lessons }
    require(removed.none { referencesSlot(profile, item.id, it.id) }) { "临时安排仍引用已移除的课程，请先撤销对应安排。" }
    return profile.copy(layouts = if (profile.layouts.any { it.id == item.id }) profile.layouts.map { if (it.id == item.id) item else it } else profile.layouts + item,
        plans = profile.plans.map { if (it.layoutId == item.id) it.copy(subjects = it.subjects.filterKeys { id -> id in lessons }, disabledSlots = it.disabledSlots.intersect(lessons)) else it })
}
