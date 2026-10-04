import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { Readable } from 'node:stream'
import { after, before, test } from 'node:test'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { basename, dirname, join, resolve } from 'node:path'
import { spawn } from 'node:child_process'
import { COMMANDS, argumentsForTool, formatOutput, run } from '../src/cli.js'
import { createClient, endpoint, hostedPageUrl } from '../src/client.js'
import { getToken, normalizeWorkspaceToken, readToken, removeToken, storeToken } from '../src/auth.js'

let server, url, lastCall, hostedCalls, uploadedMultipart
const WORKSPACE_TOKEN = `wao_workspace_${'A'.repeat(43)}`

test('Windows 真正使用 DPAPI 加密存取及删除，隔离于用户凭据目录', { skip: process.platform !== 'win32' }, async () => {
  const folder = await mkdtemp(join(tmpdir(), 'wao-dpapi-'))
  const windowsRunner = (script, input = '') => new Promise((resolve, reject) => {
    const child = spawn('powershell.exe', ['-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], {
      windowsHide: true, env: { ...process.env, APPDATA: folder }, stdio: ['pipe', 'pipe', 'pipe'],
    })
    let stdout = ''
    child.stdout.on('data', chunk => { stdout += chunk.toString() })
    child.stderr.resume()
    child.once('error', reject)
    child.once('close', code => code === 0 ? resolve(stdout) : reject(new Error('真实 DPAPI 操作失败')))
    child.stdin.end(input)
  })
  const options = { platform: 'win32', env: {}, windowsRunner }
  try {
    assert.equal(await getToken(options), null)
    await storeToken(WORKSPACE_TOKEN, options)
    const cipher = await readFile(join(folder, 'WAO', 'workspace-token.dpapi'))
    assert.ok(!cipher.includes(Buffer.from(WORKSPACE_TOKEN)), '落盘仅包含密文')
    assert.equal(await getToken(options), WORKSPACE_TOKEN)
    await removeToken(options)
    assert.equal(await getToken(options), null)
  } finally {
    assert.equal(dirname(resolve(folder)), resolve(tmpdir()))
    assert.ok(basename(folder).startsWith('wao-dpapi-'))
    await rm(folder, { recursive: true, force: true })
  }
})
const ALL_TOOLS = [
  { name: 'update_project', inputSchema: { properties: { project_id: { type: 'string' }, due: { type: 'string' } }, required: ['project_id'] }, annotations: { readOnlyHint: false } },
  { name: 'update_subtask_status', inputSchema: { properties: { task_id: { type: 'string' }, subtask_id: { type: 'string' }, status: { type: 'string' }, expected_subtask_version: { type: 'string' } }, required: ['task_id', 'subtask_id', 'status', 'expected_subtask_version'] }, annotations: { readOnlyHint: false } },
  { name: 'list_projects', inputSchema: { properties: {}, required: [] }, annotations: { readOnlyHint: true } },
  { name: 'list_open_tasks', inputSchema: { properties: { mine_only: { type: 'boolean' }, limit: { type: 'integer' }, project_id: { type: 'string' } }, required: [] }, annotations: { readOnlyHint: true } },
  { name: 'create_task', inputSchema: { properties: { project_id: { type: 'string' }, title: { type: 'string' } }, required: ['project_id', 'title'] }, annotations: { readOnlyHint: false } },
  { name: 'update_document', inputSchema: { properties: { document_id: { type: 'string' }, blocks: { type: 'array' }, expected_rev: { type: 'integer' } }, required: ['document_id'] }, annotations: { readOnlyHint: false } },
  { name: 'future_tool', inputSchema: { properties: { value: { type: 'string' } }, required: ['value'] }, annotations: { readOnlyHint: true } },
]

test('项目截止日期通过已发现工具发送，省略与空串保持不同语义', async () => {
  for (const [flags, expected] of [
    [['--due', '2027-01-15'], { project_id: 'p1', due: '2027-01-15' }],
    [['--input', '{"due":""}'], { project_id: 'p1', due: '' }],
    [[], { project_id: 'p1' }],
  ]) {
    await run(['projects', 'update', 'p1', ...flags, '--url', url], { tokenProvider: async () => 'good-token', stdout: output().stream })
    assert.deepEqual(lastCall, { name: 'update_project', arguments: expected })
  }
})

