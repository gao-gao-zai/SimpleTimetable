export class Session {
  constructor(){this.peer='';this.subscribed=false;this.paired=false;this.lastStateField=''}
  connection(event){
    const field=Number.isInteger(event.state)?'state':Number.isInteger(event.transId)?'transId':''
    const state=field?event[field]:-1,peer=event.deviceId
    if(state<0||state>3||!peer||this.peer&&this.peer!==peer)return 'ignore'
    this.lastStateField=field
    if(state===2){if(!this.peer)this.peer=peer;return 'connected'}
    if(state===0){this.reset();return 'disconnected'}
    return 'pending'
  }
  reset(){this.peer='';this.subscribed=false;this.paired=false}
}
export function capabilities(remote, mtu=113) {
  return {chunk:remote&&remote.chunk===100&&mtu>=113?100:10,lz4:!!(remote&&remote.lz4),extended:!!remote}
}
