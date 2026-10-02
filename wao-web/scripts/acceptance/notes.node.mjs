import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { NOTE_STEPS, validateNoteReport, assertNoteComplete, privateDirectory } from './notes.mjs'

const report = () => ({ runId: 'test-20261002', title: 'WAO 验收 · 小记回归 · test-20261002',
  baseURL: 'http://127.0.0.1:5241', peerURL: 'http://127.0.0.1:5242', docId: 'synthetic',
  status: 'running', cleaned: true, steps: NOTE_STEPS.map(name => ({ name, status: 'passed' })) })

test('持久化证据拒绝同源草稿和非本地目标', () => {
  assert.throws(() => validateNoteReport({ ...report(), peerURL: report().baseURL }), /独立源/)
  assert.throws(() => validateNoteReport({ ...report(), baseURL: 'https://example.com' }), /本地/)
  assert.throws(() => validateNoteReport({ ...report(), docId: '../other' }), /ID/)
})
test('缺少任一步、精确 ID 或清理不能生成通过报告', () => {
  for (const name of NOTE_STEPS) assert.throws(() => assertNoteComplete({ ...report(), steps: report().steps.filter(s => s.name !== name) }))
  assert.throws(() => assertNoteComplete({ ...report(), docId: undefined }))
  assert.throws(() => assertNoteComplete({ ...report(), cleaned: false }))
  assert.doesNotThrow(() => assertNoteComplete(report()))
})
test('失败轮次不能通过补填步骤改写成全通过', () => {
  assert.throws(() => assertNoteComplete({ ...report(), status: 'failed' }))
  assert.throws(() => assertNoteComplete({ ...report(), steps: report().steps.map(s => ({ ...s, status: 'failed' })) }))
})
test('私有报告不能写入当前仓库，兼容 Windows 分隔符', () => {
  assert.throws(() => privateDirectory(resolve(import.meta.dirname)), /仓库外/)
  assert.throws(() => privateDirectory(resolve(import.meta.dirname, '../../..')), /仓库外/)
})
