import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { apiPutTask } from '@/api/board'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import { respondWithCompanion } from '@/composables/quietCompanions'
import type { Task } from '@/types'
vi.mock('@/api/board', async original => ({ ...await original<typeof import('@/api/board')>(), apiPutTask: vi.fn() }))
vi.mock('@/composables/quietCompanions', () => ({ cancelCompanionResponse: vi.fn(), captureCompanionTicket: vi.fn(() => ({ epoch: 1 })), respondWithCompanion: vi.fn() }))
let data: ReturnType<typeof useDataStore>
beforeEach(() => {
  vi.useFakeTimers(); vi.clearAllMocks(); localStorage.clear()
  setActivePinia(createPinia()); data = useDataStore(); seedDataStore(data); data.apiLive = true
  data.tasks[0]!.status = '待办'; data.tasks[0]!.subs = []
})
afterEach(() => { vi.clearAllTimers(); vi.useRealTimers() })
it('只在服务器确认后回应完成；撤销和普通编辑不回应', async () => {
  let confirm!: (t: Task) => void
  vi.mocked(apiPutTask).mockImplementationOnce(() => new Promise(resolve => { confirm = resolve }))
  const task = data.tasks[0]!
  data.patchTask(task.id, { status: '已完成' })
  const pending = data.flushTask(task.id)
  expect(respondWithCompanion).not.toHaveBeenCalled()
  confirm({ ...task }); await pending
  expect(respondWithCompanion).toHaveBeenCalledWith('task', { epoch: 1 })
  vi.mocked(respondWithCompanion).mockClear()
  vi.mocked(apiPutTask).mockImplementation(async (_id, t) => ({ ...t }))
  data.patchTask(task.id, { status: '待办' }); await data.flushTask(task.id)
  expect(respondWithCompanion).not.toHaveBeenCalled()
})
it('失败、未收尾子项和保存期间撤销都不庆祝', async () => {
  const task = data.tasks[0]!
  vi.mocked(apiPutTask).mockRejectedValueOnce(new Error('断网'))
  data.patchTask(task.id, { status: '已完成' }); await data.flushTask(task.id)
  expect(respondWithCompanion).not.toHaveBeenCalled()
  task.status = '待办'; task.subs = [{ id: 'open', text: '待验证', done: false }]
  vi.mocked(apiPutTask).mockImplementation(async (_id, t) => ({ ...t }))
  data.patchTask(task.id, { status: '已完成' }); await data.flushTask(task.id)
  expect(respondWithCompanion).not.toHaveBeenCalled()
  task.status = '待办'; task.subs = []
  let confirm!: (t: Task) => void
  vi.mocked(apiPutTask).mockImplementationOnce(() => new Promise(resolve => { confirm = resolve }))
  data.patchTask(task.id, { status: '已完成' })
  const snapshot = { ...task }, pending = data.flushTask(task.id)
  data.patchTask(task.id, { status: '待办' })
  confirm(snapshot); await pending
  expect(respondWithCompanion).not.toHaveBeenCalled()
})
