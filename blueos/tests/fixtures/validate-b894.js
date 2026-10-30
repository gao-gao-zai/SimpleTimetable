// Validation reference from b894c0d, retained for behavior differential tests.
import {dateAt} from '../../app/src/core/engine.js'
function check(ok, message) { if (!ok) throw Error(message) }
export function validate(p) {
  check(p && typeof p.id === 'string' && p.id && typeof p.name === 'string' && p.name.trim() && p.name.length <= 200, '档案名称或标识无效')
  const list = (a, max) => { check(Array.isArray(a) && a.length <= max, '档案列表过大或缺失'); check(a.every(x => x && typeof x.id === 'string' && x.id && typeof x.name === 'string' && x.name.trim()), '条目缺少名称/标识'); check(new Set(a.map(x => x.id)).size === a.length, '条目标识重复') }
  list(p.subjects, 500); list(p.layouts, 100); list(p.plans, 500); list(p.groups, 100)
  check(Array.isArray(p.temporary) && p.temporary.length <= 1000 && new Set(p.temporary.map(t => t.id)).size === p.temporary.length, '临时安排无效')
  const has = (a, v) => !v || a.some(x => x.id === v)
  check(has(p.groups, p.activeGroup) && has(p.plans, p.manualPlan), '启用引用缺失')
  p.subjects.forEach(s => check(/^#[0-9a-f]{6}$/i.test(s.color), '科目颜色无效'))
  p.layouts.forEach(l => {
    check(Number.isInteger(l.defaultMinutes) && l.defaultMinutes >= 1 && l.defaultMinutes <= 720 && Number.isInteger(l.defaultBreakMinutes) && l.defaultBreakMinutes >= 1 && l.defaultBreakMinutes <= 720 && has(p.subjects, l.defaultSubject), '作息默认值无效')
    list(l.slots, 300)
    l.slots.forEach(s => check(['LESSON', 'BREAK', 'DIVIDER'].includes(s.type) && Number.isInteger(s.start) && s.start >= 0 && s.start < 86400 && (s.type === 'DIVIDER' || Number.isInteger(s.end) && s.end > s.start && s.end <= 86400) && has(p.subjects, s.defaultSubject), '时段无效'))
    const ranges = l.slots.filter(s => s.type !== 'DIVIDER').slice().sort((a, b) => a.start - b.start)
    check(ranges.every((s, i) => !i || ranges[i - 1].end <= s.start), '作息时段重叠')
  })
  p.plans.forEach(q => {
    const l = p.layouts.find(l => l.id === q.layoutId)
    check(l && has(p.groups, q.groupId) && q.groupId, '课表引用缺失')
    check(Number.isInteger(q.cycle) && q.cycle >= 1 && q.cycle <= 52 && Number.isInteger(q.week) && q.week >= 1 && q.week <= q.cycle && Array.isArray(q.days) && q.days.length && q.days.every(d => Number.isInteger(d) && d >= 0 && d <= 6), '轮换规则无效')
    check(dateAt(q.anchor).getDay() === 1 && Number.isInteger(q.offsetWeeks), '周次基准无效')
    const lessons = l.slots.filter(s => s.type === 'LESSON').map(s => s.id)
    check(q.subjects && Object.keys(q.subjects).every(k => lessons.includes(k) && has(p.subjects, q.subjects[k])) && Array.isArray(q.disabledSlots) && q.disabledSlots.every(k => lessons.includes(k)), '课程引用缺失')
  })
  p.temporary.forEach(t => {
    check(t.id && Number.isFinite(t.startMillis) && Number.isFinite(t.endMillis) && t.endMillis > t.startMillis && ['PLAN', 'LAYER', 'GROUP', 'LESSON', 'SWAP'].includes(t.type), '临时安排时间/类型无效')
    if (t.type === 'PLAN' || t.type === 'LAYER') check(t.targetId && has(p.plans, t.targetId), '临时课表引用缺失')
    if (t.type === 'GROUP') check(t.targetId && has(p.groups, t.targetId), '临时课表群引用缺失')
    check(Array.isArray(t.patches), '临时课程无效')
    t.patches.forEach(x => { dateAt(x.date); const q = p.plans.find(q => q.id === x.planId); const l = p.layouts.find(l => q && l.id === q.layoutId); check(l && l.slots.some(s => s.id === x.slotId && s.type === 'LESSON') && has(p.subjects, x.subjectId), '临时课程引用缺失') })
  })
  return p
}
