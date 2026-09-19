import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, existsSync, symlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve, join } from 'node:path'
import { DocumentDraftRegression } from './document-drafts.mjs'

function fixture() {
  const directory = mkdtempSync(join(tmpdir(), 'wao-draft-report-test-'))
  const tab = { screenshot: async () => Buffer.from('explicit-test-only') }
  const options = { baseURL: 'http://127.0.0.1:5231', kbPath: '/kb/acceptance', kbName: '单测知识库',
    outputDir: join(directory, 'report'), faultFile: join(directory, 'fault.json') }
  return { directory, tab, options, run: new DocumentDraftRegression(tab, options) }
}

test('未完成场景不能用重复步骤或 finish(true) 冒充整轮通过，并清除故障', async () => {
  const f = fixture()
  try {
    f.run.docId = 'test_doc'
    f.run.fault('fail')
    f.run.report.steps = Array.from({ length: 15 }, () => ({ name: '新建唯一验收文档', status: 'passed' }))
    f.run.report.documents = [{ cleaned: true }, { cleaned: true }]
    assert.equal((await f.run.finish(true)).status, 'failed')
    assert.equal(existsSync(f.options.faultFile), false)
    assert.match(JSON.parse(readFileSync(join(f.options.outputDir, 'report.json'), 'utf8')).error, /未完成/)
  } finally { rmSync(f.directory, { recursive: true, force: true }) }
})

test('恢复报告保留精确文档身份和清理状态，不新建文档或改写已有证据', async () => {
  const f = fixture()
  try {
    const doc = { id: 'test_doc', title: f.run.title, url: `${f.options.baseURL}${f.options.kbPath}?doc=test_doc`, cleaned: false }
    f.run.report.documents.push(doc)
    f.run.saveReport()
    const path = join(f.options.outputDir, 'report.json')
    const before = readFileSync(path, 'utf8')
    const resumed = DocumentDraftRegression.resume(f.tab, { reportFile: path, kbName: f.options.kbName, faultFile: f.options.faultFile })
    assert.equal(resumed.runId, f.run.runId)
    assert.equal(resumed.docId, doc.id)
    assert.deepEqual(resumed.report.documents, [doc])
    assert.equal(readFileSync(path, 'utf8'), before)
    await assert.rejects(() => resumed.create(), /已经创建/)
  } finally { rmSync(f.directory, { recursive: true, force: true }) }
})

test('恢复拒绝报告内身份不符或指向非本地的文档', () => {
  const f = fixture()
  try {
    const path = join(f.options.outputDir, 'report.json')
    for (const url of ['https://example.invalid/kb/acceptance?doc=test_doc', 'http://127.0.0.1:5231/kb/acceptance?doc=another']) {
      f.run.report.documents = [{ id: 'test_doc', title: f.run.title, url, cleaned: false }]
      f.run.saveReport()
      assert.throws(() => DocumentDraftRegression.resume(f.tab, { reportFile: path, kbName: f.options.kbName }), /身份/)
    }
  } finally { rmSync(f.directory, { recursive: true, force: true }) }
})

test('产物目录即使通过符号链接回到仓库也被拒绝', () => {
  const directory = mkdtempSync(join(tmpdir(), 'wao-draft-symlink-test-'))
  try {
    const link = join(directory, 'repo')
    symlinkSync(resolve(import.meta.dirname, '../../..'), link)
    assert.throws(() => new DocumentDraftRegression({}, { baseURL: 'http://127.0.0.1:5231',
      kbPath: '/kb/acceptance', kbName: '测试', outputDir: link }), /仓库外/)
  } finally { rmSync(directory, { recursive: true, force: true }) }
})