before(async () => {
  hostedCalls = []
  server = createServer(async (request, response) => {
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    const raw = Buffer.concat(chunks)
    const target = new URL(request.url, 'http://localhost')
    if (request.method === 'GET' && target.pathname === '/api/hosted-pages/publish-access') {
      hostedCalls.push({ kind: 'preflight', authorization: request.headers.authorization, projectId: target.searchParams.get('projectId') })
      response.setHeader('Content-Type', 'application/json')
      if (request.headers.authorization === 'Bearer html-token') response.end(JSON.stringify({ code: 0, data: true }))
      else if (request.headers.authorization === 'Bearer no-publish-token') {
        response.statusCode = 403
        response.end(JSON.stringify({ code: 403, message: 'no publish scope' }))
      } else {
        response.statusCode = 401
        response.end(JSON.stringify({ code: 401, message: 'unauthorized' }))
      }
      return
    }
    if (request.method === 'POST' && target.pathname === '/api/hosted-pages') {
      hostedCalls.push({ kind: 'publish', authorization: request.headers.authorization, contentType: request.headers['content-type'] })
      uploadedMultipart = raw
      response.setHeader('Content-Type', 'application/json')
      if (request.headers.authorization !== 'Bearer html-token') {
        response.statusCode = 403
        response.end(JSON.stringify({ code: 403, message: 'no publish scope' }))
      } else response.end(JSON.stringify({ code: 0, data: { slug: 'demo', url: '/api/pages/demo' } }))
      return
    }
    if (request.method === 'GET' && target.pathname === '/api/pages/demo') {
      hostedCalls.push({ kind: 'anonymous', authorization: request.headers.authorization })
      const encoded = Buffer.from('<!doctype html><title>demo</title>').toString('base64')
      response.setHeader('Content-Type', 'text/html; charset=UTF-8')
      response.end(`<!doctype html><script>const encodedHtml = '${encoded}';</script>`)
      return
    }
    const body = JSON.parse(raw.toString())
    response.setHeader('Content-Type', 'application/json')
    if (!['Bearer good-token', `Bearer ${WORKSPACE_TOKEN}`].includes(request.headers.authorization)) {
      response.statusCode = 401
      response.end(JSON.stringify({ jsonrpc: '2.0', id: body.id, error: { message: 'denied' } }))
      return
    }
    const readOnly = request.headers['x-read-only'] === 'true'
    const tools = readOnly ? ALL_TOOLS.filter(tool => tool.annotations.readOnlyHint) : ALL_TOOLS
    if (body.method === 'tools/list') response.end(JSON.stringify({ jsonrpc: '2.0', id: body.id, result: { tools } }))
    else if (body.method === 'tools/call') {
      lastCall = body.params
      const isError = body.params.name === 'future_tool' && body.params.arguments.value === 'fail'
      response.end(JSON.stringify({ jsonrpc: '2.0', id: body.id, result: isError
        ? { isError: true, content: [{ type: 'text', text: '业务规则拒绝' }] }
        : { isError: false, structuredContent: { data: body.params.name === 'list_projects' ? [{ id: 'p1', name: '测试项目' }] : body.params.arguments } } }))
    } else { response.statusCode = 400; response.end('{}') }
  })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  url = `http://127.0.0.1:${server.address().port}/api/mcp/workspace`
})
after(() => server.close())

function output() {
  let value = ''
  return { stream: { write(chunk) { value += chunk } }, get text() { return value } }
}

test('通过真实 HTTP 请求列出工具并调用常用子命令', async () => {
  const out = output()
  await run(['tasks', 'open', '--mine-only', 'false', '--limit', '10', '--project-id', 'p1', '--output', 'json', '--url', url],
    { tokenProvider: async () => 'good-token', stdout: out.stream })
  assert.deepEqual(lastCall, { name: 'list_open_tasks', arguments: { mine_only: false, limit: 10, project_id: 'p1' } })
  assert.deepEqual(JSON.parse(out.text), lastCall.arguments)
})

