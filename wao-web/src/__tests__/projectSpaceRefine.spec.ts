import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import TaskDrawer from '@/components/task/TaskDrawer.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { seedDataStore } from './helpers/seedDataStore'

enableAutoUnmount(afterEach)

vi.mock('@/api/ai', () => ({
  createAiRuns: vi.fn<() => Promise<never[]>>().mockResolvedValue([]),
  fetchTaskAiRuns: vi.fn<() => Promise<never[]>>().mockResolvedValue([]),
  pauseAiRun: vi.fn<() => Promise<Record<string, never>>>().mockResolvedValue({}),
}))

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/projects/:id', component: { template: '<div />' } },
      { path: '/:p(.*)*', component: { template: '<div />' } },
    ],
  })
}

/** 单元格下拉菜单挂在 body 上悬浮显示，不在表格行里。 */
function menuButton(label: string) {
  const button = [...document.querySelectorAll<HTMLButtonElement>('.cell-menu button')].find((node) => node.textContent?.includes(label))
  if (!button) throw new Error(`菜单里没有「${label}」`)
  return button
}

async function mountTable() {
  const router = makeRouter()
  await router.push('/projects/p1?view=list')
  await router.isReady()
  const wrapper = mount(ProjectDetailView, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

describe('项目表格：按需添加子任务与行内修改', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore(useDataStore())
    const session = useSessionStore()
    session.members = [
      { id: 'wg', name: '娃哥', initial: '哥', color: '#446' },
      { id: 'wd', name: '娃弟', initial: '弟', color: '#664' },
    ] as typeof session.members
  })

  it('添加子任务的输入行默认不出现，点行尾「+」才展开，Esc 收起', async () => {
    const data = useDataStore()
    const task = data.taskById('t1')!
    task.status = '待办'
    task.subs = [{ id: 'root', text: '父子任务', done: false }]
    const wrapper = await mountTable()

    expect(wrapper.find('.sub-add-row').exists()).toBe(false)
    const row = wrapper.findAll('.task-row').find((node) => node.text().includes(task.title))!
    await row.get(`[aria-label="为 ${task.title} 添加子任务"]`).trigger('click')
    await flushPromises()

    const input = wrapper.get<HTMLInputElement>('.sub-add-row input')
    expect(document.activeElement).toBe(input.element)
    await input.setValue('新子任务')
    await wrapper.get('.sub-add-row form').trigger('submit')
    expect(task.subs[0]!.text).toBe('新子任务')
    expect(task.subs[0]!.parentId).toBeNull()

    await wrapper.get('.sub-add-row input').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('.sub-add-row').exists()).toBe(false)

    // 子任务行尾的「+」挂到这条子任务下面
    const subRow = wrapper.findAll('.inline-sub-row').find((node) => node.text().includes('父子任务'))!
    await subRow.get('[aria-label="在 父子任务 下添加子任务"]').trigger('click')
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('.sub-add-row input').element.placeholder).toContain('父子任务')
    await wrapper.get('.sub-add-row input').setValue('孙子任务')
    await wrapper.get('.sub-add-row form').trigger('submit')
    expect(task.subs.find((sub) => sub.text === '孙子任务')!.parentId).toBe('root')
  })

  it('标题、优先级、负责人、日期都能在行内直接改，子任务的负责人与截止日也能改', async () => {
    const data = useDataStore()
    const task = data.taskById('t1')!
    task.status = '待办'
    task.subs = [{ id: 's1', text: '子项', done: false }]
    const wrapper = await mountTable()
    const row = () => wrapper.findAll('.task-row').find((node) => node.text().includes(task.title) || node.find('.title-input').exists())!

    await row().get(`[aria-label="修改 ${task.title} 的优先级"]`).trigger('click')
    // 菜单悬浮在表格之外，表格本身不因展开菜单而多出行
    expect(row().find('.cell-menu').exists()).toBe(false)
    menuButton('P0').click()
    await flushPromises()
    expect(task.pr).toBe('P0')

    await row().get(`[aria-label="修改 ${task.title} 的负责人"]`).trigger('click')
    menuButton('娃弟').click()
    await flushPromises()
    expect(task.owner).toBe('wd')

    await row().get(`[aria-label="${task.title} 截止日期"]`).setValue('2026-10-01')
    expect(task.due).toBe('2026-10-01')
    await row().get(`[aria-label="${task.title} 计划开始"]`).setValue('2026-09-20')
    expect(task.start).toBe('2026-09-20')

    await row().get(`[aria-label="修改 ${task.title} 的标题"]`).trigger('click')
    const titleInput = row().get('.title-input')
    await titleInput.setValue('改过的标题')
    await titleInput.trigger('keydown', { key: 'Enter' })
    expect(task.title).toBe('改过的标题')

    await wrapper.get('[aria-label="修改子任务 子项 的负责人"]').trigger('click')
    menuButton('娃哥').click()
    await flushPromises()
    await wrapper.get('[aria-label="子任务 子项 的截止日期"]').setValue('2026-09-30')
    expect(task.subs[0]).toMatchObject({ owner: 'wg', due: '2026-09-30' })
  })

  it('主任务已完成但子任务没做完时仍显示；开关控制已完成任务', async () => {
    const data = useDataStore()
    const pending = data.taskById('t1')!
    pending.status = '已完成'
    pending.subs = [{ id: 'open-sub', text: '还没做完', done: false }]
    const finished = data.tasksByProject('p1').find((task) => task.id !== 't1')!
    finished.status = '已完成'
    finished.subs = [{ id: 'done-sub', text: '做完了', done: true }]
    const wrapper = await mountTable()

    const titles = () => wrapper.findAll('.task-row').map((node) => node.text())
    expect(titles().some((text) => text.includes(pending.title))).toBe(true)
    expect(titles().some((text) => text.includes(finished.title))).toBe(false)
    expect(wrapper.find('.done-toggle').exists()).toBe(false)

    const toggle = wrapper.get('.done-switch')
    expect(toggle.text()).toContain('显示已完成')
    expect(toggle.attributes('aria-checked')).toBe('false')
    await toggle.trigger('click')
    expect(titles().some((text) => text.includes(finished.title))).toBe(true)
    expect(wrapper.text()).toContain('做完了')
  })
})

