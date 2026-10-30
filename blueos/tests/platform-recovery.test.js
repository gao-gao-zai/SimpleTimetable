import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {pathToFileURL} from 'node:url'
import {resolve,dirname} from 'node:path'
import {sample} from '../app/src/core/model.js'
import {crc32,utf8} from '../app/src/core/codec.js'
const root=resolve(import.meta.dirname,'..')
async function moduleFrom(path,replace){let source=readFileSync(path,'utf8');source=replace(source);source=source.replace(/from ['"](\.[^'"]+)['"]/g,(_,p)=>'from '+JSON.stringify(pathToFileURL(resolve(dirname(path),p)).href));return import('data:text/javascript;base64,'+Buffer.from(source+'\n// '+Math.random()).toString('base64'))}
test('cold store reads and validates once; reopening reuses checked DB without journal IO',async()=>{
 const p=sample(),db={schema:1,profiles:[p],activeProfile:p.id,settings:{},common:{},receipts:[]},json=JSON.stringify(db),disk={'simpletimetable.v1.head':'a','simpletimetable.v1.a':JSON.stringify({json,crc:crc32(utf8(json))})};let gets=0
 globalThis.testStorage={get:({key,success})=>{gets++;success({value:disk[key]||''})},set:({key,value,success})=>{disk[key]=value;success()}}
 const store=await moduleFrom(resolve(root,'app/src/platform/store.js'),s=>s.replace("import storage from '@system.storage'",'const storage=globalThis.testStorage'))
 const stages=[];const first=await store.open({mark:s=>stages.push(s)});assert.deepEqual(stages,['存储读取完成','存档校验完成']);const before=gets;assert.equal(await store.open(),first);assert.equal(gets,before);assert.equal(gets,2)
 delete globalThis.testStorage
})
test('corrupt cold DB fails and preserves its bytes rather than returning cached fallback',async()=>{
 const disk={'simpletimetable.v1.head':'a','simpletimetable.v1.a':'broken'};globalThis.testStorage={get:({key,success})=>success(disk[key]||''),set:()=>assert.fail()}
 const store=await moduleFrom(resolve(root,'app/src/platform/store.js'),s=>s.replace("import storage from '@system.storage'",'const storage=globalThis.testStorage'))
 await assert.rejects(store.open());assert.equal(disk['simpletimetable.v1.a'],'broken');delete globalThis.testStorage
})
test('native imports before first course display are limited to storage',()=>{
 for(const name of ['ble.js','sharing.js','card-check.js']){const s=readFileSync(resolve(root,'app/src/platform',name),'utf8');assert.doesNotMatch(s,/^import .*from ['"]@/m,name)}
 const page=readFileSync(resolve(root,'app/src/pages/Main/index.ux'),'utf8');assert.ok(page.indexOf('ui.refresh();trace.mark')<page.indexOf('shareLater()\n      this.startTicks()'));assert.match(page,/setTimeout\(\(\) =>/);assert.doesNotMatch(page,/await new Promise/)
})
test('independent watch diagnostic fixed 64B data and CRC cannot touch timetable store',async()=>{
 const probe=await import('../diagnostics/src/probe.js');assert.equal(probe.fixed().length,64);assert.notEqual(probe.uuid(1),'d7300001-9e2c-4c53-a1aa-2cc0bf3d7710');assert.equal(probe.crc32(utf8('123456789')),0xcbf43926);const source=readFileSync(resolve(root,'diagnostics/src/probe.js'),'utf8');assert.doesNotMatch(source,/platform\/store|OFFER|profile|code:/)
})

test('actual watch server handshake uses memory, emits only READY and isolates foreign events',async()=>{
 const {Link}=await import('../app/src/core/link.js')
 const {SERVICE,RX,TX,decode,OP}=await import('../app/src/core/transport.js')
 const pending=[],callbacks={},messages=[],reported=[];let saveCalls=0
 const db={profiles:[sample()],activeProfile:'unused',common:{},receipts:[],pending:null}
 const storeUrl='data:text/javascript;base64,'+Buffer.from('export const db=globalThis.bleTestDB;export const save=async f=>{globalThis.bleSaveCalls++;f(db)}').toString('base64')
 globalThis.bleTestDB=db;globalThis.bleSaveCalls=0
 const server={addService:()=>true,startAdvertising:()=>undefined,notifyCharacteristicChanged:(peer,value)=>{pending.push(new Uint8Array(new Uint8Array(value.characteristicValue)));return true},sendResponse:()=>{},stopAdvertising:()=>{},close:()=>{}}
 for(const name of ['ConnectStateChange','CharacteristicWrite','DescriptorWrite','DescriptorRead','CharacteristicRead'])server['subscribe'+name]=o=>{callbacks[name]=o.callback}
 globalThis.bleNative=name=>name==='@blueos.bluetooth.ble'?{BLE:{createGattServer:()=>server}}:{setKeepScreenOn:()=>{}}
 const path=resolve(root,'app/src/platform/ble.js')
 const ble=await moduleFrom(path,s=>s.replace("from './store.js'",'from '+JSON.stringify(storeUrl)).replace(/require\(([^)]+)\)/g,'globalThis.bleNative($1)'))
 try {
  const code=await ble.start((m,p,state)=>reported.push({m,state}));assert.match(code,/^[0-9]{6}$/);assert.match(reported[0].m,/已请求广播/)
  callbacks.ConnectStateChange({deviceId:'phone-a',transId:2})
  callbacks.DescriptorWrite({deviceId:'phone-a',descriptorUuid:'00002902-0000-1000-8000-00805f9b34fb',characteristicUuid:TX,offset:0,value:new Uint8Array([1,0]).buffer,needRsp:true})
  let paired=false
  const phone=new Link({sendRaw:b=>callbacks.CharacteristicWrite({deviceId:'phone-a',characteristicUuid:RX,offset:0,isPrep:false,needRsp:true,value:b.buffer}),memoryOnly:()=>!paired,deliver:async m=>{messages.push(m);if(m.type==='READY')paired=true},error:e=>assert.fail(e.message)})
  phone.enqueue(2,{type:'HELLO',protocol:1,nonce:'native-handshake',code,transport:{chunk:100,lz4:true,consent:1}})
  for(let i=0;i<200;i++){await new Promise(r=>setImmediate(r));while(pending.length)await phone.receive(pending.shift());if(paired&&!phone.current)break}
  assert.equal(paired,true);assert.deepEqual(messages.map(m=>m.type),['READY']);assert.equal(globalThis.bleSaveCalls,0)
  callbacks.ConnectStateChange({deviceId:'phone-b',state:0});assert.equal(ble.status().paired,true)
  callbacks.ConnectStateChange({deviceId:'phone-a',state:2});assert.equal(ble.status().paired,true)
  callbacks.ConnectStateChange({deviceId:'phone-a',state:0});assert.equal(ble.status().paired,false)
 } finally {ble.stop();delete globalThis.bleTestDB;delete globalThis.bleNative;delete globalThis.bleSaveCalls}
})

test('two daily-summary consumers have independent timers, subscriptions and cache-first display',async()=>{
 const {snapshot}=await import('../app/src/core/engine.js')
 const now=Date.now(),value=snapshot(sample(),now,'one',60),order=[],unsubscribed=[];let nextId=0
 globalThis.readerStorage={get:({success})=>{order.push('cache');success({value})},set:()=>{}}
 globalThis.readerExchange={get:({success})=>{order.push('exchange');success({value})}}
 globalThis.readerEvents={subscribe:()=>++nextId,unsubscribe:({id})=>unsubscribed.push(id)}
 const module=await moduleFrom(resolve(root,'app/src/platform/snapshot-reader.js'),s=>s.replace("import storage from '@system.storage'",'const storage=globalThis.readerStorage').replace("import exchange from '@service.exchange'",'const exchange=globalThis.readerExchange').replace("import event from '@system.event'",'const event=globalThis.readerEvents'))
 const seenA=[],seenB=[],a=new module.SnapshotReader(v=>seenA.push(v)),b=new module.SnapshotReader(v=>seenB.push(v))
 try{a.start();b.start();assert.equal(order[0],'cache');assert.equal(seenA[0].version,'one');assert.equal(seenB[0].version,'one');assert.notEqual(a.timer,b.timer);a.stop();assert.equal(a.running,false);assert.equal(b.running,true);assert.deepEqual(unsubscribed,[1]);b.refresh();assert.equal(b.cached.version,'one')}finally{a.stop();b.stop();delete globalThis.readerStorage;delete globalThis.readerExchange;delete globalThis.readerEvents}
})
