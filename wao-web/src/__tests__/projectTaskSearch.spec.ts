import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
import { useTaskOrderStore } from '@/stores/taskOrder'
import { searchProjectTasks } from '@/utils/projectTaskSearch'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import ProjectTaskSearch from '@/components/task/ProjectTaskSearch.vue'
import { seedDataStore } from './helpers/seedDataStore'

enableAutoUnmount(afterEach)
vi.mock('@/api/favorites', async (original) => ({
  ...await original<typeof import('@/api/favorites')>(),
  fetchPreference: vi.fn().mockResolvedValue('[]'), savePreference: vi.fn().mockResolvedValue(undefined),
}))

beforeEach(() => {
  localStorage.clear(); setActivePinia(createPinia()); seedDataStore()
  useDataStore().taskLoadState = 'loaded'
  useSessionStore().members = [{ id: 'wg', name: '甲' }, { id: 'wd', name: '乙' }] as never
})

async function view() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects/:id', component: ProjectDetailView }] })
  await router.push('/projects/p1')
  const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('项目关键词查找', () => {
  it('匹配编号、中文、正文、大小写和多层子项，字面符号不作为正则', () => {
    const task = { ...useDataStore().tasks[0]!, number: 17, title: '桂花项目', desc: 'AbC [a.*] 正文', subs: [
      { id: 'parent', text: '上级', done: true },
      { id: 'child', number: 19, parentId: 'parent', text: '下级', desc: '桂花记录', done: false, status: '待验证' as const },
    ] }
    expect(searchProjectTasks([task], '#17')[0]!.hits[0]!.field).toBe('任务编号')
    expect(searchProjectTasks([task], ' abc ')[0]!.hits[0]!.field).toBe('任务正文')
    expect(searchProjectTasks([task], 'a.*')[0]!.hits[0]!.snippet).toContain('[a.*]')
    expect(searchProjectTasks([task], '#19')[0]!.hits[0]).toMatchObject({ subId: 'child', context: ['上级', '下级'] })
    expect(searchProjectTasks([task], '桂花')[0]!.hits.map(hit => hit.field)).toEqual(['任务标题', '子任务正文'])
    expect(searchProjectTasks([task], '   ')).toEqual([])
    expect(searchProjectTasks([task], '不存在')).toEqual([])
  })

  it('长正文截取命中附近，坏层级不死循环，祖先不越过项目边界，历史子项仍可定位', () => {
    const task = { ...useDataStore().tasks[0]!, id: 'one', project: 'p1', parentTaskId: 'two', desc: '前'.repeat(5000) + '目标' + '后'.repeat(5000), subs: [
      { id: 'loop', parentId: 'loop', text: '目标', done: false }, { text: '历史目标', done: false },
    ] }
    const outside = { ...task, id: 'two', project: 'p2', title: '不可展示', subs: [], desc: '' }
    const result = searchProjectTasks([task, outside], '目标')[0]!
    expect(result.context).toEqual([])
    expect(result.hits[0]!.snippet.length).toBeLessThan(150)
    expect(result.hits[0]!.snippet).toContain('目标')
    expect(result.hits[2]!.subId).toBe('one#1')
  })

  it('项目限定并组合负责人和创建人；默认含历史，待验证和未收尾子项保留', async () => {
    const data = useDataStore(), source = data.tasks[0]!
    data.tasks = [
      { ...source, id: 'match', project: 'p1', title: '目标历史', status: '已完成', owner: 'wd', createdBy: 'wg', subs: [] },
      { ...source, id: 'open', project: 'p1', title: '目标待验', status: '待验证', owner: 'wd', createdBy: 'wd', subs: [] },
      { ...source, id: 'sub', project: 'p1', title: '目标子项', status: '已完成', owner: 'wd', createdBy: 'wg', subs: [{ text: '未收尾', done: false, owner: 'wg' }] },
      { ...source, id: 'outside', project: 'p2', title: '目标项目外', subs: [] },
    ]
    const { wrapper } = await view()
    await wrapper.get('[aria-label="项目内查找"]').setValue('目标')
    const search = wrapper.getComponent(ProjectTaskSearch)
    expect(search.findAll('.search-task')).toHaveLength(3)
    expect(search.text()).not.toContain('目标项目外')
    await search.get('input[type=checkbox]').setValue(false)
    expect(search.findAll('.search-task')).toHaveLength(2)
    useUiStore().setOwnerFilter('p1', 'wg'); useUiStore().setCreatorFilter('p1', 'wg'); await flushPromises()
    expect(search.findAll('.search-task')).toHaveLength(1)
    expect(search.text()).toContain('2 项被快捷条件或人员筛选排除')
    useUiStore().setCreatorFilter('p1', 'wd'); await flushPromises()
    expect(search.text()).toContain('当前范围没有结果')
    await search.findAll('button').find(b => b.text().includes('清除筛选'))!.trigger('click')
    expect(useUiStore().ownerFilterOf('p1')).toBe('')
    expect(useUiStore().creatorFilterOf('p1')).toBe('')
  })

  it('子项命中打开稳定定位，已收尾范围单独解释，加载/失败不声称没有匹配', async () => {
    const task = { ...useDataStore().tasks[0]!, status: '已完成' as const, subs: [{ id: 'sub', text: '独有词', done: true }] }
    const wrapper = mount(ProjectTaskSearch, { props: { query: '独有词', tasks: [task], filteredTasks: [task], loadState: 'loaded' } })
    await wrapper.get('.search-hit').trigger('click')
    expect(useUiStore().openTaskId).toBe(task.id); expect(useUiStore().openTaskSubId).toBe('sub')
    await wrapper.get('input').setValue(false)
    expect(wrapper.text()).toContain('另有 1 项已收尾')
    await wrapper.setProps({ query: '无命中', loadState: 'loading' })
    expect(wrapper.text()).toContain('正在加载'); expect(wrapper.text()).not.toContain('没有匹配')
    await wrapper.setProps({ loadState: 'error' })
    expect(wrapper.text()).toContain('加载失败'); expect(wrapper.text()).not.toContain('没有匹配')
    await wrapper.setProps({ loadState: 'loaded' })
    expect(wrapper.text()).toContain('当前项目没有匹配内容')
  })

  it('清除保持原表格节点、排序、展开、完成偏好及个人顺序；中文组合期间不查找', async () => {
    const data = useDataStore(), task = data.tasksByProject('p1')[0]!
    task.subs = [{ id: 'child', text: '桂花子项', done: false }]
    const { wrapper } = await view()
    const table = wrapper.get('table').element
    await wrapper.findAll('.task-row').find(row => row.text().includes(task.title))!.get('.tree-toggle').trigger('click')
    await wrapper.get('thead th:nth-child(2) button').trigger('click')
    const sort = wrapper.get('thead th:nth-child(2)').attributes('aria-sort')
    const order = JSON.stringify(useTaskOrderStore().state('project:p1').ids), tasks = JSON.stringify(data.tasks)
    const input = wrapper.get<HTMLInputElement>('[aria-label="项目内查找"]')
    await input.trigger('compositionstart')
    input.element.value = '桂花'; await input.trigger('input')
    expect(wrapper.findComponent(ProjectTaskSearch).exists()).toBe(false)
    await input.trigger('compositionend')
    expect(wrapper.getComponent(ProjectTaskSearch).text()).toContain('桂花子项')
    expect(wrapper.get('.project-normal-tasks').attributes('style')).toContain('display: none')
    await wrapper.findAll('button').find(button => button.text() === '清除查找')!.trigger('click')
    expect(wrapper.get('table').element).toBe(table)
    expect(wrapper.find('.table-subs').exists()).toBe(false)
    expect(wrapper.get('thead th:nth-child(2)').attributes('aria-sort')).toBe(sort)
    expect(JSON.stringify(useTaskOrderStore().state('project:p1').ids)).toBe(order)
    expect(JSON.stringify(data.tasks)).toBe(tasks)
  })

  it('命中分页且改词回到首页；切换项目清空查找，避免携带旧项目结果', async () => {
    const source = useDataStore().tasks[0]!
    const tasks = Array.from({ length: 25 }, (_, i) => ({ ...source, id: `t${i}`, project: 'p1', title: `查找${i}`, subs: [] }))
    const results = mount(ProjectTaskSearch, { props: { query: '查找', tasks, filteredTasks: tasks, loadState: 'loaded' } })
    expect(results.findAll('.search-task')).toHaveLength(20)
    await results.get('[aria-label="下一页"]').trigger('click')
    expect(results.findAll('.search-task')).toHaveLength(5)
    await results.setProps({ query: '查找1' })
    expect(results.findAll('.search-task')).toHaveLength(11)
    const { wrapper, router } = await view()
    await wrapper.get('[aria-label="项目内查找"]').setValue('查找')
    await router.push('/projects/p2'); await flushPromises()
    expect(wrapper.findComponent(ProjectTaskSearch).exists()).toBe(false)
    expect(wrapper.get<HTMLInputElement>('[aria-label="项目内查找"]').element.value).toBe('')
  })
})
