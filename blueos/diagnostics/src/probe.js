export const uuid=n=>'d731'+n.toString(16).padStart(4,'0')+'-9e2c-4c53-a1aa-2cc0bf3d7710'
export const fixed=()=>new Uint8Array(Array.from({length:64},(_,i)=>i^0xa5))
export function crc32(bytes){let c=-1;for(const b of bytes){c^=b;for(let i=0;i<8;i++)c=c>>>1^(c&1?0xedb88320:0)}return(c^-1)>>>0}
const CCCD='00002902-0000-1000-8000-00805f9b34fb'
export class DiagnosticServer {
  constructor(bluetooth,report){this.bluetooth=bluetooth;this.report=report;this.server=null;this.peer='';this.subscribed=false;this.timer=null;this.counts={read:0,write:0,notify:0};this.echo=new Uint8Array([0]);this.blocks={};this.generation=0}
  start(listenFirst=true,short=false){
    this.stop();const generation=++this.generation,current=()=>generation===this.generation&&!!this.server
    const s=this.bluetooth.BLE.createGattServer();this.server=s
    this.report(short?'模式：短UUID广播测试，不传课表，60秒停止':'模式：独立诊断，仅固定数据')
    const respond=(r,value,status=0)=>s.sendResponse({deviceId:r.deviceId,transId:r.transId,offset:r.offset||0,status,value:new Uint8Array(value).buffer})
    const fail=(e,c)=>{if(current()){this.stop();this.report('监听失败 '+c)}}
    const register=()=>{const characteristics=[2,3,4,5,6,7].map(n=>({serviceUuid:uuid(1),characteristicUuid:uuid(n),characteristicValue:new Uint8Array([0]).buffer,properties:n===2||n===3?[1]:n===4?[1,5]:n===5?[4]:n===6?[3,5]:[5],descriptors:n===6?[{serviceUuid:uuid(1),characteristicUuid:uuid(6),descriptorUuid:CCCD,descriptorValue:new Uint8Array([0,0]).buffer}]:[]}));if(s.addService({serviceUuid:uuid(1),isPrimary:true,includeServices:[],characteristics})===false)throw Error('服务注册失败');this.report('服务注册调用完成；UUID '+uuid(1))}
    const listen=()=>{
      s.subscribeConnectStateChange({callback:r=>{if(!current()||this.peer&&this.peer!==r.deviceId)return;const state=Number.isInteger(r.state)?r.state:r.transId;if(state===2)this.peer=r.deviceId;if(state===0){this.peer='';this.subscribed=false};this.report('连接回调字段 '+(Number.isInteger(r.state)?'state':'transId')+' 值 '+state)},fail})
      s.subscribeCharacteristicRead({callback:r=>{if(!current()||this.peer&&this.peer!==r.deviceId)return;this.counts.read++;let value=[0];if(r.characteristicUuid===uuid(2))value=[83,84,65,84,73,67];else if(r.characteristicUuid===uuid(3))value=[this.counts.read&255,this.counts.write&255,this.counts.notify&255,0x51];else if(r.characteristicUuid===uuid(4))value=this.echo;respond(r,value);this.report('读取回调 '+this.counts.read)},fail})
      s.subscribeCharacteristicWrite({callback:r=>{
        if(!current()||this.peer&&this.peer!==r.deviceId)return
        const value=new Uint8Array(new Uint8Array(r.value));this.counts.write++;this.peer=r.deviceId
        if(r.needRsp)respond(r,[],r.offset?13:0)
        if(r.offset)return
        if(r.characteristicUuid===uuid(4))this.echo=value
        if(r.characteristicUuid===uuid(6))this.notify(value)
        if(r.characteristicUuid===uuid(7)&&value.length===20&&value[0]===0xd7&&value[1]<4&&value[2]===4&&value[3]===16){this.blocks[value[1]]=value.slice(4);this.notify(value);if(Object.keys(this.blocks).length===4){const all=new Uint8Array(64);for(let i=0;i<4;i++)all.set(this.blocks[i],i*16);const expected=fixed();this.report('64B整段校验 '+(all.every((b,i)=>b===expected[i]))+' CRC '+crc32(all));this.blocks={}}}
        this.report('写回调 '+this.counts.write+' / 通知调用 '+this.counts.notify)
      },fail})
      s.subscribeDescriptorWrite({callback:r=>{if(!current()||this.peer&&this.peer!==r.deviceId)return;const b=new Uint8Array(r.value),valid=r.descriptorUuid===CCCD&&r.characteristicUuid===uuid(6)&&b.length===2&&b[1]===0&&(b[0]===0||b[0]===1);if(valid){this.peer=r.deviceId;this.subscribed=b[0]===1};if(r.needRsp)respond(r,[],valid?0:13);this.report('通知订阅 '+this.subscribed)},fail})
      s.subscribeDescriptorRead({callback:r=>{if(current()&&(!this.peer||this.peer===r.deviceId))respond(r,[this.subscribed?1:0,0])},fail})
      this.report('监听注册调用完成')
    }
    try{if(listenFirst){listen();register()}else{register();listen()};const accepted=s.startAdvertising({interval:160,txPower:-7,connectable:true},{serviceUuids:[short?'00001888-0000-1000-8000-00805f9b34fb':uuid(1)]});if(accepted===false)throw Error('广播拒绝');this.report('广播请求结果 '+String(accepted)+'（无回执不能证明广播成功）');if(short)this.timer=setTimeout(()=>{this.stop();this.report('60秒广播测试已结束')},60000)}catch(e){this.stop();throw e}
  }
  notify(value){if(!this.peer||!this.subscribed){this.report('未订阅通知；未发送');return};this.counts.notify++;const ok=this.server.notifyCharacteristicChanged(this.peer,{serviceUuid:uuid(1),characteristicUuid:uuid(6),characteristicValue:value.buffer,confirm:false});this.report('通知API结果 '+String(ok))}
  stop(){this.generation++;if(this.timer)clearTimeout(this.timer);this.timer=null;const s=this.server;this.server=null;if(s)for(const name of ['stopAdvertising','unsubscribeCharacteristicRead','unsubscribeCharacteristicWrite','unsubscribeDescriptorRead','unsubscribeDescriptorWrite','unsubscribeConnectStateChange','close'])try{if(typeof s[name]==='function')s[name]()}catch(e){}this.peer='';this.subscribed=false;this.blocks={}}
}
