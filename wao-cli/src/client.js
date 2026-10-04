const DEFAULT_URL = 'https://wao.wayuwa.cn/api/mcp/workspace'
const MCP_PATH = '/api/mcp/workspace'
const MAX_HTML_BYTES = 10 * 1024 * 1024

export function hostedPagesEndpoint(workspaceEndpoint = endpoint()) {
  const url = new URL(endpoint(workspaceEndpoint))
  const path = url.pathname.replace(/\/+$/, '')
  if (!path.endsWith(MCP_PATH)) throw new Error('WAO 工作区地址路径须以 /api/mcp/workspace 结尾，才能发布托管 HTML')
  url.pathname = path.slice(0, -MCP_PATH.length) + '/api/hosted-pages'
  return url.toString()
}

export function hostedPageUrl(workspaceEndpoint, page) {
  const base = new URL(endpoint(workspaceEndpoint))
  const expectedPath = '/api/pages/' + encodeURIComponent(page.slug)
  const url = new URL(page.url, base.origin)
  if (url.origin !== base.origin || url.pathname !== expectedPath || url.search || url.hash) {
    throw new Error('WAO 返回的公开链接与本次页面不匹配，已停止匿名验证')
  }
  return url
}

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
    async checkHostedPagePermission(projectId) {
      const target = new URL(hostedPagesEndpoint(url))
      target.pathname += '/publish-access'
      if (projectId) target.searchParams.set('projectId', projectId)
      let response
      try {
        response = await fetchImpl(target, {
          method: 'GET',
          headers: { Accept: 'application/json', Authorization: 'Bearer ' + token },
          redirect: 'error',
          signal: AbortSignal.timeout(20_000),
        })
      } catch (error) {
        throw new Error('无法检查 WAO 页面发布权限：' + error.message)
      }
      if (response.status === 401) throw new Error('工作区令牌无效或已吊销；请重新登录')
      if (response.status === 403) throw new Error('当前工作区令牌没有发布 HTML 页面权限；请在能力中心“我的连接”的 CLI/MCP 新授权中选择发布 HTML 页面')
      if (!response.ok) throw new Error('WAO 权限检查返回 HTTP ' + response.status)
      let body
      try { body = await response.json() } catch { throw new Error('WAO 权限检查返回的不是 JSON') }
      if (body.code !== 0 || body.data !== true) throw new Error(body.message || 'WAO 未确认当前令牌具有发布权限')
      return true
    },
    async publishHostedPage({ html, fileName, title, slug, projectId }) {
      if (!Buffer.isBuffer(html) || html.length === 0 || html.length > MAX_HTML_BYTES) {
        throw new Error('HTML 文件大小必须大于 0 且不超过 10 MB')
      }
      const form = new FormData()
      form.append('html', new Blob([html], { type: 'text/html' }), fileName)
      form.append('title', title)
      form.append('slug', slug)
      if (projectId) form.append('projectId', projectId)
      let response
      try {
        response = await fetchImpl(hostedPagesEndpoint(url), {
          method: 'POST',
          headers: { Accept: 'application/json', Authorization: 'Bearer ' + token },
          body: form,
          redirect: 'error',
          signal: AbortSignal.timeout(30_000),
        })
      } catch (error) {
        throw new Error('无法连接 WAO 托管页服务：' + error.message)
      }
      if (response.status === 401) throw new Error('工作区令牌无效或已吊销；请重新登录')
      if (response.status === 403) throw new Error('当前工作区令牌没有发布 HTML 页面权限；请在能力中心“我的连接”的 CLI/MCP 新授权中选择发布 HTML 页面')
      if (!response.ok) throw new Error('WAO 托管页服务返回 HTTP ' + response.status)
      let body
      try { body = await response.json() } catch { throw new Error('WAO 托管页服务返回的不是 JSON') }
      if (body.code !== 0 || !body.data) throw new Error(body.message || 'WAO 未能创建托管页面')
      return body.data
    },
    async verifyHostedPage(page, expectedHtml) {
      const publicUrl = hostedPageUrl(url, page)
      let response
      try {
        response = await fetchImpl(publicUrl, {
          method: 'GET',
          headers: { Accept: 'text/html' },
          redirect: 'error',
          signal: AbortSignal.timeout(20_000),
        })
      } catch (error) {
        return { ok: false, status: 0, message: '匿名访问失败：' + error.message }
      }
      if (!response.ok) return { ok: false, status: response.status, message: '匿名访问返回 HTTP ' + response.status }
      const contentType = response.headers.get('content-type') || ''
      if (!contentType.toLowerCase().includes('text/html')) {
        return { ok: false, status: response.status, message: '匿名访问返回了非 HTML 内容：' + (contentType || '未声明类型') }
      }
      const body = await response.text()
      const encoded = /const encodedHtml = '([A-Za-z0-9+/=]+)'/.exec(body)
      if (!encoded) return { ok: false, status: response.status, message: '公开页面未包含 WAO 的隔离 HTML 内容' }
      const remoteHtml = Buffer.from(encoded[1], 'base64')
      if (!remoteHtml.equals(expectedHtml)) {
        return { ok: false, status: response.status, message: '公网 HTML 与本地文件内容不一致' }
      }
      return { ok: true, status: response.status, contentType, bytes: remoteHtml.length }
    },
  }
}
