import { readTaskDrafts, taskDraftWindowId, writeTaskDraft } from '@/utils/taskDraftStorage'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import { TASKS } from '@/data/fixtures'
import type { Area, Task } from '@/types'

const putTask = vi.hoisted(() => vi.fn())
const fetchTasks = vi.fn<() => Promise<Task[]>>()
let resolveAreas: ((areas: Area[]) => void) | undefined

vi.mock('@/api/board', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/board')>()
  const empty = async () => []
  return {
    ...actual,
    apiPutTask: putTask,
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
    putTask.mockReset().mockRejectedValue(new Error('离线'))
    resolveAreas = undefined
  })

  it('初始化期间新建仍请求服务器，失败任务在同步与刷新后保留', async () => {
    const data = useDataStore()
    fetchTasks.mockResolvedValue([])
    const hydrating = data.hydrate()
    const result = await data.addTask({ title: '不应丢失的新任务', project: 'p1', pr: 'P2', owner: 'm1', due: '', desc: '' })
    expect(result.persisted).toBe(false)
    expect(putTask).toHaveBeenCalledWith(result.task.id, expect.objectContaining({ title: '不应丢失的新任务' }))
    resolveAreas?.([])
    await hydrating
    expect(data.taskById(result.task.id)?.title).toBe('不应丢失的新任务')
    const reloading = data.hydrate()
    await vi.waitFor(() => expect(data.taskLoadState).toBe('loaded'))
    resolveAreas?.([])
    await reloading
    expect(data.taskById(result.task.id)?.title).toBe('不应丢失的新任务')
  })

  it('服务器已无记录的已保存草稿不复活为任务，也不自动重放', async () => {
    writeTaskDraft('', taskDraftWindowId(), { ...TASKS[0]!, id: 'deleted', number: 1037, editVersion: 'old' })
    const data = useDataStore()
    fetchTasks.mockResolvedValue([])
    const hydrating = data.hydrate()
    await vi.waitFor(() => expect(data.taskLoadState).toBe('loaded'))
    resolveAreas?.([])
    await hydrating
    await data.flushTask('deleted')
    expect(data.taskById('deleted')).toBeUndefined()
    expect(putTask).not.toHaveBeenCalled()
    expect(readTaskDrafts('', taskDraftWindowId()).deleted).toBeDefined()
  })

  it('重新打开后恢复服务器尚无记录的草稿，仍用原 ID 重试', async () => {
    const draft = { ...TASKS[0]!, id: 't-draft', title: '离线前输入' }
    localStorage.setItem('wao-task-drafts-v1', JSON.stringify({ 't-draft': draft }))
    const data = useDataStore()
    fetchTasks.mockResolvedValue([])
    const hydrating = data.hydrate()
    await vi.waitFor(() => expect(data.taskLoadState).toBe('loaded'))
    expect(data.taskById('t-draft')?.title).toBe('离线前输入')
    resolveAreas?.([])
    await hydrating
    putTask.mockResolvedValue(draft)
    await data.flushTask('t-draft')
    expect(putTask).toHaveBeenCalledWith('t-draft', expect.objectContaining({ title: '离线前输入' }))
    expect(data.taskSaveState('t-draft')).toBe('saved')
  })

  it('切换成员后不保留上一账号任务，也不重放其旧草稿', async () => {
    const session = useSessionStore()
    session.$patch({ account: { member: { id: 'm1' } } } as never)
    localStorage.setItem('wao-task-drafts-v1', JSON.stringify({ old: { ...TASKS[0]!, id: 'old', createdBy: 'm1' } }))
    const data = useDataStore()
    data.tasks = [{ ...TASKS[0]!, id: 'old', createdBy: 'm1' }]
    session.$patch({ account: { member: { id: 'm2' } } } as never)
    fetchTasks.mockResolvedValue([])
    const hydrating = data.hydrate()
    await vi.waitFor(() => expect(data.taskLoadState).toBe('loaded'))
    resolveAreas?.([])
    await hydrating
    expect(data.tasks).toEqual([])
    expect(putTask).not.toHaveBeenCalled()
    expect(readTaskDrafts('m1', taskDraftWindowId()).old?.createdBy).toBe('m1')
  })

  it('任务接口一返回就接管空 store，不等待其他业务域', async () => {
    const data = useDataStore()
    expect(data.tasks).toEqual([])
    const realTask = { ...TASKS[0]!, id: 't-real', title: '我的真实任务' }
    fetchTasks.mockResolvedValue([realTask])

    const hydrating = data.hydrate()
    await vi.waitFor(() => expect(data.taskLoadState).toBe('loaded'))

    expect(data.tasks.map((task) => task.title)).toEqual(['我的真实任务'])
    resolveAreas?.([])
    await hydrating
  })

  it('任务接口失败时保持空值并保留可见错误', async () => {
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
