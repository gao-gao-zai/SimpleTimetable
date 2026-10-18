package io.github.gaogaoza.simpletimetable

import io.github.gaogaoza.simpletimetable.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class EngineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val monday = LocalDate.of(2026, 10, 12)
    private val math = Subject("math", "数学")
    private val english = Subject("english", "英语")
    private val a = TimeSlot("a", "第一节", 8 * 3600, 8 * 3600 + 45 * 60)
    private val b = TimeSlot("b", "第二节", 9 * 3600, 9 * 3600 + 45 * 60)
    private val layout = TimeLayout("time", "作息", slots = listOf(a, TimeSlot("break", "课间休息", a.end, b.start, SlotType.BREAK), b))
    private val plan = ClassPlan("plan", "周一", layout.id, mapOf(a.id to math.id, b.id to english.id), anchor = monday.toString())
    private val profile = Profile("p", "测试", listOf(math, english), listOf(layout), listOf(plan))
    private fun at(time: String, date: LocalDate = monday) = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
    private fun state(time: String, p: Profile = profile) = Engine.state(p, at(time), zone)

    @Test fun exactLessonBoundariesAndProgress() {
        assertEquals(Phase.BEFORE, state("07:59:59").phase)
        assertEquals(1L, state("07:59:59").remaining)
        assertNull(state("07:59:59").progress)
        assertEquals(0.0, state("08:00:00").progress!!, 0.00001)
        assertEquals(0.5, state("08:22:30").progress!!, 0.00001)
        assertEquals(1L, state("08:44:59").remaining)
        assertEquals(Phase.BREAK, state("08:45:00").phase)
        assertEquals("课间休息", state("08:45:00").status)
        assertEquals(0.5, state("08:52:30").progress!!, 0.00001)
        assertEquals(Phase.CLASS, state("09:00:00").phase)
        assertNull(state("09:00:00").next)
        assertEquals(Phase.ENDED, state("09:45:00").phase)
        assertEquals(1.0, state("09:45:00").progress!!, 0.00001)
    }
    @Test fun unnamedGapIsNotNamedBreak() {
        val p = profile.copy(layouts = listOf(layout.copy(slots = listOf(a, b))))
        assertEquals("课程间隔", state("08:50:00", p).status)
        assertEquals(1.0 / 3, state("08:50:00", p).progress!!, 0.00001)
    }
    @Test fun noClassDoesNotProduceCountdown() {
        val s = Engine.state(profile, at("08:10:00", monday.plusDays(1)), zone)
        assertEquals(Phase.EMPTY, s.phase); assertEquals(0L, s.remaining); assertNull(s.targetSecond)
    }
    @Test fun rotationsWorkBeforeAnchorAndOffset() {
        val q = plan.copy(cycle = 3, week = 3)
        assertTrue(Engine.matches(q, monday.minusWeeks(1)))
        assertFalse(Engine.matches(q, monday))
        assertTrue(Engine.matches(q.copy(offsetWeeks = 2), monday))
        assertFalse(Engine.matches(q.copy(layerOnly = true), monday.minusWeeks(1)))
    }
    @Test fun sharedLayoutChangesReachEveryReference() {
        val moved = a.copy(start = a.start + 300)
        val p = profile.copy(layouts = listOf(layout.copy(slots = listOf(moved, b))))
        assertEquals(Phase.BEFORE, state("08:01:00", p).phase)
        assertEquals(moved, state("08:06:00", p).current!!.slot)
    }
    @Test fun temporarySavesApplyAndExpireAtExclusiveEnd() {
        val t = Temporary("once", "单节", TempType.LESSON, at("08:00:00"), at("08:30:00"), patches = listOf(LessonPatch(monday.toString(), plan.id, a.id, english.id)))
        val p = profile.copy(temporary = listOf(t))
        assertEquals(english, state("08:00:00", p).current!!.subject)
        assertTrue(state("08:00:00", p).current!!.temporary)
        assertEquals(math, state("08:30:00", p).current!!.subject)
    }
    @Test fun browsingFutureMorningChangeDoesNotResolveOnlyAtNoon() {
        val t=Temporary("future","早晨",TempType.LESSON,at("08:15:00"),at("08:30:00"),patches=listOf(LessonPatch(monday.toString(),plan.id,a.id,english.id)))
        val p=profile.copy(temporary=listOf(t))
        val r=Engine.resolve(p,monday,at("12:00:00",monday.minusDays(1)),zone)
        val pieces=r.lessons.filter { it.sourceSlotId==a.id }
        assertEquals(listOf(math.id,english.id,math.id),pieces.map { it.subject!!.id })
        assertEquals(listOf(28800,29700,30600),pieces.map { it.slot.start })
        assertEquals(listOf(29700,30600,31500),pieces.map { it.slot.end })
        assertEquals(r.slots.size,r.slots.distinctBy { it.id }.size)
        assertTrue(pieces[1].temporary)
    }
    @Test fun latestSingleChangeWinsWithoutMutatingRegularPlan() {
        fun patch(id: String, subject: String) = Temporary(id, id, TempType.LESSON, at("00:00:00"), at("23:59:59"), patches = listOf(LessonPatch(monday.toString(), plan.id, a.id, subject)))
        val p = profile.copy(temporary = listOf(patch("1", english.id), patch("2", math.id)))
        assertEquals(math, state("08:15:00", p).current!!.subject)
        assertEquals(math.id, p.plans.first().subjects[a.id])
    }
    @Test fun temporaryPlanOverridesManualThenReturns() {
        val other = plan.copy(id = "other", name = "临时整张", subjects = mapOf(a.id to english.id))
        val t = Temporary("temp", "临时", TempType.PLAN, at("08:00:00"), at("08:30:00"), other.id)
        val p = profile.copy(plans = listOf(plan, other), manualPlan = plan.id, temporary = listOf(t))
        assertEquals(other.id, state("08:10:00", p).current!!.planId)
        assertEquals(plan.id, state("08:30:00", p).current!!.planId)
    }
    @Test fun temporaryLayerPreservesOtherPeriods() {
        val layerTime = TimeLayout("overTime", "临时作息", slots = listOf(a.copy(id = "overA")))
        val over = plan.copy(id = "overlay", layoutId = layerTime.id, subjects = mapOf("overA" to english.id), layerOnly = true)
        val t = Temporary("layer", "临时层", TempType.LAYER, at("00:00:00"), at("23:59:59"), over.id)
        val p = profile.copy(layouts = listOf(layout, layerTime), plans = listOf(plan, over), temporary = listOf(t))
        val r = state("08:10:00", p).resolution
        assertEquals(listOf("overA", "b"), r.lessons.map { it.slot.id })
        assertTrue(r.lessons.first().temporary); assertFalse(r.lessons.last().temporary)
    }
    @Test fun globalGroupHasLowerPriorityThanSelected() {
        val global = plan.copy(id = "g", groupId = "global", subjects = mapOf(a.id to english.id))
        val p = profile.copy(plans = listOf(global, plan))
        assertEquals(plan.id, state("08:10:00", p).current!!.planId)
        assertFalse(state("08:10:00", p).resolution.conflicts)
        assertEquals(plan.id, state("08:10:00", p.copy(activeGroup="")).current!!.planId)
        assertTrue(state("08:10:00", p.copy(plans=listOf(plan,plan.copy(id="duplicate")))).resolution.conflicts)
    }
    @Test fun disabledPeriodsAreNotClasses() {
        val p = profile.copy(plans = listOf(plan.copy(disabledSlots = setOf(a.id))))
        assertEquals(Phase.BEFORE, state("08:10:00", p).phase)
        assertEquals(b.id, state("08:10:00", p).next!!.slot.id)
    }
    @Test fun clockParsingRejectsInvalidAndKeepsSeconds() {
        assertEquals(28801, parseClock("08:00:01")); assertEquals(86400, parseClock("24:00"))
        listOf("24:01", "08:60", "-1:00", "08", "x:y").forEach { assertTrue(runCatching { parseClock(it) }.isFailure) }
        assertEquals("01:01", durationText(61)); assertEquals("1:00:00", durationText(3600))
    }
}
