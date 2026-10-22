import storage from '@blueos.storage.storage'
import { crc32, utf8 } from '../core/codec.js'
import { emptyProfile, validate } from '../core/model.js'
function get(key) { return new Promise((resolve, reject) => storage.get({ key, default: '', success: resolve, fail: (e, c) => reject(Error('读取失败 ' + c)) })) }
function put(key, value) { return new Promise((resolve, reject) => storage.set({ key, value, success: resolve, fail: (e, c) => reject(Error('存储失败 ' + c)) })) }
function unpack(raw) { const r = typeof raw === 'string' ? JSON.parse(raw) : raw; if (!r || crc32(utf8(r.json)) !== r.crc) throw Error('存储校验失败'); return JSON.parse(r.json) }
function pack(value) { const json = JSON.stringify(value); return JSON.stringify({ json, crc: crc32(utf8(json)) }) }
export class Durable {
  constructor(prefix) { this.prefix = prefix; this.head = 'a'; this.tail = Promise.resolve() }
  async load(fallback) {
    const head = await get(this.prefix + '.head')
    if (!head) { const a = await get(this.prefix + '.a'), b = await get(this.prefix + '.b'); if (a || b) throw Error('存储指针缺失，原数据已保留'); return fallback }
    if (head !== 'a' && head !== 'b') throw Error('存储指针损坏，原数据已保留')
    this.head = head
    const raw = await get(this.prefix + '.' + head)
    // Do not silently overwrite damaged live data with a new blank profile.
    return unpack(raw)
  }
  save(value) {
    const frozen = JSON.parse(JSON.stringify(value))
    const run = async () => {
      const slot = this.head === 'a' ? 'b' : 'a', raw = pack(frozen)
      await put(this.prefix + '.' + slot, raw)
      const verify = await get(this.prefix + '.' + slot); unpack(verify)
      if (JSON.stringify(unpack(verify)) !== JSON.stringify(frozen)) throw Error('存储回读不一致')
      await put(this.prefix + '.head', slot); this.head = slot
    }
    const p = this.tail.then(run); this.tail = p.catch(() => {}); return p
  }
}
export const live = new Durable('simpletimetable.v1')
export const journal = new Durable('simpletimetable.transfer.v1')
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
