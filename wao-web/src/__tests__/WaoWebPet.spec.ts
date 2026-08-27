import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import WaoWebPet from '@/components/assistant/WaoWebPet.vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import type { Task, TaskStatus } from '@/types'

function task(id: string, title: string, status: TaskStatus, pr: Task['pr'] = 'P2'): Task {
  return {
    id, title, status, pr, project: 'p1', owner: 'wg', parts: [], due: '2026-08-27',
    desc: '', steps: [], subs: [], files: [], docs: [], linkTasks: [], comments: [], custom: [], activity: [],
  }
}

describe('WaoWebPet', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('优先展示真实待验收任务并可直接打开详情', async () => {
    const data = useDataStore()
    const ui = useUiStore()
    data.taskLoadState = 'loaded'
    data.tasks.push(
      task('todo', '整理下周计划', '待办'),
      task('running', '正在实现网页宠物', 'AI 进行中'),
      task('review', '验收登录体验', '待验证', 'P1'),
    )

    const wrapper = mount(WaoWebPet, {
      props: { assistantStatus: { configured: true, message: '已连接本地 Codex' } },
    })

    expect(wrapper.get('.pet-notice b').text()).toBe('AI 已完成 · 1 项等你验收')
    expect(wrapper.get('.pet-notice small').text()).toBe('验收登录体验')
    expect(wrapper.get('.pet-badge').text()).toBe('3')
    await wrapper.get('.pet-notice').trigger('click')
    expect(ui.openTaskId).toBe('review')
    wrapper.unmount()
  })

  it('按待验收、执行中、待办顺序轮播真实任务动态', async () => {
    vi.useFakeTimers()
    const data = useDataStore()
    data.taskLoadState = 'loaded'
    data.tasks.push(
      task('todo', '整理下周计划', '待办'),
      task('running', '正在实现网页宠物', 'AI 进行中'),
      task('review', '验收登录体验', '待验证'),
    )
    const wrapper = mount(WaoWebPet, {
      props: { assistantStatus: { configured: true, message: '已连接本地 Codex' } },
    })

    expect(wrapper.text()).toContain('AI 已完成')
    await vi.advanceTimersByTimeAsync(7_000)
    await nextTick()
    expect(wrapper.text()).toContain('AI 正在工作')
    await vi.advanceTimersByTimeAsync(7_000)
    await nextTick()
    expect(wrapper.text()).toContain('待办提醒')
    wrapper.unmount()
  })

  it('任务读取失败时明确显示失败，宠物仍能打开问答工作台', async () => {
    const data = useDataStore()
    data.taskLoadState = 'error'
    data.taskLoadError = '任务接口暂时不可达'
    const wrapper = mount(WaoWebPet, {
      props: { assistantStatus: { configured: false, message: '本地 Codex 暂时离线' } },
    })

    expect(wrapper.text()).toContain('任务动态暂时不可用')
    expect(wrapper.text()).toContain('任务接口暂时不可达')
    await wrapper.get('.pet-avatar').trigger('click')
    expect(wrapper.emitted('open-assistant')).toHaveLength(1)
    wrapper.unmount()
  })
})
