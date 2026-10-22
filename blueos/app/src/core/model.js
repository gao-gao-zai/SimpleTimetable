import { clone, dateAt } from './engine.js'
let counter = 0
export function id() { return 'watch-' + Date.now().toString(36) + '-' + (++counter).toString(36) }
export function emptyProfile(name = '我的课表') { return { id: id(), name, subjects: [], layouts: [], plans: [], groups: [{ id: 'default', name: '常规课表', global: false }, { id: 'global', name: '全局课表', global: true }], activeGroup: 'default', manualPlan: '', temporary: [] } }
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
export function edit(p, action) { const n = clone(p); action(n); return validate(n) }
export function remove(p, collection, target) {
  return edit(p, n => { n[collection] = n[collection].filter(x => x.id !== target) })
}
export function copyItem(p, collection, target) {
  return edit(p, n => { const item = clone(n[collection].find(x => x.id === target)); check(item, '条目不存在'); item.id = id(); item.name += ' 副本'; n[collection].push(item) })
}
export function shift(p, layoutId, seconds) {
  return edit(p, n => { const l = n.layouts.find(l => l.id === layoutId); check(l, '作息不存在'); l.slots.forEach(s => { s.start += seconds; s.end += seconds }) })
}
export function lessonChange(p, date, planId, slotId, subjectId, permanent = false) {
  return edit(p, n => {
    if (permanent) { const q = n.plans.find(q => q.id === planId); q.subjects[slotId] = subjectId; return }
    n.temporary.push({ id: id(), name: subjectId ? '临时换课' : '指定日期停课', type: 'LESSON', startMillis: dateAt(date).getTime(), endMillis: dateAt((() => { const d = dateAt(date); d.setDate(d.getDate() + 1); return [d.getFullYear(), String(d.getMonth() + 1).padStart(2, '0'), String(d.getDate()).padStart(2, '0')].join('-') })()).getTime(), targetId: '', inheritGroup: false, patches: [{ date, planId, slotId, subjectId }] })
  })
}
export function swap(p, a, b, permanent = false) {
  check(!(a.date === b.date && a.planId === b.planId && a.slotId === b.slotId), '不能交换同一节课')
  return edit(p, n => {
    if (permanent) {
      const qa = n.plans.find(q => q.id === a.planId), qb = n.plans.find(q => q.id === b.planId)
      qa.subjects[a.slotId] = b.subjectId; qb.subjects[b.slotId] = a.subjectId
    } else {
      const dates = [dateAt(a.date).getTime(), dateAt(b.date).getTime()]
      const end = dateAt(a.date > b.date ? a.date : b.date); end.setDate(end.getDate() + 1)
      n.temporary.push({ id: id(), name: '跨日交换', type: 'SWAP', startMillis: Math.min(...dates), endMillis: end.getTime(), targetId: '', inheritGroup: false, patches: [{ date: a.date, planId: a.planId, slotId: a.slotId, subjectId: b.subjectId }, { date: b.date, planId: b.planId, slotId: b.slotId, subjectId: a.subjectId }] })
    }
  })
}
export function sample() {
  const p = emptyProfile('合成示例'), sub = [{ id: 's-a', name: '示例数学', shortName: '数', teacher: '合成教师甲', color: '#355E45', outdoor: false, favorite: false }, { id: 's-b', name: '示例体育', shortName: '体', teacher: '合成教师乙', color: '#657E9C', outdoor: true, favorite: false }]
  p.subjects = sub
  p.layouts = [{ id: 'layout', name: '示例作息', defaultMinutes: 45, defaultBreakMinutes: 10, defaultSubject: '', slots: [{ id: 'slot-a', name: '第 1 节', start: 28800, end: 31500, type: 'LESSON', defaultSubject: '' }, { id: 'slot-b', name: '第 2 节', start: 32100, end: 34800, type: 'LESSON', defaultSubject: '' }] }]
  p.plans = [{ id: 'plan', name: '示例课表', layoutId: 'layout', subjects: { 'slot-a': 's-a', 'slot-b': 's-b' }, days: [1, 2, 3, 4, 5], cycle: 1, week: 1, anchor: '2026-10-19', offsetWeeks: 0, enabled: true, groupId: 'default', layerOnly: false, disabledSlots: [] }]
  return validate(p)
}
