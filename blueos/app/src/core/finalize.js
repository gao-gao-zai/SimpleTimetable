import { text } from './codec.js'
import { decompressSteps } from './lz4.js'
export const yieldTask = () => new Promise(resolve => setTimeout(resolve,0))
export async function finalize(bytes,codec,{pause=yieldTask,stage=()=>{},decoded=()=>{}}={}) {
  stage('解压');let raw=new Uint8Array(bytes)
  if(codec===1){const iterator=decompressSteps(raw);let step;do{step=iterator.next();if(!step.done)await pause()}while(!step.done);raw=step.value}
  else if(codec!==0)throw Error('Unsupported compression')
  decoded(raw.length);stage('解码');const parts=[]
  for(let start=0;start<raw.length;){let end=Math.min(start+2048,raw.length);if(end<raw.length){while(end>start&&(raw[end]&192)===128)end--;if(end===start)throw Error('Invalid UTF-8')};parts.push(text(raw.slice(start,end)));start=end;await pause()}
  stage('验证');let message;try{message=JSON.parse(parts.join(''))}catch(e){throw Error('同步消息 JSON 无效')}await pause();return message
}
