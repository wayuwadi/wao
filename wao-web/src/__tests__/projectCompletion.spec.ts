import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useDeco } from '@/composables/deco'
import { seedDataStore } from './helpers/seedDataStore'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import ProjectsView from '@/views/ProjectsView.vue'
import { isTaskFinished, projectCompletion, projectCompletionLabel } from '@/utils/projectCompletion'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'

vi.mock('@/api/favorites', async (original) => ({
  ...await original<typeof import('@/api/favorites')>(),
  fetchPreference: vi.fn().mockResolvedValue('[]'), savePreference: vi.fn().mockResolvedValue(undefined),
}))

describe('项目未收尾口径', () => {
  beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); seedDataStore() })

  it('已完成主任务有多层未收尾子项时，卡片和页签与表格一致且不改写状态', async () => {
    const data = useDataStore(), source = data.tasksByProject('p1')[0]!
    data.tasks = [{ ...source, status: '已完成', subs: [
      { id: 'parent', text: '上级', done: true },
      { id: 'child', parentId: 'parent', text: '待验证下级', status: '待验证', done: false },
    ] }]
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects/:id', component: ProjectDetailView }] })
    await router.push('/projects/p1')
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.findAll('.task-row')).toHaveLength(1)
    expect(useDeco().decoProj(data.projectById('p1')!).taskOpen).toBe(1)
    expect(wrapper.get('.ptab-count').text()).toBe('1')
    expect(wrapper.text()).toContain('仍有 1 项子项未收尾')
    for (const view of ['board', 'group', 'quadrant']) {
      await router.replace(`/projects/p1?view=${view}`); await flushPromises()
      expect(wrapper.text()).toContain(source.title)
      expect(wrapper.text()).toContain('仍有 1 项子项未收尾')
    }
    expect(data.tasks[0]!.status).toBe('已完成')
    wrapper.unmount()
  })

  it('空项目、全部收尾、待验证和独立下级任务分别计数', () => {
    const source = useDataStore().tasks[0]!
    expect(projectCompletion([])).toMatchObject({ total: 0, open: 0, completionRate: null })
    expect(projectCompletionLabel([])).toBe('暂无任务')
    const finished = { ...source, id: 'parent', status: '已完成' as const, subs: [{ text: '已完成子项', done: true }] }
    const child = { ...source, id: 'child', parentTaskId: 'parent', status: '待验证' as const, subs: [] }
    expect(isTaskFinished(finished)).toBe(true)
    expect(projectCompletion([finished, child, child])).toEqual({ total: 2, open: 1, finished: 1, subTotal: 1, subOpen: 0, completionRate: .5 })
    expect(projectCompletionLabel([finished])).toBe('未收尾 0 项／共 1 项任务')
  })

  it('负责人筛选只改变当前范围的计数，页签仍表示整个项目', async () => {
    const data = useDataStore(), source = data.tasks[0]!
    data.tasks = [
      { ...source, id: 'mine', project: 'p1', owner: 'wg', status: '待验证', subs: [] },
      { ...source, id: 'other', project: 'p1', owner: 'wd', status: '待办', subs: [] },
    ]
    useSessionStore().members = [{ id: 'wg', name: '甲' }, { id: 'wd', name: '乙' }] as never
    useUiStore().setOwnerFilter('p1', 'wg')
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects/:id', component: ProjectDetailView }] })
    await router.push('/projects/p1')
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.get('.ptab-count').text()).toBe('2')
    expect(wrapper.text()).toContain('筛选内未收尾 1／共 1 项任务（项目共 2 项')
    expect(wrapper.findAll('.task-row')).toHaveLength(1)
    wrapper.unmount()
  })

  it('卡片不展示旧百分比，任务加载失败不能冒充零任务', async () => {
    const data = useDataStore()
    useSessionStore().account = { member: { id: 'wg' } } as never
    data.projects = [data.projects[0]!]; data.tasks = []
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects', component: ProjectsView }] })
    await router.push('/projects')
    const wrapper = mount(ProjectsView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('暂无任务')
    expect(wrapper.find('.pc-prog').exists()).toBe(false)
    data.taskLoadState = 'error'; await flushPromises()
    expect(wrapper.text()).toContain('任务计数读取失败')
    expect(wrapper.text()).not.toContain('暂无任务')
    wrapper.unmount()
  })
})
