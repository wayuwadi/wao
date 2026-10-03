import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { seedDataStore } from './helpers/seedDataStore'

enableAutoUnmount(afterEach)
vi.mock('@/api/favorites', async (original) => ({
  ...await original<typeof import('@/api/favorites')>(),
  fetchPreference: vi.fn().mockResolvedValue('[]'),
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
  beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); seedDataStore() })

  it('显示偏好仍影响原表格并按项目持久化；Esc 返回入口，外部点击不抢焦点', async () => {
    const { wrapper } = await open()
    const details = wrapper.get<HTMLDetailsElement>('.project-display-settings')
    const trigger = wrapper.get<HTMLElement>('.project-display-settings summary')
    details.element.open = true
    await wrapper.get('.table-seg button:last-child').trigger('click')
    expect(wrapper.find('.table-group').exists()).toBe(true)
    expect(JSON.parse(localStorage.getItem('wao-board-table-grouped-v1')!)).toMatchObject({ p1: true })
    await wrapper.get('.done-switch').trigger('click')
    expect(wrapper.get('.done-switch').attributes('aria-checked')).toBe('true')
    await wrapper.get('.done-switch').trigger('keydown', { key: 'Escape' })
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
