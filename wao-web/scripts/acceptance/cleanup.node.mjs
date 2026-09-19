import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { cleanupAllowed, registerCleanupDocuments, transformCleanupConfirmation } from './cleanup.mjs'

test('清理只允许登记的精确 ID 和标题，缺失、过期与其他文档拒绝', () => {
  const directory = mkdtempSync(join(tmpdir(), 'wao-cleanup-test-'))
  const path = join(directory, 'owned.json')
  const documents = [{ id: 'd_one', title: 'WAO 验收 · 草稿回归 · unit' },
    { id: 'd_two', title: 'WAO 验收 · 草稿回归 · unit（冲突副本）' }]
  try {
    assert.equal(cleanupAllowed(path, 'd_one', documents[0].title, 1000), false)
    registerCleanupDocuments(path, documents, 1000)
    assert.equal(cleanupAllowed(path, 'd_one', documents[0].title, 1001), true)
    assert.equal(cleanupAllowed(path, 'd_other', documents[0].title, 1001), false)
    assert.equal(cleanupAllowed(path, 'd_one', documents[1].title, 1001), false)
    assert.equal(cleanupAllowed(path, 'd_one', documents[0].title, 601000), false)
    assert.throws(() => registerCleanupDocuments(path, [{ id: 'business', title: '业务文档' }, documents[1]]))
    assert.throws(() => registerCleanupDocuments(path, [documents[0], documents[0]]))
  } finally { rmSync(directory, { recursive: true, force: true }) }
})

test('本地适配器只替换一处文档确认，源码变化时停止而非广泛替换', () => {
  const source = readFileSync(new URL('../../src/views/KbDetailView.vue', import.meta.url), 'utf8')
  const transformed = transformCleanupConfirmation(source)
  assert.equal((transformed.match(/virtual:wao-acceptance-confirm/g) ?? []).length, 1)
  assert.equal((source.match(/window\.confirm/g) ?? []).length - (transformed.match(/window\.confirm/g) ?? []).length, 1)
  assert.ok(transformed.includes('await data.deleteDoc(id)'))
  assert.throws(() => transformCleanupConfirmation('changed source'), /人工更新/)
  assert.throws(() => transformCleanupConfirmation(source + source), /人工更新/)
})
