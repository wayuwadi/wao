import { describe, expect, it, vi } from 'vitest'
import { EdgeApiRouting } from '@/api/edgeRouting'

const origin = 'https://linux2.wayuwa.cn:9443'
const sha = 'a'.repeat(40)
function fixture() {
  let now = 1000
  const config = { enabled: true, apiEnabled: true, apiWritesEnabled: true, mediaEnabled: false, forceCloud: false, allowedReleases: [sha], nodes: [{ origin, enabled: true }] }
  const fetcher = vi.fn(async (url: string, _init: RequestInit) => {
    const body = url === '/_edge/routing.json' ? config : url.endsWith('/csrf') ? { data: { csrf: 'test-csrf' } }
      : url.endsWith('/tickets') ? { data: { ticket: 'test-ticket' } } : { data: { protocol: 1 } }
    return new Response(JSON.stringify(body))
  })
  const routing = new EdgeApiRouting({ origin: () => 'https://wao.wayuwa.cn', release: () => sha, now: () => now, fetch: fetcher })
  const connected = async () => {
    expect(routing.route('/api/projects', 'get')).toBeNull()
    await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(6))
    await vi.waitFor(() => expect(routing.route('/api/projects', 'get')?.origin).toBe(origin))
  }
  return { routing, fetcher, config, connected, advance: (ms: number) => { now += ms } }
}

describe('API 节点连接', () => {
  it('媒体默认关闭；开启后首次上传先验证节点会话，其他写入仍不扩围', async () => {
    const off = fixture()
    expect(await off.routing.prepareUpload('/api/files')).toBeNull()
    const on = fixture(); on.config.mediaEnabled = true
    expect(await on.routing.prepareUpload('/api/files')).toMatchObject({ origin, csrf: 'test-csrf' })
    expect(on.fetcher.mock.calls[on.fetcher.mock.calls.length - 1]?.[0]).toBe(`${origin}/api/edge/session`)
    expect(on.routing.route('/api/images', 'post')?.origin).toBe(origin)
    expect(on.routing.route('/api/files?id=one', 'get')?.origin).toBe(origin)
    expect(on.routing.route('/api/files/file-one/preview', 'get')?.origin).toBe(origin)
    for (const url of ['/api/files/one/download?ticket=secret', '/api/files/one', '/api/images/one/preview']) {
      expect(on.routing.route(url, 'post')).toBeNull()
    }
  })

  it('先走云端，完成票据兑换并确认 host-only Cookie 后才允许业务直连', async () => {
    const f = fixture()
    await f.connected()
    expect(f.routing.route('/api/tasks/one/operations/12345678-1234-4123-8123-123456789abc', 'put')).toMatchObject({ origin, csrf: 'test-csrf' })
    expect(f.routing.route('/api/tasks/one', 'patch')).toBeNull()
    expect(f.routing.route('/api/projects', 'post')).toBeNull()
    const calls = f.fetcher.mock.calls
    expect(calls.map(call => call[0])).toEqual(['/_edge/routing.json', `${origin}/api/edge/ready`, '/api/edge/csrf', '/api/edge/tickets', `${origin}/api/edge/exchange`, `${origin}/api/edge/session`])
  })

  it('总开关、API开关、版本名单及节点白名单都必须通过', async () => {
    for (const modify of [
      (c: ReturnType<typeof fixture>['config']) => { c.apiEnabled = false },
      (c: ReturnType<typeof fixture>['config']) => { c.forceCloud = true },
      (c: ReturnType<typeof fixture>['config']) => { c.allowedReleases = [] },
      (c: ReturnType<typeof fixture>['config']) => { c.nodes[0]!.origin = 'https://attacker.invalid' },
    ]) {
      const f = fixture(); modify(f.config)
      expect(f.routing.route('/api/projects', 'get')).toBeNull()
      await vi.waitFor(() => expect(f.fetcher).toHaveBeenCalledTimes(1))
      expect(f.routing.route('/api/projects', 'get')).toBeNull()
    }
  })

  it('认证、Agent、匿名文件和绝对URL不进入节点路由', async () => {
    const f = fixture()
    for (const url of ['/api/auth/session', '/api/agent/tasks', '/api/files/one/download', '/api/ai/chat', 'https://other.invalid/api/tasks']) {
      expect(f.routing.route(url, 'get')).toBeNull()
    }
    expect(f.fetcher).not.toHaveBeenCalled()
  })

  it('写直连独立开关未开启时，读取直连但保存仍走主站', async () => {
    const f = fixture(); f.config.apiWritesEnabled = false; await f.connected()
    expect(f.routing.route('/api/tasks/one', 'patch')).toBeNull()
    expect(f.routing.route('/api/tasks/one/operations/12345678-1234-4123-8123-123456789abc', 'put')).toBeNull()
    expect(f.routing.route('/api/docs/catalog', 'get')?.origin).toBe(origin)
  })

  it('故障节点冷却；主站注销丢弃缓存；30秒内读取回退开关', async () => {
    const f = fixture(); await f.connected()
    f.routing.failed(origin)
    expect(f.routing.route('/api/projects', 'get')).toBeNull()
    f.routing.reset()
    expect(f.routing.route('/api/projects', 'get')).toBeNull()
    const g = fixture(); await g.connected()
    g.config.forceCloud = true; g.advance(30_001)
    expect(g.routing.route('/api/projects', 'get')).toBeNull()
    await vi.waitFor(() => expect(g.fetcher.mock.calls.filter(c => c[0] === '/_edge/routing.json')).toHaveLength(2))
    expect(g.routing.route('/api/projects', 'get')).toBeNull()
  })
})
