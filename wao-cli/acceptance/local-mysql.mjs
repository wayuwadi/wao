import assert from 'node:assert/strict'
import { writeFile, mkdir } from 'node:fs/promises'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { Readable } from 'node:stream'
import { run } from '../src/cli.js'
import { createClient } from '../src/client.js'

let raw = ''
for await (const chunk of process.stdin) raw += chunk
const config = JSON.parse(raw)
raw = ''
assert.equal(new URL(config.url).hostname, '127.0.0.1')
assert.equal(new URL(config.url).port, '18084')
assert.ok(config.prefix.startsWith('[MCP CLI 本地验收]'))
await mkdir(config.evidenceDir, { recursive: true })
const evidence = { kind: 'real-local-mysql-http-cli', memberId: config.memberId, prefix: config.prefix, checks: [], fixtures: {}, retained: true }
const uuid = () => randomUUID().replaceAll('-', '')
const body = `## ${config.prefix} 标题

第一段中文与 emoji 🙂。

第二段保留段落。

- 列表甲
- 列表乙

> 引用内容`
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
const future = new Date(today + 'T12:00:00+08:00'); future.setUTCDate(future.getUTCDate() + 2)
const due = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(future)
const tagA = config.prefix + '-A', tagB = config.prefix + '-B'
assert.ok(tagA.length <= 32 && tagB.length <= 32)
assert.ok(body.includes('\n\n') && body.includes('\n- 列表甲\n- 列表乙') && body.includes('\n> 引用内容'))
assert.equal(body.includes('\\n'), false)
const client = createClient({ url: config.url, token: config.token })
async function cli(args, markdown) {
  let output = ''
  await run([...args, '--output', 'json', '--url', config.url], {
    tokenProvider: async () => config.token,
    stdin: Readable.from(markdown === undefined ? [] : [Buffer.from(markdown)]),
    stdout: { write(value) { output += value } }, stderr: { write() {} },
    receiptDirectory: join(config.evidenceDir, 'operations'),
  })
  return JSON.parse(output)
}
async function check(name, fn) {
  await fn()
  evidence.checks.push({ name, status: 'passed', at: new Date().toISOString() })
  await writeFile(join(config.evidenceDir, 'summary.json'), JSON.stringify(evidence, null, 2) + '\n')
  process.stdout.write('真实链路通过：' + name + '\n')
}
try {
  const directory = await client.listTools()
  assert.ok(directory.some(tool => tool.name === 'record_daily_note'))
  const identity = await cli(['workspace', 'info'])
  assert.equal(identity.member.id, config.memberId)
  assert.equal(identity.permissions.daily, true)
  const noteId = uuid(), noteTitle = config.prefix + ' 小记'
  evidence.fixtures.noteId = noteId
  let note
  await check('Markdown 与标签创建，真实回读保留段落', async () => {
    note = await cli(['notes', 'create', '--document-id', noteId, '--title', noteTitle, '--tags', JSON.stringify([tagA]), '--file', '-'], body)
    note = await cli(['notes', 'get', noteId])
    assert.equal(note.content.markdown, body)
    assert.deepEqual(note.tags, [tagA])
    assert.equal(note.author, config.memberId)
  })
  const initialRev = note.rev
  await check('标签 add/remove/clear 与 CAS 失败保护正文', async () => {
    note = await cli(['notes', 'tags', 'add', noteId, '--expected-rev', String(note.rev), '--tags', JSON.stringify([tagB])])
    assert.deepEqual(new Set(note.tags), new Set([tagA, tagB]))
    await assert.rejects(() => cli(['notes', 'tags', 'clear', noteId, '--expected-rev', String(initialRev)]), /更新|冲突|版本|修订/)
    const preserved = await cli(['notes', 'get', noteId])
    assert.equal(preserved.content.markdown, body)
    assert.deepEqual(new Set(preserved.tags), new Set([tagA, tagB]))
    note = await cli(['notes', 'tags', 'remove', noteId, '--expected-rev', String(note.rev), '--tags', JSON.stringify([tagB])])
    assert.deepEqual(note.tags, [tagA])
    note = await cli(['notes', 'tags', 'clear', noteId, '--expected-rev', String(note.rev)])
    assert.deepEqual(note.tags, [])
    assert.equal(note.content.markdown, body)
  })
  await check('稳定 ID 重放返回最新 rev；异内容拒绝覆盖', async () => {
    const replay = { document_id: noteId, title: noteTitle, tags: [tagA], content: { version: 1, markdown: body, document: null } }
    const returned = await client.call('create_note', replay)
    assert.equal(returned.id, noteId)
    assert.equal(returned.rev, note.rev)
    assert.deepEqual(returned.tags, [])
    await assert.rejects(() => client.call('create_note', { ...replay, title: config.prefix + ' 异内容' }), /另一份|内容|已用于/)
    assert.equal((await cli(['notes', 'get', noteId])).content.markdown, body)
  })
  await check('本人/标签/创建日期与真实多页查询', async () => {
    note = await cli(['notes', 'tags', 'set', noteId, '--expected-rev', String(note.rev), '--tags', JSON.stringify([tagA])])
    const otherId = uuid(); evidence.fixtures.secondNoteId = otherId
    await cli(['notes', 'create', '--document-id', otherId, '--title', config.prefix + ' 第二篇', '--tags', JSON.stringify([tagA]), '--file', '-'], body)
    const args = ['notes', 'list', '--keyword', config.prefix, '--mine-only', 'true', '--tags', JSON.stringify([tagA]), '--from', today, '--to', today, '--limit', '1']
    const first = await cli([...args, '--offset', '0']), second = await cli([...args, '--offset', '1'])
    assert.equal(first.total, 2); assert.equal(first.hasMore, true)
    assert.equal(second.items.length, 1); assert.equal(second.hasMore, false)
    assert.notEqual(first.items[0].id, second.items[0].id)
    const futureNotes = await cli(['notes', 'list', '--keyword', config.prefix, '--from', due, '--to', due])
    assert.equal(futureNotes.total, 0)
  })
  let document
  await check('真实模板更新、套用文档、归档与恢复', async () => {
    let template = await cli(['templates', 'create', '--name', config.prefix + ' 模板', '--source-document-id', noteId])
    evidence.fixtures.templateId = template.id
    template = await cli(['templates', 'update', template.id, '--description', config.prefix + ' 修订', '--expected-updated-at', template.updatedAt])
    assert.equal(template.description, config.prefix + ' 修订')
    const kb = await cli(['knowledge', 'create', '--name', config.prefix + ' 知识库'])
    evidence.fixtures.kbId = kb.id
    const full = await cli(['knowledge', 'get', kb.id]), folder = full.folders[0]
    assert.ok(folder?.id)
    document = await cli(['documents', 'create', '--kb-id', kb.id, '--folder-id', folder.id, '--title', config.prefix + ' 文档', '--template-id', template.id])
    evidence.fixtures.documentId = document.id
    document = await cli(['documents', 'get', document.id])
    assert.equal(document.content.markdown, body)
    await cli(['documents', 'archive', document.id, '--expected-rev', String(document.rev)])
    const archived = await cli(['documents', 'archived', kb.id])
    assert.ok(archived.items.some(item => item.id === document.id))
    document = await cli(['documents', 'get', document.id])
    await cli(['documents', 'restore', document.id, '--expected-rev', String(document.rev)])
    const visible = await cli(['documents', 'list', kb.id])
    assert.ok(visible.items.some(item => item.id === document.id))
  })
  let task
  await check('真实任务、子任务、本人评论与文档关联', async () => {
    const project = await cli(['projects', 'create', '--name', config.prefix + ' 项目'])
    evidence.fixtures.projectId = project.id
    task = await cli(['tasks', 'create', '--project-id', project.id, '--title', config.prefix + ' 任务', '--due', due])
    evidence.fixtures.taskId = task.id
    task = await cli(['subtasks', 'create', task.id, '--text', config.prefix + ' 子任务', '--expected-edit-version', task.editVersion])
    const sub = task.subs[0]
    task = await cli(['subtasks', 'update', sub.id, '--task-id', task.id, '--description', config.prefix + ' 子任务描述', '--expected-edit-version', task.editVersion])
    assert.equal(task.subs[0].desc, config.prefix + ' 子任务描述')
    const comment = await cli(['tasks', 'comment', task.id, '--text', config.prefix + ' 评论'])
    await cli(['comments', 'update', comment.id, '--task-id', task.id, '--text', config.prefix + ' 评论修订'])
    task = await cli(['tasks', 'get', task.id])
    assert.ok(task.comments.some(item => item.text === config.prefix + ' 评论修订'))
    task = await cli(['tasks', 'document', task.id, '--document-id', document.id, '--mode', 'add', '--expected-edit-version', task.editVersion])
    assert.ok(task.docs.includes(document.id))
  })
  await check('执行安排保持截止；日小记原子关联及回执', async () => {
    const operation = randomUUID(); evidence.fixtures.scheduleOperationId = operation
    const schedule = await cli(['tasks', 'schedule', task.id, '--operation-id', operation, '--expected-edit-version', task.editVersion,
      '--start', today, '--start-at', 'null', '--end-at', 'null', '--schedule-period', 'MORNING'])
    task = schedule.task
    assert.equal(task.due, due); assert.equal(task.start, today); assert.equal(task.schedulePeriod, 'MORNING')
    assert.equal((await cli(['daily', 'operation', operation])).status, 'COMMITTED')
    const dailyOperation = randomUUID(); evidence.fixtures.dailyOperationId = dailyOperation
    const daily = await cli(['daily', 'record', '--operation-id', dailyOperation, '--business-date', today, '--title', config.prefix + ' 日小记',
      '--task-id', task.id, '--expected-task-edit-version', task.editVersion, '--tags', JSON.stringify([tagA]), '--file', '-'], body)
    evidence.fixtures.dailyNoteId = daily.doc.id
    assert.equal(daily.doc.content.markdown, body)
    assert.ok(daily.task.docs.includes(daily.doc.id))
    const receipt = await cli(['daily', 'operation', dailyOperation])
    assert.equal(receipt.status, 'COMMITTED'); assert.equal(receipt.result.doc.id, daily.doc.id)
    const day = await cli(['todo', 'day', today])
    assert.ok(day.tasks.some(item => item.task.id === task.id))
    assert.ok(day.notes.some(item => item.docId === daily.doc.id))
  })
  await check('全文检索命中真实任务、小记和知识文档', async () => {
    const results = await cli(['search', '--keyword', config.prefix, '--kinds', '["task","note","document"]', '--limit', '100'])
    assert.ok(results.items.some(item => item.kind === 'task' && item.id === task.id))
    assert.ok(results.items.some(item => item.kind === 'note' && item.id === noteId))
    assert.ok(results.items.some(item => item.kind === 'document' && item.id === document.id))
  })
  evidence.completed = true
  await writeFile(join(config.evidenceDir, 'summary.json'), JSON.stringify(evidence, null, 2) + '\n')
  process.stdout.write('真实 CLI 链路完成；fixture IDs 已保存到 summary.json；验收内容保留。\n')
} catch (error) {
  evidence.completed = false
  evidence.failure = String(error.message).replaceAll(config.token, '[REDACTED]')
  await writeFile(join(config.evidenceDir, 'summary.json'), JSON.stringify(evidence, null, 2) + '\n')
  process.stderr.write('真实验收失败：' + evidence.failure + '\n')
  process.exitCode = 1
}
