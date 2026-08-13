import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import type { Area, Task } from '@/types'

const fetchTasks = vi.fn<() => Promise<Task[]>>()
let resolveAreas: ((areas: Area[]) => void) | undefined

vi.mock('@/api/board', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/board')>()
  const empty = async () => []
  return {
    ...actual,
    fetchAreas: () => new Promise<Area[]>((resolve) => { resolveAreas = resolve }),
    fetchProjects: empty,
    fetchTasks: () => fetchTasks(),
    fetchKbs: empty,
    fetchKbGroups: empty,
    fetchDocs: empty,
    fetchJobs: empty,
    fetchFiles: empty,
    fetchImages: empty,
    fetchVault: empty,
    fetchSchedule: empty,
    fetchSuggestions: empty,
    fetchGoals: empty,
  }
})

describe('任务真实数据加载', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    fetchTasks.mockReset()
    resolveAreas = undefined
  })

  it('任务接口一返回就替换 fixtures，不等待其他业务域', async () => {
    const data = useDataStore()
    const realTask = { ...data.tasks[0]!, id: 't-real', title: '我的真实任务' }
    fetchTasks.mockResolvedValue([realTask])

    const hydrating = data.hydrate()
    await vi.waitFor(() => expect(data.taskLoadState).toBe('loaded'))

    expect(data.tasks.map((task) => task.title)).toEqual(['我的真实任务'])
    resolveAreas?.([])
    await hydrating
  })

  it('任务接口失败时清空 fixtures 并保留可见错误', async () => {
    const data = useDataStore()
    fetchTasks.mockRejectedValue(new Error('任务接口超时'))

    const hydrating = data.hydrate()
    await vi.waitFor(() => expect(data.taskLoadState).toBe('error'))

    expect(data.tasks).toEqual([])
    expect(data.taskLoadError).toBe('任务接口超时')
    resolveAreas?.([])
    await hydrating
  })
})