test('托管页发布需显式授权，上传后匿名核对同一份 HTML 字节', async () => {
  const folder = await mkdtemp(join(tmpdir(), 'wao-cli-'))
  const file = join(folder, 'share.html')
  const html = '<!doctype html><title>demo</title>'
  try {
    await writeFile(file, html, 'utf8')
    hostedCalls = []
    uploadedMultipart = null
    const out = output()
    await run(['pages', 'publish', '--file', file, '--title', '分享稿', '--slug', 'demo', '--project-id', 'p1', '--yes', '--url', url], {
      tokenProvider: async () => 'html-token', stdout: out.stream,
    })
    assert.match(out.text, /http:\/\/127\.0\.0\.1:\d+\/api\/pages\/demo/)
    assert.match(out.text, /匿名访问核验通过/)
    assert.deepEqual(hostedCalls.map(call => call.kind), ['preflight', 'publish', 'anonymous'])
    assert.equal(hostedCalls[0].authorization, 'Bearer html-token')
    assert.equal(hostedCalls[0].projectId, 'p1')
    assert.equal(hostedCalls[1].authorization, 'Bearer html-token')
    assert.match(hostedCalls[1].contentType, /^multipart\/form-data; boundary=/)
    assert.equal(hostedCalls[2].authorization, undefined)
    assert.ok(uploadedMultipart.includes(Buffer.from(html)))
  } finally { await rm(folder, { recursive: true, force: true }) }
})

test('缺少发布范围、未确认或无效 UTF-8 时不创建页面', async () => {
  const folder = await mkdtemp(join(tmpdir(), 'wao-cli-'))
  const file = join(folder, 'share.html')
  try {
    await writeFile(file, '<html>ok</html>', 'utf8')
    hostedCalls = []
    await assert.rejects(() => run(['pages', 'publish', '--file', file, '--title', '分享稿', '--slug', 'demo', '--yes', '--url', url], {
      tokenProvider: async () => 'no-publish-token', stdout: output().stream,
    }), /没有发布 HTML 页面权限/)
    assert.deepEqual(hostedCalls.map(call => call.kind), ['preflight'])

    hostedCalls = []
    await assert.rejects(() => run(['pages', 'publish', '--file', file, '--title', '分享稿', '--slug', 'demo', '--url', url], {
      tokenProvider: async () => 'html-token', stdout: output().stream, stdin: Readable.from([]),
    }), /公开发布需要交互确认/)
    assert.deepEqual(hostedCalls.map(call => call.kind), ['preflight'])

    hostedCalls = []
    await writeFile(file, Buffer.from([0xff, 0xfe]))
    await assert.rejects(() => run(['pages', 'publish', '--file', file, '--title', '分享稿', '--slug', 'demo', '--yes', '--url', url], {
      tokenProvider: async () => 'html-token', stdout: output().stream,
    }), /有效的 UTF-8/)
    assert.deepEqual(hostedCalls, [])
  } finally { await rm(folder, { recursive: true, force: true }) }
})

test('Windows DPAPI 凭据将令牌经 stdin 传递，不拼入 PowerShell 命令', async () => {
  const calls = []
  const runner = async (script, input = '') => {
    calls.push({ script, input })
    return script.includes('Unprotect') ? WORKSPACE_TOKEN : ''
  }
  assert.equal(await getToken({ platform: 'win32', env: {}, windowsRunner: runner }), WORKSPACE_TOKEN)
  await storeToken(WORKSPACE_TOKEN, { platform: 'win32', windowsRunner: runner })
  await removeToken({ platform: 'win32', windowsRunner: runner })
  assert.equal(calls.length, 3)
  assert.match(calls[0].script, /DataProtectionScope.*CurrentUser/)
  assert.ok(calls[0].script.includes('\n'))
  assert.match(calls[0].script, /Unprotect/)
  assert.match(calls[1].script, /Protect/)
  assert.equal(calls[1].input, WORKSPACE_TOKEN)
  assert.ok(calls.every(call => !call.script.includes(WORKSPACE_TOKEN)))
  assert.match(calls[2].script, /Remove-Item/)
  assert.equal(calls[2].input, '')
})

test('通用调用可使用服务端新增工具，无需更新 CLI 子命令', async () => {
  const out = output()
  await run(['tools', 'call', 'future_tool', '--input', '{"value":"hello"}', '--url', url],
    { tokenProvider: async () => 'good-token', stdout: out.stream })
  assert.deepEqual(lastCall, { name: 'future_tool', arguments: { value: 'hello' } })
  assert.match(out.text, /hello/)
  await assert.rejects(() => run(['tools', 'call', 'future_tool', '--input', '{"value":"fail"}', '--url', url],
    { tokenProvider: async () => 'good-token', stdout: out.stream }), /业务规则拒绝/)
})

