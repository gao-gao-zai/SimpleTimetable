import { Link } from '../core/link.js'
import { SERVICE, RX, TX } from '../core/transport.js'
import { revision, crc32, crc16, utf8 } from '../core/codec.js'
import { offer, preview, accept } from '../core/sync.js'
import { db, save } from './store.js'
import { Consent } from '../core/consent.js'
import { Session, capabilities } from '../core/session.js'
import { id } from '../core/model.js'
const session = new Session(), consent = new Consent()
let generation=0, brightness=null
let counters={}
const PROBE='d7300004-9e2c-4c53-a1aa-2cc0bf3d7710'
export function status(){return {active:!!server,paired,subscribed,peer:peer?peer.slice(-5):'',stateField:session.lastStateField,counters:{...counters},transport:link?{...link.options}: {},transfer:link&&link.current?{payload:link.current.chunk,codec:link.current.codec,rawBytes:link.current.rawSize,wireBytes:link.current.bytes.length}:null}}
function failure(label,code){stop();inform(label+' '+String(code),null,{active:false})}
let server = null, peer = '', subscribed = false, paired = false, code = '', expires = 0, failures = 0, timer = null, link = null
let inform = () => {}
const CCCD = '00002902-0000-1000-8000-00805f9b34fb'
function nonce() { return id() }
function send(message, options) { const m = { protocol: 1, nonce: nonce(), ...message }; link.enqueue(crc32(utf8(m.nonce)) % 65535 + 1, m, options) }
function catalog() { send({ type: 'CATALOG', profiles: db.profiles.map(p => ({ id: p.id, name: p.name, revision: revision(p) })) }) }
async function deliver(m, metadata) {
  if (m.protocol !== 1 || typeof m.nonce !== 'string' || m.nonce.length > 100) throw Error('不支持的消息')
  if (Date.now() > expires) { stop(); throw Error('连接码已过期，请重新开启同步') }
  if (m.type === 'HELLO') {
    if (m.code !== code) { if (++failures >= 5) { failure('验证码错误次数过多，请重开同步',5);return };send({type:'HELLO_ERROR',reason:'CODE_MISMATCH'},{});inform('验证码不匹配');return }
    if(!m.transport||m.transport.consent!==1){send({type:'HELLO_ERROR',reason:'UPGRADE_REQUIRED'},{});inform('手机版本过旧，请使用本次配套版本');return}
    paired = true; session.paired = true; link.options = capabilities(m.transport)
    send({ type: 'READY', transport: {...link.options,consent:1} }, {}); inform('已验证连接码，请主动选择发送或请求接收')
    return
  }
  if (!paired) throw Error('请先验证连接码')
  if(m.type==='REQUEST'){consent.receive(m);inform('手机请求发送 '+m.name+'，请在手表确认接收');return}
  if(m.type==='RESPONSE'){const body=consent.response(m);if(body){send(body);inform('传输中，正文结束后等待手机生成预览')}else inform('手机已拒绝接收');return}
  if(m.type==='CANCEL'){const pending=consent.outgoing||consent.incoming;if(!pending||m.requestId!==pending.request.requestId)return;consent.clear('cancelled');link.generation++;link.receivers={};link.current=null;link.queue=[];inform('手机已取消本次同步');return}
  if (m.type === 'LIST') { catalog(); return }
  if (m.type === 'CATALOG') { inform('手机档案列表已更新', m.profiles); return }
  if (m.type === 'PULL') { const p = db.profiles.find(p => p.id === m.profileId); if (!p) throw Error('档案不存在'); await sendProfile(p); return }
  if (m.type === 'OFFER') {
    consent.verify(m,metadata.bytes)
    if (db.receipts.some(r => r.offerNonce === m.nonce)) { send({ type: 'SAVED', ...db.receipts.find(r => r.offerNonce === m.nonce) }); return }
    const local = db.profiles.find(p => p.id === m.profile.id)
    const pending = preview(m, local, db.common[m.profile.id])
    await save(d => { d.pending = pending })
    consent.clear('waiting-save');send({type:'STAGED',requestId:m.requestId,revision:m.revision});inform('收到档案，请预览并选择保存方式'); return
  }
  if(m.type==='STAGED'){if(!consent.outgoing||m.requestId!==consent.outgoing.request.requestId||m.revision!==consent.outgoing.message.revision)throw Error('预览回执已失效');consent.phase='waiting-save';inform('手机已生成预览，等待手机选择保存方式');return}
  if (m.type === 'SAVED') {
    if (!consent.outgoing || m.offerNonce !== consent.outgoing.message.nonce || m.revision !== consent.outgoing.message.revision) throw Error('回执与发送版本不匹配')
    await save(d => { if (m.choice === 'replace') d.common[m.profileId] = m.revision; d.outbox = null })
    consent.clear('complete');inform(m.choice === 'keep' ? '手机保留了本机版本' : '手机已确认保存'); return
  }
  throw Error('未知同步消息')
}
export async function sendProfile(p) {
  if (!paired) throw Error('请先连接手机')
  const m=offer(p,nonce());m.requestId=m.nonce;const bytes=utf8(JSON.stringify(m)).length
  if(bytes>65536)throw Error('完整档案超过 64 KiB，未发送')
  const request=consent.request(m,{bytes,counts:{subjects:p.subjects.length,layouts:p.layouts.length,plans:p.plans.length,temporary:p.temporary.length}})
  send(request);inform('等待手机确认接收，尚未发送档案正文')
}
export async function decide(choice, expectedNonce) {
  if (!db.pending) throw Error('没有待确认档案')
  const pending = db.pending, m = pending.message
  if (m.nonce !== expectedNonce) throw Error('收到的档案已更新，请重新预览')
  const receipt = { offerNonce: m.nonce, profileId: m.profile.id, revision: m.revision, choice }
  await save(d => { d.profiles = accept(d.profiles, pending, choice, expectedNonce); if (choice === 'replace') d.common[m.profile.id] = m.revision; d.receipts = d.receipts.concat(receipt).slice(-32); d.pending = null })
  if (paired) send({ type: 'SAVED', ...receipt })
  consent.clear('complete');inform(choice === 'keep' ? '已保留手表版' : '已保存；已落盘后才发送回执')
}
export function incomingRequest(){return consent.incoming&&!consent.incoming.accepted?consent.incoming.request:null}
export function respond(accepted){send(consent.respond(accepted));inform(accepted?'已同意接收，等待手机发送正文':'已拒绝接收')}
export function cancel(){const pending=consent.outgoing||consent.incoming;const requestId=pending&&pending.request.requestId;consent.clear('cancelled');if(link){link.disconnect();link.reconnect()};if(requestId)send({type:'CANCEL',requestId});inform('本次同步已取消')}
export function requestCatalog() { if (!paired) throw Error('请先连接手机'); send({ type:'LIST' }) }
export function pull(profileId) { if (!paired) throw Error('请先连接手机'); send({ type: 'PULL', profileId }) }
export async function start(callback) {
  stop();
  const bluetooth = require('@blueos.bluetooth.ble')
  try {
  const attempt=++generation
  counters={writes:0,reads:0,descriptors:0,notifications:0,validPackets:0}
  const fail=label=>(e,c)=>{if(attempt===generation)failure(label,c)}
  const current=()=>attempt===generation&&!!server
  try{brightness=require('@system.brightness');brightness.setKeepScreenOn({keepScreenOn:true})}catch(e){brightness=null}
  consent.clear();inform = callback; code = String(Math.floor(Math.random() * 900000) + 100000); expires = Date.now() + 300000; failures = 0
  server = bluetooth.BLE.createGattServer()
  const raw = b => {
    if (!peer || !subscribed) throw Error('手机未订阅通知')
    counters.notifications++;const ok = server.notifyCharacteristicChanged(peer, { serviceUuid: SERVICE, characteristicUuid: TX, characteristicValue: b.buffer, confirm: false })
    if (ok === false) throw Error('蓝牙发送失败')
  }
  link = new Link({sendRaw:raw,memoryOnly:()=>!paired,deliver,stage:s=>inform('分包收齐 · '+s+'并生成预览'),error:e=>{counters.lastError=e.message;inform(e.message)}})
  const response = (r, status = 0, value = new ArrayBuffer(0)) => server.sendResponse({ deviceId: r.deviceId, transId: r.transId, status, offset: r.offset, value })
  server.subscribeConnectStateChange({ callback: s => {
    if(!current())return
    const result = session.connection(s)
    if(result==='disconnected')consent.clear('disconnected')
    if(result==='disconnected'){peer='';subscribed=false;paired=false;link.disconnect();link.current=null;link.queue=[];link.receivers={};inform('已断开；重新连接后请重新请求接收')}
    if(result==='connected')peer=session.peer
  }, fail:fail('蓝牙连接监听失败') })
  server.subscribeCharacteristicWrite({ callback: r => {
    if(!current())return;counters.writes++
    if (r.characteristicUuid.toLowerCase() !== RX || r.offset !== 0 || r.isPrep || peer && peer !== r.deviceId || !subscribed) { if (r.needRsp) response(r, 13); return }
    peer = r.deviceId;session.peer=peer; if (r.needRsp) response(r)
    link.receive(r.value)
  }, fail:fail('蓝牙写入监听失败') })
  server.subscribeDescriptorWrite({ callback: r => {
    if(!current())return;counters.descriptors++
    const b = new Uint8Array(r.value)
    const valid = r.descriptorUuid.toLowerCase() === CCCD && r.characteristicUuid.toLowerCase() === TX && r.offset === 0 && !r.isPrep && b.length === 2 && (b[0] === 0 || b[0] === 1) && b[1] === 0 && (!peer || peer === r.deviceId)
    if (valid) { peer = r.deviceId;session.peer=peer; subscribed = b[0] === 1; if (subscribed) link.reconnect();else {paired=false;session.paired=false;link.disconnect()} }
    if (r.needRsp) response(r, valid ? 0 : 13)
  }, fail:fail('通知订阅失败') })
  server.subscribeDescriptorRead({callback:r=>{if(current()&&(!peer||peer===r.deviceId))response(r,0,new Uint8Array([subscribed?1:0,0]).buffer)},fail:fail('描述符读监听失败')})
  if(typeof server.subscribeCharacteristicRead==='function')server.subscribeCharacteristicRead({callback:r=>{
    if(!current()||peer&&peer!==r.deviceId)return
    counters.reads++
    if(r.characteristicUuid.toLowerCase()!==PROBE){response(r,13);return}
    const bytes=utf8('STPROBE1'),sum=crc16(bytes);response(r,0,new Uint8Array([...bytes,sum&255,sum>>8]).buffer)
  },fail:(e,c)=>{if(current())inform('可选读取诊断不可用 '+c)}})
  const descriptor = { serviceUuid: SERVICE, characteristicUuid: TX, descriptorUuid: CCCD, descriptorValue: new Uint8Array([0, 0]).buffer }
  if (server.addService({ serviceUuid: SERVICE, isPrimary: true, includeServices: [], characteristics: [
    { serviceUuid:SERVICE,characteristicUuid:PROBE,characteristicValue:new Uint8Array([0]).buffer,descriptors:[],properties:[1] },
    { serviceUuid: SERVICE, characteristicUuid: RX, characteristicValue: new ArrayBuffer(0), descriptors: [], properties: [5] },
    { serviceUuid: SERVICE, characteristicUuid: TX, characteristicValue: new ArrayBuffer(0), descriptors: [descriptor], properties: [3] }
  ] }) === false) { stop(); throw Error('无法启动 GATT 服务') }
  const result=server.startAdvertising({interval:1600,txPower:-7,connectable:true},{serviceUuids:[SERVICE]})
  if(result===false)throw Error('广播请求被拒绝')
  inform(result===true?'模式：普通课表同步 · 广播接口已接受请求':'模式：普通课表同步 · 已请求广播，尚无成功回执')
  timer = setInterval(() => { if (Date.now() > expires) { stop(); inform('同步已结束，连接码过期') } else {if(consent.expire())inform('接收确认超时，需重新请求');link.tick()} }, 250)
  return code
  } catch(e){failure('开启同步失败',e.message);throw e}
}
export function stop() {
  generation++
  if(timer)clearInterval(timer);timer=null
  if(link)link.disconnect()
  if(server){const previous=server;for(const name of ['stopAdvertising','unsubscribeCharacteristicWrite','unsubscribeCharacteristicRead','unsubscribeDescriptorWrite','unsubscribeDescriptorRead','unsubscribeConnectStateChange','close']){try{if(typeof previous[name]==='function')previous[name]()}catch(e){inform('关闭蓝牙失败：'+name)}}}
  if(brightness){try{brightness.setKeepScreenOn({keepScreenOn:false})}catch(e){inform('释放常亮失败')}brightness=null}
  consent.clear('disconnected');session.reset();server=null;peer='';subscribed=false;paired=false;code=''
}
