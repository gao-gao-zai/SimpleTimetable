import test from 'node:test'
import assert from 'node:assert/strict'
import { utf8, text, crc32, crc16, revision } from '../app/src/core/codec.js'
import { Receiver, Sender, decode, frame, OP } from '../app/src/core/transport.js'
import { sample, validate, edit, remove, shift, swap, lessonChange } from '../app/src/core/model.js'
import { state, resolve, matches, clone, snapshot } from '../app/src/core/engine.js'
import { preview, offer, accept, direction } from '../app/src/core/sync.js'
const time = s => new Date('2026-10-19T' + s).getTime()
test('UTF-8 Chinese, emoji, malformed sequences', () => { assert.equal(text(utf8('合成😀')), '合成😀'); assert.throws(() => text([0xc0, 0x80])); assert.throws(() => text([0xf4, 0x90, 0x80, 0x80])); assert.throws(() => utf8('\ud800')) })
test('published CRC vectors', () => { assert.equal(crc32(utf8('123456789')), 0xcbf43926); assert.equal(crc16(utf8('123456789')), 0x29b1) })
test('canonical revision ignores map insertion order', () => assert.equal(revision({ a: 1, b: 2 }), revision({ b: 2, a: 1 })))
test('cycle matches pre-anchor and week offsets', () => { const q = sample().plans[0]; q.cycle = 2; assert.equal(matches(q, '2026-10-12'), false); q.offsetWeeks = 1; assert.equal(matches(q, '2026-10-12'), true) })
test('current boundaries and countdown', () => { const p = sample(); assert.equal(state(p, time('08:00:00')).phase, 'CLASS'); assert.equal(state(p, time('08:45:00')).phase, 'BREAK'); assert.equal(state(p, time('08:22:30')).progress, 0.5); assert.equal(state(p, time('09:40:00')).phase, 'ENDED') })
test('manual activation separate from weekday match', () => { const p = sample(); p.manualPlan = 'plan'; assert.equal(state(p, new Date('2026-10-18T08:10:00').getTime()).phase, 'CLASS') })
test('temporary replacement expires and withdrawal restores', () => { const p = lessonChange(sample(), '2026-10-19', 'plan', 'slot-a', 's-b'); assert.equal(state(p, time('08:10:00')).current.subject.id, 's-b'); assert.equal(state(p, new Date('2026-10-20T08:10:00').getTime()).current.subject.id, 's-a'); p.temporary = []; assert.equal(state(p, time('08:10:00')).current.subject.id, 's-a') })
test('cross-day and permanent swaps', () => { const a = { date: '2026-10-19', planId: 'plan', slotId: 'slot-a', subjectId: 's-a' }, b = { date: '2026-10-20', planId: 'plan', slotId: 'slot-b', subjectId: 's-b' }; const p = swap(sample(), a, b); assert.equal(state(p, time('08:10:00')).current.subject.id, 's-b'); assert.equal(swap(sample(), a, b, true).plans[0].subjects['slot-a'], 's-b') })
test('partial-day overrides split browsing timeline', () => { const p = lessonChange(sample(), '2026-10-19', 'plan', 'slot-a', 's-b'); p.temporary[0].startMillis = time('08:10:00'); p.temporary[0].endMillis = time('08:20:00'); const r = resolve(p, '2026-10-19', time('08:30:00')); assert.deepEqual(r.lessons.slice(0, 3).map(e => [e.slot.start, e.slot.end, e.subject.id]), [[28800, 29400, 's-a'], [29400, 30000, 's-b'], [30000, 31500, 's-a']]) })
test('stop lesson on one date only', () => { const p = lessonChange(sample(), '2026-10-19', 'plan', 'slot-a', ''); assert.equal(state(p, time('08:10:00')).current.subject, null) })
test('reference protection and invalid layout shift', () => { assert.throws(() => remove(sample(), 'subjects', 's-a')); assert.throws(() => remove(sample(), 'layouts', 'layout')); assert.throws(() => shift(sample(), 'layout', -30000)); assert.equal(shift(sample(), 'layout', 60).layouts[0].slots[0].start, 28860) })
test('unknown incoming color metadata preserved; invalid schema rejected', () => { const p = sample(); p.subjects[0].future = { keep: true }; assert.equal(validate(p).subjects[0].future.keep, true); p.plans[0].days = [8]; assert.throws(() => validate(p)) })
test('conflict directions', () => { assert.equal(direction('a', 'a', null), 'same'); assert.equal(direction('a', 'b', 'a'), 'receive'); assert.equal(direction('a', 'b', 'b'), 'send'); assert.equal(direction('a', 'b', 'c'), 'conflict') })
test('preview and copy/replace plus stale preview protection', () => { const p = sample(), incoming = clone(p); incoming.name = '合成新版'; const v = preview(offer(incoming, 'nonce'), p, revision(p)); assert.equal(accept([p], v, 'replace')[0].name, incoming.name); assert.equal(accept([p], v, 'copy').length, 2); p.name = '本地新改'; assert.throws(() => accept([p], v, 'replace')) })
test('face snapshot has validity and parsed timeline', () => { const s = snapshot(sample(), time('08:10:00'), 'v'); assert.equal(s.timeline.length, 2); assert.ok(s.validUntil > s.updatedAt); assert.equal(s.schema, 1) })
test('frame detects corruption, truncated and oversized packets', () => { const f = frame(OP.DATA, 1, 1, [2, 3]); f[10] ^= 1; assert.throws(() => decode(f)); assert.throws(() => decode([1])); assert.throws(() => frame(OP.DATA, 1, 1, Array(11).fill(0))) })
test('dropped ACK, duplicate DATA/END, durable restart and resume', async () => {
  const message = { type: 'OFFER', profile: sample(), nonce: 'synthetic' }; let disk = null, delivered = 0
  let r = new Receiver(null, async s => { disk = clone(s) }, async m => { assert.deepEqual(m, message); delivered++ })
  const s = new Sender(42, message)
  s.ack(await r.receive(s.packet()))
  const packet = s.packet(); const ack = await r.receive(packet); assert.deepEqual(await r.receive(packet), ack); s.ack(ack)
  r = new Receiver(disk, async st => { disk = clone(st) }, async () => { delivered++ }); s.reconnect(); s.ack(await r.receive(s.packet())); assert.equal(s.cursor, 2)
  while (s.packet()) { const p = s.packet(); const a = await r.receive(p); if (decode(p).op === OP.END) assert.deepEqual(await r.receive(p), a); assert.equal(s.ack(a), true) }
  assert.equal(delivered, 1)
})
test('failed journal write does not advance ACK cursor', async () => { const s = new Sender(1, { a: 1 }); const r = new Receiver(null, async () => { throw Error('disk full') }, async () => {}); await assert.rejects(r.receive(s.packet())); assert.equal(r.state, null) })
test('failed delivery keeps existing profile and allows retry', async () => { let fail = true; const s = new Sender(2, { a: 1 }); const r = new Receiver(null, async () => {}, async () => { if (fail) throw Error('disk full') }); s.ack(await r.receive(s.packet())); while (decode(s.packet()).op === OP.DATA) s.ack(await r.receive(s.packet())); await assert.rejects(r.receive(s.packet())); assert.equal(r.state.complete, false); fail = false; s.ack(await r.receive(s.packet())); assert.equal(s.packet(), null) })
test('wrong transfer and payload corruption fail without delivery', async () => { const s = new Sender(3, { a: 1 }), r = new Receiver(null, async () => {}, async () => assert.fail()); s.ack(await r.receive(s.packet())); await assert.rejects(r.receive(frame(OP.DATA, 4, 1, [1]))); s.ack(await r.receive(frame(OP.DATA, 3, 1, Array.from(s.bytes).map((b, i) => i ? b : b ^ 1)))); await assert.rejects(r.receive(s.packet())) })
