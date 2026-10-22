import storage from '@blueos.storage.storage'
import { Durable } from '../core/durable.js'
import { emptyProfile, validate } from '../core/model.js'
function get(key) { return new Promise((resolve, reject) => storage.get({ key, default: '', success: resolve, fail: (e, c) => reject(Error('读取失败 ' + c)) })) }
function put(key, value) { return new Promise((resolve, reject) => storage.set({ key, value, success: resolve, fail: (e, c) => reject(Error('存储失败 ' + c)) })) }
export const live = new Durable('simpletimetable.v1', get, put)
export const journal = new Durable('simpletimetable.transfer.v1', get, put)
export let db = null
export let transfers = {}
export async function open() {
  const p = emptyProfile()
  const value = await live.load({ schema: 1, profiles: [p], activeProfile: p.id, settings: { theme: 'dark', largeText: false }, common: {}, pending: null, receipts: [], outbox: null })
  if (value.schema !== 1 || !value.profiles || !value.profiles.length) throw Error('存储版本无效')
  value.profiles.forEach(validate); db = value; transfers = await journal.load({}); return db
}
let changes = Promise.resolve(), transferChanges = Promise.resolve()
export function save(change) {
  const result = changes.then(async () => {
    const next = JSON.parse(JSON.stringify(db)); change(next); next.profiles.forEach(validate)
    await live.save(next); db = next; return db
  })
  changes = result.catch(() => {}); return result
}
export function saveTransfer(id, state) {
  const result = transferChanges.then(async () => {
    const next = { ...transfers, [id]: state }
    const keys = Object.keys(next)
    if (keys.length > 6) delete next[keys.find(k => k !== String(id) && next[k].complete) || keys.find(k => k !== String(id))]
    await journal.save(next); transfers = next
  })
  transferChanges = result.catch(() => {}); return result
}
export function active() { return db.profiles.find(p => p.id === db.activeProfile) || db.profiles[0] }
