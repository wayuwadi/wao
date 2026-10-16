import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { seedDataStore } from './helpers/seedDataStore'
import { useSessionStore } from '@/stores/session'
import { savePreference } from '@/api/favorites'

enableAutoUnmount(afterEach)
vi.mock('@/api/favorites', async (original) => ({
  ...await original<typeof import('@/api/favorites')>(),
  fetchPreference: vi.fn().mockResolvedValue(''),
  savePreference: vi.fn().mockResolvedValue(undefined),
}))

async function open() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects/:id', component: { template: '<div />' } }] })
  await router.push('/projects/p1')
  const wrapper = mount(ProjectDetailView, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return { wrapper, router }
}

describe('项目显示设置与任务上下文', () => {
  it('快速创建成功后的空框可回车收起，Esc 收起并保留未提交草稿', async () => {
    const { wrapper } = await open()
    const data = seedDataStore()
    const add = vi.spyOn(data, 'addTask').mockResolvedValue({ task: data.tasks[0]!, persisted: true })
    await wrapper.get('[aria-label="快速新建任务"]').trigger('click')
    await wrapper.get('.table-quick-add input').setValue('快速新建验收')
    await wrapper.get('.table-quick-add input').trigger('keydown', { key: 'Enter' }); await flushPromises()
    expect(wrapper.get<HTMLInputElement>('.table-quick-add input').element.value).toBe('')
    await wrapper.get('.table-quick-add input').trigger('keydown', { key: 'Enter' })
    expect(wrapper.find('.table-quick-add').exists()).toBe(false)
    expect(add).toHaveBeenCalledTimes(1)
    await wrapper.get('[aria-label="快速新建任务"]').trigger('click')
    await wrapper.get('.table-quick-add input').setValue('尚未提交')
    await wrapper.get('.table-quick-add input').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('.table-quick-add').exists()).toBe(false)
    await wrapper.get('[aria-label="快速新建任务"]').trigger('click')
    expect(wrapper.get<HTMLInputElement>('.table-quick-add input').element.value).toBe('尚未提交')
    add.mockRestore()
  })

  it('列宽拖动只改宽度，失败保持本机宽度且可重试；键盘也可调整', async () => {
    const { wrapper } = await open()
    const handle = wrapper.get('[aria-label="调整标题列宽"]')
    vi.mocked(savePreference).mockRejectedValueOnce(new Error('offline'))
    handle.element.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true, button: 0, clientX: 100 }))
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 220 }))
    window.dispatchEvent(new Event('pointerup')); await flushPromises()
    expect(wrapper.get('.project-task-table col').attributes('style')).toContain('400px')
    expect(wrapper.text()).toContain('尚未同步到账号')
    await wrapper.findAll('button').find(button => button.text() === '重试列宽')!.trigger('click'); await flushPromises()
    expect(savePreference).toHaveBeenLastCalledWith('project-table-widths-v1:p1', expect.stringContaining('"title":400'))
    await handle.trigger('keydown', { key: 'ArrowLeft' }); await flushPromises()
    expect(wrapper.get('.project-task-table col').attributes('style')).toContain('384px')
    expect(wrapper.get('th').attributes('aria-sort')).toBe('none')
  })
  beforeEach(() => { localStorage.clear(); vi.mocked(savePreference).mockReset().mockResolvedValue(); setActivePinia(createPinia()); seedDataStore(); useSessionStore().accept({ accountId: 'a', member: { id: 'm', name: '验收', initial: '验', color: '', role: '' }, identities: [], createdAt: '' }) })

  it('默认常用字段，主任务与子项保持同列，完整字段和成员切换不影响标题输入', async () => {
    const { wrapper } = await open()
    expect(wrapper.findAll('.project-task-table th')).toHaveLength(6)
    expect(wrapper.findAll('.task-row')[0]!.findAll('td')).toHaveLength(6)
    for (const row of wrapper.findAll('.inline-sub-row')) expect(row.findAll('td')).toHaveLength(6)
    await wrapper.get('.column-choices input').setValue(true)
    expect(wrapper.findAll('.project-task-table th')).toHaveLength(7)
    await wrapper.findAll('button').find(button => button.text() === '全部字段')!.trigger('click')
    expect(wrapper.findAll('.project-task-table th')).toHaveLength(10)
    expect(wrapper.findAll('.task-row')[0]!.findAll('td')).toHaveLength(10)
    useSessionStore().accept({ accountId: 'other', member: { id: 'other', name: '乙', initial: '乙', color: '', role: '' }, identities: [], createdAt: '' })
    await flushPromises()
    expect(wrapper.findAll('.project-task-table th')).toHaveLength(6)
  })

  it('存储失败保持临时选择并可重试，标题字段不可隐藏', async () => {
    const { wrapper } = await open()
    expect(wrapper.findAll('.column-choices input')[1]!.attributes('disabled')).toBeDefined()
    vi.mocked(savePreference).mockRejectedValueOnce(new Error('offline'))
    await wrapper.findAll('button').find(button => button.text() === '全部字段')!.trigger('click')
    expect(wrapper.findAll('.project-task-table th')).toHaveLength(10)
    await flushPromises(); expect(wrapper.text()).toContain('尚未同步到账号')
    await wrapper.findAll('button').find(button => button.text() === '重试同步字段')!.trigger('click')
    await flushPromises(); expect(wrapper.text()).not.toContain('尚未同步到账号'); expect(wrapper.text()).toContain('已同步到账号')
  })

  it('显示偏好仍影响原表格并按项目持久化；Esc 返回入口，外部点击不抢焦点', async () => {
    const { wrapper } = await open()
    const details = wrapper.get<HTMLDetailsElement>('.project-display-settings')
    const trigger = wrapper.get<HTMLElement>('.project-display-settings summary')
    details.element.open = true
    await wrapper.get('[aria-label="表格分组方式"] button:last-child').trigger('click')
    expect(wrapper.find('.table-group').exists()).toBe(true)
    expect(JSON.parse(localStorage.getItem('wao-board-table-grouped-v1')!)).toMatchObject({ p1: true })
    await wrapper.get('.subtask-controls [role="switch"]:nth-child(2)').trigger('click')
    expect(wrapper.get('.subtask-controls [role="switch"]:nth-child(2)').attributes('aria-checked')).toBe('true')
    await trigger.trigger('keydown', { key: 'Escape' })
    expect(details.element.open).toBe(false)
    expect(document.activeElement).toBe(trigger.element)
    details.element.open = true
    const search = wrapper.get<HTMLInputElement>('[aria-label="项目内查找"]')
    search.element.focus()
    await search.trigger('pointerdown')
    expect(details.element.open).toBe(false)
    expect(document.activeElement).toBe(search.element)
  })

  it('查找及内容页签切换收起浮层，普通视图保留原节点与输入', async () => {
    const { wrapper, router } = await open()
    const table = wrapper.get('.project-normal-tasks').element
    const details = wrapper.get<HTMLDetailsElement>('.project-display-settings').element
    details.open = true
    await wrapper.get('[aria-label="项目内查找"]').setValue('测试')
    expect(details.open).toBe(false)
    expect(wrapper.get('.project-normal-tasks').element).toBe(table)
    await wrapper.get('[aria-label="项目内查找"]').setValue('')
    wrapper.get<HTMLDetailsElement>('.project-display-settings').element.open = true
    await router.replace('/projects/p1?tab=文档')
    await flushPromises()
    expect(wrapper.find('.task-view-toolbar').exists()).toBe(false)
    await router.replace('/projects/p1')
    await flushPromises()
    expect(wrapper.get<HTMLDetailsElement>('.project-display-settings').element.open).toBe(false)
  })
})
