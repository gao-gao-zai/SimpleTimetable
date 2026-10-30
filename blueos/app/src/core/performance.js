export class StartupTrace {
  constructor(now = () => Date.now()) { this.now = now; this.started = now(); this.previous = this.started; this.stages = []; this.spans = []; this.metrics = new Map() }
  mark(name) {
    const at = this.now()
    this.stages.push({ name, ms: Math.max(0, at - this.started), duration: Math.max(0, at - this.previous) }); this.previous = at
  }
  measure(name, action) {
    const from = this.now()
    try { return action() } finally { this.spans.push({ name, ms: Math.max(0, this.now() - from) }) }
  }
  record(name, from) { this.spans.push({ name, ms: Math.max(0, this.now() - from) }) }
  metric(name, value, unit = '') { this.metrics.set(name, String(value) + (unit ? ' ' + unit : '')) }
  rows() {
    return this.stages.map(s => ({ label: s.name, sub: s.ms + ' ms 累计 · +' + s.duration + ' ms 本阶段' }))
      .concat(this.spans.map(s => ({ label: s.name + '（独立计时）', sub: s.ms + ' ms' })))
      .concat(Array.from(this.metrics, ([label, sub]) => ({ label, sub })))
  }
}
// Assign only changed fields. Timer updates deliberately omit rows/actions.
export function commitChanged(target, values) {
  let writes = 0
  Object.keys(values).forEach(key => { if (target[key] !== values[key]) { target[key] = values[key]; writes++ } })
  return writes
}
