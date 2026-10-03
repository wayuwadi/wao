import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'

enableAutoUnmount(afterEach)
vi.mock('@/api/favorites', async (original) => ({
  ...await original<typeof import('@/api/favorites')>(),
  fetchPreference: vi.fn().mockResolvedValue('[]'),
  savePreference: vi.fn().mockResolvedValue(undefined),
}))

async function open(view = 'list') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects/:id', component: { template: '<div />' } }] })
  await router.push(`/projects/p1?view=${view}`)
  return mount(ProjectDetailView, { global: { plugins: [router] }, attachTo: document.body })
}

describe('项目详情读取状态', () => {
  beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()) })

  it('初次加载、读取失败与确实不可访问分开，失败可重试', async () => {
    const data = useDataStore()
    const retry = vi.spyOn(data, 'hydrate').mockResolvedValue()
    const wrapper = await open()
    expect(wrapper.text()).toContain('正在加载项目信息')
    expect(wrapper.text()).not.toContain('项目不存在')
    data.loadStates.projects = 'error'
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('暂时无法确认项目是否可访问')
    await wrapper.get('.missing button').trigger('click')
    expect(retry).toHaveBeenCalledOnce()
    data.loadStates.projects = 'loaded'
    await flushPromises()
    expect(wrapper.text()).toContain('项目不存在或当前账号无权访问')
  })

  it.each(['list', 'board', 'group', 'quadrant'])('%s 首次任务失败不展示空视图或伪零值，恢复后显示真正空数据', async (view) => {
    const data = seedDataStore()
    data.tasks = []
    data.taskLoadState = 'error'
    const wrapper = await open(view)
    expect(wrapper.text()).toContain('暂时无法确认任务数量')
    expect(wrapper.get('.project-normal-tasks').isVisible()).toBe(false)
    expect(wrapper.find('.ptab-count').element?.parentElement?.textContent).not.toContain('任务')
    expect(wrapper.get('.ptab').attributes('title')).not.toContain('暂无任务')
    data.taskLoadState = 'loading'
    await flushPromises()
    expect(wrapper.text()).toContain('正在加载任务')
    expect(wrapper.get('.project-normal-tasks').isVisible()).toBe(false)
    data.taskLoadState = 'loaded'
    await flushPromises()
    expect(wrapper.get('.project-normal-tasks').isVisible()).toBe(true)
    expect(wrapper.get('.ptab').text()).toContain('0')
  })

  it('已有任务刷新失败保留原视图、搜索输入和内容，恢复不重建视图', async () => {
    const data = seedDataStore()
    const wrapper = await open()
    const taskTitle = data.tasksByProject('p1')[0]!.title
    const table = wrapper.get('.project-normal-tasks').element
    await wrapper.get('[aria-label="项目内查找"]').setValue('保留查找')
    data.taskLoadState = 'error'
    data.loadStates.projects = 'error'
    await flushPromises()
    expect(wrapper.text()).toContain('当前保留已读取的任务及未保存输入')
    expect(wrapper.text()).toContain('当前显示上次读取的项目信息')
    expect(wrapper.get<HTMLInputElement>('[aria-label="项目内查找"]').element.value).toBe('保留查找')
    await wrapper.get('[aria-label="项目内查找"]').setValue('')
    expect(wrapper.get('.project-normal-tasks').element).toBe(table)
    expect(wrapper.get('.project-normal-tasks').isVisible()).toBe(true)
    expect(wrapper.text()).toContain(taskTitle)
    data.taskLoadState = 'loaded'
    data.loadStates.projects = 'loaded'
    await flushPromises()
    expect(wrapper.get('.project-normal-tasks').element).toBe(table)
    expect(wrapper.text()).not.toContain('刷新失败')
  })
})
