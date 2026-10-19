package io.github.gaogaoza.simpletimetable

import com.google.gson.Gson
import io.github.gaogaoza.simpletimetable.data.*
import io.github.gaogaoza.simpletimetable.ui.*
import org.junit.Assert.*
import org.junit.Test

class LayoutDraftTest {
    private fun fixture(): Pair<Profile, LayoutDraft> {
        val p = SampleData.profile(); return p to LayoutDraft(p.layouts.first())
    }
    @Test fun editsAreIsolatedThenCleanSharedAssignmentsInOneCommit() {
        val (p, d) = fixture(); val removed = d.slot!!.id
        d.delete(p)
        assertTrue(p.layouts.first().slots.any { it.id == removed })
        assertTrue(p.plans.all { removed in it.subjects })
        val committed = profileWithLayout(p, d.layout)
        assertTrue(committed.plans.all { removed !in it.subjects && removed !in it.disabledSlots })
        Repository.validate(AppData(profiles = listOf(committed)))
    }
    @Test fun insertionFindsNextFreeGapAndHistoryRestoresSelection() {
        val (_, d) = fixture(); val first = d.selected
        d.insert(SlotType.BREAK)
        assertEquals(8 * 3600 + 45 * 60, d.slot!!.start)
        assertEquals(10 * 60, d.slot!!.end - d.slot!!.start)
        val added = d.selected
        d.history(true); assertEquals(first, d.selected); assertFalse(d.layout.slots.any { it.id == added })
        d.history(false); assertEquals(added, d.selected)
        d.insert(SlotType.LESSON)
        assertEquals(11 * 3600 + 35 * 60, d.slot!!.start)
        validateTimes(d.layout)
    }
    @Test fun duplicateRetainsDurationAndDefaultSubject() {
        val (_, d) = fixture(); val old = d.slot!!
        d.updateSlot(old.copy(defaultSubject = "custom")); d.insert(SlotType.LESSON, true)
        assertNotEquals(old.id, d.slot!!.id); assertEquals(old.end - old.start, d.slot!!.end - d.slot!!.start)
        assertEquals("custom", d.slot!!.defaultSubject)
    }
    @Test fun draggingClampsNeighborsAndProducesSingleUndoEntry() {
        val (_, d) = fixture(); val before = d.snapshot()
        repeat(5) { d.resize(before, false, (it + 1) * 1200f, 5) }
        assertEquals(9 * 3600, d.slot!!.end); assertTrue(d.undo.isEmpty())
        d.finishResize(before); assertEquals(1, d.undo.size)
        d.history(true); assertEquals(before.layout, d.layout)
    }
    @Test fun dragCancellationDoesNotPolluteHistory() {
        val (_, d) = fixture(); val before = d.snapshot()
        d.resize(before, true, -621f, 5); assertEquals(7 * 3600 + 50 * 60, d.slot!!.start)
        d.restore(before); assertEquals(before.layout, d.layout); assertTrue(d.undo.isEmpty())
    }
    @Test fun invalidEndpointAndShiftLeavePreviousDraftIntact() {
        val (_, d) = fixture(); val before = d.layout
        assertTrue(runCatching { d.updateSlot(d.slot!!.copy(end = 10 * 3600)) }.isFailure)
        assertEquals(before, d.layout)
        assertTrue(runCatching { d.shift(-600) }.isFailure); assertEquals(before, d.layout)
        d.shift(5); assertEquals(before.slots.first().start + 300, d.slot!!.start)
    }
    @Test fun temporaryReferencesBlockDeletionAndTypeConversionAtCommit() {
        val (original, d) = fixture(); val slot = d.slot!!; val plan = original.plans.first()
        val p = original.copy(temporary = listOf(Temporary(type = TempType.LESSON, startMillis = 1, endMillis = 2,
            patches = listOf(LessonPatch("2026-10-12", plan.id, slot.id, original.subjects.first().id)))))
        assertTrue(runCatching { d.delete(p) }.isFailure)
        d.updateSlot(slot.copy(type = SlotType.BREAK))
        assertTrue(runCatching { profileWithLayout(p, d.layout) }.isFailure)
    }
    @Test fun midnightEndAndDividerAreValidButNextDayIsRejected() {
        val layout = TimeLayout(name = "夜间", slots = listOf(TimeSlot(name = "晚课", start = 86300, end = 86400), TimeSlot(name = "分割", start = 86399, end = 86399, type = SlotType.DIVIDER)))
        validateTimes(layout)
        assertTrue(runCatching { validateTimes(layout.copy(slots = listOf(layout.slots.first().copy(end = 86401)))) }.isFailure)
    }
    @Test fun olderStoredLayoutsKeepDefaultBreakDuration() {
        val old = Gson().fromJson("""{"id":"old","name":"旧作息","defaultMinutes":45,"defaultSubject":"","slots":[]}""", TimeLayout::class.java)
        assertEquals(10, old.defaultBreakMinutes)
    }
}
