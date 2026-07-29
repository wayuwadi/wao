import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import type { AiRunner } from '@/types'
import { fetchAiRunners, removeAiRunner, requestRunnerScriptUpdate, rerunAiRun } from '@/api/ai'

vi.mock('@/api/ai', () => ({
  fetchAiRuns: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      id: 'ar1',
      taskId: 't1',
      taskTitle: '修复自动任务',
      projectId: 'p1',
      projectName: 'WAO',
      engine: 'codex',
      status: 'failed',
      runnerId: 'rn1',
      runnerName: '娃哥电脑',
      requestedRunnerId: null,
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
      engine: 'claude',
      status: 'running', runnerId: 'rn2', runnerName: 'Linux 主节点', requestedRunnerId: 'rn2',
      branchName: 'ai/wao-ar2',
      resultSummary: null, latestProgress: '已定位问题，正在实现实时反馈',
      progressUpdatedAt: '2026-07-25T20:18:20',
      progress: [{ id: 'ap3', kind: 'message', content: '已定位问题，正在实现实时反馈', createdAt: '2026-07-25T20:18:20' }],
      sessionId: 'sess-1', exitCode: null, createdAt: '2026-07-25T20:18:00',
      startedAt: '2026-07-25T20:18:05', finishedAt: null,
    },
  ]),
  fetchAiRunners: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      id: 'rn1', name: '娃哥电脑', platform: 'Windows 11', engines: ['codex'],
      maxConcurrency: 2, activeRuns: 0, online: false, lastSeenAt: '2026-07-25T18:00:00',
    },
    {
      id: 'rn2', name: 'Linux 主节点', platform: 'Linux 7.0', engines: ['claude'],
      maxConcurrency: 2, activeRuns: 1, online: true, lastSeenAt: '2026-07-25T20:18:20',
    },
  ]),
  fetchAiWorkflows: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      projectId: 'p1',
      projectName: 'Wa All in One',
      config: {
        projectId: 'p1', enabled: true, repoUrl: 'https://github.com/acme/wao.git', defaultBranch: 'main',
        workspaceDir: 'wao', defaultEngine: 'claude', preferredRunnerId: 'rn2', maxParallelRuns: 3,
      },
      preferredRunnerName: 'Linux 主节点',
      activeRuns: 1,
      succeededRuns: 4,
      failedRuns: 1,
      lastRunAt: '2026-07-25T20:18:00',
    },
  ]),
  createAiPairCode: vi.fn<() => Promise<{ code: string; expiresAt: string }>>()
    .mockResolvedValue({ code: 'ABCD2345KMNP', expiresAt: '2026-07-25T20:30:00' }),
  saveAiProjectConfig: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({}),
  requestRunnerScriptUpdate: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      id: 'rn1', name: '娃哥电脑', platform: 'Windows 11', engines: ['codex'],
      maxConcurrency: 2, activeRuns: 0, online: false, lastSeenAt: '2026-07-25T18:00:00',
      scriptUpToDate: false, updateRequested: true,
    },
    {
      id: 'rn2', name: 'Linux 主节点', platform: 'Linux 7.0', engines: ['claude'],
      maxConcurrency: 2, activeRuns: 1, online: true, lastSeenAt: '2026-07-25T20:18:20',
      scriptUpToDate: true, updateRequested: false,
    },
  ]),
  removeAiRunner: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      id: 'rn2', name: 'Linux 主节点', platform: 'Linux 7.0', engines: ['claude'],
      maxConcurrency: 2, activeRuns: 1, online: true, lastSeenAt: '2026-07-25T20:18:20',
    },
  ]),
  rerunAiRun: vi.fn<() => Promise<Record<string, unknown>>>()
    .mockResolvedValue({ id: 'ar3', taskId: 't1', taskTitle: '修复自动任务', retryOfRunId: 'ar1' }),
}))

/** 解除接入用例里要按台改列表，这里补齐 AiRunner 的全部字段。 */
function runner(patch: Partial<AiRunner>): AiRunner {
  return {
    id: 'rn1', name: '娃哥电脑', platform: 'Windows 11', engines: ['codex'],
    maxConcurrency: 2, configuredMaxConcurrency: null, activeRuns: 0, online: false,
    lastSeenAt: '2026-07-25T18:00:00', wakeMac: '', wakeHost: '', wakePort: 9,
    wakeConfigured: false, lastWakeRequestedAt: null, scriptUpToDate: null, updateRequested: false,
    ...patch,
  }
}

const windowsRunner = () => runner({})
const linuxRunner = () => runner({
  id: 'rn2', name: 'Linux 主节点', platform: 'Linux 7.0', engines: ['claude'],
  activeRuns: 1, online: true, lastSeenAt: '2026-07-25T20:18:20',
})

function mountHome() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  return { router, wrapper: mount(HomeView, { global: { plugins: [router] } }) }
}

