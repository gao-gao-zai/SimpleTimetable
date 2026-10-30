export class StartupTrace {
  constructor(now = () => Date.now()) { this.now = now; this.started = now(); this.stages = [] }
  mark(name) { this.stages.push({ name, ms: Math.max(0, this.now() - this.started) }) }
  rows() { return this.stages.map(s => ({ label: s.name, sub: s.ms + ' ms' })) }
}
// Assign only changed fields. Timer updates deliberately omit rows/actions.
export function commitChanged(target, values) {
  let writes = 0
  Object.keys(values).forEach(key => { if (target[key] !== values[key]) { target[key] = values[key]; writes++ } })
  return writes
}
