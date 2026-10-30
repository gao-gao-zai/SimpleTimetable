import { crc32Utf8Update } from './codec.js'
import { EngineCache, snapshot } from './engine.js'
import { offsetSeconds } from './clock.js'

export function* canonicalBlocks(value, blockSize = 2048) {
  if (!Number.isInteger(blockSize) || blockSize < 2) throw Error('Invalid canonical block size')
  const stack = [[value, false]], parts = []
  let length = 0
  while (stack.length) {
    const entry = stack.pop()
    let token
    if (typeof entry === 'string') token = entry
    else {
      const v = entry[0]
      if (Array.isArray(v)) {
        stack.push(']')
        for (let i=v.length-1;i>=0;i--) { stack.push([v[i],true]);if(i)stack.push(',') }
        stack.push('[');continue
      }
      if (v && typeof v === 'object') {
        stack.push('}');const keys=Object.keys(v).sort()
        for (let i=keys.length-1;i>=0;i--) { stack.push([v[keys[i]],false],JSON.stringify(keys[i])+':');if(i)stack.push(',') }
        stack.push('{');continue
      }
      token=JSON.stringify(v)
      if(token===undefined)token=entry[1]?'':'undefined'
    }
    for (let from=0;from<token.length;) {
      let end=Math.min(token.length,from+blockSize-length)
      if(end<token.length&&token.charCodeAt(end-1)>=0xd800&&token.charCodeAt(end-1)<=0xdbff)end--
      if(end>from){parts.push(token.slice(from,end));length+=end-from;from=end}
      if(length===blockSize||from<token.length&&blockSize-length<2){yield parts.join('');parts.length=0;length=0}
    }
  }
  if(length)yield parts.join('')
}

export const pauseTask = () => new Promise(resolve => setTimeout(resolve, 0))
export async function sharedRevision(profile, { pause = pauseTask, now = () => Date.now(), active = () => true, metrics } = {}) {
  let crc = -1, units = 0, started = now()
  if(metrics){metrics.yields=0;metrics.maxContinuousMs=0}
  for (const block of canonicalBlocks(profile)) {
    if (!active()) throw Error('共享更新已取消')
    crc = crc32Utf8Update(block, crc); units += block.length
    if (units >= 8192 || now() - started >= 8) {
      if(metrics){metrics.maxContinuousMs=Math.max(metrics.maxContinuousMs,now()-started);metrics.yields++}
      await pause(); units = 0; started = now()
    }
  }
  if (!active()) throw Error('共享更新已取消')
  if(metrics)metrics.maxContinuousMs=Math.max(metrics.maxContinuousMs,now()-started)
  return ((crc ^ -1) >>> 0).toString(16).padStart(8, '0')
}

// Only committed immutable profile references may enter this cache.
export class SharedSnapshot {
  constructor(options = {}) { this.options = options; this.profile = null; this.version = ''; this.cache = new EngineCache(); this.revisionBuilds = 0 }
  async build(profile, settings, realNow, timing = {}) {
    const now=this.options.now||Date.now,from=now()
    timing.revisionCacheHit=this.profile===profile
    timing.revisionWork={yields:0,maxContinuousMs:0}
    let version = this.version
    if (this.profile !== profile) {
      version = await sharedRevision(profile, {...this.options,metrics:timing.revisionWork})
      this.profile = profile; this.version = version; this.revisionBuilds++
    }
    timing.revisionMs=now()-from
    const start=now(),value=snapshot(profile, realNow === undefined ? now() : realNow, version, offsetSeconds(settings), this.cache)
    timing.snapshotMs=now()-start
    return value
  }
}

export function createPublisher({ write, emit, now = () => Date.now(), ...options }) {
  const builder = new SharedSnapshot({ ...options, now })
  let tail = Promise.resolve()
  return (profile, settings, report = () => {}) => {
    const run = async () => {
      const timing={},started=now(),value=await builder.build(profile,settings,undefined,timing)
      const writeAt=now(),pending=write(value);timing.writeSubmitMs=now()-writeAt
      await pending;timing.writeCompleteMs=now()-writeAt
      const eventAt=now();emit(value);timing.eventMs=now()-eventAt;timing.totalMs=now()-started
      report(timing);return value
    }
    const result = tail.then(run); tail = result.catch(() => {}); return result
  }
}
