import { Sender, Receiver, decode, OP } from './transport.js'
// Transport runtime, single outstanding packet per direction. sendRaw is an ATT operation.
export class Link {
  constructor({ sendRaw, load, save, deliver, error, stage = () => {}, memoryOnly = () => false, now = () => Date.now() }) {
    this.stage=stage;this.generation=0; this.memoryOnly = memoryOnly; this.sendRaw = sendRaw; this.load = load; this.save = save; this.deliver = deliver; this.error = error; this.now = now
    this.options = {}; this.queue = []; this.current = null; this.receivers = {}; this.chain = Promise.resolve(); this.online = true; this.sentAt = 0
  }
  enqueue(id, message, options = this.options) { this.queue.push(new Sender(id, message, options)); this.pump() }
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
    if (!this.online || !this.current || !this.current.waiting || this.now() - this.sentAt < (this.current.cursor===this.current.end?60000:2000)) return
    if(this.current.fallback()) { this.pump(); return }
    if (++this.current.retries > 5) { this.online = false; this.error(Error('传输中断，请重新连接后重试')); return }
    this.current.waiting = false; this.pump()
  }
  disconnect() { this.online=false;this.generation++;this.queue=[];this.current=null;this.receivers={};this.chain=Promise.resolve() }
  reconnect() { this.online = true; this.pump() }
  receive(bytes) {
    const copy = new Uint8Array(new Uint8Array(bytes)), generation=this.generation
    this.chain = this.chain.then(async () => {
      if(generation!==this.generation||!this.online)return
      const f = decode(copy)
      if (f.op === OP.ACK) { if (this.current && this.current.ack(copy)) this.pump(); return }
      if (!this.receivers[f.id] && Object.keys(this.receivers).length >= 6) delete this.receivers[Object.keys(this.receivers)[0]]
      if (!this.receivers[f.id]) { const memory = this.memoryOnly(); this.receivers[f.id] = new Receiver(null, async () => {}, this.deliver, { stage:this.stage, handshake: memory, memory: true, active:()=>generation===this.generation&&this.online }) }
      const ack = await this.receivers[f.id].receive(copy)
      if(generation===this.generation&&this.online)this.sendRaw(ack)
    }).catch(e => this.error(e))
    return this.chain
  }
}
