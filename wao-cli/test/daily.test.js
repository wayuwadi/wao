import assert from 'node:assert/strict'
import { test } from 'node:test'
import { Readable } from 'node:stream'
import { mkdtemp, readFile, rm, writeFile, readdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { once } from 'node:events'
import { createServer } from 'node:http'
import { argumentsForTool, formatOutput, run } from '../src/cli.js'
import { documentMarkdown } from '../src/content.js'

const string = { type: 'string' }
const integer = { type: 'integer' }
const nullable = { type: ['string', 'null'] }
const tools = [
  { name: 'get_workspace_info', inputSchema: { properties: {} }, annotations: { readOnlyHint: true } },
  { name: 'list_notes', inputSchema: { properties: { keyword: string, tags: { type: 'array', items: string }, offset: integer, limit: integer } }, annotations: { readOnlyHint: true } },
  { name: 'get_document', inputSchema: { properties: { document_id: string }, required: ['document_id'] }, annotations: { readOnlyHint: true } },
  { name: 'create_note', inputSchema: { properties: { document_id: string, title: string, text: string, content: { type: 'object' }, blocks: { type: 'array' }, tags: { type: 'array', items: string } } } },
  { name: 'update_note_tags', inputSchema: { properties: { document_id: string, mode: { type: 'string', enum: ['add', 'remove', 'replace', 'clear'] }, tags: { type: 'array', items: string }, expected_rev: integer }, required: ['document_id', 'mode', 'expected_rev'] } },
  { name: 'schedule_task', inputSchema: { properties: { task_id: string, operation_id: string, expected_edit_version: string, start: nullable, start_at: nullable, end_at: nullable, schedule_period: nullable }, required: ['task_id', 'operation_id', 'expected_edit_version', 'start', 'start_at', 'end_at', 'schedule_period'] } },
  { name: 'record_daily_note', inputSchema: { properties: { operation_id: string, document_id: string, business_date: string, content: { type: 'object' } }, required: ['operation_id', 'document_id', 'business_date'] } },
]
function output() {
  let text = ''
  return { stream: { write(value) { text += value } }, get text() { return text } }
}
function fixture({ visible = tools, data = args => args, fail = null } = {}) {
  const calls = [], stdout = output(), stderr = output()
  const client = { async listTools() { return visible }, async call(name, args) {
    assert.ok(visible.some(tool => tool.name === name))
    calls.push({ name, args })
    if (fail) throw new Error(fail)
    return data(args, name)
  } }
  return { calls, stdout, stderr, options: { clientFactory: () => client, tokenProvider: async () => 'test-token', stdout: stdout.stream, stderr: stderr.stream } }
}

test('文件与多块文档的 Markdown 导出保留标题、段落、列表与引用', () => {
  assert.equal(documentMarkdown({ title: '文章', content: { markdown: '## 章节\n\n第一段\n\n第二段\n\n- 项目' }, blocks: [{ p: '旧正文' }] }), '# 文章\n\n## 章节\n\n第一段\n\n第二段\n\n- 项目\n')
  assert.match(documentMarkdown({ blocks: [{ h: '章节', hLevel: 3 }, { p: '段落' }, { ul: ['甲', '乙'] }, { q: ['引用', '作者'] }, { call: '提醒' }] }), /### 章节\n\n段落\n\n- 甲\n- 乙\n\n> 引用\n>\n> 作者\n\n> 提醒/)
})

test('小记文件导入保存完整 UTF-8 Markdown，先落稳定 ID 回执再调用', async () => {
  const folder = await mkdtemp(join(tmpdir(), 'wao-cli-daily-'))
  const file = join(folder, 'article.md'), receiptDirectory = join(folder, 'operations')
  const article = '# 小标题\n\n第一段中文🙂\n\n第二段\n\n- 标签'
  const f = fixture()
  try {
    await writeFile(file, article)
    await run(['notes', 'create', '--title', '文章', '--file', file, '--tags', '["生活"]', '--output', 'json'], { ...f.options, receiptDirectory })
    const [{ name, args }] = f.calls
    assert.equal(name, 'create_note')
    assert.match(args.document_id, /^[a-f0-9]{32}$/)
    assert.deepEqual(args.content, { version: 1, markdown: article, document: null })
    assert.deepEqual(args.tags, ['生活'])
    const [receipt] = await readdir(receiptDirectory)
    const stored = JSON.parse(await readFile(join(receiptDirectory, receipt), 'utf8'))
    assert.equal(stored.state, 'succeeded')
    assert.equal(stored.document_id, args.document_id)
    assert.match(f.stderr.text, new RegExp(args.document_id))
    assert.deepEqual(JSON.parse(f.stdout.text), args)
    assert.ok(!JSON.stringify(stored).includes('test-token'))
  } finally { await rm(folder, { recursive: true, force: true }) }
})

test('正文与 JSON 标准输入冲突在读取前拒绝，拆分 UTF-8 字节无乱码', async () => {
  const schema = tools.find(tool => tool.name === 'create_note').inputSchema
  await assert.rejects(() => argumentsForTool(['--file', '-', '--input-file', '-'], null, schema, Readable.from([])), /标准输入.*不能同时/)
  await assert.rejects(() => argumentsForTool(['--file', '-', '--input', '{"blocks":[]}'], null, schema, Readable.from([])), /只能选一种/)
  const bytes = Buffer.from('中文🙂\n\n下一段')
  const args = await argumentsForTool(['--file', '-'], null, schema, Readable.from([...bytes].map(byte => Buffer.from([byte]))))
  assert.equal(args.content.markdown, '中文🙂\n\n下一段')
  await assert.rejects(() => argumentsForTool(['--file', '-'], null, schema, Readable.from([Buffer.from([0xff])])), /有效的 UTF-8/)
})

test('标签友好命令传正确 mode 与 CAS，不自动读取或覆盖版本', async () => {
  for (const [command, mode] of [['add', 'add'], ['remove', 'remove'], ['set', 'replace'], ['clear', 'clear']]) {
    const f = fixture()
    await run(['notes', 'tags', command, 'd1', '--expected-rev', '7', ...(command === 'clear' ? [] : ['--tags', '["工作"]'])], f.options)
    assert.equal(f.calls[0].args.mode, mode)
    assert.equal(f.calls[0].args.expected_rev, 7)
  }
  const f = fixture()
  await assert.rejects(() => run(['notes', 'tags', 'add', 'd1', '--tags', '["工作"]'], f.options), /缺少必填字段/)
  await assert.rejects(() => run(['notes', 'tags', 'add', 'd1', '--expected-rev', '7', '--mode', 'clear'], f.options), /mode.*不一致/)
  assert.equal(f.calls.length, 0)
})

test('排程的全部可空字段必须出现，超时后可凭回执核对，写请求仅一次', async () => {
  const folder = await mkdtemp(join(tmpdir(), 'wao-cli-daily-'))
  const file = join(folder, 'receipt.json'), f = fixture({ fail: '网络超时' })
  const args = ['tasks', 'schedule', 't1', '--expected-edit-version', 'v1', '--start', 'null', '--start-at', 'null', '--end-at', 'null', '--schedule-period', 'null', '--receipt-file', file]
  try {
    await assert.rejects(() => run(args, f.options), /结果待核对.*未自动重试/)
    assert.equal(f.calls.length, 1)
    assert.equal(f.calls[0].args.start, null)
    assert.match(f.calls[0].args.operation_id, /^[a-f0-9-]{36}$/)
    const stored = JSON.parse(await readFile(file, 'utf8'))
    assert.equal(stored.operation_id, f.calls[0].args.operation_id)
    assert.equal(stored.state, 'unknown')
    await assert.rejects(() => run(args, f.options), /回执已存在/)
    assert.equal(f.calls.length, 1)
    await assert.rejects(() => run(['tasks', 'schedule', 't1', '--expected-edit-version', 'v1', '--start', 'null'], f.options), /缺少必填字段/)
  } finally { await rm(folder, { recursive: true, force: true }) }
})

test('导出拒绝意外覆盖，force 不吞位置参数，标准输出只含内容', async () => {
  const folder = await mkdtemp(join(tmpdir(), 'wao-cli-daily-')), file = join(folder, 'out.md')
  const doc = { id: 'd1', title: '标题', rev: 3, content: { markdown: '正文' } }
  const f = fixture({ data: () => doc })
  try {
    await writeFile(file, '已有文件')
    await assert.rejects(() => run(['notes', 'export', 'd1', '--file', file], f.options), /文件已存在/)
    assert.equal(await readFile(file, 'utf8'), '已有文件')
    await run(['documents', 'export', '--force', 'd1', '--format', 'md', '--file', file], f.options)
    assert.equal(await readFile(file, 'utf8'), '# 标题\n\n正文\n')
    const pipe = fixture({ data: () => doc })
    await run(['notes', 'export', 'd1', '--format', 'json', '--file', '-'], pipe.options)
    assert.deepEqual(JSON.parse(pipe.stdout.text), doc)
    await assert.rejects(() => run(['notes', 'list', '--force'], f.options), /仅适用于/)
  } finally { await rm(folder, { recursive: true, force: true }) }
})

test('动态帮助展示当前 schema；无工具权限时不伪造帮助或调用', async () => {
  const f = fixture()
  await run(['notes', 'create', '--help'], f.options)
  assert.match(f.stdout.text, /--document-id/)
  assert.equal(f.calls.length, 0)
  const schema = fixture()
  await run(['tools', 'describe', 'update_note_tags', '--output', 'json'], schema.options)
  assert.equal(JSON.parse(schema.stdout.text).inputSchema.required.includes('expected_rev'), true)
  const readOnly = fixture({ visible: tools.filter(tool => tool.annotations?.readOnlyHint) })
  await assert.rejects(() => run(['notes', 'create', '--title', '无权'], readOnly.options), /没有工具/)
  assert.equal(readOnly.calls.length, 0)
})

test('doctor 与 auth status 查询身份权限版本，旧服务兼容且不推测身份', async () => {
  const info = { member: { id: 'm1', name: '成员' }, permissions: { write: false, daily: false, hostedHtml: false }, version: '1.2.3' }
  const f = fixture({ data: () => info })
  await run(['auth', 'status', '--output', 'json'], f.options)
  assert.deepEqual(JSON.parse(f.stdout.text).workspace, info)
  const old = fixture({ visible: tools.filter(tool => tool.name !== 'get_workspace_info') })
  await run(['doctor'], old.options)
  assert.match(old.stdout.text, /不可查询/)
  const diagnostics = JSON.parse(old.stdout.text)
  assert.equal(diagnostics.connectionStatus, 'connected')
  assert.match(diagnostics.compatibility, /未授权.*或服务端尚未提供/)
  assert.match(diagnostics.authorizationUrl, /\/capabilities\?area=connections$/)
  assert.deepEqual(diagnostics.availableTools, tools.filter(tool => tool.name !== 'get_workspace_info').map(tool => tool.name))
  assert.equal(old.calls.length, 0)
  const version = fixture()
  await run(['--version'], { ...version.options, tokenProvider: async () => { throw new Error('不应读取认证') } })
  assert.equal(version.stdout.text.trim(), '0.2.0')
})

test('分页表格渲染 items 和数量，JSON 输出保留分页对象', () => {
  const page = { items: [{ id: 'd1', title: '中文' }], total: 9, offset: 2, limit: 1, hasMore: true }
  assert.match(formatOutput(page), /中文.*\n共 9 条，偏移 2，本页 1 条，还有下一页/s)
  assert.deepEqual(JSON.parse(formatOutput(page, 'json')), page)
  assert.match(formatOutput({ items: [], total: 0, offset: 0 }), /无结果.*共 0 条/s)
})

test('动态参数验证 JSON 类型、嵌套必填、范围与格式，缺少 CAS 不能绕过', async () => {
  const schema = { properties: {
    expected_rev: integer,
    limit: { type: 'integer', minimum: 1, maximum: 100 },
    content: { type: 'object', properties: { markdown: string }, required: ['markdown'], additionalProperties: false },
    name: { type: 'string', minLength: 1, maxLength: 8, pattern: '^[a-z]+$' },
  }, required: ['expected_rev'] }
  await assert.rejects(() => argumentsForTool(['--input', '{"expected_rev":"1"}'], null, schema), /不符合工具声明的类型/)
  await assert.rejects(() => argumentsForTool(['--expected-rev', '1', '--limit', '101'], null, schema), /超出工具允许范围/)
  await assert.rejects(() => argumentsForTool(['--expected-rev', '1', '--limit', ''], null, schema), /必须是整数/)
  await assert.rejects(() => argumentsForTool(['--expected-rev', '1', '--content', '{}'], null, schema), /缺少必填字段/)
  await assert.rejects(() => argumentsForTool(['--expected-rev', '1', '--name', 'UPPER'], null, schema), /不符合工具声明的格式/)
  await assert.rejects(() => argumentsForTool(['--input', '{"expected_rev":null}'], null, schema), /不符合工具声明的类型/)
})

test('文档列表只在发现分页字段后默认 offset=0/limit=50，显式 JSON 可覆盖', async () => {
  const base = { name: 'list_documents', annotations: { readOnlyHint: true }, inputSchema: { properties: { kb_id: string, limit: integer }, required: ['kb_id'] } }
  const old = fixture({ visible: [base] })
  await run(['documents', 'list', 'kb1'], old.options)
  assert.deepEqual(old.calls[0].args, { kb_id: 'kb1' })
  const currentTool = { ...base, inputSchema: { ...base.inputSchema, properties: { ...base.inputSchema.properties, offset: integer } } }
  const current = fixture({ visible: [currentTool] })
  await run(['documents', 'list', 'kb1'], current.options)
  assert.deepEqual(current.calls[0].args, { kb_id: 'kb1', offset: 0, limit: 50 })
  await run(['documents', 'list', 'kb1', '--input', '{"offset":50,"limit":25}'], current.options)
  assert.deepEqual(current.calls[1].args, { kb_id: 'kb1', offset: 50, limit: 25 })
})

test('日小记自动生成文档与操作标识并同时保存，业务日期仍显式必填', async () => {
  const folder = await mkdtemp(join(tmpdir(), 'wao-cli-daily-'))
  const file = join(folder, 'new.json'), explicit = join(folder, 'existing.json'), f = fixture()
  try {
    await run(['daily', 'record', '--business-date', '2026-10-04', '--file', '-', '--receipt-file', file], { ...f.options, stdin: Readable.from(['正文']) })
    const first = f.calls[0].args, receipt = JSON.parse(await readFile(file, 'utf8'))
    assert.match(first.document_id, /^[a-f0-9]{32}$/)
    assert.match(first.operation_id, /^[a-f0-9-]{36}$/)
    assert.equal(receipt.document_id, first.document_id)
    assert.equal(receipt.operation_id, first.operation_id)
    await run(['daily', 'record', '--business-date', '2026-10-04', '--document-id', 'existing-id', '--receipt-file', explicit], f.options)
    assert.equal(f.calls[1].args.document_id, 'existing-id')
    await assert.rejects(() => run(['daily', 'record'], f.options), /缺少必填字段.*business_date/)
    assert.equal(f.calls.length, 2)
  } finally { await rm(folder, { recursive: true, force: true }) }
})

test('文本 schema 的百万字符上限与标题 200 字符分开，合法长篇 UTF-8 可导入', async () => {
  const text = '中🙂\n\n'.repeat(10000)
  const schema = { properties: { title: { type: 'string', maxLength: 200 }, text: { type: 'string', maxLength: 1000000 }, content: { type: 'object' } } }
  const parsed = await argumentsForTool(['--title', '标题', '--text', text], null, schema)
  assert.equal(parsed.text, text)
  const fromFile = await argumentsForTool(['--file', '-'], null, schema, Readable.from([Buffer.from(text)]))
  assert.equal(fromFile.content.markdown, text)
  await assert.rejects(() => argumentsForTool(['--title', 'a'.repeat(201)], null, schema), /title 长度超出/)
})

test('真实 HTTP 只读目录拒绝写入，工具错误造成真实进程非零退出', async () => {
  let writes = 0
  const server = createServer(async (request, response) => {
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    const body = JSON.parse(Buffer.concat(chunks).toString())
    response.setHeader('content-type', 'application/json')
    if (body.method === 'tools/list') response.end(JSON.stringify({ result: { tools: tools.filter(tool => tool.annotations?.readOnlyHint) } }))
    else { writes++; response.end(JSON.stringify({ result: { isError: true, content: [{ type: 'text', text: '真实业务拒绝' }] } })) }
  })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const url = `http://127.0.0.1:${server.address().port}/api/mcp/workspace`
  const execute = args => new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [fileURLToPath(new URL('../bin/wao.js', import.meta.url)), ...args, '--url', url], { env: { ...process.env, WAO_TOKEN: 'test-token' }, windowsHide: true })
    let stderr = ''
    child.stdout.resume()
    child.stderr.on('data', data => { stderr += data })
    child.once('error', reject)
    child.once('close', code => resolve({ code, stderr }))
  })
  try {
    const denied = await execute(['notes', 'create', '--title', '拒绝'])
    assert.equal(denied.code, 1)
    assert.match(denied.stderr, /没有工具/)
    assert.equal(writes, 0)
    const failed = await execute(['notes', 'get', 'd1'])
    assert.equal(failed.code, 1)
    assert.match(failed.stderr, /真实业务拒绝/)
    assert.equal(writes, 1)
  } finally { await new Promise(resolve => server.close(resolve)) }
})
