import {writeFileSync} from 'node:fs'
import {Sender,decode,frame,OP} from '../app/src/core/transport.js'
const messages=[{type:'synthetic',protocol:1,nonce:'interop',text:'合成中文😀'.repeat(100),escaped:'\u2028 <>& " \\'},{type:'synthetic',protocol:1,nonce:'small',text:'tiny'}]
const vectors=[]
for(const options of [{chunk:100,lz4:true,extended:true},{chunk:10,lz4:true,extended:true},{chunk:100,lz4:false,extended:true},{}])for(const message of messages){const sender=new Sender(42,message,options),frames=[];while(sender.packet()){const packet=sender.packet();frames.push(Array.from(packet));const f=decode(packet);sender.cursor=f.seq===0?1:f.seq+1}vectors.push({message,options,frames})}
writeFileSync(new URL('../../protocol/transfer-vectors.json',import.meta.url),JSON.stringify(vectors)+'\n')
