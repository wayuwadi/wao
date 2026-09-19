import { createPinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useDataStore } from '@/stores/data'
import * as api from '@/api/board'

afterEach(() => vi.restoreAllMocks())
describe('托管页首次加载', () => {
  it('无需等待其它模块判定 apiLive，直接读取持久化列表', async () => {
    const data = useDataStore(createPinia())
    const pages = [
      {
        id: 'h1',
        slug: 'stable',
        title: '标题',
        fileName: 'new.html',
        hasIcon: false,
        updatedAt: '刚刚',
        url: '/api/pages/stable',
      },
    ]
    const fetch = vi.spyOn(api, 'fetchHostedPages').mockResolvedValue(pages)
    expect(data.apiLive).toBe(false)
    expect(await data.refreshHostedPages()).toBe(true)
    expect(fetch).toHaveBeenCalledOnce()
    expect(data.hostedPages).toEqual(pages)
    expect(data.hostedPageLoadState).toBe('loaded')
  })
  it('请求失败报告错误，恢复后可重试并清除错误', async () => {
    const data = useDataStore(createPinia())
    vi.spyOn(api, 'fetchHostedPages')
      .mockRejectedValueOnce(new Error('连接失败'))
      .mockResolvedValueOnce([])
    expect(await data.refreshHostedPages()).toBe(false)
    expect(data.hostedPageLoadState).toBe('error')
    expect(data.hostedPageLoadError).toBe('连接失败')
    expect(await data.refreshHostedPages()).toBe(true)
    expect(data.hostedPageLoadState).toBe('loaded')
    expect(data.hostedPageLoadError).toBe('')
  })
})