describe('项目表格：本轮体验优化', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore(useDataStore())
    const session = useSessionStore()
    session.members = [
      { id: 'wg', name: '娃哥', initial: '哥', color: '#446' },
      { id: 'wd', name: '娃弟', initial: '弟', color: '#664' },
    ] as typeof session.members
  })

  it('子任务很多时，点「+」的输入行出现在该任务下的第一行，新子任务的负责人和创建者就是录入人', async () => {
    const data = useDataStore()
    const session = useSessionStore()
    session.account = { member: { id: 'wd' } } as never
    const task = data.taskById('t1')!
    task.status = '待办'
    task.subs = Array.from({ length: 6 }, (_, i) => ({ id: `s${i}`, text: `旧子任务${i}`, done: false }))
    const wrapper = await mountTable()
    const row = wrapper.findAll('.task-row').find((node) => node.text().includes(task.title))!
    await row.get(`[aria-label="为 ${task.title} 添加子任务"]`).trigger('click')
    await flushPromises()

    const subRows = wrapper.get('.table-subs').findAll('tr')
    expect(subRows[0]!.classes()).toContain('sub-add-row')
    await wrapper.get('.sub-add-row input').setValue('新来的')
    await wrapper.get('.sub-add-row form').trigger('submit')
    await flushPromises()
    // 新子任务落在第一条，紧挨着输入行
    expect(wrapper.get('.table-subs').findAll('.inline-sub-row')[0]!.text()).toContain('新来的')
    expect(task.subs[0]).toMatchObject({ owner: 'wd', createdBy: 'wd' })
    expect(task.subs[0]!.createdAt).toBeTruthy()
  })

  it('有下级的子任务可以收起，子任务多了「进行中」状态', async () => {
    const data = useDataStore()
    const task = data.taskById('t1')!
    task.status = '待办'
    task.subs = [
      { id: 'a', text: '第一层', done: false },
      { id: 'b', text: '第二层', done: false, parentId: 'a' },
      { id: 'c', text: '第三层', done: false, parentId: 'b' },
    ]
    const wrapper = await mountTable()
    expect(wrapper.text()).toContain('第三层')
    await wrapper.get('[aria-label="展开或收起 第二层 的子任务"]').trigger('click')
    expect(wrapper.text()).not.toContain('第三层')
    await wrapper.get('[aria-label="展开或收起 第一层 的子任务"]').trigger('click')
    expect(wrapper.text()).not.toContain('第二层')
    await wrapper.get('[aria-label="展开或收起 第一层 的子任务"]').trigger('click')
    expect(wrapper.text()).toContain('第二层')

    await wrapper.get('[aria-label="修改子任务 第一层 的状态"]').trigger('click')
    menuButton('进行中').click()
    await flushPromises()
    expect(task.subs[0]).toMatchObject({ done: false, status: '进行中' })
    expect(wrapper.get('[aria-label="修改子任务 第一层 的状态"]').text()).toBe('进行中')
  })

  it('状态单元格任意处都能打开悬浮菜单，第一次 Esc 就收起', async () => {
    const data = useDataStore()
    const task = data.taskById('t1')!
    task.status = '待办'
    const wrapper = await mountTable()
    const row = wrapper.findAll('.task-row').find((node) => node.text().includes(task.title))!
    const cell = row.get('.task-status-trigger').element.parentElement!
    cell.click()
    await flushPromises()
    expect(document.querySelector('.cell-menu')).not.toBeNull()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(document.querySelector('.cell-menu')).toBeNull()
  })

  it('按负责人筛选只看自己的任务；表格展示创建日期且没有批量选择', async () => {
    const data = useDataStore()
    const tasks = data.tasksByProject('p1')
    tasks.forEach((task) => { task.status = '待办'; task.owner = 'wg'; task.subs = [] })
    tasks[0]!.owner = 'wd'
    tasks[0]!.createdAt = '2026-09-01T10:00:00'
    const wrapper = await mountTable()
    expect(wrapper.find('.select-dot').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('批量选择')
    expect(wrapper.findAll('th').map((th) => th.text())).toContain('创建日期')
    expect(wrapper.text()).toContain('2026/09/01')

    const ownerButton = wrapper.findAll('.creator-filter-btn').find((node) => node.text().includes('全部负责人'))!
    await ownerButton.trigger('click')
    await wrapper.findAll('.creator-filter-menu button').find((node) => node.text().includes('娃弟'))!.trigger('click')
    const titles = wrapper.findAll('.task-row').map((node) => node.text())
    expect(titles).toHaveLength(1)
    expect(titles[0]).toContain(tasks[0]!.title)
  })
})

