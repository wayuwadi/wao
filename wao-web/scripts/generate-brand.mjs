import { readFile, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

// Run with the installed workspace Sharp package; no runtime/build dependency is added.
const require = createRequire(import.meta.url)
const sharp = require(process.argv[2] || 'sharp')
const root = new URL('../public/', import.meta.url)
const master = await readFile(new URL('brand/wao-soft-loop-v4.svg', root), 'utf8')
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
  if ([64, 180, 192, 512].includes(size)) await writeFile(new URL(`brand/wao-soft-loop-${size}-v4.png`, root), png)
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
console.log('Generated v4 SVG, PNG, maskable and multi-size ICO assets.')
