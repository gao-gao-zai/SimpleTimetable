import exchange from '@blueos.storage.exchange'
import event from '@blueos.app.event.eventManager'
import { snapshot } from '../core/engine.js'
import { revision } from '../core/codec.js'
export const KEY = 'io.github.gaogaoza.simpletimetable.snapshot.v1'
export const EVENT = 'io.github.gaogaoza.simpletimetable.updated.v1'
export function publish(profile) {
  const value = snapshot(profile, Date.now(), revision(profile))
  return new Promise((resolve, reject) => exchange.set({ scope: 'vendor', key: KEY, value, success: () => { event.publish({ eventName: EVENT, options: { params: { version: value.version } } }); resolve(value) }, fail: (e, c) => reject(Error('共享更新失败 ' + c)) }))
}
