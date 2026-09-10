import { readdirSync, readFileSync, mkdirSync, copyFileSync, writeFileSync } from 'node:fs'
import { resolve, join } from 'node:path'
import { createHash } from 'node:crypto'

const [raw, output, version] = process.argv.slice(2)
if (!raw || !output || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version ?? '')) throw new Error('Expected raw directory, output directory and stable version')
function files(path) { return readdirSync(path, { withFileTypes: true }).flatMap(entry => entry.isDirectory() ? files(join(path, entry.name)) : [join(path, entry.name)]) }
const installers = files(resolve(raw)).filter(path => path.endsWith('-setup.exe'))
if (installers.length !== 1) throw new Error(`Expected one installer, found ${installers.length}`)
const installer = installers[0]
const signature = readFileSync(`${installer}.sig`, 'utf8').trim()
if (signature.length < 20) throw new Error('Missing updater signature')
mkdirSync(output, { recursive: true })
const name = `WAO_${version}_windows-x64-setup.exe`
copyFileSync(installer, join(output, name))
writeFileSync(join(output, `${name}.sig`), signature + '\n')
copyFileSync(installer, join(output, 'WAO-windows-x64-setup.exe'))
const hash = createHash('sha256').update(readFileSync(installer)).digest('hex')
writeFileSync(join(output, `SHA256-${version}.txt`), `${hash}  ${name}\n`)
writeFileSync(join(output, 'latest.json'), JSON.stringify({
  version, notes: `WAO 桌面客户端 ${version}。完整工作区、窗口状态保留与签名更新。`, pub_date: new Date().toISOString(),
  platforms: { 'windows-x86_64': { signature, url: `https://wao.wayuwa.cn/downloads/desktop/client/${name}` } },
}, null, 2) + '\n')
