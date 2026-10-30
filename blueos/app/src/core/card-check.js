export async function checkCard(query) {
  const targets=[{name:'应用',type:'package'},{name:'课程卡',type:'widget',moduleName:'widgets/Now'},{name:'不存在的空模块',type:'widget',moduleName:'widgets/__missing_probe__'},{name:'普通页负对照',type:'widget',moduleName:'pages/Main'}]
  const results=await Promise.all(targets.map(async t=>{try{return {...t,...await query(t)}}catch(e){return {...t,state:'unavailable',error:e.message}}}))
  const card=results[1],negative=results.slice(2)
  let verdict='当前查询不能证明卡片已安装'
  if(card.state==='timeout')verdict='卡片查询超时'
  else if(card.state==='unavailable')verdict='卡片查询接口不可用'
  else if(card.state==='ok'&&!card.result)verdict='查询显示课程卡未安装'
  else if(card.state==='ok'&&card.result&&negative.every(r=>r.state==='ok'&&r.result===false))verdict='查询区分了卡片与负对照；侧滑显示仍需真机确认'
  return {results,verdict}
}
