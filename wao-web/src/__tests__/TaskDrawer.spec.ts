import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SETTLE_MS } from '@/composables/completion'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import TaskDrawer from '@/components/task/TaskDrawer.vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import type { AiRun } from '@/types'

vi.mock('@/api/ai', () => ({
  createAiRuns: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([]),
  fetchAiRunners: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      id: 'rn1', name: '娃哥电脑', platform: 'Windows 11', engines: ['codex'],
      maxConcurrency: 2, activeRuns: 0, online: true, lastSeenAt: '2026-07-25T20:30:00',
    },
    {
      id: 'rn2', name: 'Linux 主节点', platform: 'Linux 7.0', engines: ['claude'],
      maxConcurrency: 2, activeRuns: 0, online: true, lastSeenAt: '2026-07-25T20:30:00',
    },
  ]),
  fetchAiProjectConfig: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({
    projectId: 'p1', enabled: true, repoUrl: 'https://github.com/acme/wao.git', defaultBranch: 'main',
    workspaceDir: 'wao', defaultEngine: 'codex', preferredRunnerId: null, maxParallelRuns: 2,
  }),
  fetchTaskAiRuns: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      id: 'ar1', taskId: 't1', taskTitle: 'AI 任务', projectId: 'p1', projectName: 'WAO',
      engine: 'codex',
      status: 'running', runnerId: 'rn1', runnerName: '娃哥电脑', requestedRunnerId: null, branchName: 'ai/wao-ar1',
      resultSummary: null, latestProgress: '正在分析任务验收标准', progressUpdatedAt: '2026-07-25T20:30:00',
      progress: [{ id: 'ap1', kind: 'message', content: '正在分析任务验收标准', createdAt: '2026-07-25T20:30:00' }],
      sessionId: 'thread-1', exitCode: null, createdAt: '2026-07-25T20:29:00',
      startedAt: '2026-07-25T20:29:10', finishedAt: null,
    },
  ]),
}))

/** 一条最小可用的 AI 运行记录；只写这条用例关心的字段，其余用默认值补齐。 */
function aiRun(overrides: Partial<AiRun> & Pick<AiRun, 'id' | 'taskId'>): AiRun {
  return {
    taskTitle: 'AI 任务', subId: null, subTitle: null, projectId: 'p1', projectName: 'WAO',
    engine: 'codex', status: 'running', runnerId: null, runnerName: null, requestedRunnerId: null,
    branchName: null, retryOfRunId: null, resultSummary: null, latestProgress: null,
    progressUpdatedAt: null, progress: [],
    usage: {
      model: null, models: null, inputTokens: 0, outputTokens: 0, cachedInputTokens: 0,
      totalTokens: 0, costUsd: null, recorded: false,
    },
    sessionId: null, exitCode: null, createdAt: '2026-07-27T09:00:00',
    startedAt: null, finishedAt: null,
    ...overrides,
  }
}

const router = createRouter({ history: createWebHistory(), routes: [{ path: '/:p(.*)*', component: { template: '<div/>' } }] })

function mountDrawer() {
  return mount(TaskDrawer, { global: { plugins: [router] }, attachTo: document.body })
}

/**
 * 等异步组件（描述编辑器）解析完成。
 *
 * 动态 import 的解析要跨若干个微 / 宏任务，固定睡一个时长要么慢要么偶发失败，
 * 所以这里轮询到出现为止，超时才判失败。
 */
async function waitFor(w: ReturnType<typeof mountDrawer>, selector: string, tries = 50) {
  for (let i = 0; i < tries; i++) {
    await flushPromises()
    if (w.find(selector).exists()) return true
    await new Promise((r) => setTimeout(r, 2))
  }
  return false
}

