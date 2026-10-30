// Independent LZ4 block implementation. No frame headers or external dependencies.
// Format: https://github.com/lz4/lz4/blob/dev/doc/lz4_Block_format.md
export function compress(input) {
  const b=new Uint8Array(input), out=[], table=new Int32Array(65536);table.fill(-1)
  const length=n=>{while(n>=255){out.push(255);n-=255}out.push(n)}
  const hash=i=>Math.imul((b[i]|b[i+1]<<8|b[i+2]<<16|b[i+3]<<24),-1640531535)>>>16
  let anchor=0,i=0
  while(i<=b.length-12) {
    const h=hash(i),ref=table[h];table[h]=i
    if(ref<0||i-ref>65535||b[ref]!==b[i]||b[ref+1]!==b[i+1]||b[ref+2]!==b[i+2]||b[ref+3]!==b[i+3]){i++;continue}
    let match=4;while(i+match<b.length-5&&b[ref+match]===b[i+match])match++
    const literals=i-anchor,ml=match-4
    out.push(Math.min(literals,15)<<4|Math.min(ml,15));if(literals>=15)length(literals-15)
    for(let p=anchor;p<i;p++)out.push(b[p])
    const offset=i-ref;out.push(offset&255,offset>>8);if(ml>=15)length(ml-15)
    i+=match;anchor=i
  }
  const literals=b.length-anchor;out.push(Math.min(literals,15)<<4);if(literals>=15)length(literals-15)
  for(let p=anchor;p<b.length;p++)out.push(b[p])
  return new Uint8Array(out)
}
export function* decompressSteps(input, limit=65536, budget=4096) {
  const b=new Uint8Array(input),out=new Uint8Array(limit);let i=0,n=0,worked=0
  const length=initial=>{let value=initial;if(initial===15){let x;do{if(i>=b.length)throw Error('Truncated LZ4 length');x=b[i++];value+=x;if(value>limit)throw Error('LZ4 expansion limit')}while(x===255)}return value}
  if(!b.length)throw Error('Empty LZ4 block')
  while(i<b.length) {
    const token=b[i++],literal=length(token>>4)
    if(i+literal>b.length||n+literal>limit)throw Error('Invalid LZ4 literal')
    for(let end=i+literal;i<end;){out[n++]=b[i++];if(++worked>=budget){worked=0;yield n}}
    if(i===b.length)return out.slice(0,n)
    if(i+2>b.length)throw Error('Truncated LZ4 offset')
    const offset=b[i++]|b[i++]<<8;if(!offset||offset>n)throw Error('Invalid LZ4 offset')
    const match=length(token&15)+4;if(n+match>limit)throw Error('LZ4 expansion limit')
    for(let end=n+match;n<end;){out[n]=out[n-offset];n++;if(++worked>=budget){worked=0;yield n}}
    if(i===b.length)throw Error('LZ4 final literals missing')
  }
  throw Error('Invalid LZ4 block')
}
export function decompress(input,limit=65536) {const steps=decompressSteps(input,limit);let step;do{step=steps.next()}while(!step.done);return step.value}
