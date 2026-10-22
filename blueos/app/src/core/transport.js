import { crc16, crc32, utf8, text } from './codec.js'
export const SERVICE = 'd7300001-9e2c-4c53-a1aa-2cc0bf3d7710'
export const RX = 'd7300002-9e2c-4c53-a1aa-2cc0bf3d7710'
export const TX = 'd7300003-9e2c-4c53-a1aa-2cc0bf3d7710'
export const OP = { START: 1, DATA: 2, END: 3, ACK: 4, ERROR: 5 }
export const MAX = 262144
export function frame(op, id, seq, data = []) {
  if (data.length > 10) throw Error('Frame too large')
  const b = new Uint8Array(10 + data.length)
  b.set([83, 1, op, id & 255, id >> 8, seq & 255, seq >> 8, data.length]); b.set(data, 10)
  const c = crc16(new Uint8Array([...b.slice(0, 8), ...data])); b[8] = c & 255; b[9] = c >> 8
  return b
}
export function decode(b) {
  b = new Uint8Array(b)
  if (b.length < 10 || b.length > 20 || b[0] !== 83 || b[1] !== 1 || b[7] !== b.length - 10 || b[2] < 1 || b[2] > 5) throw Error('Invalid frame')
  if (crc16(new Uint8Array([...b.slice(0, 8), ...b.slice(10)])) !== (b[8] | b[9] << 8)) throw Error('Frame checksum')
  return { op: b[2], id: b[3] | b[4] << 8, seq: b[5] | b[6] << 8, data: b.slice(10) }
}
function u32(b, i) { return (b[i] | b[i + 1] << 8 | b[i + 2] << 16 | b[i + 3] << 24) >>> 0 }
function words(...values) { const b = new Uint8Array(values.length * 4); values.forEach((v, i) => { for (let j = 0; j < 4; j++) b[i * 4 + j] = v >>> (j * 8) & 255 }); return b }
// Durable hooks must resolve only after storage success. ACK always follows journal write.
export class Receiver {
  constructor(state, persist, deliver) { this.state = state || null; this.persist = persist; this.deliver = deliver }
  async receive(input) {
    const f = decode(input)
    if (f.op === OP.START) {
      if (f.data.length !== 8 || f.seq !== 0) throw Error('Invalid start')
      const size = u32(f.data, 0), sum = u32(f.data, 4)
      if (!size || size > MAX) throw Error('Transfer limit')
      if (!this.state || this.state.id !== f.id || this.state.sum !== sum || this.state.size !== size) {
        const s = { id: f.id, size, sum, next: 1, bytes: [], complete: false }
        await this.persist(s); this.state = s
      }
    } else {
      const s = this.state
      if (!s || s.id !== f.id) throw Error('Unknown transfer')
      if (f.op === OP.DATA && !s.complete && f.seq === s.next) {
        if (!f.data.length || s.bytes.length + f.data.length > s.size || f.data.length !== Math.min(10, s.size - s.bytes.length)) throw Error('Invalid chunk')
        const n = { ...s, bytes: s.bytes.concat(Array.from(f.data)), next: s.next + 1 }
        await this.persist(n); this.state = n
      } else if (f.op === OP.END && !s.complete && f.seq === s.next) {
        if (f.data.length || s.bytes.length !== s.size || crc32(s.bytes) !== s.sum) throw Error('Transfer checksum/length')
        const message = JSON.parse(text(s.bytes))
        // Delivery must be idempotent by message nonce; lost ACK may repeat it after restart.
        await this.deliver(message)
        const n = { ...s, next: s.next + 1, complete: true }
        await this.persist(n); this.state = n
      } else if (f.op !== OP.DATA && f.op !== OP.END) throw Error('Unexpected operation')
    }
    return frame(OP.ACK, f.id, this.state.next)
  }
}
export class Sender {
  constructor(id, message) {
    this.id = id; this.bytes = utf8(JSON.stringify(message)); this.cursor = 0; this.waiting = false; this.retries = 0
    if (!this.bytes.length || this.bytes.length > MAX) throw Error('Transfer limit')
    this.end = Math.ceil(this.bytes.length / 10) + 1
  }
  packet() {
    if (this.cursor > this.end) return null
    if (this.cursor === 0) return frame(OP.START, this.id, 0, words(this.bytes.length, crc32(this.bytes)))
    if (this.cursor === this.end) return frame(OP.END, this.id, this.cursor)
    return frame(OP.DATA, this.id, this.cursor, this.bytes.slice((this.cursor - 1) * 10, this.cursor * 10))
  }
  ack(input) {
    const f = decode(input)
    if (f.op !== OP.ACK || f.id !== this.id || f.seq < 1 || f.seq > this.end + 1) return false
    // START can resume at a journaled sequence; ordinary ACK must advance exactly once.
    if (this.cursor !== 0 && f.seq !== this.cursor + 1) return false
    this.cursor = f.seq; this.retries = 0; this.waiting = false; return true
  }
  reconnect() { this.cursor = 0; this.waiting = false; this.retries = 0 }
}
