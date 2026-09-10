import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextPetTaskNotice } from '@/utils/petTaskNotice'
let member = ''
beforeEach(() => {
  localStorage.clear()
  member = crypto.randomUUID()
})
afterEach(() => vi.restoreAllMocks())
describe('按任务状态消费宠物提醒', () => {
  it('新增待办不会重播旧 AI 待验收提醒', () => {
    const review = { id: 'r', status: '待验证' as const }
    const todo = { id: 't', status: '待办' as const }
    expect(nextPetTaskNotice([review], member)?.id).toBe('r')
    expect(nextPetTaskNotice([review], member)).toBeNull()
    expect(nextPetTaskNotice([review, todo], member)?.id).toBe('t')
    expect(nextPetTaskNotice([review, todo], member)).toBeNull()
  })
  it('多项状态同时出现只提示一次，新一轮 AI 才再次提醒', () => {
    expect(
      nextPetTaskNotice(
        [
          { id: 'r', status: '待验证' },
          { id: 't', status: '待办' },
        ],
        member,
      )?.id,
    ).toBe('r')
    expect(
      nextPetTaskNotice(
        [
          { id: 'r', status: '待验证' },
          { id: 't', status: '待办' },
        ],
        member,
      ),
    ).toBeNull()
    expect(nextPetTaskNotice([{ id: 'r', status: 'AI 进行中' }], member)?.label).toContain('AI')
    expect(nextPetTaskNotice([{ id: 'r', status: '待验证' }], member)?.id).toBe('r')
  })
  it('存储不可用仍在本次会话去重，不串成员', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    expect(nextPetTaskNotice([{ id: 't', status: '待办' }], member)?.id).toBe('t')
    expect(nextPetTaskNotice([{ id: 't', status: '待办' }], member)).toBeNull()
    expect(nextPetTaskNotice([{ id: 't', status: '待办' }], `${member}-other`)?.id).toBe('t')
  })
})
