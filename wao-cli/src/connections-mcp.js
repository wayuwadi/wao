import { createInterface } from 'node:readline'
import { APPLET_PROFILE } from './applet.js'
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
const DEVOPS_ENVIRONMENTS = {
  name: 'devops_environments',
  description: '优先使用 WAO 密码本中的设备授权，只读查询星狮 DevOps 的可访问环境。',
  inputSchema: { type: 'object', additionalProperties: false, properties: {} },
  annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: true },
}

const APPLET_STATUS = {
  name: 'applet_status',
  description: '使用 WAO 密码本中的设备授权，通过原 Test Passport 检查本机指定配置的小程序登录状态。',
  inputSchema: {
    type: 'object', additionalProperties: false, required: ['profile'],
    properties: { profile: { type: 'string', pattern: APPLET_PROFILE.source, description: '已配置的小程序本机配置名' } },
  },
  annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: true },
}

export async function serveConnections({ stdin, stdout, version, query = codeupStatus, queryEnvironments, queryApplet }) {
  const tools = [CODEUP_STATUS]
  if (queryEnvironments) tools.push(DEVOPS_ENVIRONMENTS)
  if (queryApplet) tools.push(APPLET_STATUS)
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
    if (method === 'tools/list') { send({ id, result: { tools } }); continue }
    if (method !== 'tools/call') { fail(id, -32601, '不支持的方法'); continue }
    if (!tools.some(tool => tool.name === params?.name)) { fail(id, -32602, '不支持的工具'); continue }
    const args = params.arguments ?? {}
    const codeup = params.name === CODEUP_STATUS.name
    const applet = params.name === APPLET_STATUS.name
    if (codeup && (Object.keys(args).length !== 1 || !Number.isSafeInteger(args.number) || args.number < 1)) {
      fail(id, -32602, '请提供一个正整数合并请求编号 number')
      continue
    }
    if (applet && (Object.keys(args).length !== 1 || typeof args.profile !== 'string' || !APPLET_PROFILE.test(args.profile))) {
      fail(id, -32602, '请提供一个本机小程序配置名 profile')
      continue
    }
    if (!codeup && !applet && (typeof args !== 'object' || Array.isArray(args) || Object.keys(args).length !== 0)) {
      fail(id, -32602, '环境查询无需参数')
      continue
    }
    try {
      const result = codeup ? await query(args.number) : applet ? await queryApplet(args.profile) : await queryEnvironments()
      send({ id, result: { content: [{ type: 'text', text: JSON.stringify(result) }], structuredContent: result } })
    } catch (error) {
      send({ id, result: { isError: true, content: [{ type: 'text', text: error.message }] } })
    }
  }
}
