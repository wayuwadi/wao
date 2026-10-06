import { readFile, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import { zipSync } from 'fflate'

// Run with the installed workspace Sharp package; no runtime/build dependency is added.
const require = createRequire(import.meta.url)
const sharp = require(process.argv[2] || 'sharp')
const root = new URL('../public/', import.meta.url)
const master = await readFile(new URL('brand/wao-soft-loop-v4.svg', root), 'utf8')
const identity = JSON.parse(await readFile(new URL('../src/config/brandIdentity.json', import.meta.url), 'utf8'))
if (!master.includes(identity.primary)) throw new Error('图形母版与品牌配置主色不一致')
const small = master.replace('0 0 100 100', '7 11 86 76').replaceAll('7.2', '7.8').replace('12.5', '15')
const mono = master.replaceAll('#5665e9', 'currentColor')
await writeFile(new URL('brand/wao-soft-loop-small-v4.svg', root), small)
await writeFile(new URL('brand/wao-soft-loop-mono-v4.svg', root), mono)
await writeFile(new URL('favicon.svg', root), small)
const pngs = new Map()
for (const size of [16, 24, 32, 48, 64, 180, 192, 512]) {
  const svg = size <= 64 ? small : master
  let render = sharp(Buffer.from(svg)).resize(size, size)
  if (size === 180) render = render.flatten({ background: '#ffffff' })
  const png = await render.png().toBuffer()
  pngs.set(size, png)
  if ([16, 24, 32, 64, 180, 192, 512].includes(size)) {
    // 新增的 32px 独立素材使用标准图形，浏览器图标仍保留既有光学校正。
    const exported = size === 32 ? await sharp(Buffer.from(master)).resize(32, 32).png().toBuffer() : png
    await writeFile(new URL(`brand/wao-soft-loop-${size}-v4.png`, root), exported)
  }
}
const safe = await sharp(Buffer.from(master)).resize(410, 410).png().toBuffer()
await sharp({ create: { width: 512, height: 512, channels: 4, background: '#ffffff' } }).composite([{ input: safe, left: 51, top: 51 }]).png().toFile(fileURLToPath(new URL('brand/wao-soft-loop-maskable-512-v4.png', root)))
const sizes = [16, 32, 48, 64]
const header = Buffer.alloc(6 + sizes.length * 16)
header.writeUInt16LE(1, 2); header.writeUInt16LE(sizes.length, 4)
let offset = header.length
sizes.forEach((size, i) => {
  const png = pngs.get(size), p = 6 + i * 16
  header[p] = size; header[p + 1] = size
  header.writeUInt16LE(1, p + 4); header.writeUInt16LE(32, p + 6)
  header.writeUInt32LE(png.length, p + 8); header.writeUInt32LE(offset, p + 12)
  offset += png.length
})
await writeFile(new URL('favicon.ico', root), Buffer.concat([header, ...sizes.map(size => pngs.get(size))]))

const wordmarkPath = `<path d="${identity.wordmark.path}" fill="none" stroke="${identity.primary}" stroke-width="${identity.wordmark.strokeWidth}" stroke-linecap="round" stroke-linejoin="round"/>`
const wordmark = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${identity.wordmark.viewBox}">${wordmarkPath}</svg>\n`
const symbolBody = master.slice(master.indexOf('>') + 1, master.lastIndexOf('</svg>'))
// 32px 图形、8px 间距和约 53px 字标，与 WaoLogo 的默认组合比例一致。
const lockup = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 290 100">${symbolBody}<g transform="translate(125 19.667) scale(2.1667)">${wordmarkPath}</g></svg>\n`
const vectors = {
  'wao-wordmark-v4.svg': wordmark,
  'wao-lockup-v4.svg': lockup,
  'wao-lockup-ink-v4.svg': lockup.replaceAll(identity.primary, identity.ink),
  'wao-lockup-white-v4.svg': lockup.replaceAll(identity.primary, '#ffffff'),
  'wao-soft-loop-ink-v4.svg': master.replaceAll(identity.primary, identity.ink),
  'wao-soft-loop-white-v4.svg': master.replaceAll(identity.primary, '#ffffff'),
}
for (const [name, svg] of Object.entries(vectors)) {
  await writeFile(new URL(`brand/${name}`, root), svg)
  if (name.startsWith('wao-lockup')) {
    await sharp(Buffer.from(svg)).resize(1160, 400).png().toFile(fileURLToPath(new URL(`brand/${name.replace('.svg', '.png')}`, root)))
  }
}
const names = [
  'wao-soft-loop-v4.svg', 'wao-soft-loop-small-v4.svg', 'wao-soft-loop-mono-v4.svg',
  ...Object.keys(vectors), ...[16,24,32,64,180,192,512].map(size => `wao-soft-loop-${size}-v4.png`),
  'wao-soft-loop-maskable-512-v4.png', ...Object.keys(vectors).filter(name => name.startsWith('wao-lockup')).map(name => name.replace('.svg', '.png')),
  'guide.html',
]
const archive = {}, files = []
for (const name of names.sort()) {
  const bytes = await readFile(new URL(`brand/${name}`, root))
  archive[name] = bytes
  files.push({ name, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') })
}
for (const name of ['favicon.svg', 'favicon.ico']) {
  const bytes = await readFile(new URL(name, root))
  archive[name] = bytes
  files.push({ name, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') })
}
const usage = await readFile(new URL('../../docs/design/wao-brand-usage.md', import.meta.url))
archive['wao-brand-usage.md'] = usage
files.push({ name:'wao-brand-usage.md', bytes:usage.length, sha256:createHash('sha256').update(usage).digest('hex') })
const manifest = Buffer.from(JSON.stringify({ version:identity.version, primary:identity.primary, files }, null, 2) + '\n')
await writeFile(new URL('brand/brand-manifest-v4.json', root), manifest)
archive['brand-manifest-v4.json'] = manifest
// 固定时间使同样输入得到同样 ZIP，避免无意义的二进制改动。
await writeFile(new URL('brand/wao-brand-kit-v4.zip', root), zipSync(archive, { level:6, mtime:new Date('2026-10-07T00:00:00Z') }))
console.log(`Generated v4 icons, wordmark, lockups and ZIP with ${Object.keys(archive).length} files.`)
