import { createInterface } from 'node:readline'
import { codeupStatus } from './codeup.js'

const PROTOCOL_VERSION = '2025-06-18'
const CODEUP_STATUS = {
  name: 'codeup_pr_status',
  description: '使用本机已配置的 Codeup CLI 查询合并请求的状态、目标分支及合并提交。',
  inputSchema: {
    type: 'object', additionalProperties: false, required: ['number'],
    properties: { number: { type: 'integer', minimum: 1, maximum: Number.MAX_SAFE_INTEGER, description: '仓库内合并请求编号' } },
  },
  annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: true },
}

export async function serveConnections({ stdin, stdout, version, query = codeupStatus }) {
  const lines = createInterface({ input: stdin, crlfDelay: Infinity, terminal: false })
  let initialized = false
  let ready = false
  const send = (body) => stdout.write(JSON.stringify({ jsonrpc: '2.0', ...body }) + '\n')
  const fail = (id, code, message) => send({ id, error: { code, message } })

  for await (const line of lines) {
    let request
    try { request = JSON.parse(line) } catch { fail(null, -32700, '无效的 JSON'); continue }
    const { id, method, params } = request ?? {}
    const isRequestId = typeof id === 'string' || Number.isSafeInteger(id)
    if (request?.jsonrpc !== '2.0' || typeof method !== 'string' || (id !== undefined && !isRequestId)) {
      fail(null, -32600, '无效的 JSON-RPC 请求')
      continue
    }
    if (id === undefined) {
      if (initialized && method === 'notifications/initialized') ready = true
      continue
    }
    if (method === 'ping') { send({ id, result: {} }); continue }
    if (method === 'initialize' && !initialized) {
      initialized = true
      send({ id, result: { protocolVersion: PROTOCOL_VERSION, capabilities: { tools: {} },
        serverInfo: { name: 'wao-local-connections', version } } })
      continue
    }
    if (!ready) { fail(id, -32000, '本机连接尚未初始化'); continue }
    if (method === 'tools/list') { send({ id, result: { tools: [CODEUP_STATUS] } }); continue }
    if (method !== 'tools/call') { fail(id, -32601, '不支持的方法'); continue }
    if (params?.name !== CODEUP_STATUS.name) { fail(id, -32602, '不支持的工具'); continue }

    const args = params.arguments
    if (!args || Object.keys(args).length !== 1 || !Number.isSafeInteger(args.number) || args.number < 1) {
      fail(id, -32602, '请提供一个正整数合并请求编号 number')
      continue
    }
    try {
      const result = await query(args.number)
      send({ id, result: { content: [{ type: 'text', text: JSON.stringify(result) }], structuredContent: result } })
    } catch (error) {
      send({ id, result: { isError: true, content: [{ type: 'text', text: error.message }] } })
    }
  }
}