describe('TaskDrawer', () => {
  let data: ReturnType<typeof useDataStore>
  let ui: ReturnType<typeof useUiStore>
  let taskId: string

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
    ui = useUiStore()
    taskId = data.tasks[0]!.id
    data.tasks[0]!.subs = []
  })

  it('没有打开的任务时不渲染抽屉', () => {
    expect(mountDrawer().find('.drawer').exists()).toBe(false)
  })

  it('打开任务后渲染标题与描述编辑器', async () => {
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    expect(w.find('.drawer').exists()).toBe(true)
    expect(w.find('.title-input').attributes('value')).toBe(data.taskById(taskId)!.title)

    // 编辑器是异步组件（ProseMirror 不进首屏包），要等它解析完才出现在 DOM 里
    expect(w.find('textarea.desc').exists()).toBe(false)
    expect(await waitFor(w, '.md-body')).toBe(true)
  })

  it('评论发送中会锁定提交，避免重复新增评论', async () => {
    let resolveComment!: (value: boolean) => void
    const addComment = vi.spyOn(data, 'addTaskComment').mockImplementation(() =>
      new Promise<boolean>((resolve) => { resolveComment = resolve }),
    )
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    await w.find('.comment-input input').setValue('只发送一次')
    await w.find('.comment-input .send').trigger('click')
    await w.find('.comment-input .send').trigger('click')

    expect(addComment).toHaveBeenCalledTimes(1)
    expect(w.find('.comment-input .send').text()).toContain('发送中')
    resolveComment(true)
    await flushPromises()
    expect(w.find('.comment-input input').attributes('disabled')).toBeUndefined()
  })

  it('在任务内保存并展示 AI 执行过程', async () => {
    ui.openTask(taskId)
    const w = mountDrawer()
    await flushPromises()

    expect(w.find('.ai-archive').text()).toContain('实时更新中')
    expect(w.find('.ai-archive').text()).toContain('正在分析任务验收标准')
    w.unmount()
  })

  it('默认展示任务属性栏，点击子任务后切成子任务详情', async () => {
    const subId = data.addSub(taskId, '扫描身份证')!
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    // 只看右侧栏——「自定义属性」这几个字也出现在种子数据的评论里，全局断言会误判
    expect(w.find('.props').text()).toContain('自定义属性')
    expect(w.find('.sub-back').exists()).toBe(false)

    await w.find('.sub-row').trigger('click')

    expect(w.find('.sub-back').exists()).toBe(true)
    expect(w.find('.sub-title-input').attributes('value')).toBe('扫描身份证')
    expect(w.find('.props').text()).not.toContain('自定义属性')
    expect(data.subById(taskId, subId)).toBeDefined()
  })

  it('子任务详情里改名会写回 store', async () => {
    const subId = data.addSub(taskId, '旧名字')!
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()
    await w.find('.sub-row').trigger('click')

    const input = w.find('.sub-title-input')
    ;(input.element as HTMLInputElement).value = '新名字'
    await input.trigger('input')

    expect(data.subById(taskId, subId)?.text).toBe('新名字')
  })

  it('点子任务的勾选框只切完成态，不展开详情', async () => {
    const subId = data.addSub(taskId, '扫描身份证')!
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    await w.find('.sub-row .cbox').trigger('click')

    expect(data.subById(taskId, subId)?.done).toBe(true)
    expect(w.find('.sub-back').exists()).toBe(false)
  })

  it('分享菜单同时给出分享页与 Markdown 两种链接', async () => {
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    await w.find('.share-wrap .icon-btn').trigger('click')

    const menu = w.find('.share-menu')
    expect(menu.exists()).toBe(true)
    expect(menu.text()).toContain('本任务 + 全部子任务')
    expect(menu.text()).toContain('复制分享页链接')
    expect(menu.text()).toContain('复制 Markdown 地址')
  })

  it('已完成的子任务沉到底部并默认收起，点击才展开', async () => {
    data.addSub(taskId, '未完成的')
    const doneId = data.addSub(taskId, '已完成的')!
    data.updateSub(taskId, doneId, { done: true })
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    // 默认只看得到未完成的那条，已完成的藏在折叠条后面
    expect(w.findAll('.sub-row')).toHaveLength(1)
    expect(w.find('.sub-row').text()).toContain('未完成的')

    const toggle = w.find('.sub-done-toggle')
    expect(toggle.exists()).toBe(true)
    expect(toggle.text()).toContain('1')

    await toggle.trigger('click')

    const rows = w.findAll('.sub-row')
    expect(rows).toHaveLength(2)
    // 已完成的排在最底部
    expect(rows[1]!.text()).toContain('已完成的')
  })

  it('没有已完成子任务时不显示折叠条', async () => {
    data.addSub(taskId, '只有这一条')
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    expect(w.find('.sub-done-toggle').exists()).toBe(false)
  })

  it('刚勾完成的子任务先原地停留，不会立刻沉底消失', async () => {
    vi.useFakeTimers()
    try {
      data.addSub(taskId, '马上要勾的')
      ui.openTask(taskId)
      const w = mountDrawer()
      await w.vm.$nextTick()

      await w.find('.sub-row .cbox').trigger('click')

      // 仍在原位，只是淡出
      expect(w.findAll('.sub-row')).toHaveLength(1)
      expect(w.find('.sub-row').classes()).toContain('settling')

      vi.advanceTimersByTime(SETTLE_MS)
      await w.vm.$nextTick()

      expect(w.findAll('.sub-row')).toHaveLength(0)
      expect(w.find('.sub-done-toggle').exists()).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })

  it('子任务标题用可撑高的 textarea，长标题不会被截断', async () => {
    data.addSub(taskId, '一个特别长的子任务标题'.repeat(6))
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()
    await w.find('.sub-row').trigger('click')

    const title = w.find('.sub-title-input')
    expect(title.element.tagName).toBe('TEXTAREA')
    expect((title.element as HTMLTextAreaElement).value).toBe('一个特别长的子任务标题'.repeat(6))
  })

  it('有已完成子任务时，分享菜单给出「只分享未完成」开关', async () => {
    data.addSub(taskId, '未完成的')
    const doneId = data.addSub(taskId, '已完成的')!
    data.updateSub(taskId, doneId, { done: true })
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()
    await w.find('.share-wrap .icon-btn').trigger('click')

    const filter = w.find('.share-filter')
    expect(filter.exists()).toBe(true)
    expect(filter.text()).toContain('只分享还没人做的子任务')
    expect(w.find('.share-scope').text()).toContain('全部子任务')

    await filter.find('input').setValue(true)

    expect(w.find('.share-scope').text()).toContain('还没人做的子任务')
  })

  it('没有已完成子任务时不显示这个开关——没有可过滤的东西', async () => {
    data.addSub(taskId, '只有未完成的')
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()
    await w.find('.share-wrap .icon-btn').trigger('click')

    expect(w.find('.share-filter').exists()).toBe(false)
  })

  it('点 AI run 先选电脑和 AI 工具，选定后才入队', async () => {
    const { createAiRuns } = await import('@/api/ai')
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    await w.find('.ai-run-btn').trigger('click')
    await flushPromises()

    expect(w.find('.launcher').text()).toContain('Claude Code')
    expect(createAiRuns).not.toHaveBeenCalled()

    await w.findAll('.engine-option')[1]!.trigger('click')
    await w.find('.launcher footer .primary').trigger('click')
    await flushPromises()

    expect(createAiRuns).toHaveBeenCalledWith([taskId], { subId: null, engine: 'claude', runnerId: null })
    w.unmount()
  })

  it('子任务可以单独派给 AI，选择器上写明本次只跑这一条', async () => {
    const { createAiRuns } = await import('@/api/ai')
    vi.mocked(createAiRuns).mockClear()
    const subId = data.addSub(taskId, '子任务也能单独跑')!
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    await w.find('.sub-row .sub-ai').trigger('click')
    await flushPromises()

    expect(w.find('.launcher .scope').text()).toContain('子任务也能单独跑')

    await w.find('.launcher footer .primary').trigger('click')
    await flushPromises()

    expect(createAiRuns).toHaveBeenCalledWith([taskId], { subId, engine: 'codex', runnerId: null })
    w.unmount()
  })

  it('已经派过工的子任务只显示标记，不再给第二个派工入口', async () => {
    const { fetchTaskAiRuns } = await import('@/api/ai')
    const subId = data.addSub(taskId, '已经在跑的子任务')!
    vi.mocked(fetchTaskAiRuns).mockResolvedValueOnce([
      aiRun({ id: 'ar9', taskId, subId, subTitle: '已经在跑的子任务', status: 'running' }),
    ])
    ui.openTask(taskId)
    const w = mountDrawer()
    await flushPromises()

    expect(w.find('.sub-row .sub-dispatched').exists()).toBe(true)
    expect(w.find('.sub-row .sub-ai').exists()).toBe(false)
    w.unmount()
  })

  it('跑成功的运行也能继续给提示词，按钮写「继续优化」', async () => {
    const { fetchTaskAiRuns } = await import('@/api/ai')
    vi.mocked(fetchTaskAiRuns).mockResolvedValueOnce([
      aiRun({
        id: 'ar8', taskId, status: 'succeeded', engine: 'claude',
        runnerId: 'rn1', sessionId: 'thread-8', resultSummary: '移动端已优化',
      }),
    ])
    ui.openTask(taskId)
    const w = mountDrawer()
    await flushPromises()

    expect(w.find('.ai-continue button').text()).toContain('继续优化')
    w.unmount()
  })

  it('执行记录里的用量展示 输入 / 输出 / 缓存 / 合计 明细，而不只有汇总', async () => {
    const { fetchTaskAiRuns } = await import('@/api/ai')
    vi.mocked(fetchTaskAiRuns).mockResolvedValueOnce([
      aiRun({
        id: 'ar9', taskId, status: 'succeeded', engine: 'claude',
        usage: {
          model: 'claude-opus-5-20260501', models: 'claude-opus-5-20260501',
          inputTokens: 2, outputTokens: 4, cachedInputTokens: 19_189,
          totalTokens: 19_195, costUsd: 0.087, recorded: true,
        },
      }),
    ])
    ui.openTask(taskId)
    const w = mountDrawer()
    await flushPromises()

    const usage = w.find('.ai-run-usage')
    expect(usage.exists()).toBe(true)
    expect(usage.text()).toContain('输入 2')
    expect(usage.text()).toContain('输出 4')
    expect(usage.text()).toContain('缓存 19K')
    expect(usage.text()).toContain('合计 19K')
    expect(usage.text()).toContain('$0.09')
    w.unmount()
  })

  it('继续优化的输入框是 Markdown 编辑器，可以像描述一样粘贴图片', async () => {
    const { fetchTaskAiRuns } = await import('@/api/ai')
    vi.mocked(fetchTaskAiRuns).mockResolvedValueOnce([
      aiRun({
        id: 'ar10', taskId, status: 'succeeded', engine: 'claude',
        runnerId: 'rn1', sessionId: 'thread-10',
      }),
    ])
    ui.openTask(taskId)
    const w = mountDrawer()
    await flushPromises()

    expect(w.find('.ai-continue textarea').exists()).toBe(false)
    expect(await waitFor(w, '.ai-continue-input .md-body')).toBe(true)
    w.unmount()
  })

  it('展开子任务时，分享范围收敛到该子任务', async () => {
    data.addSub(taskId, '扫描身份证')
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()
    await w.find('.sub-row').trigger('click')

    await w.find('.share-wrap .icon-btn').trigger('click')

    expect(w.find('.share-menu').text()).toContain('当前子任务')
  })
})