describe('HomeView AI 控制台', () => {
  afterEach(() => vi.useRealTimers())

  it('首屏不再展示已要求删除的 AI 说明段落', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    expect(wrapper.find('.hero').text()).not.toContain('想法有多大，产出就有多大')
    expect(wrapper.find('.hero').text()).not.toContain('多台电脑并行开工')
    wrapper.unmount()
  })

  it('在失败记录中展示摘要，点击后展示完整原因和退出码', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    expect(wrapper.find('.recent-main small').text()).toBe('失败阶段：准备 Git 工作区')

    await wrapper.find('.recent-list button').trigger('click')

    expect(wrapper.find('.run-detail').text()).toContain('失败原因')
    expect(wrapper.find('.run-detail').text()).toContain('仓库地址无法克隆')
    expect(wrapper.find('.run-detail').text()).toContain('-1')
    expect(wrapper.find('.run-detail').text()).not.toContain('等待第一条 AI 反馈')
    wrapper.unmount()
  })

  it('失败的运行可以带着报错重跑，成功的运行不给这个按钮', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    // 失败记录：详情里有「基于报错重跑」
    await wrapper.find('.recent-list button').trigger('click')
    const retry = wrapper.find('.run-detail footer .retry')
    expect(retry.exists()).toBe(true)
    expect(retry.text()).toContain('基于报错重跑')

    await retry.trigger('click')
    await flushPromises()
    expect(rerunAiRun).toHaveBeenCalledWith('ar1')
    // 重跑后收起详情，回到列表看新排队的运行
    expect(wrapper.find('.run-detail').exists()).toBe(false)

    // 进行中的运行不该出现这个按钮
    await wrapper.find('.run-card').trigger('click')
    expect(wrapper.find('.run-detail footer .retry').exists()).toBe(false)
    wrapper.unmount()
  })

  it('运行卡片展示最新反馈和使用的 AI 工具，点击先打开实时记录而不是跳走', async () => {
    vi.useFakeTimers()
    const { router, wrapper } = mountHome()
    await flushPromises()

    expect(wrapper.find('.run-card').text()).toContain('已定位问题，正在实现实时反馈')
    expect(wrapper.find('.run-card').text()).toContain('Claude Code')
    await wrapper.find('.run-card').trigger('click')

    expect(wrapper.find('.run-detail').text()).toContain('AI 执行记录')
    expect(wrapper.find('.run-detail').text()).toContain('Claude Code')
    expect(router.currentRoute.value.fullPath).toBe('/')
    wrapper.unmount()
  })

  it('电脑视图按机器展示接入的 AI 工具、槽位和正在跑的任务', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.findAll('.console-tabs button')[1]!.trigger('click')

    const cards = wrapper.findAll('.runner-card')
    expect(cards).toHaveLength(2)
    expect(cards[0]!.text()).toContain('Codex')
    expect(cards[0]!.classes()).toContain('offline')
    expect(cards[1]!.text()).toContain('Claude Code')
    expect(cards[1]!.text()).toContain('槽位 1 / 2')
    expect(cards[1]!.find('.runner-tasks button').text()).toBe('实时任务')
    wrapper.unmount()
  })

  it('电脑视图可以一键下发 Runner 脚本更新，卡片展示更新进度', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.findAll('.console-tabs button')[1]!.trigger('click')
    await wrapper.find('.section-head .pair-btn.ghost').trigger('click')
    await flushPromises()

    expect(requestRunnerScriptUpdate).toHaveBeenCalledTimes(1)
    const cards = wrapper.findAll('.runner-card')
    expect(cards[0]!.text()).toContain('更新已下发，等该电脑空闲后自动重启生效')
    expect(cards[1]!.text()).not.toContain('更新已下发')
    wrapper.unmount()
  })

  it('空闲的电脑可以确认后解除接入，卡片随即消失', async () => {
    vi.useFakeTimers()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.findAll('.console-tabs button')[1]!.trigger('click')
    // 解除后再拉列表时，这台电脑已经不在了
    vi.mocked(fetchAiRunners).mockResolvedValue([linuxRunner()])
    await wrapper.findAll('.runner-card')[0]!.find('header .icon-btn.danger').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalled()
    expect(removeAiRunner).toHaveBeenCalledWith('rn1')
    const cards = wrapper.findAll('.runner-card')
    expect(cards).toHaveLength(1)
    expect(cards[0]!.text()).toContain('Linux 主节点')
    confirm.mockRestore()
    wrapper.unmount()
  })

  it('还有任务在跑的电脑不能解除接入，也不会弹确认框', async () => {
    vi.useFakeTimers()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(fetchAiRunners).mockResolvedValue([windowsRunner(), linuxRunner()])
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.findAll('.console-tabs button')[1]!.trigger('click')
    await wrapper.findAll('.runner-card')[1]!.find('header .icon-btn.danger').trigger('click')
    await flushPromises()

    expect(confirm).not.toHaveBeenCalled()
    expect(removeAiRunner).not.toHaveBeenCalledWith('rn2')
    expect(wrapper.findAll('.runner-card')).toHaveLength(2)
    confirm.mockRestore()
    wrapper.unmount()
  })

  it('工作流视图展示项目配置，点击后可以就地编辑默认 AI 工具', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.findAll('.console-tabs button')[2]!.trigger('click')
    await flushPromises()

    const card = wrapper.find('.workflow-card')
    expect(card.text()).toContain('Wa All in One')
    expect(card.text()).toContain('Claude Code')
    expect(card.text()).toContain('Linux 主节点')

    await card.find('.link-btn').trigger('click')
    const engineSelect = card.findAll('.flow-form select')[0]!
    expect((engineSelect.element as HTMLSelectElement).value).toBe('claude')
    wrapper.unmount()
  })
})
