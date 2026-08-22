import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import IdeasView from '@/views/IdeasView.vue'
import { createIdea, fetchIdea, fetchIdeas, startIdeaResearch } from '@/api/ideas'
import type { Idea } from '@/types'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/stores/session', () => ({
  useSessionStore: () => ({
    members: [
      { id: 'wg', name: '娃哥' },
      { id: 'wd', name: '瓦弟' },
    ],
    refreshDirectory: vi.fn().mockResolvedValue(undefined),
  }),
}))
vi.mock('@/api/board', () => ({
  fetchProjects: vi.fn().mockResolvedValue([{ id: 'p1', name: 'WAO' }]),
  apiCreateProject: vi.fn(),
}))
vi.mock('@/api/ideas', () => ({
  fetchIdeas: vi.fn(),
  fetchIdea: vi.fn(),
  createIdea: vi.fn(),
  patchIdea: vi.fn(),
  transitionIdea: vi.fn(),
  acceptIdeaResearch: vi.fn(),
  startIdeaResearch: vi.fn(),
  addIdeaTask: vi.fn(),
  unlinkIdeaTask: vi.fn(),
  createIdeaIteration: vi.fn(),
  patchIdeaIteration: vi.fn(),
}))

const idea: Idea = {
  id: 'i1',
  title: 'AI 项目合作流程',
  rawIdea: '把想法一路推到验证',
  problem: '',
  targetOutcome: '',
  priority: 'P1',
  stage: 'RESEARCHING',
  createdBy: 'wg',
  ownerId: 'wg',
  projectId: null,
  detail: { activity: [{ action: '创建了点子', by: 'wg', at: '2026-08-23T01:00:00' }] },
  artifacts: [],
  tasks: [],
  iterations: [],
  researchRuns: [],
  createdAt: '2026-08-23T01:00:00',
  updatedAt: '2026-08-23T01:00:00',
}

describe('IdeasView', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(fetchIdeas).mockResolvedValue([idea])
    vi.mocked(fetchIdea).mockResolvedValue(idea)
    vi.mocked(createIdea).mockResolvedValue({ ...idea, id: 'i2', title: '新点子', stage: 'INBOX' })
    vi.mocked(startIdeaResearch).mockResolvedValue({
      id: 'ir1',
      status: 'QUEUED',
      createdBy: 'wg',
      createdAt: '2026-08-23T02:00:00',
    })
  })
  afterEach(() => {
    vi.clearAllMocks()
    vi.useRealTimers()
  })

  it('展示完整阶段、团队筛选和进行中统计', async () => {
    const wrapper = mount(IdeasView)
    await flushPromises()

    expect(wrapper.text()).toContain('1 个点子 · 1 个进行中')
    for (const label of [
      '收集箱',
      '看明白',
      '想明白',
      '说明白',
      '干明白',
      '验证中',
      '已达成',
      '已放弃',
    ]) {
      expect(wrapper.text()).toContain(label)
    }
    expect(wrapper.text()).toContain('全部负责人')
    expect(wrapper.text()).toContain('全部创建人')
  })

  it('快速录入只要求标题并默认落入收集箱', async () => {
    const wrapper = mount(IdeasView)
    await flushPromises()
    await wrapper.get('.quick input').setValue('新点子')
    await wrapper.get('.quick').trigger('submit')
    await flushPromises()

    expect(createIdea).toHaveBeenCalledWith({ title: '新点子', rawIdea: '', priority: 'P1' })
    expect(wrapper.text()).toContain('新点子')
  })

  it('编辑立即写本地草稿且调研使用独立入口', async () => {
    vi.useFakeTimers()
    const wrapper = mount(IdeasView)
    await flushPromises()
    const rawIdea = wrapper
      .findAll('textarea')
      .find((item) => item.element.parentElement?.textContent?.includes('原始想法'))!
    await rawIdea.setValue('尚未保存的补充')

    expect(localStorage.getItem('wao-idea-draft:i1')).toContain('尚未保存的补充')
    await wrapper.get('.panel .primary').trigger('click')
    await flushPromises()
    expect(startIdeaResearch).toHaveBeenCalledWith('i1')
  })
})
