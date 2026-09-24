import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { Readable } from 'node:stream'
import { after, before, test } from 'node:test'
import { readFile } from 'node:fs/promises'
import { COMMANDS, argumentsForTool, formatOutput, run } from '../src/cli.js'
import { createClient, endpoint } from '../src/client.js'

let server, url, lastCall
const ALL_TOOLS = [
  { name: 'list_projects', inputSchema: { properties: {}, required: [] }, annotations: { readOnlyHint: true } },
  { name: 'list_open_tasks', inputSchema: { properties: { mine_only: { type: 'boolean' }, limit: { type: 'integer' }, project_id: { type: 'string' } }, required: [] }, annotations: { readOnlyHint: true } },
  { name: 'create_task', inputSchema: { properties: { project_id: { type: 'string' }, title: { type: 'string' } }, required: ['project_id', 'title'] }, annotations: { readOnlyHint: false } },
  { name: 'update_document', inputSchema: { properties: { document_id: { type: 'string' }, blocks: { type: 'array' }, expected_rev: { type: 'integer' } }, required: ['document_id'] }, annotations: { readOnlyHint: false } },
  { name: 'future_tool', inputSchema: { properties: { value: { type: 'string' } }, required: ['value'] }, annotations: { readOnlyHint: true } },
]

before(async () => {
  server = createServer(async (request, response) => {
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    const body = JSON.parse(Buffer.concat(chunks).toString())
    response.setHeader('Content-Type', 'application/json')
    if (request.headers.authorization !== 'Bearer good-token') {
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

test('通用调用可使用服务端新增工具，无需更新 CLI 子命令', async () => {
  const out = output()
  await run(['tools', 'call', 'future_tool', '--input', '{"value":"hello"}', '--url', url],
    { tokenProvider: async () => 'good-token', stdout: out.stream })
  assert.deepEqual(lastCall, { name: 'future_tool', arguments: { value: 'hello' } })
  assert.match(out.text, /hello/)
  await assert.rejects(() => run(['tools', 'call', 'future_tool', '--input', '{"value":"fail"}', '--url', url],
    { tokenProvider: async () => 'good-token', stdout: out.stream }), /业务规则拒绝/)
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
  assert.match(formatOutput([{ id: 'p1', name: '项目' }]), /项目/)
  const args = await argumentsForTool(['--mine-only', 'false'], null,
    { properties: { mine_only: { type: 'boolean' } }, required: [] })
  assert.deepEqual(args, { mine_only: false })
})
