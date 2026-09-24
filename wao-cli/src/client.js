const DEFAULT_URL = 'https://wao.wayuwa.cn/api/mcp/workspace'

export function endpoint(value = process.env.WAO_URL || DEFAULT_URL) {
  let url
  try { url = new URL(value) } catch { throw new Error('WAO_URL 必须是完整 URL') }
  if (url.username || url.password || url.search || url.hash) throw new Error('WAO_URL 不得包含账号、查询参数或片段')
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname))) {
    throw new Error('WAO_URL 必须使用 HTTPS；仅本机测试可使用 HTTP')
  }
  return url.toString()
}

export function createClient({ url = endpoint(), token, fetchImpl = fetch } = {}) {
  if (!token) throw new Error('未找到工作区令牌。运行 wao auth login，或设置 WAO_TOKEN')
  let nextId = 1
  async function request(method, params) {
    let response
    try {
      response = await fetchImpl(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
          'Authorization': `Bearer ${token}`,
        },
        body: JSON.stringify({ jsonrpc: '2.0', id: nextId++, method, ...(params === undefined ? {} : { params }) }),
        signal: AbortSignal.timeout(20_000),
      })
    } catch (error) {
      throw new Error(`无法连接 WAO：${error.message}`)
    }
    if (response.status === 401) throw new Error('工作区令牌无效或已吊销；请重新登录')
    if (!response.ok) throw new Error(`WAO 返回 HTTP ${response.status}`)
    let body
    try { body = await response.json() } catch { throw new Error('WAO 返回的不是 JSON') }
    if (body.error) throw new Error(body.error.message || `MCP 错误 ${body.error.code}`)
    if (!body.result) throw new Error('WAO 响应缺少 result')
    return body.result
  }
  return {
    async listTools() {
      const result = await request('tools/list')
      if (!Array.isArray(result.tools)) throw new Error('WAO 工具目录格式无效')
      return result.tools
    },
    async call(name, args = {}) {
      const tools = await this.listTools()
      if (!tools.some(tool => tool.name === name)) throw new Error(`当前令牌没有工具 ${name}，或服务端尚未提供该工具`)
      const result = await request('tools/call', { name, arguments: args })
      if (result.isError) throw new Error(result.content?.find(part => part.type === 'text')?.text || '工具调用失败')
      if (result.structuredContent && Object.hasOwn(result.structuredContent, 'data')) return result.structuredContent.data
      const text = result.content?.find(part => part.type === 'text')?.text
      if (text === undefined) throw new Error('工具结果缺少数据')
      try { return JSON.parse(text) } catch { return text }
    },
  }
}
