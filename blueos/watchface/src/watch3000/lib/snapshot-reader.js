import storage from '@blueos.storage.storage'
import exchange from '@blueos.storage.exchange'
import event from '@blueos.app.event.eventManager'
const KEY = 'io.github.gaogaoza.simpletimetable.snapshot.v1'
const EVENT = 'io.github.gaogaoza.simpletimetable.updated.v1'
const CACHE = 'simpletimetable.snapshot.cache.v1'
export class SnapshotReader {
  constructor(update) { this.update = update; this.cached = null; this.eventId = null; this.timer = null }
  valid(v) { return v && v.schema === 1 && Number.isFinite(v.updatedAt) && Number.isFinite(v.validUntil) && v.validUntil > v.updatedAt && Array.isArray(v.timeline) && v.timeline.every(e => Number.isInteger(e.start) && Number.isInteger(e.end) && e.end > e.start && e.start >= 0 && e.end <= 86400) }
  refresh() {
    // exchange is volatile; successful reads are copied into this app's local cache.
    exchange.get({ scope: 'vendor', key: KEY, success: v => {
      if (this.valid(v) && (!this.cached || v.updatedAt >= this.cached.updatedAt)) { this.cached = v; storage.set({ key: CACHE, value: v }); this.show() }
    }, fail: () => this.show() })
    this.show()
  }
  show() {
    const now = Date.now(), d = new Date(now), second = d.getHours() * 3600 + d.getMinutes() * 60 + d.getSeconds()
    const v = this.cached
    if (!v) { this.update({ title: '请打开简单课表', next: '', expired: true, version: '', updatedAt: 0 }); return }
    const expired = now >= v.validUntil || now < v.updatedAt
    const current = v.timeline.find(e => second >= e.start && second < e.end), next = v.timeline.find(e => second < e.start)
    this.update({ title: expired ? '课表已过期，请打开 App' : current ? current.title : next ? '等待下一节' : '今日课程已结束', next: expired ? '' : next ? next.title : '', expired, version: v.version, updatedAt: v.updatedAt })
  }
  start() {
    storage.get({ key: CACHE, success: v => { if (this.valid(v) && !this.cached) this.cached = v; this.show() } })
    this.eventId = event.subscribe({ eventName: EVENT, callback: r => { if (r.package === 'io.github.gaogaoza.simpletimetable.blueos') this.refresh() } })
    this.refresh(); this.timer = setInterval(() => this.refresh(), 60000)
  }
  stop() { if (this.timer) clearInterval(this.timer); this.timer = null; if (this.eventId !== null) event.unsubscribe({ id: this.eventId }); this.eventId = null }
}
