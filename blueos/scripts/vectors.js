import { writeFileSync } from 'node:fs'
import { crc16, crc32, utf8, revision } from '../app/src/core/codec.js'
import { frame, OP } from '../app/src/core/transport.js'
const messages = [{ type:'HELLO', protocol:1, nonce:'synthetic-hello', code:'123456' }, { b:2, a:'合成😀 <>& " \\ \u2028' }]
const data = { crc:{text:'123456789',crc16:crc16(utf8('123456789')),crc32:crc32(utf8('123456789'))}, frames:[{op:OP.DATA,id:42,seq:2,data:[0,1,127,128,255]},{op:OP.ACK,id:65535,seq:1,data:[]}].map(v=>({...v,hex:Array.from(frame(v.op,v.id,v.seq,v.data)).map(n=>n.toString(16).padStart(2,'0')).join('')})), revisions:messages.map(message=>({message,revision:revision(message)})) }
writeFileSync(new URL('../../protocol/vectors.json',import.meta.url),JSON.stringify(data,null,2)+'\n')
