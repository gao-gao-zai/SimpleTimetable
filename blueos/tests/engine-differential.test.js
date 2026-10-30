import test from 'node:test'
import assert from 'node:assert/strict'
import {resolve,clone} from '../app/src/core/engine.js'
import {resolve as baseline} from './fixtures/engine-v010.js'
import {sample,lessonChange} from '../app/src/core/model.js'
const day='2026-10-19',at=s=>new Date(day+'T'+s).getTime()
function coverage(r){const result=[];for(let sec=0;sec<86400;sec+=30){result.push(r.slots.filter(s=>s.type==='BREAK'&&sec>=s.start&&sec<s.end).map(s=>s.id).sort().join(','))}return result}
test('200 deterministic rule/layout cases match baseline courses, conflicts and break coverage',()=>{
 for(let i=0;i<200;i++){
  const p=lessonChange(sample(),day,'plan','slot-a',i%3?'s-b':'')
  p.temporary[0].startMillis=at('08:00:00')+(i%30)*60000;p.temporary[0].endMillis=p.temporary[0].startMillis+300000+(i%4)*60000
  p.layouts[0].slots.push({id:'break',name:'课间',type:'BREAK',start:31500,end:32100,defaultSubject:''})
  for(let j=0;j<i%9;j++){const layout=clone(p.layouts[0]);layout.id='inactive-'+j;layout.slots=layout.slots.map(s=>({...s,start:s.start+j*17,end:s.end+j*17}));p.layouts.push(layout)}
  if(i%5===0){const q=clone(p.plans[0]);q.id='conflict';p.plans.push(q)}
  if(i%7===0){const l=clone(p.layouts[0]);l.id='layer';l.slots=[{...l.slots[0],id:'layer-slot',start:29400,end:30600}];p.layouts.push(l);const q={...clone(p.plans[0]),id:'layer-plan',layoutId:'layer',layerOnly:true,subjects:{'layer-slot':'s-b'}};p.plans.push(q);p.temporary.push({id:'layer-temp',type:'LAYER',targetId:q.id,startMillis:at('08:05:00'),endMillis:at('08:25:00'),patches:[]})}
  const now=at('08:15:00'),a=resolve(p,day,now),b=baseline(p,day,now)
  assert.deepEqual(a.lessons,b.lessons,'lessons case '+i);assert.equal(a.conflicts,b.conflicts,'conflicts case '+i);assert.deepEqual(coverage(a),coverage(b),'breaks case '+i)
 }
})
