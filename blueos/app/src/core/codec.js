// Small UTF-8 codec, independent of Node/TextEncoder (BlueOS runtime).
export function utf8(text) {
  const out = []
  for (let i = 0; i < text.length; i++) {
    let c = text.charCodeAt(i)
    if (c >= 0xd800 && c <= 0xdbff) {
      const d = text.charCodeAt(++i)
      if (!(d >= 0xdc00 && d <= 0xdfff)) throw Error('Invalid UTF-16')
      c = 0x10000 + ((c - 0xd800) << 10) + d - 0xdc00
    } else if (c >= 0xdc00 && c <= 0xdfff) throw Error('Invalid UTF-16')
    if (c < 128) out.push(c)
    else if (c < 2048) out.push(192 | c >> 6, 128 | c & 63)
    else if (c < 65536) out.push(224 | c >> 12, 128 | c >> 6 & 63, 128 | c & 63)
    else out.push(240 | c >> 18, 128 | c >> 12 & 63, 128 | c >> 6 & 63, 128 | c & 63)
  }
  return new Uint8Array(out)
}
export function text(bytes) {
  let out = ''
  for (let i = 0; i < bytes.length;) {
    const a = bytes[i++]; let c, n, min
    if (a < 128) { out += String.fromCharCode(a); continue }
    if (a >= 194 && a <= 223) { c = a & 31; n = 1; min = 128 }
    else if (a >= 224 && a <= 239) { c = a & 15; n = 2; min = 2048 }
    else if (a >= 240 && a <= 244) { c = a & 7; n = 3; min = 65536 }
    else throw Error('Invalid UTF-8')
    while (n--) { const b = bytes[i++]; if (!(b >= 128 && b <= 191)) throw Error('Invalid UTF-8'); c = c << 6 | b & 63 }
    if (c < min || c > 0x10ffff || c >= 0xd800 && c <= 0xdfff) throw Error('Invalid UTF-8')
    if (c <= 65535) out += String.fromCharCode(c)
    else { c -= 65536; out += String.fromCharCode(0xd800 | c >> 10, 0xdc00 | c & 1023) }
  }
  return out
}
export function crc32(bytes) {
  let c = -1
  for (let i = 0; i < bytes.length; i++) { c ^= bytes[i]; for (let j = 0; j < 8; j++) c = c >>> 1 ^ (c & 1 ? 0xedb88320 : 0) }
  return (c ^ -1) >>> 0
}
export function crc16(bytes) {
  let c = 65535
  for (let i = 0; i < bytes.length; i++) { c ^= bytes[i] << 8; for (let j = 0; j < 8; j++) c = (c << 1 ^ (c & 32768 ? 0x1021 : 0)) & 65535 }
  return c
}
export function canonical(value) {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']'
  if (value && typeof value === 'object') return '{' + Object.keys(value).sort().map(k => JSON.stringify(k) + ':' + canonical(value[k])).join(',') + '}'
  return JSON.stringify(value)
}
export function revision(profile) { return crc32(utf8(canonical(profile))).toString(16).padStart(8, '0') }
