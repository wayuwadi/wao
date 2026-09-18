import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { mergeSubtasks } from '@/utils/subtaskMerge'
import { useDataStore } from '@/stores/data'
import { apiGetTask, apiPutTask } from '@/api/board'
import { seedDataStore } from './helpers/seedDataStore'
import type { Task, TaskStep } from '@/types'

vi.mock('@/api/board', async (original) => ({
  ...(await original<object>()),
  apiPutTask: vi.fn<(id: string, task: Task) => Promise<Task>>(),
  apiGetTask: vi.fn<(id: string) => Promise<Task>>(),
}))

const sub = (id: string, text: string, extra: Partial<TaskStep> = {}): TaskStep => ({ id, text, done: false, ...extra })

describe('子任务三方合并', () => {
  it('保留两边各自的新增与修改，尊重未冲突的删除', () => {
    const base = [sub('a', 'A'), sub('b', 'B'), sub('c', 'C')]
    const local = [sub('n1', '本地新增', { parentId: 'a' }), sub('a', 'A 本地改名'), sub('b', 'B')]
    const server = [sub('n2', '别处新增'), sub('a', 'A'), sub('b', 'B', { done: true }), sub('c', 'C')]

    const merged = mergeSubtasks(base, local, server)

    expect(merged.map((item) => item.id)).toEqual(['n2', 'n1', 'a', 'b'])
    expect(merged.find((item) => item.id === 'a')!.text).toBe('A 本地改名')
    expect(merged.find((item) => item.id === 'b')!.done).toBe(true)
    expect(merged.find((item) => item.id === 'n1')!.parentId).toBe('a')
  })

  it('服务端删了父节点时子节点挂回根，本地改过的被删条目保留', () => {
    const base = [sub('p', '父'), sub('x', 'X')]
    const local = [sub('child', '新子', { parentId: 'p' }), sub('p', '父'), sub('x', 'X 改过')]
    const server: TaskStep[] = []

    const merged = mergeSubtasks(base, local, server)

    expect(merged.map((item) => item.id)).toEqual(['child', 'x'])
    expect(merged[0]!.parentId).toBeNull()
  })

  it('两边调整层级绕成环时挂回根', () => {
    const base = [sub('a', 'A'), sub('b', 'B')]
    const local = [sub('a', 'A', { parentId: 'b' }), sub('b', 'B')]
    const server = [sub('a', 'A'), sub('b', 'B', { parentId: 'a' })]
    const merged = mergeSubtasks(base, local, server)
    const byId = Object.fromEntries(merged.map((item) => [item.id, item.parentId ?? null]))
    expect(byId.a === 'b' && byId.b === 'a').toBe(false)
  })

  it('只有编号不同不算修改', () => {
    const base = [sub('a', 'A')]
    const local = [sub('a', 'A', { number: 3 })]
    const server = [sub('a', 'A 别处改', { number: 3 })]
    expect(mergeSubtasks(base, local, server)[0]!.text).toBe('A 别处改')
  })
})

describe('保存遇到子任务版本冲突', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore(useDataStore())
    useDataStore().apiLive = true
    vi.mocked(apiPutTask).mockReset()
    vi.mocked(apiGetTask).mockReset()
  })

  it('自动合并服务端最新子任务并以新版本重试，不再停在保存失败', async () => {
    const data = useDataStore()
    const task = data.tasks[0]!
    task.subs = [sub('parent', '父子任务')]
    task.subtaskVersion = 'v-old'
    const server = { ...(JSON.parse(JSON.stringify(task)) as Task), subs: [sub('remote', '另一窗口新增'), sub('parent', '父子任务')], subtaskVersion: 'v-new' }
    vi.mocked(apiGetTask).mockResolvedValue(server)
    vi.mocked(apiPutTask)
      .mockRejectedValueOnce(Object.assign(new Error('子任务已被其他窗口更新'), { status: 409 }))
      .mockImplementation(async (_id, body) => ({ ...body, subtaskVersion: 'v-saved' }))

    const childId = data.addSub(task.id, '孙子任务', 'parent')!
    await data.flushTask(task.id)
    await flushPromises()

    expect(data.taskSaveState(task.id)).toBe('saved')
    expect(apiPutTask).toHaveBeenCalledTimes(2)
    const retried = vi.mocked(apiPutTask).mock.calls[1]![1]
    expect(retried.subtaskVersion).toBe('v-new')
    expect(retried.subs.map((item) => item.id)).toEqual(['remote', childId, 'parent'])
    expect(task.subtaskVersion).toBe('v-saved')
  })

  it('非冲突错误仍如实显示保存失败', async () => {
    const data = useDataStore()
    const task = data.tasks[0]!
    vi.mocked(apiPutTask).mockRejectedValue(Object.assign(new Error('服务不可用'), { status: 500 }))
    data.addSub(task.id, '新子任务')
    await data.flushTask(task.id)
    expect(data.taskSaveState(task.id)).toBe('error')
    expect(apiGetTask).not.toHaveBeenCalled()
  })
})
