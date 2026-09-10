import { readFileSync, writeFileSync } from 'node:fs'
const version = process.argv[2]
if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version ?? '')) throw new Error('请输入稳定版本号，例如 npm run version:set -- 1.0.1')
for (const file of ['package.json', 'src-tauri/tauri.conf.json']) {
  const path = new URL(`../${file}`, import.meta.url)
  const data = JSON.parse(readFileSync(path, 'utf8')); data.version = version
  writeFileSync(path, JSON.stringify(data, null, 2) + '\n')
}
const cargo = new URL('../src-tauri/Cargo.toml', import.meta.url)
writeFileSync(cargo, readFileSync(cargo, 'utf8').replace(/^(version = ")[^"]+("\r?)$/m, `$1${version}$2`))
console.log(`版本已设为 ${version}。请运行 npm install --package-lock-only 和 cargo check 同步锁文件，再提交 PR；合并后推送 client-v${version} 标签。`)
