/** Browser-only route selection. Credentials and tickets remain in memory. */
const MAIN = 'https://wao.wayuwa.cn'
const TRUSTED = new Set(['https://linux1.wayuwa.cn:9443', 'https://linux2.wayuwa.cn:9443'])
const BUSINESS = /^\/api\/(?:tasks|projects|areas|members|org|kbs|kb-groups|docs)(?:\/|\?|$)/
const SAFE = new Set(['get', 'head'])
const RECEIPT_WRITE = /^\/api\/tasks\/[^/?]+\/operations\/[0-9a-f-]{36}$/
const MEDIA_UPLOAD = /^\/api\/(?:images|files)$/
const MEDIA_READ = /^\/api\/(?:(?:images|files)(?:\?[^#]*)?|files\/[A-Za-z0-9_-]+\/preview)$/
type Node = { origin: string; csrf: string; validUntil: number }
type Routing = { enabled?: boolean; apiEnabled?: boolean; apiWritesEnabled?: boolean; mediaEnabled?: boolean; forceCloud?: boolean; allowedReleases?: string[]; nodes?: { origin: string; enabled?: boolean }[] }

export class EdgeApiRouting {
  private selected: Node | null = null
  private configuration: Routing | null = null
  private configUntil = 0
  private warming: Promise<void> | null = null
  private cooldown = new Map<string, number>()
  private generation = 0

  constructor(private readonly context = {
    origin: () => window.location.origin,
    release: () => import.meta.env.BASE_URL.match(/^\/releases\/([0-9a-f]{40})\/$/)?.[1],
    now: () => Date.now(),
    fetch: (url: string, init: RequestInit) => fetch(url, init),
  }) {}

  /** Authentication changes discard every cached node binding before any business request. */
  reset() { this.generation++; this.selected = null; this.configuration = null; this.configUntil = 0 }

  /** Warm in the background: the first request can keep using the working cloud entry. */
  route(url: string, method: string): Node | null {
    if (this.context.origin() !== MAIN || !this.context.release() || !(BUSINESS.test(url) || MEDIA_READ.test(url))) return null
    if (!['get', 'head', 'post', 'put', 'patch', 'delete'].includes(method)) return null
    if (!this.warming) {
      this.warming = this.warm().catch(() => { /* Fail closed to the existing cloud route. */ })
        .finally(() => { this.warming = null })
    }
    const now = this.context.now()
    return this.configUntil > now && this.enabled() && this.selected && this.selected.validUntil > now
      && (BUSINESS.test(url)
        ? (SAFE.has(method) || (method === 'put' && RECEIPT_WRITE.test(url) && this.configuration?.apiWritesEnabled === true))
        : (this.configuration?.mediaEnabled === true && (SAFE.has(method) || (method === 'post' && MEDIA_UPLOAD.test(url)))))
      && this.configuration?.nodes?.some(node => node.enabled === true && node.origin === this.selected?.origin)
      && (this.cooldown.get(this.selected.origin) ?? 0) <= now ? this.selected : null
  }

  /** 上传第一次使用时等待连接准备；失败仍只发送一次，不重放文件正文。 */
  async prepareUpload(url: string) {
    this.route(url, 'post')
    await this.warming
    return this.route(url, 'post')
  }

  failed(origin: string) {
    this.cooldown.set(origin, this.context.now() + 60_000)
    if (this.selected?.origin === origin) this.selected = null
  }

  private enabled() {
    const c = this.configuration
    return c?.enabled === true && c.apiEnabled === true && c.forceCloud === false
      && Array.isArray(c.allowedReleases) && c.allowedReleases.includes(this.context.release() ?? '')
  }

  private async json(url: string, init: RequestInit = {}, timeout = 4000) {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), timeout)
    try {
      const response = await this.context.fetch(url, { ...init, credentials: 'include', cache: 'no-store',
        redirect: 'error', signal: controller.signal })
      if (!response.ok) throw new Error(`节点连接失败：${response.status}`)
      return await response.json()
    } finally { clearTimeout(timer) }
  }

  private async warm() {
    const generation = this.generation
    const now = this.context.now()
    if (this.configUntil <= now) {
      this.configuration = null
      // A failed config fetch also gets a short cooldown instead of one fetch per API call.
      this.configUntil = now + 30_000
      const configuration = await this.json('/_edge/routing.json') as Routing
      if (generation !== this.generation) return
      this.configuration = configuration
    }
    if (!this.enabled()) return
    if (this.selected && this.selected.validUntil > now
      && this.configuration?.nodes?.some(node => node.enabled === true && node.origin === this.selected?.origin)) return
    const nodes = this.configuration?.nodes
    if (!Array.isArray(nodes)) return
    for (const node of nodes) {
      if (!node || node.enabled !== true || !TRUSTED.has(node.origin) || (this.cooldown.get(node.origin) ?? 0) > now) continue
      try {
        // A public readiness check saves two cloud calls when a node is offline.
        const ready = await this.json(`${node.origin}/api/edge/ready`, {}, 1500)
        if (ready?.data?.protocol !== 1) throw new Error('节点协议不兼容')
        const csrf = await this.json('/api/edge/csrf')
        if (typeof csrf?.data?.csrf !== 'string') throw new Error('主站未认证')
        const grant = await this.json('/api/edge/tickets', { method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-WAO-CSRF': csrf.data.csrf },
          body: JSON.stringify({ targetOrigin: node.origin }) })
        if (typeof grant?.data?.ticket !== 'string') throw new Error('票据签发失败')
        await this.json(`${node.origin}/api/edge/exchange`, { method: 'POST',
          headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ticket: grant.data.ticket }) })
        // Confirm that the browser accepted the host-only cookie before sending a write.
        await this.json(`${node.origin}/api/edge/session`)
        if (generation !== this.generation) return
        this.selected = { origin: node.origin, csrf: csrf.data.csrf, validUntil: this.context.now() + 300_000 }
        return
      } catch { this.failed(node.origin) }
    }
  }
}

export const edgeApiRouting = new EdgeApiRouting()
export const isSafeEdgeMethod = (method: string) => SAFE.has(method.toLowerCase())
