// Self-contained consumer logic: only the precomputed daily summary, never the profile DB.
export function validSnapshot(v) {
  return !!(v && v.schema === 1 && Number.isFinite(v.updatedAt) && Number.isFinite(v.validUntil) && v.validUntil > v.updatedAt && v.validUntil <= v.updatedAt + 3600000 && Number.isInteger(v.timeOffsetSeconds === undefined ? 0 : v.timeOffsetSeconds) && Math.abs(v.timeOffsetSeconds || 0) <= 86400 && Array.isArray(v.timeline) && v.timeline.every(e => Number.isInteger(e.start) && Number.isInteger(e.end) && e.end > e.start && e.start >= 0 && e.end <= 86400))
}
export function snapshotView(v, realNow) {
  if (!validSnapshot(v)) return { title:'请打开简单课表',next:'',expired:true,version:'',updatedAt:0 }
  const expired = realNow >= v.validUntil || realNow < v.updatedAt
  const d = new Date(realNow + (v.timeOffsetSeconds || 0) * 1000), second = d.getHours()*3600+d.getMinutes()*60+d.getSeconds()
  const current = v.timeline.find(e => second >= e.start && second < e.end), next = v.timeline.find(e => second < e.start)
  return { title:expired?'课表已过期，请打开 App':current?current.title:next?'等待下一节':'今日课程已结束',next:expired?'':next?next.title:'',expired,version:v.version,updatedAt:v.updatedAt }
}
