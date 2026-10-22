import bluetooth from '@blueos.bluetooth.ble'
import { Link } from '../core/link.js'
import { SERVICE, RX, TX } from '../core/transport.js'
import { revision, crc32, utf8 } from '../core/codec.js'
import { offer, preview, accept } from '../core/sync.js'
import { db, transfers, saveTransfer, save } from './store.js'
import { id } from '../core/model.js'
let server = null, peer = '', subscribed = false, paired = false, code = '', expires = 0, failures = 0, timer = null, link = null
let inform = () => {}
const CCCD = '00002902-0000-1000-8000-00805f9b34fb'
function nonce() { return id() }
function send(message) { const m = { protocol: 1, nonce: nonce(), ...message }; link.enqueue(crc32(utf8(m.nonce)) % 65535 + 1, m) }
function catalog() { send({ type: 'CATALOG', profiles: db.profiles.map(p => ({ id: p.id, name: p.name, revision: revision(p) })) }) }
async function deliver(m) {
  if (m.protocol !== 1 || typeof m.nonce !== 'string' || m.nonce.length > 100) throw Error('不支持的消息')
  if (Date.now() > expires) { stop(); throw Error('连接码已过期，请重新开启同步') }
  if (m.type === 'HELLO') {
    if (m.code !== code) { if (++failures >= 5) stop(); throw Error('连接码不匹配') }
    paired = true; send({ type: 'READY' }); catalog(); inform('已连接，请选择发送或接收')
    if (db.outbox) send(db.outbox)
    return
  }
  if (!paired) throw Error('请先验证连接码')
  if (m.type === 'CATALOG') { inform('手机档案列表已更新', m.profiles); return }
  if (m.type === 'PULL') { const p = db.profiles.find(p => p.id === m.profileId); if (!p) throw Error('档案不存在'); await sendProfile(p); return }
  if (m.type === 'OFFER') {
    if (db.receipts.some(r => r.offerNonce === m.nonce)) { send({ type: 'SAVED', ...db.receipts.find(r => r.offerNonce === m.nonce) }); return }
    const local = db.profiles.find(p => p.id === m.profile.id)
    const pending = preview(m, local, db.common[m.profile.id])
    await save(d => { d.pending = pending })
    inform('收到档案，请预览并选择保存方式'); return
  }
  if (m.type === 'SAVED') {
    if (!db.outbox || m.offerNonce !== db.outbox.nonce || m.revision !== db.outbox.revision) throw Error('回执与发送版本不匹配')
    await save(d => { if (m.choice === 'replace') d.common[m.profileId] = m.revision; d.outbox = null })
    inform(m.choice === 'keep' ? '手机保留了本机版本' : '手机已确认保存'); return
  }
  throw Error('未知同步消息')
}
export async function sendProfile(p) {
  if (!paired) throw Error('请先连接手机')
  const m = offer(p, nonce()); await save(d => { d.outbox = m }); send(m); inform('等待手机预览确认保存')
}
export async function decide(choice, expectedNonce) {
  if (!db.pending) throw Error('没有待确认档案')
  const pending = db.pending, m = pending.message
  if (m.nonce !== expectedNonce) throw Error('收到的档案已更新，请重新预览')
  const receipt = { offerNonce: m.nonce, profileId: m.profile.id, revision: m.revision, choice }
  await save(d => { d.profiles = accept(d.profiles, pending, choice, expectedNonce); if (choice === 'replace') d.common[m.profile.id] = m.revision; d.receipts = d.receipts.concat(receipt).slice(-32); d.pending = null })
  if (paired) send({ type: 'SAVED', ...receipt })
  inform(choice === 'keep' ? '已保留手表版' : '已保存；已落盘后才发送回执')
}
export function pull(profileId) { if (!paired) throw Error('请先连接手机'); send({ type: 'PULL', profileId }) }
export function start(callback) {
  stop(); inform = callback; code = String(Math.floor(Math.random() * 900000) + 100000); expires = Date.now() + 300000; failures = 0
  server = bluetooth.BLE.createGattServer()
  const raw = b => {
    if (!peer || !subscribed) throw Error('手机未订阅通知')
    const ok = server.notifyCharacteristicChanged(peer, { serviceUuid: SERVICE, characteristicUuid: TX, characteristicValue: b.buffer, confirm: false })
    if (ok === false) throw Error('蓝牙发送失败')
  }
  link = new Link({ sendRaw: raw, load: async n => transfers[n], save: saveTransfer, deliver, error: e => inform(e.message) })
  const response = (r, status = 0, value = new ArrayBuffer(0)) => server.sendResponse({ deviceId: r.deviceId, transId: r.transId, status, offset: r.offset, value })
  server.subscribeConnectStateChange({ callback: s => {
    if (s.state === 0) { peer = ''; subscribed = false; paired = false; link.disconnect(); link.current = null; link.queue = []; link.receivers = {}; inform('已断开；已接收分包保留，重新连接可恢复') }
    if (s.state === 2) { if (peer && peer !== s.deviceId) { inform('已有手机连接'); return }; peer = s.deviceId; paired = false }
  }, fail: () => inform('蓝牙连接监听失败') })
  server.subscribeCharacteristicWrite({ callback: r => {
    if (r.characteristicUuid.toLowerCase() !== RX || r.offset !== 0 || r.isPrep || peer && peer !== r.deviceId || !subscribed) { if (r.needRsp) response(r, 13); return }
    peer = r.deviceId; if (r.needRsp) response(r)
    link.receive(r.value)
  }, fail: () => inform('蓝牙写入监听失败') })
  server.subscribeDescriptorWrite({ callback: r => {
    const b = new Uint8Array(r.value)
    const valid = r.descriptorUuid.toLowerCase() === CCCD && r.characteristicUuid.toLowerCase() === TX && r.offset === 0 && !r.isPrep && b.length === 2 && (b[0] === 0 || b[0] === 1) && b[1] === 0 && (!peer || peer === r.deviceId)
    if (valid) { peer = r.deviceId; subscribed = b[0] === 1; if (subscribed) link.reconnect() }
    if (r.needRsp) response(r, valid ? 0 : 13)
  }, fail: () => inform('通知订阅失败') })
  server.subscribeDescriptorRead({ callback: r => response(r, 0, new Uint8Array([subscribed ? 1 : 0, 0]).buffer) })
  const descriptor = { serviceUuid: SERVICE, characteristicUuid: TX, descriptorUuid: CCCD, descriptorValue: new Uint8Array([0, 0]).buffer }
  if (server.addService({ serviceUuid: SERVICE, isPrimary: true, includeServices: [], characteristics: [
    { serviceUuid: SERVICE, characteristicUuid: RX, characteristicValue: new ArrayBuffer(0), descriptors: [], properties: [5] },
    { serviceUuid: SERVICE, characteristicUuid: TX, characteristicValue: new ArrayBuffer(0), descriptors: [descriptor], properties: [3] }
  ] }) === false) { stop(); throw Error('无法启动 GATT 服务') }
  server.startAdvertising({ interval: 1600, txPower: -7, connectable: true }, { serviceUuids: [SERVICE] })
  timer = setInterval(() => { if (Date.now() > expires) { stop(); inform('同步已结束，连接码过期') } else link.tick() }, 250)
  return code
}
export function stop() {
  if (timer) clearInterval(timer); timer = null
  if (link) link.disconnect()
  if (server) { try { server.stopAdvertising(); server.unsubscribeCharacteristicWrite(); server.unsubscribeDescriptorWrite(); server.unsubscribeDescriptorRead(); server.unsubscribeConnectStateChange(); server.close() } catch (e) { inform('关闭蓝牙失败：' + e.message) } }
  server = null; peer = ''; subscribed = false; paired = false; code = ''
}
