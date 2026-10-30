import { offsetSeconds } from '../core/clock.js'
import { snapshot } from '../core/engine.js'
import { revision } from '../core/codec.js'
export const KEY = 'io.github.gaogaoza.simpletimetable.snapshot.v1'
export const EVENT = 'io.github.gaogaoza.simpletimetable.updated.v1'
export function publish(profile, settings) {
  const exchange = require('@service.exchange')
  const event = require('@system.event')
  const value = snapshot(profile, Date.now(), revision(profile), offsetSeconds(settings))
  return new Promise((resolve, reject) => exchange.set({ scope: 'vendor', key: KEY, value, success: () => { event.publish({ eventName: EVENT, options: { params: { version: value.version } } }); resolve(value) }, fail: (e, c) => reject(Error('共享更新失败 ' + c)) }))
}
