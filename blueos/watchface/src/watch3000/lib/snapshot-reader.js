import storage from '@system.storage'
import exchange from '@service.exchange'
import event from '@system.event'
import { validSnapshot, snapshotView } from './snapshot.js'
const KEY = 'io.github.gaogaoza.simpletimetable.snapshot.v1'
const EVENT = 'io.github.gaogaoza.simpletimetable.updated.v1'
const CACHE = 'simpletimetable.snapshot.cache.v1'
export class SnapshotReader {
  constructor(update) { this.update=update;this.cached=null;this.eventId=null;this.timer=null;this.displayTimer=null;this.running=false;this.generation=0;this.last='' }
  valid(v) { return validSnapshot(v) }
  refresh() {
    if(!this.running)return
    const generation=this.generation
    exchange.get({scope:'vendor',key:KEY,success:result=>{const v=result&&typeof result==='object'&&'value' in result?result.value:result;
      if(!this.running||generation!==this.generation)return
      if(this.valid(v)&&(!this.cached||v.updatedAt>=this.cached.updatedAt)) {
        const changed=!this.cached||JSON.stringify(v)!==JSON.stringify(this.cached)
        this.cached=v;if(changed)storage.set({key:CACHE,value:v});this.show()
      }
    },fail:()=>{if(this.running&&generation===this.generation)this.show()}})
    this.show()
  }
  show() {
    const value=snapshotView(this.cached,Date.now()),signature=JSON.stringify(value)
    if(signature!==this.last){this.last=signature;this.update(value)}
  }
  start() {
    if(this.running)return
    this.running=true;const generation=++this.generation
    // Prefer the local cache, then ask exchange for a newer daily summary.
    const read=()=>{if(this.running&&generation===this.generation){this.show();this.refresh()}}
    storage.get({key:CACHE,success:result=>{const v=result&&typeof result==='object'&&'value' in result?result.value:result;if(!this.running||generation!==this.generation)return;if(this.valid(v)&&!this.cached)this.cached=v;read()},fail:read})
    this.eventId=event.subscribe({eventName:EVENT,callback:r=>{if(this.running&&r.package==='com.ggz.app.simpletimetable')this.refresh()}})
    this.timer=setInterval(()=>this.refresh(),60000)
    this.displayTimer=setInterval(()=>this.show(),1000)
  }
  stop() {this.running=false;++this.generation;if(this.timer)clearInterval(this.timer);if(this.displayTimer)clearInterval(this.displayTimer);this.timer=null;this.displayTimer=null;if(this.eventId!==null)event.unsubscribe({id:this.eventId});this.eventId=null}
}
