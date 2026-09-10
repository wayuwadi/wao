import { readFileSync, writeFileSync, mkdirSync, copyFileSync } from 'node:fs'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
const [source, destination, version] = process.argv.slice(2)
if (!source || !destination || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version ?? '')) throw new Error('Expected source, destination and X.Y.Z version')
const checksums = new Map(readFileSync(join(source, 'SHA256SUMS.txt'), 'utf8').trim().split('\n').map(line => {
  const match = /^([a-f0-9]{64})  (WAO-[\w.-]+)$/.exec(line)
  if (!match) throw new Error('Invalid checksum entry')
  return [match[2], match[1]]
}))
const files = {}
for (const [platform, suffix] of Object.entries({ android: 'android.apk', ios: 'ios-unsigned.ipa' })) {
  const name = `WAO-${version}-${suffix}`
  const bytes = readFileSync(join(source, name))
  const sha256 = createHash('sha256').update(bytes).digest('hex')
  if (bytes.length < 100 || bytes.readUInt32LE(0) !== 0x04034b50 || sha256 !== checksums.get(name)) throw new Error(`Invalid binary/checksum: ${name}`)
  files[platform] = { name, size: bytes.length, sha256 }
}
mkdirSync(join(destination, version), { recursive: true })
for (const file of Object.values(files)) copyFileSync(join(source, file.name), join(destination, version, file.name))
writeFileSync(join(destination, version, 'SHA256SUMS.txt'), Object.values(files).map(file => `${file.sha256}  ${file.name}\n`).join(''))
writeFileSync(join(destination, 'latest.json'), JSON.stringify({ version, publishedAt: new Date().toISOString(), files }, null, 2) + '\n')
