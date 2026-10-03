import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectsView from '@/views/ProjectsView.vue'
import { useDataStore } from '@/stores/data'
import type { Project } from '@/types'
import { respondWithCompanion } from '@/composables/quietCompanions'
vi.mock('@/composables/quietCompanions', () => ({ captureCompanionTicket: vi.fn(() => ({})), respondWithCompanion: vi.fn() }))

const fetchProjects = vi.fn<() => Promise<Project[]>>()

vi.mock('@/api/board', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/board')>()
  const empty = async () => []
  return {
    ...actual,
    fetchAreas: empty,
    fetchProjects: () => fetchProjects(),
    fetchTasks: empty,
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

function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/projects', component: ProjectsView }],
  })
  void router.push('/projects')
  return mount(ProjectsView, { global: { plugins: [router] } })
}

describe('项目真实数据加载', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    fetchProjects.mockReset()
    vi.mocked(respondWithCompanion).mockClear()
  })

  it('接口未返回时只展示加载提示，不渲染原型项目', () => {
    fetchProjects.mockReturnValue(new Promise(() => {}))
    const wrapper = mountView()

    expect(wrapper.text()).toContain('正在加载项目')
    expect(wrapper.text()).not.toContain('WAO 1.0 原型')
    expect(wrapper.findAll('.proj-card')).toHaveLength(0)
    expect(respondWithCompanion).not.toHaveBeenCalled()
  })

  it('接口失败时保持空列表并提供重试入口', async () => {
    fetchProjects.mockRejectedValue(new Error('项目接口超时'))
    const data = useDataStore()
    const wrapper = mountView()

    await data.hydrate()
    await flushPromises()

    expect(data.projects).toEqual([])
    expect(wrapper.text()).toContain('项目加载失败')
    expect(wrapper.text()).toContain('项目接口超时')
    expect(wrapper.get('.load-state button').text()).toContain('重新加载')
    expect(respondWithCompanion).not.toHaveBeenCalled()
  })

  it('真实空项目才出现书虫，搜索无结果不会触发', async () => {
    fetchProjects.mockResolvedValue([])
    const data = useDataStore(), wrapper = mountView()
    await data.hydrate(); await flushPromises()
    expect(wrapper.text()).toContain('还没有项目')
    expect(respondWithCompanion).toHaveBeenCalledWith('empty', {})
    vi.mocked(respondWithCompanion).mockClear()
    await wrapper.get('.search input').setValue('不存在')
    expect(wrapper.text()).toContain('没有符合条件的项目')
    expect(respondWithCompanion).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
