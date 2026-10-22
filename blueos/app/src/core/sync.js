import { revision } from './codec.js'
import { clone } from './engine.js'
import { validate, id } from './model.js'
export function direction(local, remote, base) {
  if (local === remote) return 'same'
  if (!base) return 'choose'
  if (local === base) return 'receive'
  if (remote === base) return 'send'
  return 'conflict'
}
// Device settings deliberately stay outside this profile envelope.
export function offer(profile, nonce) { validate(profile); return { type: 'OFFER', protocol: 1, nonce, revision: revision(profile), profile: clone(profile) } }
export function preview(message, local, base) {
  if (message.protocol !== 1 || message.type !== 'OFFER' || typeof message.nonce !== 'string' || message.nonce.length > 100) throw Error('不支持的同步消息')
  validate(message.profile)
  if (revision(message.profile) !== message.revision) throw Error('档案版本校验失败')
  const p = message.profile
  return { message, localRevision: local ? revision(local) : null, direction: local ? direction(revision(local), message.revision, base) : 'receive', summary: `${p.name} · ${p.subjects.length} 科目 / ${p.layouts.length} 作息 / ${p.plans.length} 课表 / ${p.temporary.length} 临时安排` }
}
export function accept(profiles, pending, choice) {
  const m = pending.message, incoming = clone(m.profile), local = profiles.find(p => p.id === incoming.id)
  if (local && revision(local) !== pending.localRevision) throw Error('预览后本机已修改，请重新预览')
  if (choice === 'keep') return clone(profiles)
  if (choice === 'copy') { incoming.id = id(); incoming.name += ' 接收副本'; return profiles.concat(incoming) }
  if (choice !== 'replace') throw Error('必须明确选择版本')
  return local ? profiles.map(p => p.id === incoming.id ? incoming : p) : profiles.concat(incoming)
}
