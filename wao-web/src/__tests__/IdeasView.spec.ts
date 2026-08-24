import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import IdeasView from '@/views/IdeasView.vue'
import { apiCreateProject, fetchProjects } from '@/api/board'
import { convertIdeaToProject, fetchIdeas } from '@/api/ideas'
import type { Idea, Project } from '@/types'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('@/stores/session', () => ({
  useSessionStore: () => ({
    members: [{ id: 'wg', name: '娃哥' }, { id: 'wd', name: '瓦弟' }],
    refreshDirectory: vi.fn().mockResolvedValue(undefined),
  }),
}))
vi.mock('@/api/board', () => ({ fetchProjects: vi.fn(), apiCreateProject: vi.fn() }))
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
    vi.mocked(apiCreateProject).mockResolvedValue(project({ id: 'p3', name: '新点子' }))
    vi.mocked(convertIdeaToProject).mockResolvedValue(project({ id: 'p4', name: '旧点子' }))
  })
  afterEach(() => vi.clearAllMocks())

  it('只把规划中项目展示为想法，并保留进行中统计', async () => {
    const wrapper = mount(IdeasView)
    await flushPromises()

    expect(wrapper.text()).toContain('1 个待推进 · 1 个进行中')
    expect(wrapper.text()).toContain('桌面应用')
    expect(wrapper.text()).not.toContain('打开项目开始第一轮 进行中项目')
    expect(wrapper.text()).toContain('历史点子待归并')
  })

  it('快速记录静默创建规划中项目并清掉本地草稿', async () => {
    const wrapper = mount(IdeasView)
    await flushPromises()
    const inputs = wrapper.findAll('.capture input')
    await inputs[0]!.setValue('新点子')
    await inputs[1]!.setValue('做到自己满意')
    expect(localStorage.getItem('wao-project-idea-draft')).toContain('做到自己满意')

    await wrapper.get('.capture').trigger('submit')
    await flushPromises()

    expect(apiCreateProject).toHaveBeenCalledWith({
      name: '新点子', area: 'career', goal: '做到自己满意', visAll: true, announce: false,
    })
    expect(wrapper.text()).toContain('已静默保存，团队尚未收到通知')
    expect(localStorage.getItem('wao-project-idea-draft')).toBeNull()
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
})
