import test from 'node:test'
import assert from 'node:assert/strict'
import { Durable } from '../app/src/core/durable.js'
function fs(){const disk={};let fail='';return {disk,setFail:s=>fail=s,get:async k=>disk[k]||'',put:async(k,v)=>{if(k===fail)throw Error('disk full');disk[k]=v}}}
test('failed inactive write preserves live timetable after restart',async()=>{const f=fs(),d=new Durable('db',f.get,f.put);await d.load({});await d.save({version:1});f.setFail('db.a');await assert.rejects(d.save({version:2}));assert.deepEqual(await new Durable('db',f.get,f.put).load({}),{version:1})})
test('crash before pointer update preserves previous commit',async()=>{const f=fs(),d=new Durable('db',f.get,f.put);await d.save({version:1});f.setFail('db.head');await assert.rejects(d.save({version:2}));assert.deepEqual(await new Durable('db',f.get,f.put).load({}),{version:1})})
test('checksum corruption is surfaced without silently replacing data',async()=>{const f=fs(),d=new Durable('db',f.get,f.put);await d.save({version:1});f.disk['db.b']=f.disk['db.b'].replace('version','corrupt');await assert.rejects(new Durable('db',f.get,f.put).load({version:0}));assert.match(f.disk['db.b'],/corrupt/)})
test('missing head with existing slot is a recoverable error, not empty data',async()=>{const f=fs(),d=new Durable('db',f.get,f.put);await d.save({version:1});delete f.disk['db.head'];await assert.rejects(new Durable('db',f.get,f.put).load({version:0}))})
