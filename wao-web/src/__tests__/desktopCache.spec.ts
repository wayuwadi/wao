import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { desktopCachedRead, invalidateDesktopCache, setDesktopCacheMember } from '@/api/desktopCache'
vi.mock('@/utils/desktopWorkspace', () => ({ isDesktopWorkspace: () => true }))
beforeEach(() => { localStorage.clear(); setDesktopCacheMember(''); setDesktopCacheMember('alice'); vi.useFakeTimers() })
afterEach(() => vi.useRealTimers())
describe('桌面成员缓存', () => {
  it('缓存命中、过期和保存失效均使用正确来源', async () => {
    const fetch = vi.fn<() => Promise<{ title: string }[]>>().mockResolvedValue([{ title: '任务' }])
    const result = await desktopCachedRead('/api/tasks', fetch)
    result[0]!.title = '本地未保存'
    expect((await desktopCachedRead('/api/tasks', fetch))[0]!.title).toBe('任务')
    expect(fetch).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(10_001)
    await desktopCachedRead('/api/tasks', fetch)
    expect(fetch).toHaveBeenCalledTimes(2)
    invalidateDesktopCache()
    await desktopCachedRead('/api/tasks', fetch)
    expect(fetch).toHaveBeenCalledTimes(3)
  })
  it('切换成员不复用上个成员数据，密码库不缓存', async () => {
    const fetch = vi.fn().mockResolvedValue('alice')
    await desktopCachedRead('/api/tasks', fetch)
    setDesktopCacheMember('bob')
    fetch.mockResolvedValue('bob')
    expect(await desktopCachedRead('/api/tasks', fetch)).toBe('bob')
    await desktopCachedRead('/api/vault', fetch)
    await desktopCachedRead('/api/vault', fetch)
    expect(fetch).toHaveBeenCalledTimes(4)
  })
  it('在途请求在失效后不能重新填充旧缓存，失败不缓存', async () => {
    let resolve!: (value: string[]) => void
    const first = desktopCachedRead('/api/tasks', () => new Promise<string[]>(done => { resolve = done }))
    invalidateDesktopCache()
    resolve(['old'])
    await first
    const fetch = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(['new'])
    await expect(desktopCachedRead('/api/tasks', fetch)).rejects.toThrow('offline')
    expect(await desktopCachedRead('/api/tasks', fetch)).toEqual(['new'])
  })
})
