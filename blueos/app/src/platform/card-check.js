import { checkCard } from '../core/card-check.js'
export function diagnose() {
  let manager
  try {manager=require('@system.package')}catch(e){return checkCard(async()=>({state:'unavailable'}))}
  return checkCard(target=>new Promise(resolve=>{
    let done=false
    const finish=value=>{if(done)return;done=true;clearTimeout(timer);resolve(value)}
    const timer=setTimeout(()=>finish({state:'timeout'}),3000)
    try{manager.hasInstalled({package:'com.ggz.app.simpletimetable',type:target.type,moduleName:target.moduleName,success:r=>finish({state:'ok',result:r.result===true}),fail:(e,c)=>finish({state:'unavailable',error:String(c)})})}catch(e){finish({state:'unavailable',error:e.message})}
  }))
}
