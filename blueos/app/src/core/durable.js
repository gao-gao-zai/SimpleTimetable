import { crc32, utf8 } from './codec.js'
function unpack(raw) { const r = typeof raw === 'string' ? JSON.parse(raw) : raw; if (!r || crc32(utf8(r.json)) !== r.crc) throw Error('存储校验失败'); return JSON.parse(r.json) }
function pack(value) { const json = JSON.stringify(value); return JSON.stringify({ json, crc: crc32(utf8(json)) }) }
export class Durable {
  constructor(prefix, get, put) { this.prefix = prefix; this.get = get; this.put = put; this.head = 'a'; this.tail = Promise.resolve() }
  async load(fallback) {
    const head = await this.get(this.prefix + '.head')
    if (!head) { const a = await this.get(this.prefix + '.a'), b = await this.get(this.prefix + '.b'); if (a || b) throw Error('存储指针缺失，原数据已保留'); return fallback }
    if (head !== 'a' && head !== 'b') throw Error('存储指针损坏，原数据已保留')
    this.head = head
    const raw = await this.get(this.prefix + '.' + head)
    // Do not silently overwrite damaged live data with a new blank profile.
    return unpack(raw)
  }
  save(value) {
    const frozen = JSON.parse(JSON.stringify(value))
    const run = async () => {
      const slot = this.head === 'a' ? 'b' : 'a', raw = pack(frozen)
      await this.put(this.prefix + '.' + slot, raw)
      const verify = await this.get(this.prefix + '.' + slot); unpack(verify)
      if (JSON.stringify(unpack(verify)) !== JSON.stringify(frozen)) throw Error('存储回读不一致')
      await this.put(this.prefix + '.head', slot); this.head = slot
    }
    const p = this.tail.then(run); this.tail = p.catch(() => {}); return p
  }
}
