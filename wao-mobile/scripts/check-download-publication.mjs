import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
const prepare = fileURLToPath(new URL('./prepare-downloads.mjs', import.meta.url))
const publisher = fileURLToPath(new URL('./publish-downloads.sh', import.meta.url))
function fixture(root, version = '1.0.0', marker = 0) {
  const source = join(root, `source-${version}-${marker}`)
  mkdirSync(source)
  let checksums = ''
  for (const suffix of ['android.apk', 'ios-unsigned.ipa']) {
    const name = `WAO-${version}-${suffix}`
    const bytes = Buffer.alloc(200, marker); bytes.writeUInt32LE(0x04034b50)
    writeFileSync(join(source, name), bytes)
    checksums += `${createHash('sha256').update(bytes).digest('hex')}  ${name}\n`
  }
  writeFileSync(join(source, 'SHA256SUMS.txt'), checksums)
  return source
}
test('清单只从完整且通过校验的双端包生成', () => {
  const root = mkdtempSync(join(tmpdir(), 'wao-download-test-'))
  try {
    const source = fixture(root); const dest = join(root, 'public')
    assert.equal(spawnSync(process.execPath, [prepare, source, dest, '1.0.0']).status, 0)
    assert.equal(JSON.parse(readFileSync(join(dest, 'latest.json'))).files.android.size, 200)
    writeFileSync(join(source, 'WAO-1.0.0-android.apk'), 'corrupted')
    assert.notEqual(spawnSync(process.execPath, [prepare, source, dest, '1.0.0']).status, 0)
    assert.notEqual(spawnSync(process.execPath, [prepare, source, dest, '../1']).status, 0)
  } finally { rmSync(root, { recursive: true, force: true }) }
})
test('原子发布可重试、拒绝降级与同版本覆盖，失败保持现有清单', { skip: process.platform !== 'linux' }, () => {
  const root = mkdtempSync(join(tmpdir(), 'wao-download-test-'))
  const incoming = `/tmp/wao-mobile-${process.pid}-99`
  try {
    const script = join(root, 'publish.sh')
    writeFileSync(script, readFileSync(publisher, 'utf8').replace('destination=/var/www/wao-downloads/mobile', `destination=${root}/public`))
    const publish = (version, marker = 0) => {
      rmSync(incoming, { recursive: true, force: true })
      const source = fixture(root, version, marker)
      assert.equal(spawnSync(process.execPath, [prepare, source, incoming, version]).status, 0)
      return spawnSync('bash', [script, incoming, version], { encoding: 'utf8' })
    }
    assert.equal(publish('1.0.0').status, 0)
    const live = readFileSync(join(root, 'public/latest.json'), 'utf8')
    assert.notEqual(publish('0.9.0').status, 0)
    assert.notEqual(publish('1.0.0', 1).status, 0)
    assert.equal(readFileSync(join(root, 'public/latest.json'), 'utf8'), live)
    rmSync(join(root, 'source-1.0.0-0'), { recursive: true })
    assert.equal(publish('1.0.0').status, 0)
    assert.equal(publish('1.0.1').status, 0)
    assert.equal(JSON.parse(readFileSync(join(root, 'public/latest.json'))).version, '1.0.1')
  } finally { rmSync(root, { recursive: true, force: true }); rmSync(incoming, { recursive: true, force: true }) }
})
