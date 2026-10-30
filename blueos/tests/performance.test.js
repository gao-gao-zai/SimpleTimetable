import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {pathToFileURL} from 'node:url'
import {resolve,dirname} from 'node:path'
import {crc32,utf8,crc32Utf8,crc32Utf8Update,canonical,revision} from '../app/src/core/codec.js'
import {Durable} from '../app/src/core/durable.js'
import {StartupTrace} from '../app/src/core/performance.js'
import {SharedSnapshot,sharedRevision,canonicalBlocks,createPublisher} from '../app/src/core/shared-snapshot.js'
import {sample,validate,lessonChange} from '../app/src/core/model.js'
import {snapshot,clone} from '../app/src/core/engine.js'
import {WatchUI} from '../app/src/core/ui.js'
import {finalize} from '../app/src/core/finalize.js'
const time=s=>new Date('2026-10-19T'+s).getTime()
test('allocation-free UTF-8 CRC matches byte codec for all Unicode widths and rejects malformed UTF-16',()=>{
 const values=['','123456789','ASCII\u0000\n','中文😀<>&\u2028','\u007f\u0080\u07ff\u0800\ud7ff\ue000\uffff\u{10000}\u{10ffff}']
 let seed=21;for(let i=0;i<300;i++){let s='';for(let j=0;j<50;j++){seed=(Math.imul(seed,1664525)+1013904223)>>>0;let c=seed%0x110000;if(c>=0xd800&&c<=0xdfff)c=32;s+=String.fromCodePoint(c)}values.push(s)}
 for(const s of values){const info={};assert.equal(crc32Utf8(s,info),crc32(utf8(s)));assert.equal(info.bytes,utf8(s).length)}
 for(const s of ['\ud800','\udc00','x\ud800a'])assert.throws(()=>crc32Utf8(s),/UTF-16/)
 const a='中文',b='😀end';assert.equal((crc32Utf8Update(b,crc32Utf8Update(a))^-1)>>>0,crc32(utf8(a+b)))
})
test('streamed canonical blocks preserve canonical revision, escapes and surrogate boundaries',async()=>{
 for(const v of [{z:'😀'.repeat(3000),a:'\u2028中文',b:[null,true,1.25,{key:'"\\\n'}]},sample(),{a:undefined,b:[undefined,2]}]){
  for(const size of [2,7,2048]){const blocks=[...canonicalBlocks(v,size)];assert.equal(blocks.join(''),canonical(v));assert.ok(blocks.every(b=>b.length<=size));blocks.forEach(b=>assert.doesNotThrow(()=>utf8(b)))}
  assert.equal(await sharedRevision(v,{pause:async()=>{}}),revision(v))
 }
 assert.throws(()=>[...canonicalBlocks({},1)])
})
test('first shared version yields bounded work and cancellation stops subsequent blocks',async()=>{
 const p=sample();p.future='中文😀'.repeat(12000);let pauses=0;assert.equal(await sharedRevision(p,{pause:async()=>pauses++,now:()=>0}),revision(p));assert.ok(pauses>1)
 let active=true,stops=0;await assert.rejects(sharedRevision(p,{now:()=>0,pause:async()=>{stops++;active=false},active:()=>active}),/取消/);assert.equal(stops,1)
})
test('shared summary caches only immutable profiles and uses current real time after async hashing',async()=>{
 const p=sample(),builder=new SharedSnapshot({pause:async()=>{}})
 const first=await builder.build(p,{},time('08:00:00'));assert.deepEqual(first,snapshot(p,time('08:00:00'),revision(p)))
 assert.deepEqual(await builder.build(p,{},time('08:01:00')),snapshot(p,time('08:01:00'),revision(p)));assert.equal(builder.revisionBuilds,1)
 const next=clone(p);next.subjects[0].name='修改后的合成科目';await builder.build(next,{},time('08:02:00'));assert.equal(builder.revisionBuilds,2)
 let clock=time('07:59:59');p.future='x'.repeat(20000);const crossing=new SharedSnapshot({now:()=>clock,pause:async()=>{clock=time('08:00:01')}})
 assert.equal((await crossing.build(p,{})).title,'示例数学');assert.equal((await crossing.build(p,{})).updatedAt,clock)
})
test('shared cache respects offset, midnight, temporary expiry and real validity limits',async()=>{
 const p=lessonChange(sample(),'2026-10-19','plan','slot-a','s-b');p.temporary[0].startMillis=time('08:00:00');p.temporary[0].endMillis=time('08:10:00')
 const b=new SharedSnapshot({pause:async()=>{}})
 for(const [real,offset] of [[time('07:59:30'),60],[time('08:09:30'),60],[time('08:00:30'),-60],[new Date('2026-10-19T23:59:30').getTime(),60]]){
  assert.deepEqual(await b.build(p,{timeOffsetSeconds:offset},real),snapshot(p,real,revision(p),offset))
 }
 assert.equal(b.revisionBuilds,1)
})
test('publisher serializes writes, emits only on success, and recovers after a failed write',async()=>{
 const first=sample(),second=clone(first);second.name='新的合成档案';const writes=[],emits=[];let release,fail=false
 const publish=createPublisher({pause:async()=>{},now:()=>time('08:00:00'),write:async value=>{writes.push(value.version);if(writes.length===1)await new Promise(r=>{release=r});if(fail)throw Error('disk full')},emit:value=>emits.push(value.version)})
 const a=publish(first,{}),b=publish(second,{})
 for(let i=0;i<100&&!release;i++)await Promise.resolve();assert.equal(writes.length,1);release();await Promise.all([a,b]);assert.deepEqual(writes,[revision(first),revision(second)]);assert.deepEqual(emits,writes)
 fail=true;await assert.rejects(publish(first,{}));assert.equal(emits.length,2);fail=false;await publish(second,{});assert.equal(emits.length,3)
})
test('serialized-at-call durable save keeps frozen values and rejects corrupted cold data',async()=>{
 const disk={},get=async k=>disk[k]||'',put=async(k,v)=>{disk[k]=v},d=new Durable('db',get,put),value={text:'中文😀',n:1}
 const saved=d.save(value);value.n=2;await saved;assert.deepEqual(await new Durable('db',get,put).load({}),{text:'中文😀',n:1})
 const raw=JSON.parse(disk['db.b']);raw.json=raw.json.replace('中文','篡改');disk['db.b']=JSON.stringify(raw);await assert.rejects(new Durable('db',get,put).load({}),/校验/)
})
test('cold startup trace separates IO, both JSON parses, CRC, payload bytes and cumulative boundaries',async()=>{
 const p=sample(),json=JSON.stringify({schema:1,profiles:[p]}),disk={'db.head':'a','db.a':JSON.stringify({json,crc:crc32(utf8(json))})};let clock=0;const trace=new StartupTrace(()=>clock)
 const d=new Durable('db',async k=>{clock+=k.endsWith('head')?37:16;return disk[k]},async()=>{})
 await d.load({},trace)
 const names=trace.rows().map(r=>r.label);for(const name of ['读取存储指针（独立计时）','读取存储正文（独立计时）','存储封套 JSON 解析（独立计时）','正文 JSON 解析（独立计时）','正文 CRC 校验（独立计时）','正文 UTF-8 字节数'])assert.ok(names.includes(name),name)
 assert.equal(trace.metrics.get('正文 UTF-8 字节数'),utf8(json).length+' B');assert.equal(trace.spans.find(s=>s.name==='读取存储指针').ms,37);assert.match(trace.rows()[0].sub,/累计/)
})
test('unchanged menu refresh retains rows while replacing stale callbacks with latest profile',async()=>{
 let db={profiles:[sample()],activeProfile:'unused',settings:{}};const ui=new WatchUI({db:()=>db,save:async()=>{},publish:()=>{}},()=>{})
 ui.go('subjects');const rows=ui.rows,actions=ui.actions;ui.message='新状态';ui.refresh();assert.equal(ui.rows,rows);assert.notEqual(ui.actions,actions)
 db=clone(db);db.profiles[0].subjects[0].teacher='新的合成教师';ui.refresh();assert.equal(ui.rows,rows)
 await ui.click(rows.find(r=>r.label==='示例数学').key);assert.equal(ui.item().teacher,'新的合成教师')
})
test('large menu action identifiers are unique and stable without enumerating prior actions',()=>{
 const p=sample();p.subjects=Array.from({length:500},(_,i)=>({...p.subjects[0],id:'s'+i,name:'合成科目'+i}));const db={profiles:[p],activeProfile:p.id,settings:{}}
 const ui=new WatchUI({db:()=>db},()=>{});ui.go('subjects');assert.equal(ui.rows.length,502);assert.equal(new Set(ui.rows.map(r=>r.key)).size,502);const first=ui.rows;ui.refresh();assert.equal(ui.rows,first)
})
test('indexed validation still rejects missing refs in every collection and temporary patch',()=>{
 const p=sample();for(const mutate of [q=>q.activeGroup='missing',q=>q.manualPlan='missing',q=>q.layouts[0].defaultSubject='missing',q=>q.layouts[0].slots[0].defaultSubject='missing',q=>q.plans[0].layoutId='missing',q=>q.plans[0].subjects['slot-a']='missing',q=>q.plans[0].disabledSlots=['missing']]){const v=clone(p);mutate(v);assert.throws(()=>validate(v))}
 const temp=lessonChange(p,'2026-10-19','plan','slot-a','s-b');temp.temporary[0].patches[0].slotId='missing';assert.throws(()=>validate(temp))
})
test('cancelled finalization stops at the first pending yield and never parses the whole message',async()=>{
 let active=true,pauses=0;await assert.rejects(finalize(utf8(JSON.stringify({text:'中文'.repeat(20000)})),0,{owned:true,active:()=>active,pause:async()=>{pauses++;active=false}}),/cancelled/);assert.equal(pauses,1)
})
test('actual Main page loads optional JS modules only after first binding or an explicit sync action',async()=>{
 const path=resolve(import.meta.dirname,'../app/src/pages/Main/index.ux'),source=readFileSync(path,'utf8').match(/<script>([\s\S]*?)<\/script>/)[1]
 const p=sample(),db={profiles:[p],activeProfile:p.id,settings:{theme:'dark'}},required=[];let stops=0
 globalThis.performanceStore={db,open:async()=>db,save:async()=>{},active:()=>p}
 globalThis.performanceRequire=name=>{required.push(name);if(name.endsWith('/sharing.js'))return {publish:async()=>({})};if(name.endsWith('/ble.js'))return {start:async()=> '123456',stop:()=>stops++,incomingRequest:()=>null};throw Error('Unexpected optional module '+name)}
 let script=source.replace("import * as store from '../../platform/store.js'",'const store=globalThis.performanceStore').replace(/require\(/g,'globalThis.performanceRequire(')
 script=script.replace(/from ['"](\.[^'"]+)['"]/g,(_,p)=>'from '+JSON.stringify(pathToFileURL(resolve(dirname(path),p)).href))
 const module=await import('data:text/javascript;base64,'+Buffer.from(script).toString('base64')),page={...module.default,...module.default.data}
 try {page.onInit();await page.onReady();assert.deepEqual(required,[]);assert.equal(page.home,true);assert.notEqual(page.course,'');await new Promise(r=>setTimeout(r,5));assert.deepEqual(required,['../../platform/sharing.js']);page.navigate('more');page.choose(page.rows.find(r=>r.label==='手机同步').key);await Promise.resolve();assert.equal(required.filter(r=>r.endsWith('/ble.js')).length,0);await page.choose(page.rows.find(r=>r.label==='开启同步').key);for(let i=0;i<5;i++)await Promise.resolve();assert.equal(required.filter(r=>r.endsWith('/ble.js')).length,1);page.onHide();assert.equal(stops,1)}finally{page.onDestroy();delete globalThis.performanceStore;delete globalThis.performanceRequire}
})

test('200 indexed/reference validation cases retain acceptance and error reasons',async()=>{
 const old=await import('./fixtures/validate-b894.js')
 const mutations=[()=>{},p=>p.activeGroup='missing',p=>p.manualPlan='missing',p=>p.subjects.push({...p.subjects[0]}),p=>p.plans[0].layoutId='missing',p=>p.plans[0].subjects['missing']='s-a',p=>p.plans[0].subjects['slot-a']='missing',p=>p.layouts[0].slots[0].defaultSubject='missing',p=>p.plans[0].week=53,p=>p.plans[0].anchor='2026-10-20',p=>p.layouts[0].slots[0].end=p.layouts[0].slots[0].start,p=>p.temporary[0].patches[0].slotId='missing',p=>p.temporary[0].patches[0].subjectId='missing',p=>p.temporary[0].patches[0].planId='missing']
 const outcome=(fn,p)=>{try{fn(p);return 'accepted'}catch(e){return e.message}}
 for(let i=0;i<200;i++){const p=lessonChange(sample(),'2026-10-19','plan','slot-a','s-b');if(i%3===0){for(let j=0;j<30;j++)p.subjects.push({...p.subjects[0],id:'extra-'+j})};mutations[i%mutations.length](p);assert.equal(outcome(validate,p),outcome(old.validate,p),'case '+i)}
})

test('publisher diagnostics separate native submission, completion wait and event work',async()=>{
 let clock=0,reported
 const publish=createPublisher({now:()=>clock,pause:async()=>{},write:()=>{clock+=5;return Promise.resolve().then(()=>{clock+=12})},emit:()=>{clock+=3}})
 await publish(sample(),{},timing=>{reported=timing})
 assert.equal(reported.writeSubmitMs,5);assert.equal(reported.writeCompleteMs,17);assert.equal(reported.eventMs,3);assert.equal(reported.totalMs,20);assert.equal(reported.revisionCacheHit,false)
 await publish(sample(),{},timing=>{reported=timing});assert.equal(reported.revisionCacheHit,false)
 const p=sample();await publish(p,{});await publish(p,{},timing=>{reported=timing});assert.equal(reported.revisionCacheHit,true)
})
test('actual sharing adapter defers native loading and preserves exchange-before-event ordering',async()=>{
 const path=resolve(import.meta.dirname,'../app/src/platform/sharing.js'),calls=[]
 globalThis.performanceSharingNative=name=>{calls.push(name);return name==='@service.exchange'?{set:({success})=>{calls.push('write');success()}}:{publish:()=>calls.push('event')}}
 let script=readFileSync(path,'utf8').replace(/require\(/g,'globalThis.performanceSharingNative(')
 script=script.replace(/from ['"](\.[^'"]+)['"]/g,(_,p)=>'from '+JSON.stringify(pathToFileURL(resolve(dirname(path),p)).href))
 const adapter=await import('data:text/javascript;base64,'+Buffer.from(script).toString('base64'))
 try{assert.deepEqual(calls,[]);const p=sample(),timings=[];await adapter.publish(p,{},v=>timings.push(v));await adapter.publish(p,{},v=>timings.push(v));assert.deepEqual(calls.slice(0,4),['@service.exchange','write','@system.event','event']);assert.equal(timings[0].revisionCacheHit,false);assert.equal(timings[1].revisionCacheHit,true)}finally{delete globalThis.performanceSharingNative}
})

test('optional shared-module load failure is contained after first course binding',async()=>{
 const path=resolve(import.meta.dirname,'../app/src/pages/Main/index.ux'),p=sample(),db={profiles:[p],activeProfile:p.id,settings:{theme:'dark'}}
 globalThis.failedShareStore={db,open:async()=>db,active:()=>p,save:async()=>{}}
 globalThis.failedShareRequire=()=>{throw Error('optional module unavailable')}
 let script=readFileSync(path,'utf8').match(/<script>([\s\S]*?)<\/script>/)[1].replace("import * as store from '../../platform/store.js'",'const store=globalThis.failedShareStore').replace(/require\(/g,'globalThis.failedShareRequire(')
 script=script.replace(/from ['"](\.[^'"]+)['"]/g,(_,p)=>'from '+JSON.stringify(pathToFileURL(resolve(dirname(path),p)).href))
 const module=await import('data:text/javascript;base64,'+Buffer.from(script).toString('base64')),page={...module.default,...module.default.data}
 try{page.onInit();await page.onReady();assert.equal(page.home,true);await new Promise(r=>setTimeout(r,5));assert.match(page.message,/optional module unavailable/);assert.equal(page.home,true)}finally{page.onDestroy();delete globalThis.failedShareStore;delete globalThis.failedShareRequire}
})
