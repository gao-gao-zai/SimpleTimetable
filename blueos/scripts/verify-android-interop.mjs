import {readFileSync} from 'node:fs'
import assert from 'node:assert/strict'
import {Receiver} from '../app/src/core/transport.js'
const vectors=JSON.parse(readFileSync(new URL('../../app/build/interop/kotlin-transfers.json',import.meta.url)))
for(const vector of vectors){let count=0;const receiver=new Receiver(null,async()=>{},async m=>{assert.deepEqual(m,vector.message);count++},{memory:true,pause:async()=>{}});for(const frame of vector.frames)await receiver.receive(frame);assert.equal(count,1)}
console.log(`Decoded ${vectors.length} Kotlin-generated streams in JavaScript; reciprocal test ran in CompressionTest.`)
