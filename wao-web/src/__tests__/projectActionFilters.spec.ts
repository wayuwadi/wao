import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
import { useTaskOrderStore } from '@/stores/taskOrder'
import { seedDataStore } from './helpers/seedDataStore'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { matchesProjectAction } from '@/utils/projectActionFilters'

enableAutoUnmount(afterEach)
vi.mock('@/api/favorites', async original => ({ ...await original<typeof import('@/api/favorites')>(), fetchPreference: vi.fn().mockResolvedValue('[]'), savePreference: vi.fn().mockResolvedValue(undefined) }))
beforeEach(() => {
  localStorage.clear(); setActivePinia(createPinia()); const data = seedDataStore(); data.taskLoadState = 'loaded'; data.today = '2026-10-03'
  useSessionStore().members = [{ id: 'wg', name: '甲' }, { id: 'wd', name: '乙' }] as never
  const source = data.tasks[0]!
  data.tasks = [
    { ...source, id: 'verify', project: 'p1', title: '甲 验收', owner: 'wg', createdBy: 'wg', due: '2026-10-02', status: '待验证', waiting: '等反馈', subs: [] },
    { ...source, id: 'sub', project: 'p1', title: '乙 子项仍待验证', owner: 'wd', createdBy: 'wg', due: '2026-10-02', status: '已完成', waiting: '', subs: [{ id: 'parent', text: '父', done: true }, { id: 'child', parentId: 'parent', text: '子待验', status: '待验证', done: false }] },
    { ...source, id: 'closed', project: 'p1', title: '全收尾', owner: 'wg', createdBy: 'wd', due: '2026-10-01', status: '已完成', waiting: '旧等待', subs: [] },
    { ...source, id: 'open', project: 'p1', title: '普通任务', owner: 'wg', createdBy: 'wd', due: '2026-10-03', status: '待办', waiting: ' ', subs: [] },
  ] as typeof data.tasks
})
async function view() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects/:id', component: ProjectDetailView }] })
  await router.push('/projects/p1')
  const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } }); await flushPromises()
  return wrapper
}
const button = (w: Awaited<ReturnType<typeof view>>, label: string) => w.findAll('.project-action-filters button').find(b => b.text().startsWith(label))!

describe('项目需要处理事项', () => {
  it('按任务计数，不把内嵌子项当任务；已收尾和空等待排除', () => {
    const tasks = useDataStore().tasks
    expect(tasks.filter(t => matchesProjectAction(t, 'verify', '2026-10-03')).map(t => t.id)).toEqual(['verify', 'sub'])
    expect(tasks.filter(t => matchesProjectAction(t, 'waiting', '2026-10-03')).map(t => t.id)).toEqual(['verify'])
    expect(tasks.filter(t => matchesProjectAction(t, 'overdue', '2026-10-03')).map(t => t.id)).toEqual(['verify', 'sub'])
  })
  it('项目计数不受人员和关键词影响，交集结果可清除条件，任务状态不改写', async () => {
    const w = await view(); const ui = useUiStore(); const original = JSON.stringify(useDataStore().tasks)
    expect(button(w, '待验证').text()).toContain('2')
    await button(w, '待验证').trigger('click')
    expect(w.findAll('.task-row')).toHaveLength(2)
    ui.setOwnerFilter('p1', 'wg'); ui.setCreatorFilter('p1', 'wg'); await flushPromises()
    expect(w.findAll('.task-row')).toHaveLength(1)
    expect(button(w, '待验证').text()).toContain('2')
    expect(w.text()).toContain('筛选内未收尾 1／共 1 项任务（项目共 4 项')
    await w.get('[aria-label="项目内查找"]').setValue('子待验')
    expect(w.text()).toContain('筛选内未收尾 0／共 0 项任务')
    expect(w.get('.project-search-results').text()).toContain('1 项被快捷条件或人员筛选排除')
    await w.findAll('.project-search-results button').find(b => b.text().includes('清除筛选'))!.trigger('click')
    expect(button(w, '全部').attributes('aria-pressed')).toBe('true')
    expect(w.get('.project-search-results').text()).toContain('命中 1 项任务')
    expect(JSON.stringify(useDataStore().tasks)).toBe(original)
  })
  it('快捷条件清除后恢复排序、子项折叠和个人顺序；失败不显示伪零', async () => {
    const w = await view()
    await w.findAll('.task-row').find(row => row.text().includes('乙 子项'))!.get('.tree-toggle').trigger('click')
    await w.get('thead th:nth-child(2) button').trigger('click')
    const sort = w.get('thead th:nth-child(2)').attributes('aria-sort')
    const order = JSON.stringify(useTaskOrderStore().state('project:p1').ids)
    await button(w, '等待反馈').trigger('click'); expect(w.findAll('.task-row')).toHaveLength(1)
    await button(w, '全部').trigger('click')
    expect(w.find('.table-subs').exists()).toBe(false)
    expect(w.get('thead th:nth-child(2)').attributes('aria-sort')).toBe(sort)
    expect(JSON.stringify(useTaskOrderStore().state('project:p1').ids)).toBe(order)
    useDataStore().taskLoadState = 'error'; await flushPromises()
    expect(button(w, '待验证').text()).toContain('—')
    expect(button(w, '待验证').attributes('disabled')).toBeDefined()
    expect(w.text()).toContain('任务刷新失败')
    useDataStore().taskLoadState = 'loaded'; await flushPromises()
    expect(button(w, '待验证').text()).toContain('2')
  })
})
