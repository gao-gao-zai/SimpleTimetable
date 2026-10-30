// Synthetic repeatable comparison. Neither Node mode is the BlueOS runtime.
import {execFileSync} from 'node:child_process'
import {mkdtempSync,writeFileSync,mkdirSync,rmSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {join,dirname} from 'node:path'
import {fileURLToPath,pathToFileURL} from 'node:url'
import {performance} from 'node:perf_hooks'
const BASE='b894c0d8a8017b52519f50e7276e59b9a0f068d8',root=fileURLToPath(new URL('../../',import.meta.url)),scratch=mkdtempSync(join(tmpdir(),'timetable-perf-'))
const results=[]
function median(values){return values.slice().sort((a,b)=>a-b)[Math.floor(values.length/2)]}
async function bench(name,before,after,count=20){
 for(let i=0;i<3;i++){await before();await after()}
 const a=[],b=[]
 for(let round=0;round<7;round++){
  const measure=async fn=>{const start=performance.now();for(let i=0;i<count;i++)await fn();return (performance.now()-start)/count}
  if(round%2){b.push(await measure(after));a.push(await measure(before))}else{a.push(await measure(before));b.push(await measure(after))}
 }
 const baseline=median(a),current=median(b);results.push({name,baseline_ms:baseline,current_ms:current,speedup:baseline/current,batches:7,iterations_per_batch:count})
}
try{
 const paths=execFileSync('git',['-C',root,'ls-tree','-r','--name-only',BASE,'blueos/app/src/core'],{encoding:'utf8'}).trim().split('\n')
 for(const path of paths){const p=join(scratch,path);mkdirSync(dirname(p),{recursive:true});writeFileSync(p,execFileSync('git',['-C',root,'show',BASE+':'+path]))}
 writeFileSync(join(scratch,'package.json'),'{"type":"module"}\n')
 const oldCodec=await import(pathToFileURL(join(scratch,'blueos/app/src/core/codec.js'))),oldModel=await import(pathToFileURL(join(scratch,'blueos/app/src/core/model.js'))),oldDurable=await import(pathToFileURL(join(scratch,'blueos/app/src/core/durable.js'))),oldUI=await import(pathToFileURL(join(scratch,'blueos/app/src/core/ui.js'))),oldEngine=await import(pathToFileURL(join(scratch,'blueos/app/src/core/engine.js')))
 const codec=await import('../app/src/core/codec.js'),model=await import('../app/src/core/model.js'),durable=await import('../app/src/core/durable.js'),uiModule=await import('../app/src/core/ui.js'),shared=await import('../app/src/core/shared-snapshot.js')
 const profile=model.sample();profile.id='synthetic-benchmark';profile.name='合成性能基准（非用户数据）'
 profile.subjects=Array.from({length:100},(_,i)=>({...profile.subjects[0],id:'s'+i,name:'合成科目 '+i+' 中文😀',teacher:'合成教师 '+i}))
 profile.layouts[0].slots=Array.from({length:24},(_,i)=>({id:'slot'+i,name:'第 '+i+' 节',start:21600+i*900,end:22200+i*900,type:'LESSON',defaultSubject:''}))
 profile.plans=Array.from({length:8},(_,i)=>({...profile.plans[0],id:'plan'+i,subjects:Object.fromEntries(profile.layouts[0].slots.map((s,j)=>[s.id,'s'+((i+j)%100)]))}))
 model.validate(profile);oldModel.validate(profile)
 const text='中文😀ABCD'.repeat(2000),json=JSON.stringify({schema:1,profiles:Array.from({length:4},(_,i)=>({...profile,id:'profile'+i})),activeProfile:'profile0',settings:{},common:{},receipts:[],pending:null}),raw=JSON.stringify({json,crc:oldCodec.crc32(oldCodec.utf8(json))})
 const get=async key=>key.endsWith('head')?'a':raw
 await bench('UTF-8 CRC: 16000 UTF-16 units / 28000 B',()=>oldCodec.crc32(oldCodec.utf8(text)),()=>codec.crc32Utf8(text),50)
 await bench('cold archive load: in-memory IO, complete CRC + JSON checks',()=>new oldDurable.Durable('db',get,async()=>{}).load({}),()=>new durable.Durable('db',get,async()=>{}).load({}),15)
 const value={text,profiles:[profile]}
 await bench('durable save: freeze, encode, checksum and readback',async()=>{const disk={};return new oldDurable.Durable('db',async k=>disk[k],async(k,v)=>{disk[k]=v}).save(value)},async()=>{const disk={};return new durable.Durable('db',async k=>disk[k],async(k,v)=>{disk[k]=v}).save(value)},15)
 await bench('profile reference validation: 100 subjects / 8 plans / 24 slots',()=>oldModel.validate(profile),()=>model.validate(profile),30)
 const large=structuredClone(profile);large.subjects=Array.from({length:500},(_,i)=>({...profile.subjects[0],id:'s'+i,name:'合成科目'+i}));const db={profiles:[large],activeProfile:large.id,settings:{}}
 const api={db:()=>db,now:()=>new Date('2026-10-19T08:00:00').getTime()};const oldMenu=new oldUI.WatchUI(api,()=>{}),newMenu=new uiModule.WatchUI(api,()=>{});oldMenu.go('subjects');newMenu.go('subjects')
 await bench('menu refresh: 500 subjects, unchanged visual rows',()=>oldMenu.refresh(),()=>newMenu.refresh(),15)
 const now=new Date('2026-10-19T08:00:00').getTime(),baselineShare=()=>oldEngine.snapshot(profile,now,oldCodec.revision(profile))
 await bench('first shared summary: compute only, pause hook is a resolved promise',baselineShare,()=>new shared.SharedSnapshot({pause:async()=>{}}).build(profile,{},now),15)
 const builder=new shared.SharedSnapshot({pause:async()=>{}});await builder.build(profile,{},now)
 await bench('repeat shared summary: immutable profile cache, same synthetic day',baselineShare,()=>builder.build(profile,{},now),30)
 const counts={}
 for(const [name,UI]of[['baseline',oldUI.WatchUI],['current',uiModule.WatchUI]]){const instance=new UI(api,()=>{});instance.go('subjects');let count=0;const original=Object.keys;Object.keys=function(...args){count++;return original(...args)};try{instance.refresh()}finally{Object.keys=original};counts[name]={object_key_enumerations_per_menu_refresh:count}}
 for(const [name,Store]of[['baseline',oldDurable.Durable],['current',durable.Durable]]){const disk={},originalParse=JSON.parse,originalStringify=JSON.stringify;let parses=0,stringifies=0;JSON.parse=function(...a){parses++;return originalParse(...a)};JSON.stringify=function(...a){stringifies++;return originalStringify(...a)};try{await new Store('db',async k=>disk[k],async(k,v)=>{disk[k]=v}).save(value)}finally{JSON.parse=originalParse;JSON.stringify=originalStringify};Object.assign(counts[name],{parse_calls_per_durable_save:parses,stringify_calls_per_durable_save:stringifies})}
 let sliceStart=performance.now(),maxSlice=0,yields=0;const split=new shared.SharedSnapshot({pause:async()=>{maxSlice=Math.max(maxSlice,performance.now()-sliceStart);yields++;sliceStart=performance.now()}});await split.build(profile,{},now);maxSlice=Math.max(maxSlice,performance.now()-sliceStart)
 const report={base:BASE,runtime:process.version,exec_argv:process.execArgv,environment:'Synthetic Node/V8 only; no actual BlueOS IO, paint or hardware measurement',fixtures:{text_utf16_units:text.length,text_utf8_bytes:codec.utf8(text).length,profile_utf8_bytes:codec.utf8(JSON.stringify(profile)).length,archive_payload_utf8_bytes:codec.utf8(json).length},results,operation_counts:counts,first_share_slices:{yield_count:yields,max_observed_compute_slice_ms:maxSlice,note:'One synthetic run; not a bound on GT2 execution time or native rendering'}}
 console.log(JSON.stringify(report,null,2))
}finally{rmSync(scratch,{recursive:true,force:true})}