describe('任务弹框 Esc', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore(useDataStore())
  })

  it('从表格直接点开的子任务，按一次 Esc 就回到表格', async () => {
    const data = useDataStore()
    const taskId = data.tasks[0]!.id
    const subId = data.addSub(taskId, '子任务')!
    const ui = useUiStore()
    ui.openTask(taskId, subId)
    const wrapper = mount(TaskDrawer, { global: { plugins: [makeRouter()] }, attachTo: document.body })
    await flushPromises()
    expect(wrapper.find('.drawer').exists()).toBe(true)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()

    expect(ui.openTaskId).toBeNull()
    expect(wrapper.find('.drawer').exists()).toBe(false)
  })

  it('在弹框里逐层点开的子任务，Esc 一次退回上一层', async () => {
    const data = useDataStore()
    const taskId = data.tasks[0]!.id
    const first = data.addSub(taskId, '第一层')!
    const second = data.addSub(taskId, '第二层', first)!
    const ui = useUiStore()
    ui.openTask(taskId, first)
    const wrapper = mount(TaskDrawer, { global: { plugins: [makeRouter()] }, attachTo: document.body })
    await flushPromises()
    ui.openTask(taskId, second)
    await flushPromises()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(ui.openTaskId).toBe(taskId)
    expect(ui.openTaskSubId).toBe(first)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(ui.openTaskId).toBeNull()
    expect(wrapper.find('.drawer').exists()).toBe(false)
  })
})
