import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TodoView from '@/views/TodoView.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'

describe('TodoView', () => {
  let data: ReturnType<typeof useDataStore>

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
    data.taskLoadState = 'loaded'
    useSessionStore().accept({
      accountId: 'a1',
      member: { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' },
      identities: [],
      createdAt: '2026-08-11T09:00:00+08:00',
    })
  })

  it('真实任务未返回时只显示加载状态，不展示启动 fixtures', () => {
    data.taskLoadState = 'loading'
    const fixtureTitle = data.tasks[0]!.title

    const wrapper = mount(TodoView)

    expect(wrapper.get('[role="status"]').text()).toContain('正在加载中')
    expect(wrapper.text()).not.toContain(fixtureTitle)
    expect(wrapper.find('.trow').exists()).toBe(false)
    wrapper.unmount()
  })

  it('真实任务读取失败时显示错误，并可主动重新加载', async () => {
    data.taskLoadState = 'error'
    data.taskLoadError = '任务接口超时'
    const reload = vi.spyOn(data, 'hydrate').mockResolvedValue()

    const wrapper = mount(TodoView)
    expect(wrapper.get('[role="alert"]').text()).toContain('任务接口超时')
    expect(wrapper.find('.trow').exists()).toBe(false)

    await wrapper.get('[role="alert"] button').trigger('click')
    expect(reload).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('只保留列表和看板入口，历史 PDCA 数据不再形成用户可见视图', () => {
    data.tasks[0]!.pdcaCycles = [{
      id: 'pc-history', plan: '旧计划', check: '旧复盘', act: '旧行动', outcome: '达成',
      startedAt: '2026-08-11T09:00:00+08:00', reviewedAt: '2026-08-11T20:00:00+08:00',
    }]
    const wrapper = mount(TodoView)

    expect(wrapper.findAll('.seg-btn').map((button) => button.text())).toEqual(['列表', '看板'])
    expect(wrapper.find('.pdca-view').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('PDCA')
    wrapper.unmount()
  })
})
