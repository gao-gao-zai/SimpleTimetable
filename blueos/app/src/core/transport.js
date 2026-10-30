import { crc16, crc32, utf8, text } from './codec.js'
import { compress } from './lz4.js'
import { finalize } from './finalize.js'
export const CHUNK = 100
export const SERVICE = 'd7300001-9e2c-4c53-a1aa-2cc0bf3d7710'
export const RX = 'd7300002-9e2c-4c53-a1aa-2cc0bf3d7710'
export const TX = 'd7300003-9e2c-4c53-a1aa-2cc0bf3d7710'
export const OP = { START: 1, DATA: 2, END: 3, ACK: 4, ERROR: 5 }
export const MAX = 65536
export function frame(op, id, seq, data = []) {
  if (data.length > CHUNK) throw Error('Frame too large')
  const b = new Uint8Array(10 + data.length)
  b.set([83, 1, op, id & 255, id >> 8, seq & 255, seq >> 8, data.length]); b.set(data, 10)
  const c = crc16(new Uint8Array([...b.slice(0, 8), ...data])); b[8] = c & 255; b[9] = c >> 8
  return b
}
export function decode(b) {
  b = new Uint8Array(b)
  if (b.length < 10 || b.length > CHUNK + 10 || b[0] !== 83 || b[1] !== 1 || b[7] !== b.length - 10 || b[2] < 1 || b[2] > 5) throw Error('Invalid frame')
  if (crc16(new Uint8Array([...b.slice(0, 8), ...b.slice(10)])) !== (b[8] | b[9] << 8)) throw Error('Frame checksum')
  return { op: b[2], id: b[3] | b[4] << 8, seq: b[5] | b[6] << 8, data: b.slice(10) }
}
function u32(b, i) { return (b[i] | b[i + 1] << 8 | b[i + 2] << 16 | b[i + 3] << 24) >>> 0 }
function words(...values) { const b = new Uint8Array(values.length * 4); values.forEach((v, i) => { for (let j = 0; j < 4; j++) b[i * 4 + j] = v >>> (j * 8) & 255 }); return b }
// Durable hooks must resolve only after storage success. ACK always follows journal write.
export class Receiver {
  constructor(state, persist, deliver, options = {}) { this.state = state || null; this.persist = persist; this.deliver = deliver; this.options = options; this.buffer = null; this.received = 0 }
  async receive(input) {
    const f = decode(input)
    if (f.op === OP.START) {
      if (![8,10].includes(f.data.length) || f.seq !== 0) throw Error('Invalid start')
      const size = u32(f.data, 0), sum = u32(f.data, 4), chunk = f.data.length === 10 ? f.data[8] : 10, codec = f.data.length === 10 ? f.data[9] : 0
      if (![10,100].includes(chunk) || ![0,1].includes(codec)) throw Error('Unsupported transfer format')
      if (this.options.handshake && (size > 4096 || f.data.length !== 8)) throw Error('Handshake limit')
      if (!size || size > MAX) throw Error('Transfer limit')
      if (!this.state || this.state.id !== f.id || this.state.sum !== sum || this.state.size !== size || (this.state.chunk || 10) !== chunk || (this.state.codec || 0) !== codec) {
        const s = { id: f.id, size, sum, chunk, codec, extended: f.data.length === 10, next: 1, bytes: [], complete: false }
        await this.persist(s); this.state = s; if(this.options.memory){this.buffer=new Uint8Array(size);this.received=0}
      }
    } else {
      const s = this.state
      if (!s || s.id !== f.id) throw Error('Unknown transfer')
      const count=this.options.memory?this.received:s.bytes.length
      if (f.op === OP.DATA && !s.complete && f.seq === s.next) {
        if (!f.data.length || count + f.data.length > s.size || f.data.length !== Math.min(s.chunk || 10, s.size - count)) throw Error('Invalid chunk')
        const n = { ...s, bytes: this.options.memory ? [] : s.bytes.concat(Array.from(f.data)), next: s.next + 1 }
        if(this.options.memory){this.buffer.set(f.data,this.received);this.received+=f.data.length}
        await this.persist(n); this.state = n
      } else if (f.op === OP.END && !s.complete && f.seq === s.next) {
        const bytes=this.options.memory?this.buffer.subarray(0,this.received):s.bytes
        if (f.data.length || bytes.length !== s.size || crc32(bytes) !== s.sum) throw Error('Transfer checksum/length')
        if(this.options.active && !this.options.active())throw Error('Transfer cancelled')
        let decodedBytes=bytes.length
        const message = this.options.handshake ? JSON.parse(text(bytes)) : await finalize(bytes,s.codec || 0,{...this.options,decoded:size=>{decodedBytes=size}})
        // Delivery must be idempotent by message nonce; lost ACK may repeat it after restart.
        if(this.options.active && !this.options.active())throw Error('Transfer cancelled')
        if(this.options.stage)this.options.stage('暂存')
        await this.deliver(message,{bytes:decodedBytes})
        const n = { ...s, next: s.next + 1, complete: true }
        await this.persist(n); this.state = n
      } else if (f.op !== OP.DATA && f.op !== OP.END) throw Error('Unexpected operation')
    }
    return frame(OP.ACK, f.id, this.state.next, this.state.extended ? [this.state.chunk, ...words(this.state.sum)] : [])
  }
}
export class Sender {
  constructor(id, message, options = {}) {
    this.id=id;this.chunk=options.chunk===100?100:10;this.extended=!!options.extended||this.chunk===100||!!options.lz4
    const raw=utf8(JSON.stringify(message))
    if(!raw.length||raw.length>MAX)throw Error('Transfer limit')
    this.rawSize=raw.length
    const packed=options.lz4?compress(raw):raw
    this.codec=options.lz4&&packed.length<raw.length?1:0;this.bytes=this.codec?packed:raw;this.sum=crc32(this.bytes)
    this.cursor=0;this.waiting=false;this.retries=0;this.end=Math.ceil(this.bytes.length/this.chunk)+1
  }
  fallback() { if(this.chunk!==100)return false;this.chunk=10;this.end=Math.ceil(this.bytes.length/10)+1;this.reconnect();return true }

  packet() {
    if (this.cursor > this.end) return null
    if (this.cursor === 0) return frame(OP.START, this.id, 0, this.extended ? [...words(this.bytes.length,this.sum),this.chunk,this.codec] : words(this.bytes.length,this.sum))
    if (this.cursor === this.end) return frame(OP.END, this.id, this.cursor)
    return frame(OP.DATA, this.id, this.cursor, this.bytes.slice((this.cursor - 1) * this.chunk, this.cursor * this.chunk))
  }
  ack(input) {
    const f = decode(input)
    if (f.op !== OP.ACK || f.id !== this.id || f.seq < 1 || f.seq > this.end + 1) return false
    if (this.extended && (f.data.length !== 5 || f.data[0] !== this.chunk || u32(f.data,1) !== this.sum) || !this.extended && f.data.length) return false
    // START can resume at a journaled sequence; ordinary ACK must advance exactly once.
    if (this.cursor !== 0 && f.seq !== this.cursor + 1) return false
    this.cursor = f.seq; this.retries = 0; this.waiting = false; return true
  }
  reconnect() { this.cursor = 0; this.waiting = false; this.retries = 0 }
}
