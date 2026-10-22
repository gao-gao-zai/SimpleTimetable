export function clone(v) { return JSON.parse(JSON.stringify(v)) }
export function dateText(d) { return [d.getFullYear(), String(d.getMonth() + 1).padStart(2, '0'), String(d.getDate()).padStart(2, '0')].join('-') }
export function dateAt(s) { const p = s.split('-').map(Number); const d = new Date(p[0], p[1] - 1, p[2]); if (dateText(d) !== s) throw Error('Invalid date'); return d }
export function moveDate(s, n) { const d = dateAt(s); d.setDate(d.getDate() + n); return dateText(d) }
function monday(s) { const d = dateAt(s); d.setDate(d.getDate() - (d.getDay() + 6) % 7); return Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) }
export function matches(q, date) {
  const day = dateAt(date).getDay()
  if (!q.enabled || q.layerOnly || !q.days.includes(day)) return false
  const w = Math.floor((monday(date) - monday(q.anchor)) / 604800000) + (q.offsetWeeks || 0)
  return ((w % q.cycle + q.cycle) % q.cycle) + 1 === q.week
}
function at(profile, date, millis) {
  const active = profile.temporary.filter(t => millis >= t.startMillis && millis < t.endMillis)
  const group = active.filter(t => t.type === 'GROUP').pop(), selected = group ? group.targetId : profile.activeGroup
  const globals = profile.groups.filter(g => g.global).map(g => g.id)
  const allowed = [selected].concat(group && group.inheritGroup ? [profile.activeGroup] : [])
  const priority = q => group && q.groupId === group.targetId ? 3 : q.groupId === profile.activeGroup || !profile.activeGroup && !globals.includes(q.groupId) ? 2 : globals.includes(q.groupId) ? 1 : 0
  const candidates = profile.plans.filter(q => matches(q, date) && (!selected || allowed.includes(q.groupId) || globals.includes(q.groupId))).sort((a, b) => priority(b) - priority(a))
  const tp = active.filter(t => t.type === 'PLAN').pop()
  let plan = profile.plans.find(q => tp && q.id === tp.targetId) || profile.plans.find(q => q.id === profile.manualPlan) || candidates[0]
  let slots = clone((profile.layouts.find(l => plan && l.id === plan.layoutId) || { slots: [] }).slots)
  const lessons = (q, points, temporary) => {
    if (!q) return []
    const l = profile.layouts.find(l => l.id === q.layoutId)
    return points.filter(s => s.type === 'LESSON' && !(q.disabledSlots || []).includes(s.id)).map(s => ({ slot: s, sourceSlotId: s.id, planId: q.id, temporary: !!temporary, subject: profile.subjects.find(x => x.id === (q.subjects[s.id] || s.defaultSubject || l.defaultSubject)) || null }))
  }
  let entries = lessons(plan, slots, !!tp)
  active.filter(t => t.type === 'LAYER').forEach(t => {
    const q = profile.plans.find(q => q.id === t.targetId), l = profile.layouts.find(l => q && l.id === q.layoutId)
    if (!l) return
    const over = lessons(q, l.slots, true).filter(e => e.subject)
    const covered = s => over.some(e => s.start < e.slot.end && e.slot.start < s.end)
    entries = entries.filter(e => !covered(e.slot)).concat(over)
    slots = slots.filter(s => !covered(s)).concat(l.slots.filter(s => s.type !== 'LESSON' && !covered(s)), over.map(e => e.slot))
  })
  active.filter(t => ['LESSON', 'SWAP'].includes(t.type)).forEach(t => (t.patches || []).filter(p => p.date === date).forEach(p => {
    entries = entries.map(e => e.slot.id === p.slotId && e.planId === p.planId ? { ...e, subject: profile.subjects.find(s => s.id === p.subjectId) || null, temporary: true } : e)
  }))
  return { date, plan, slots: slots.sort((a, b) => a.start - b.start), lessons: entries.sort((a, b) => a.slot.start - b.slot.start), conflicts: !tp && !profile.manualPlan && candidates.length > 1 && priority(candidates[0]) === priority(candidates[1]) }
}
export function resolve(profile, date, millis = Date.now()) {
  const start = dateAt(date).getTime(), end = dateAt(moveDate(date, 1)).getTime()
  const relevant = profile.temporary.filter(t => t.startMillis < end && t.endMillis > start)
  const effective = dateText(new Date(millis)) === date ? millis : start + (end - start) / 2
  const base = at(profile, date, effective)
  if (!relevant.length) return base
  const epoch = s => { if (s === 86400) return end; const d = dateAt(date); d.setSeconds(s); return d.getTime() }
  const bounds = Array.from(new Set([start, end].concat(profile.layouts.flatMap(l => l.slots.flatMap(s => [epoch(s.start), epoch(s.end)])), relevant.flatMap(t => [Math.max(start, t.startMillis), Math.min(end, t.endMillis)])))).sort((a, b) => a - b)
  const pieces = [], other = []; let conflicts = false
  for (let i = 0; i < bounds.length - 1; i++) {
    const from = bounds[i], until = bounds[i + 1], r = at(profile, date, from + (until - from) / 2)
    conflicts = conflicts || r.conflicts
    const sec = t => { const d = new Date(t); return d.getHours() * 3600 + d.getMinutes() * 60 + d.getSeconds() }
    const lo = sec(from), hi = until === end ? 86400 : sec(until)
    r.lessons.filter(e => e.slot.start < hi && e.slot.end > lo).forEach(e => {
      const c = { ...e, slot: { ...e.slot, start: Math.max(lo, e.slot.start), end: Math.min(hi, e.slot.end) } }
      const last = pieces[pieces.length - 1]
      if (last && last.planId === c.planId && last.sourceSlotId === c.sourceSlotId && (last.subject || {}).id === (c.subject || {}).id && last.temporary === c.temporary && last.slot.end === c.slot.start) last.slot.end = c.slot.end
      else pieces.push(c)
    })
    r.slots.filter(s => s.type !== 'LESSON' && (s.type === 'DIVIDER' ? s.start >= lo && s.start < hi : s.start < hi && s.end > lo)).forEach(s => other.push({ ...s, start: Math.max(lo, s.start), end: Math.min(hi, s.end) }))
  }
  return { ...base, lessons: pieces, slots: other.concat(pieces.map(e => e.slot)).sort((a, b) => a.start - b.start), conflicts }
}
export function state(profile, millis = Date.now()) {
  const d = new Date(millis), date = dateText(d), r = at(profile, date, millis)
  const second = d.getHours() * 3600 + d.getMinutes() * 60 + d.getSeconds()
  for (let i = 0; i < r.lessons.length; i++) {
    const e = r.lessons[i], s = e.slot
    if (second >= s.start && second < s.end) return { ...r, phase: 'CLASS', title: e.subject ? e.subject.name : '未安排科目', current: e, next: r.lessons[i + 1], remaining: s.end - second, progress: (second - s.start) / (s.end - s.start) }
    if (second < s.start) {
      const explicit = r.slots.find(x => x.type === 'BREAK' && second >= x.start && second < x.end)
      const start = explicit ? explicit.start : i ? r.lessons[i - 1].slot.end : null
      return { ...r, phase: i ? 'BREAK' : 'BEFORE', title: i ? explicit && explicit.name || '课间休息' : '今日尚未开课', next: e, remaining: s.start - second, progress: start == null ? 0 : Math.max(0, Math.min(1, (second - start) / (s.start - start))) }
    }
  }
  return { ...r, phase: r.lessons.length ? 'ENDED' : 'EMPTY', title: r.lessons.length ? '今日课程已结束' : '今天没有课程', remaining: 0, progress: r.lessons.length ? 1 : 0 }
}
export function clock(s) { return [Math.floor(s / 3600), Math.floor(s / 60) % 60, s % 60].map(n => String(n).padStart(2, '0')).join(':') }
export function snapshot(profile, now, version) {
  const s = state(profile, now), date = dateText(new Date(now)), timeline = resolve(profile, date, now).lessons.map(e => ({ start: e.slot.start, end: e.slot.end, title: e.subject ? e.subject.name : '停课/未安排', teacher: e.subject ? e.subject.teacher : '', outdoor: !!(e.subject && e.subject.outdoor) }))
  const tomorrow = dateAt(moveDate(date, 1)).getTime()
  const changes = profile.temporary.flatMap(t => [t.startMillis, t.endMillis]).filter(t => t > now)
  return { schema: 1, version, updatedAt: now, validUntil: Math.min(tomorrow, now + 3600000, ...changes), date, title: s.title, next: s.next && s.next.subject ? s.next.subject.name : '', timeline }
}
