import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import HomeView from '@/views/HomeView.vue'

vi.mock('@/api/ai', () => ({
  fetchAiRuns: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      id: 'ar1',
      taskId: 't1',
      taskTitle: '修复自动任务',
      projectId: 'p1',
      projectName: 'WAO',
      status: 'failed',
      runnerId: 'rn1',
      runnerName: '娃哥电脑',
      branchName: 'ai/wao-ar1',
      resultSummary: '失败阶段：准备 Git 工作区\n错误详情：仓库地址无法克隆',
      latestProgress: null,
      progressUpdatedAt: null,
      progress: [],
      sessionId: null,
      exitCode: -1,
      createdAt: '2026-07-25T18:50:00',
      startedAt: '2026-07-25T18:50:10',
      finishedAt: '2026-07-25T18:51:00',
    },
    {
      id: 'ar2', taskId: 't2', taskTitle: '实时任务', projectId: 'p1', projectName: 'WAO',
      status: 'running', runnerId: 'rn1', runnerName: '娃哥电脑', branchName: 'ai/wao-ar2',
      resultSummary: null, latestProgress: '已定位问题，正在实现实时反馈',
      progressUpdatedAt: '2026-07-25T20:18:20',
      progress: [{ id: 'ap3', kind: 'message', content: '已定位问题，正在实现实时反馈', createdAt: '2026-07-25T20:18:20' }],
      sessionId: 'thread-1', exitCode: null, createdAt: '2026-07-25T20:18:00',
      startedAt: '2026-07-25T20:18:05', finishedAt: null,
    },
  ]),
  fetchAiRunners: vi.fn<() => Promise<never[]>>().mockResolvedValue([]),
}))

describe('HomeView AI 运行详情', () => {
  afterEach(() => vi.useRealTimers())

  it('在失败记录中展示摘要，点击后展示完整原因和退出码', async () => {
    vi.useFakeTimers()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    const wrapper = mount(HomeView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('.recent-main small').text()).toBe('失败阶段：准备 Git 工作区')

    await wrapper.find('.recent-list button').trigger('click')

    expect(wrapper.find('.run-detail').text()).toContain('失败原因')
    expect(wrapper.find('.run-detail').text()).toContain('仓库地址无法克隆')
    expect(wrapper.find('.run-detail').text()).toContain('-1')
    expect(wrapper.find('.run-detail').text()).not.toContain('等待第一条 AI 反馈')
    wrapper.unmount()
  })

  it('运行卡片直接展示最新反馈，点击先打开实时记录而不是跳走', async () => {
    vi.useFakeTimers()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    const wrapper = mount(HomeView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('.run-card').text()).toContain('已定位问题，正在实现实时反馈')
    await wrapper.find('.run-card').trigger('click')

    expect(wrapper.find('.run-detail').text()).toContain('AI 执行记录')
    expect(wrapper.find('.run-detail').text()).toContain('已定位问题，正在实现实时反馈')
    expect(router.currentRoute.value.fullPath).toBe('/')
    wrapper.unmount()
  })
})
