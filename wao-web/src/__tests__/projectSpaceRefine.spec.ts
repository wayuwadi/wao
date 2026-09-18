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
    await row().findAll('.cell-menu button').find((b) => b.text().includes('P0'))!.trigger('click')
    expect(task.pr).toBe('P0')

    await row().get(`[aria-label="修改 ${task.title} 的负责人"]`).trigger('click')
    await row().findAll('.cell-menu button').find((b) => b.text().includes('娃弟'))!.trigger('click')
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

    await wrapper.get('[aria-label="子任务 子项 的负责人"]').setValue('wg')
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
    expect(toggle.text()).toContain('显示已完成的任务')
    expect(toggle.attributes('aria-checked')).toBe('false')
    await toggle.trigger('click')
    expect(titles().some((text) => text.includes(finished.title))).toBe(true)
    expect(wrapper.text()).toContain('做完了')
  })
})

describe('任务弹框 Esc', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore(useDataStore())
  })

  it('子任务详情里按一次 Esc 直接关闭弹框', async () => {
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
})
