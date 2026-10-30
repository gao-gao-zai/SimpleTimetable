// Device-only correction: positive values advance course time, never the OS clock.
export function offsetSeconds(settings) {
  const value = settings && settings.timeOffsetSeconds
  return Number.isInteger(value) && Math.abs(value) <= 86400 ? value : 0
}
export function effectiveNow(settings, realNow = Date.now()) { return realNow + offsetSeconds(settings) * 1000 }
export function offsetLabel(value) { return (value > 0 ? '+' : '') + value + ' 秒' }
