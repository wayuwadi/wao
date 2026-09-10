import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import {
  detectClientPlatform,
  fetchMobileDownloads,
  fetchWindowsDownload,
  parseMobileManifest,
  parseWindowsManifest,
  parseMacosManifest,
  fetchMacosDownload,
} from '@/api/clientDownloads'
import ClientDownloadView from '@/views/ClientDownloadView.vue'
vi.mock('@/components/SiteFooter.vue', () => ({ default: { template: '<footer />' } }))
const manifest = () => ({
  version: '1.0.0',
  files: {
    android: { name: 'WAO-1.0.0-android.apk', size: 1000, sha256: 'a'.repeat(64) },
    ios: { name: 'WAO-1.0.0-ios-unsigned.ipa', size: 800, sha256: 'b'.repeat(64) },
  },
})
const desktopManifest = () => ({
  version: '1.0.1',
  platforms: {
    'windows-x86_64': {
      signature: 'x'.repeat(40),
      url: 'https://wao.wayuwa.cn/downloads/desktop/client/WAO_1.0.1_windows-x64-setup.exe',
    },
    'darwin-x86_64': {
      signature: 'm'.repeat(40),
      url: 'https://wao.wayuwa.cn/downloads/desktop/client/WAO_1.0.1_macos-universal.app.tar.gz',
    },
    'darwin-aarch64': {
      signature: 'm'.repeat(40),
      url: 'https://wao.wayuwa.cn/downloads/desktop/client/WAO_1.0.1_macos-universal.app.tar.gz',
    },
  },
  downloads: {
    macos: {
      url: 'https://wao.wayuwa.cn/downloads/desktop/client/WAO_1.0.1_macos-universal.dmg',
      size: 4096,
      sha256: 'c'.repeat(64),
    },
  },
})
const json = (data: unknown) =>
  new Response(JSON.stringify(data), { headers: { 'content-type': 'application/json' } })
const binary = (size: number) =>
  new Response(null, {
    headers: { 'content-type': 'application/octet-stream', 'content-length': String(size) },
  })
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.useRealTimers()
})
describe('客户端下载与发布状态', () => {
  it('优先识别 Android 和使用桌面 UA 的 iPad', () => {
    expect(detectClientPlatform('Android', 'Linux')).toBe('android')
    expect(detectClientPlatform('Macintosh', 'MacIntel', 5)).toBe('ios')
    expect(detectClientPlatform('Macintosh', 'MacIntel', 0)).toBe('macos')
    expect(detectClientPlatform('Windows', 'Win32')).toBe('windows')
  })
  it('只接受固定版本目录中的平台制品，拒绝路径注入和不完整信息', () => {
    expect(parseMobileManifest(manifest())[0]?.url).toBe(
      '/downloads/mobile/1.0.0/WAO-1.0.0-android.apk',
    )
    const invalid = manifest()
    invalid.files.android.name = '../../other.apk'
    expect(() => parseMobileManifest(invalid)).toThrow()
    expect(() => parseMobileManifest({ ...manifest(), version: '1/../2' })).toThrow()
    invalid.files.android.name = 'WAO-1.0.0-android.apk'
    invalid.files.ios.sha256 = 'missing'
    expect(() => parseMobileManifest(invalid)).toThrow()
    expect(() =>
      parseWindowsManifest({
        version: '1.0.0',
        platforms: {
          'windows-x86_64': { signature: 'x'.repeat(30), url: 'https://example.com/app.exe' },
        },
      }),
    ).toThrow()
  })
  it('Mac 首次安装使用 DMG，两个架构的更新归档和签名必须一致', () => {
    const data = desktopManifest()
    expect(parseMacosManifest(data)?.url).toMatch(/macos-universal\.dmg$/)
    expect(parseMacosManifest({ version: '1.0.0', platforms: {} })).toBeNull()
    data.platforms['darwin-aarch64'].signature = 'other-signature'.repeat(3)
    expect(() => parseMacosManifest(data)).toThrow()
    const invalid = desktopManifest()
    invalid.downloads.macos.url = 'https://example.com/WAO.dmg'
    expect(() => parseMacosManifest(invalid)).toThrow()
  })
  it('Mac 下载独立于 Windows 失败，真实制品校验后才展示按钮', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url === '/downloads/mobile/latest.json') return new Response(null, { status: 404 })
        if (url.endsWith('latest.json')) return json(desktopManifest())
        if (url.endsWith('.dmg')) return binary(4096)
        return new Response(null, { status: 503 })
      }),
    )
    const wrapper = mount(ClientDownloadView)
    await flushPromises()
    expect(wrapper.get('.macos-download a[download]').attributes('href')).toMatch(
      /macos-universal\.dmg$/,
    )
    expect(wrapper.text()).toContain('桌面版发布信息加载失败')
    expect(wrapper.text()).toContain('Apple Developer ID')
    expect(wrapper.get('.macos-download').text()).toContain('v1.0.1')
    wrapper.unmount()
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(json(desktopManifest()))
        .mockResolvedValue(
          new Response('<html/>', {
            headers: { 'content-type': 'text/html', 'content-length': '4096' },
          }),
        ),
    )
    await expect(fetchMacosDownload()).rejects.toThrow()
  })
  it('200 HTML 和尺寸不一致不能变为下载链接', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(new Response('<html/>', { headers: { 'content-type': 'text/html' } })),
    )
    await expect(fetchMobileDownloads()).rejects.toThrow()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValueOnce(json(manifest())).mockResolvedValue(binary(50)),
    )
    await expect(fetchMobileDownloads()).rejects.toThrow()
  })
  it('没有 AbortSignal.timeout 的旧浏览器仍可读取安装包', async () => {
    vi.spyOn(AbortSignal, 'timeout').mockImplementation(() => {
      throw new Error('unsupported API')
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) =>
        url.endsWith('latest.json') ? json(manifest()) : binary(url.endsWith('.apk') ? 1000 : 800),
      ),
    )
    await expect(fetchMobileDownloads()).resolves.toHaveLength(2)
  })
  it('长时间没有响应时终止请求，失败后释放计时器', async () => {
    vi.useFakeTimers()
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url: string, init: RequestInit) =>
          new Promise((_resolve, reject) => {
            init.signal!.addEventListener('abort', () => reject(new Error('aborted')))
          }),
      ),
    )
    const outcome = expect(fetchWindowsDownload()).rejects.toThrow('aborted')
    await vi.advanceTimersByTimeAsync(12000)
    await outcome
    expect(vi.getTimerCount()).toBe(0)
  })
  it('404 表示尚未发布；网络失败独立展示重试', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 404 })))
    await expect(fetchWindowsDownload()).resolves.toEqual([])
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    const wrapper = mount(ClientDownloadView)
    await flushPromises()
    expect(wrapper.findAll('a[download]')).toHaveLength(0)
    expect(wrapper.text()).toContain('发布信息加载失败')
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) =>
        url.endsWith('latest.json') ? json(manifest()) : binary(url.endsWith('.apk') ? 1000 : 800),
      ),
    )
    await wrapper.get('.mobile-section .load-error button').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('a[download]')).toHaveLength(2)
    expect(wrapper.text()).toContain('iOS 需个人签名')
    expect(wrapper.text()).toContain('桌面版发布信息加载失败')
    wrapper.unmount()
  })
})
