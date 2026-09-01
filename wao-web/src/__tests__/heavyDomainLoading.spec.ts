import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'

const fetchDocs = vi.fn<() => Promise<never[]>>()
const fetchImages = vi.fn<() => Promise<never[]>>()
const fetchJobs = vi.fn<() => Promise<never[]>>()

vi.mock('@/api/board', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/board')>()
  const empty = async () => []
  return {
    ...actual,
    fetchAreas: empty,
    fetchProjects: empty,
    fetchTasks: empty,
    fetchKbs: empty,
    fetchKbGroups: empty,
    fetchDocs: () => fetchDocs(),
    fetchJobs: () => fetchJobs(),
    fetchFiles: empty,
    fetchImages: () => fetchImages(),
    fetchVault: empty,
    fetchSchedule: empty,
    fetchSuggestions: empty,
    fetchGoals: empty,
  }
})

describe('大体量领域按需加载', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    fetchDocs.mockReset()
    fetchImages.mockReset()
    fetchJobs.mockReset()
    fetchDocs.mockResolvedValue([])
    fetchImages.mockResolvedValue([])
    fetchJobs.mockResolvedValue([])
  })

  it('应用壳层启动不预取文档正文、图床列表和自动化历史', async () => {
    const data = useDataStore()

    await data.hydrate()

    expect(fetchDocs).not.toHaveBeenCalled()
    expect(fetchImages).not.toHaveBeenCalled()
    expect(fetchJobs).not.toHaveBeenCalled()
    expect(data.loadStates.docs).toBe('idle')
    expect(data.loadStates.images).toBe('idle')
    expect(data.loadStates.automation).toBe('idle')
  })

  it('多个文档入口同时打开时复用一个在途请求', async () => {
    let finish: ((docs: never[]) => void) | undefined
    fetchDocs.mockReturnValue(new Promise((resolve) => { finish = resolve }))
    const data = useDataStore()

    const first = data.ensureDocsLoaded()
    const second = data.ensureDocsLoaded()

    expect(fetchDocs).toHaveBeenCalledOnce()
    expect(data.loadStates.docs).toBe('loading')
    finish?.([])
    await Promise.all([first, second])
    expect(data.loadStates.docs).toBe('loaded')
  })

  it('多个自动化入口同时打开时复用一个在途请求', async () => {
    let finish: ((jobs: never[]) => void) | undefined
    fetchJobs.mockReturnValue(new Promise((resolve) => { finish = resolve }))
    const data = useDataStore()

    const first = data.ensureAutomationLoaded()
    const second = data.ensureAutomationLoaded()

    expect(fetchJobs).toHaveBeenCalledOnce()
    expect(data.loadStates.automation).toBe('loading')
    finish?.([])
    await Promise.all([first, second])
    expect(data.loadStates.automation).toBe('loaded')
  })

  it('手动重新加载会刷新已经进入过的按需领域', async () => {
    const data = useDataStore()
    await Promise.all([
      data.ensureDocsLoaded(), data.refreshImages(), data.ensureAutomationLoaded(),
    ])

    await data.hydrate()

    expect(fetchDocs).toHaveBeenCalledTimes(2)
    expect(fetchImages).toHaveBeenCalledTimes(2)
    expect(fetchJobs).toHaveBeenCalledTimes(2)
    expect(data.loadStates.docs).toBe('loaded')
    expect(data.loadStates.images).toBe('loaded')
    expect(data.loadStates.automation).toBe('loaded')
  })

  it('图床页并发刷新只读取一次并保留失败重试状态', async () => {
    let fail: ((reason: Error) => void) | undefined
    fetchImages.mockReturnValue(new Promise((_, reject) => { fail = reject }))
    const data = useDataStore()

    const first = data.refreshImages()
    const second = data.refreshImages()

    expect(fetchImages).toHaveBeenCalledOnce()
    fail?.(new Error('图床请求超时'))
    await Promise.all([first, second])
    expect(data.loadStates.images).toBe('error')
    expect(data.loadErrors.images).toBe('图床请求超时')
  })
})
