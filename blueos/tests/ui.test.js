import test from 'node:test'
import assert from 'node:assert/strict'
import { WatchUI } from '../app/src/core/ui.js'
import { sample } from '../app/src/core/model.js'
import { clone } from '../app/src/core/engine.js'
import { Link } from '../app/src/core/link.js'
import { OP, decode } from '../app/src/core/transport.js'
function setup() {
 const p=sample(); let db={schema:1,profiles:[p],activeProfile:p.id,settings:{theme:'dark',largeText:false}};let renders=0
 const ui=new WatchUI({db:()=>db,save:async f=>{const n=clone(db);f(n);db=n},publish:()=>{}},()=>renders++)
 ui.refresh(); return {ui,db:()=>db,p}
}
async function press(ui,label){const r=ui.rows.find(r=>r.label===label);assert.ok(r,'missing row '+label);await ui.click(r.key)}
test('native editor new subject draft, preset, confirmed save',async()=>{
 const {ui,db}=setup();ui.go('subjects');await press(ui,'新建');assert.equal(ui.view,'edit');assert.equal(db().profiles[0].subjects.length,2)
 await press(ui,'名称：新科目');await press(ui,'数学');await press(ui,'保存并确认');assert.equal(ui.view,'confirm');await press(ui,'确认');assert.equal(db().profiles[0].subjects.length,3);assert.equal(db().profiles[0].subjects[2].name,'数学')
})
test('discard leaves original timetable untouched',async()=>{const {ui,db}=setup();ui.newItem('layouts');await press(ui,'放弃/返回');await press(ui,'确认');assert.equal(ui.draft,null);assert.equal(db().profiles[0].layouts.length,1)})
test('referenced item deletion blocked before storage',async()=>{const {ui,db,p}=setup();ui.begin('subjects',p.subjects[0]);await press(ui,'删除（引用保护）');await press(ui,'确认');assert.match(ui.message,/引用缺失/);assert.equal(db().profiles[0].subjects.length,2)})
test('explicit activation applies only on confirmed save',async()=>{const {ui,db,p}=setup();ui.begin('plans',p.plans[0]);await press(ui,'显式启用（保存后）');assert.equal(db().profiles[0].manualPlan,'');await press(ui,'保存并确认');await press(ui,'确认');assert.equal(db().profiles[0].manualPlan,'plan')})
test('three-column time increments seconds independently',()=>{const {ui}=setup();let value=0;ui.chooseTime('time',8*3600,v=>value=v,'home');ui.timeDelta(2,1);assert.equal(ui.timeEdit.value,28801);ui.timeDelta(1,1);assert.equal(ui.timeEdit.value,28861)})
test('link retries dropped ACK and times out after limit',async()=>{let now=0,sent=0,error='';const l=new Link({sendRaw:()=>sent++,load:async()=>null,save:async()=>{},deliver:async()=>{},error:e=>error=e.message,now:()=>now});l.enqueue(1,{synthetic:true});for(let i=0;i<6;i++){now+=2001;l.tick()}assert.equal(sent,6);assert.equal(l.online,false);assert.match(error,/中断/)})
test('full duplex links deliver with lost ACK and durable restart state',async()=>{
 let now=0;const qa=[],qb=[],diskA={},diskB={};let gotA=0,gotB=0,drop=true
 const a=new Link({sendRaw:p=>qb.push(p),load:async n=>diskA[n],save:async(n,s)=>{diskA[n]=clone(s)},deliver:async()=>gotA++,error:e=>assert.fail(e.message),now:()=>now})
 const b=new Link({sendRaw:p=>{if(drop&&decode(p).op===OP.ACK){drop=false;return};qa.push(p)},load:async n=>diskB[n],save:async(n,s)=>{diskB[n]=clone(s)},deliver:async()=>gotB++,error:e=>assert.fail(e.message),now:()=>now})
 a.enqueue(10,{direction:'a',text:'合成'});b.enqueue(20,{direction:'b'})
 for(let i=0;i<200;i++){while(qa.length)await a.receive(qa.shift());while(qb.length)await b.receive(qb.shift());now+=2100;a.tick();b.tick();if(!a.current&&!b.current)break}
 assert.equal(gotA,1);assert.equal(gotB,1)
})
