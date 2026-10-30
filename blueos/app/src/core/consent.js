// Consent is session memory. A reconnect never grants permission to replay a body.
export class Consent {
  constructor(now=()=>Date.now()){this.now=now;this.outgoing=null;this.incoming=null;this.phase='idle'}
  request(message,summary){
    if(this.outgoing||this.incoming)throw Error('请先完成或取消当前同步')
    const request={type:'REQUEST',requestId:message.nonce,profileId:message.profile.id,name:message.profile.name,revision:message.revision,bytes:summary.bytes,counts:summary.counts}
    this.outgoing={message,request,expires:this.now()+60000};this.phase='waiting-consent';return request
  }
  receive(request){
    if(!request||typeof request.requestId!=='string'||!request.requestId||typeof request.profileId!=='string'||typeof request.revision!=='string'||typeof request.name!=='string'||!Number.isInteger(request.bytes)||request.bytes<1||request.bytes>65536||request.profile)throw Error('接收请求无效或档案超过 64 KiB')
    if(this.incoming&&this.incoming.request.requestId===request.requestId)return
    if(this.incoming||this.outgoing)throw Error('当前已有同步，请先取消')
    this.incoming={request,accepted:false,expires:this.now()+60000};this.phase='confirm-receive'
  }
  respond(accepted){const pending=this.incoming;if(!pending)throw Error('没有待确认接收');if(this.now()>pending.expires){this.clear('consent-timeout');throw Error('接收确认已超时')};pending.accepted=accepted;this.phase=accepted?'receiving':'rejected';if(!accepted)this.incoming=null;return {type:'RESPONSE',requestId:pending.request.requestId,accepted}}
  response(message){const pending=this.outgoing;if(!pending||message.requestId!==pending.request.requestId)throw Error('接收确认已失效');if(this.now()>pending.expires){this.clear('consent-timeout');throw Error('接收确认已超时')};if(message.accepted!==true){this.clear('rejected');return null};this.phase='transferring';return {...pending.message,requestId:pending.request.requestId}}
  verify(message,bytes){const p=this.incoming;if(!p||!p.accepted||message.requestId!==p.request.requestId||message.profile.id!==p.request.profileId||message.revision!==p.request.revision||bytes!==p.request.bytes)throw Error('未同意此档案的接收请求');this.phase='preview'}
  expire(){if(this.phase==='waiting-consent'&&this.outgoing&&this.now()>this.outgoing.expires||this.phase==='confirm-receive'&&this.incoming&&this.now()>this.incoming.expires){this.clear('consent-timeout');return true}return false}
  clear(phase='idle'){this.incoming=null;this.outgoing=null;this.phase=phase}
}