test('登录接受真实工作区令牌前缀和复制的完整连接说明', async () => {
  const instruction = `请将以下 WAO MCP Server 添加到当前客户端。\nToken：${WORKSPACE_TOKEN}\n验证 tools/list。`
  assert.equal(normalizeWorkspaceToken(WORKSPACE_TOKEN), WORKSPACE_TOKEN)
  assert.equal(normalizeWorkspaceToken(instruction), WORKSPACE_TOKEN)
  assert.throws(() => normalizeWorkspaceToken('请将以下 WAO MCP Server 添加到当前客户端。'), /wao_workspace_/)
  assert.throws(() => normalizeWorkspaceToken(`wao_mcp_${'A'.repeat(43)}`), /记忆 MCP/)
  let stored
  await run(['auth', 'login', '--token-stdin', '--url', url], {
    stdin: Readable.from([instruction]), stdout: output().stream,
    tokenStore: async token => { stored = token },
  })
  assert.equal(stored, WORKSPACE_TOKEN)
  const tty = Readable.from([Buffer.from('请将以下 WAO MCP Server 添加到当前客户端。\n'),
    Buffer.from(`服务名称：WAO\nToken：${WORKSPACE_TOKEN}\n`)])
  tty.isTTY = true
  tty.setRawMode = () => {}
  const entered = await readToken({ stdin: tty, stdout: output().stream })
  assert.equal(normalizeWorkspaceToken(entered), WORKSPACE_TOKEN)
})

test('令牌失效、只读权限和参数错误均阻止调用', async () => {
  const client = createClient({ url, token: 'invalid' })
  await assert.rejects(() => client.listTools(), /令牌无效/)
  const originalFetch = fetch
  const readClient = createClient({ url, token: 'good-token', fetchImpl: (target, options) =>
    originalFetch(target, { ...options, headers: { ...options.headers, 'x-read-only': 'true' } }) })
  await assert.rejects(() => readClient.call('create_task', { title: '无权写入' }), /没有工具/)
  await assert.rejects(() => run(['tasks', 'create', '--title', '缺项目', '--url', url],
    { tokenProvider: async () => 'good-token', stdout: output().stream }), /缺少必填字段/)
  await assert.rejects(() => run(['tasks', 'open', '--limit', 'abc', '--url', url],
    { tokenProvider: async () => 'good-token', stdout: output().stream }), /必须是整数/)
})

test('文档替换可从标准输入读取复杂 JSON 参数', async () => {
  const out = output()
  await run(['documents', 'update', 'd1', '--input-file', '-', '--output', 'json', '--url', url], {
    tokenProvider: async () => 'good-token', stdout: out.stream,
    stdin: Readable.from(['{"blocks":[{"text":"hello"}],"expected_rev":3}']),
  })
  assert.deepEqual(lastCall.arguments, { document_id: 'd1', blocks: [{ text: 'hello' }], expected_rev: 3 })
})

test('命令表覆盖服务端工作区 MCP 工具目录', async () => {
  const names = [...COMMANDS.values()].map(([name]) => name)
  const source = await readFile(new URL('../../wao-server/src/main/java/cn/wayuwa/wao/service/WorkspaceMcpTools.java', import.meta.url), 'utf8')
  const serverTools = [...source.matchAll(/add\(list, "([a-z_]+)"/g)].map(match => match[1])
  assert.equal(new Set(names).size, names.length)
  assert.deepEqual(names.sort(), serverTools.sort())
})

test('URL 和输出不会泄露令牌或接受远程明文 HTTP', async () => {
  assert.throws(() => endpoint('http://wao.example/api/mcp/workspace'), /HTTPS/)
  assert.throws(() => endpoint('https://token@wao.example/api/mcp/workspace'), /不得包含账号/)
  assert.throws(() => hostedPageUrl(url, { slug: 'demo', url: 'https://evil.example/api/pages/demo' }), /不匹配/)
  assert.match(formatOutput([{ id: 'p1', name: '项目' }]), /项目/)
  const args = await argumentsForTool(['--mine-only', 'false'], null,
    { properties: { mine_only: { type: 'boolean' } }, required: [] })
  assert.deepEqual(args, { mine_only: false })
})

 test('子任务状态命令传递标识和快照，缺少快照时拒绝', async () => {
  const args = ['subtasks', 'status', 's1', '--task-id', 't1', '--status', '待验证', '--url', url]
  await assert.rejects(() => run(args, { tokenProvider: async () => 'good-token', stdout: output().stream }), /缺少必填字段/)
  await run([...args, '--expected-subtask-version', 'v1'], { tokenProvider: async () => 'good-token', stdout: output().stream })
  assert.deepEqual(lastCall, { name: 'update_subtask_status', arguments: { subtask_id: 's1', task_id: 't1', status: '待验证', expected_subtask_version: 'v1' } })
})
