import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TodoView from '@/views/TodoView.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import type { PdcaCycle, Task, TaskStatus } from '@/types'

describe('TodoView PDCA 视图', () => {
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

  function task(title: string, status: TaskStatus, cycle?: Partial<PdcaCycle>): Task {
    const base = JSON.parse(JSON.stringify(data.tasks[0])) as Task
    return {
      ...base,
      id: `t-${title}`,
      title,
      owner: 'wg',
      parts: [],
      status,
      pdcaCycles: cycle ? [{
        id: `pc-${title}`,
        plan: '本轮目标', check: '', act: '', outcome: null,
        startedAt: '2026-08-11T09:00:00+08:00', reviewedAt: null,
        ...cycle,
      }] : [],
    }
  }

  it('只统计启用 PDCA 的任务，并按真实状态投影四阶段', async () => {
    data.tasks = [
      task('计划事项', '待办', {}),
      task('执行事项', '进行中', {}),
      task('复盘事项', '待验证', {}),
      task('闭环事项', '已完成', {
        check: '达成了目标', act: '保持当前做法', outcome: '达成',
        reviewedAt: '2026-08-11T20:00:00+08:00',
      }),
      task('普通事项', '待办'),
    ]
    const wrapper = mount(TodoView)

    await wrapper.findAll('.seg-btn')[2]!.trigger('click')

    const columns = wrapper.findAll('.pdca-col')
    expect(columns).toHaveLength(4)
    expect(columns[0]!.text()).toContain('计划事项')
    expect(columns[1]!.text()).toContain('执行事项')
    expect(columns[2]!.text()).toContain('复盘事项')
    expect(columns[3]!.text()).toContain('闭环事项')
    expect(wrapper.find('.pdca-summary').text()).toContain('4跟踪中的事情')
    expect(wrapper.find('.pdca-view').text()).not.toContain('普通事项')
    wrapper.unmount()
  })
})
