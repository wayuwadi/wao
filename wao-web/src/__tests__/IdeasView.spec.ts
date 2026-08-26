import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import IdeasView from '@/views/IdeasView.vue'
import { fetchProjects } from '@/api/board'
import { convertIdeaToProject, fetchIdeas } from '@/api/ideas'
import type { Idea, Project } from '@/types'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('@/api/board', () => ({ fetchProjects: vi.fn() }))
vi.mock('@/api/ideas', () => ({ fetchIdeas: vi.fn(), convertIdeaToProject: vi.fn() }))

const project = (overrides: Partial<Project> = {}): Project => ({
  id: 'p1', name: '桌面应用', area: 'career', status: '规划中', health: '良好', progress: 0,
  owner: 'wg', members: ['wg', 'wd'], start: '2026-08-24', due: '2026-10-24', visibility: '组织内',
  updated: '刚刚', goal: '持续迭代到自己满意', milestones: [], msDates: [], trend: [], blockers: [],
  docs: [], files: [], activity: [], ...overrides,
})
const idea: Idea = {
  id: 'i1', title: '旧点子', rawIdea: '旧八阶段记录', problem: '', targetOutcome: '', priority: 'P1',
  stage: 'RESEARCHING', createdBy: 'wg', ownerId: 'wg', projectId: null, detail: { activity: [] },
  artifacts: [], tasks: [], iterations: [], researchRuns: [], createdAt: '2026-08-23T01:00:00',
  updatedAt: '2026-08-23T01:00:00',
}

describe('IdeasView', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(fetchProjects).mockResolvedValue([project(), project({ id: 'p2', name: '进行中项目', status: '进行中' })])
    vi.mocked(fetchIdeas).mockResolvedValue([idea])
    vi.mocked(convertIdeaToProject).mockResolvedValue(project({ id: 'p4', name: '旧点子' }))
  })
  afterEach(() => vi.clearAllMocks())

  it('只保留历史归并，不再复制规划中项目或提供新点子入口', async () => {
    const wrapper = mount(IdeasView)
    await flushPromises()

    expect(wrapper.text()).toContain('新想法已融合进任务与项目')
    expect(wrapper.text()).toContain('历史点子待归并')
    expect(wrapper.find('.project-card').exists()).toBe(false)
    expect(wrapper.find('.capture').exists()).toBe(false)
  })

  it('归并旧点子后进入项目且不再保留独立编辑流程', async () => {
    const wrapper = mount(IdeasView)
    await flushPromises()
    await wrapper.get('.legacy article button').trigger('click')
    await flushPromises()

    expect(convertIdeaToProject).toHaveBeenCalledWith('i1', undefined)
    expect(push).toHaveBeenCalledWith('/projects/p4')
    expect(wrapper.text()).not.toContain('看明白')
  })

  it('历史记录已全部归并时引导从一句任务开始', async () => {
    vi.mocked(fetchIdeas).mockResolvedValue([])
    const wrapper = mount(IdeasView)
    await flushPromises()

    expect(wrapper.text()).toContain('没有待归并的历史点子')
    expect(wrapper.text()).toContain('新想法请直接创建为任务，在任务中持续补充和验收')
  })
})
