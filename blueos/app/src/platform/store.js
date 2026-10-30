import { Durable } from '../core/durable.js'
import { emptyProfile, validate } from '../core/model.js'
let storage = null
function backend(trace) {
  if (!storage) {
    const from=trace&&trace.now?trace.now():0
    storage=require('@system.storage')
    if(trace&&trace.record)trace.record('存储接口加载',from)
  }
  return storage
}
function get(key) { return new Promise((resolve, reject) => backend().get({ key, default: '', success: r => resolve(r && typeof r === 'object' && 'value' in r ? r.value : r), fail: (e, c) => reject(Error('读取失败 ' + c)) })) }
function put(key, value) { return new Promise((resolve, reject) => backend().set({ key, value, success: r => resolve(r && typeof r === 'object' && 'value' in r ? r.value : r), fail: (e, c) => reject(Error('存储失败 ' + c)) })) }
export const live = new Durable('simpletimetable.v1', get, put)
export let db = null
let opening = null
export function open(trace) {
  if (db) {if(trace){if(trace.metric)trace.metric('存档来源','进程缓存（已校验）');trace.mark('复用已校验存档')}return Promise.resolve(db)}
  if (!opening) opening = openCold(trace).catch(e => { opening = null; throw e })
  return opening
}
async function openCold(trace) {
  backend(trace)
  if (trace && trace.metric) trace.metric('存档来源','冷读取（完整校验）')
  const p = emptyProfile()
  const value = await live.load({ schema: 1, profiles: [p], activeProfile: p.id, settings: { theme: 'dark', largeText: false }, common: {}, pending: null, receipts: [], outbox: null }, trace)
  if (value.schema !== 1 || !value.profiles || !value.profiles.length) throw Error('存储版本无效')
  if (trace && trace.measure) trace.measure('档案引用检查', () => value.profiles.forEach(validate))
  else value.profiles.forEach(validate)
  if (trace) trace.mark('存档校验完成')
  db = value; return db
}
let changes = Promise.resolve()
export function save(change) {
  const result = changes.then(async () => {
    const next = JSON.parse(JSON.stringify(db)); change(next); next.profiles.forEach(validate)
    await live.save(next); db = next; return db
  })
  changes = result.catch(() => {}); return result
}
export function active() { return db.profiles.find(p => p.id === db.activeProfile) || db.profiles[0] }
