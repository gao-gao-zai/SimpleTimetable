import { Sender, Receiver, decode, OP } from './transport.js'
// Transport runtime, single outstanding packet per direction. sendRaw is an ATT operation.
export class Link {
  constructor({ sendRaw, load, save, deliver, error, now = () => Date.now() }) {
    this.sendRaw = sendRaw; this.load = load; this.save = save; this.deliver = deliver; this.error = error; this.now = now
    this.queue = []; this.current = null; this.receivers = {}; this.chain = Promise.resolve(); this.online = true; this.sentAt = 0
  }
  enqueue(id, message) { this.queue.push(new Sender(id, message)); this.pump() }
  pump() {
    if (!this.online) return
    if (!this.current) this.current = this.queue.shift() || null
    if (!this.current || this.current.waiting) return
    const p = this.current.packet()
    if (!p) { this.current = null; this.pump(); return }
    this.current.waiting = true; this.sentAt = this.now()
    try { this.sendRaw(p) } catch (e) { this.online = false; this.error(e) }
  }
  tick() {
    if (!this.online || !this.current || !this.current.waiting || this.now() - this.sentAt < 2000) return
    if (++this.current.retries > 5) { this.online = false; this.error(Error('传输中断，请重新连接后重试')); return }
    this.current.waiting = false; this.pump()
  }
  disconnect() { this.online = false; if (this.current) this.current.reconnect() }
  reconnect() { this.online = true; this.pump() }
  receive(bytes) {
    const copy = new Uint8Array(bytes)
    this.chain = this.chain.then(async () => {
      const f = decode(copy)
      if (f.op === OP.ACK) { if (this.current && this.current.ack(copy)) this.pump(); return }
      if (!this.receivers[f.id] && Object.keys(this.receivers).length >= 6) delete this.receivers[Object.keys(this.receivers)[0]]
      if (!this.receivers[f.id]) this.receivers[f.id] = new Receiver(await this.load(f.id), s => this.save(f.id, s), this.deliver)
      const ack = await this.receivers[f.id].receive(copy)
      this.sendRaw(ack)
    }).catch(e => this.error(e))
    return this.chain
  }
}
