import { readFileSync, existsSync, readdirSync } from 'node:fs'
import { resolve, dirname, join } from 'node:path'
import { execFileSync } from 'node:child_process'
const root = resolve(import.meta.dirname, '..')
let files = 0
function walk(path) {
 for (const d of readdirSync(path,{withFileTypes:true})) {
  const f=join(path,d.name)
  if(d.isDirectory()){walk(f);continue}
  if(!/\.(js|ux)$/.test(f))continue
  const source=readFileSync(f,'utf8'),script=f.endsWith('.ux')?(source.match(/<script[^>]*>([\s\S]*?)<\/script>/)||[])[1]:source
  if(script===undefined)throw Error('No script: '+f)
  execFileSync(process.execPath,['--input-type=module','--check'],{input:script,stdio:['pipe','pipe','pipe']})
  for(const m of script.matchAll(/(?:from\s*|import\s*)['"]([^'"]+)['"]/g))if(m[1].startsWith('.')&&!existsSync(resolve(dirname(f),m[1])))throw Error('Missing import '+f+' '+m[1])
  files++
 }
}
walk(join(root,'app/src'));walk(join(root,'watchface/src'))
for(const name of ['app','watchface']){
 const dir=join(root,name,'src'),m=JSON.parse(readFileSync(join(dir,'manifest.json')))
 for(const category of ['pages','widgets','watchfaces'])for(const [path,route] of Object.entries(m.router[category]||{})){
  if(!existsSync(join(dir,path,route.component+'.ux')))throw Error('Missing route '+path)
  if(category==='widgets'&&route.type!=='js')throw Error('Expected standard card')
 }
}
for(const target of ['app/src/widgets/Now/lib/snapshot-reader.js','watchface/src/watch3000/lib/snapshot-reader.js'])if(readFileSync(join(root,target),'utf8')!==readFileSync(join(root,'app/src/platform/snapshot-reader.js'),'utf8'))throw Error('Run sync-shared.py')
console.log(`Checked ${files} JS/UX scripts, relative imports, manifest routes and shared consumer copies. This is not a BlueOS Toolkit build.`)
