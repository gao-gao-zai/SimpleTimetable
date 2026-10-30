import { createPublisher } from '../core/shared-snapshot.js'
export const KEY = 'io.github.gaogaoza.simpletimetable.snapshot.v1'
export const EVENT = 'io.github.gaogaoza.simpletimetable.updated.v1'
const publisher = createPublisher({
  write: value => new Promise((resolve, reject) => require('@service.exchange').set({ scope: 'vendor', key: KEY, value, success: resolve, fail: (e, c) => reject(Error('共享更新失败 ' + c)) })),
  emit: value => require('@system.event').publish({ eventName: EVENT, options: { params: { version: value.version } } })
})
export function publish(profile, settings, report) { return publisher(profile, settings, report) }
