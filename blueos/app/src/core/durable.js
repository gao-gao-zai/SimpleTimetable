import { crc32Utf8 } from './codec.js'
function measure(trace, name, action) { return trace && trace.measure ? trace.measure(name, action) : action() }
function unpack(raw, trace) {
  const r = measure(trace, '存储封套 JSON 解析', () => typeof raw === 'string' ? JSON.parse(raw) : raw)
  if (!r || typeof r.json !== 'string') throw Error('存储校验失败')
  const metrics = {}
  const crc = measure(trace, '正文 CRC 校验', () => crc32Utf8(r.json, metrics))
  if (trace && trace.metric) trace.metric('正文 UTF-8 字节数', metrics.bytes, 'B')
  if (crc !== r.crc) throw Error('存储校验失败')
  return measure(trace, '正文 JSON 解析', () => JSON.parse(r.json))
}
function pack(json) { return JSON.stringify({ json, crc: crc32Utf8(json) }) }
export class Durable {
  constructor(prefix, get, put) { this.prefix = prefix; this.get = get; this.put = put; this.head = 'a'; this.tail = Promise.resolve() }
  async load(fallback, trace) {
    let from = trace && trace.now ? trace.now() : 0
    const head = await this.get(this.prefix + '.head')
    if (trace && trace.record) trace.record('读取存储指针', from)
    if (!head) { const a = await this.get(this.prefix + '.a'), b = await this.get(this.prefix + '.b'); if (a || b) throw Error('存储指针缺失，原数据已保留');if(trace)trace.mark('存储读取完成');return fallback }
    if (head !== 'a' && head !== 'b') throw Error('存储指针损坏，原数据已保留')
    this.head = head
    from = trace && trace.now ? trace.now() : 0
    const raw = await this.get(this.prefix + '.' + head)
    if (trace && trace.record) trace.record('读取存储正文', from)
    // Do not silently overwrite damaged live data with a new blank profile.
    if(trace)trace.mark('存储读取完成')
    return unpack(raw, trace)
  }
  save(value) {
    // Serialize at invocation to freeze the value; the queue never observes later edits.
    const json = JSON.stringify(value)
    const run = async () => {
      const slot = this.head === 'a' ? 'b' : 'a', raw = pack(json)
      await this.put(this.prefix + '.' + slot, raw)
      const verify = await this.get(this.prefix + '.' + slot)
      if (verify !== raw) throw Error('存储回读不一致')
      await this.put(this.prefix + '.head', slot); this.head = slot
    }
    const p = this.tail.then(run); this.tail = p.catch(() => {}); return p
  }
}
