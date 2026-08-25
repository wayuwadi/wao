import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SETTLE_MS } from '@/composables/completion'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import TaskDrawer from '@/components/task/TaskDrawer.vue'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import { useUiStore } from '@/stores/ui'
import type { AiRun } from '@/types'

vi.mock('@/api/ai', () => ({
  createAiRuns: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([]),
  continueAiRun: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({}),
  pauseAiRun: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({}),
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

vi.mock('@/api/aiAccount', () => ({
  fetchAiAccounts: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([]),
}))

/** 一条最小可用的 AI 运行记录；只写这条用例关心的字段，其余用默认值补齐。 */
function aiRun(overrides: Partial<AiRun> & Pick<AiRun, 'id' | 'taskId'>): AiRun {
  return {
    taskTitle: 'AI 任务', subId: null, subTitle: null, projectId: 'p1', projectName: 'WAO',
    engine: 'codex', status: 'running', runnerId: null, runnerName: null, requestedRunnerId: null,
    branchName: null, retryOfRunId: null, resultSummary: null, reportUrl: null, latestProgress: null,
    progressUpdatedAt: null, progress: [],
    usage: {
      model: null, models: null, inputTokens: 0, outputTokens: 0, cachedInputTokens: 0,
      totalTokens: 0, costUsd: null, recorded: false,
    },
    continuationPending: false,
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
async function waitFor(w: ReturnType<typeof mountDrawer>, selector: string, tries = 150) {
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
    seedDataStore(data)
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

  it('历史 PDCA 数据仍可加载，但任务详情不再展示或操作该模块', async () => {
    const current = data.taskById(taskId)!
    current.pdcaCycles = [{
      id: 'pc-history', plan: '旧计划', check: '旧复盘', act: '旧行动', outcome: '达成',
      startedAt: '2026-08-01T09:00:00+08:00', reviewedAt: '2026-08-02T09:00:00+08:00',
    }]
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    expect(w.find('.pdca').exists()).toBe(false)
    expect(w.text()).not.toContain('PDCA 闭环')
    expect(current.pdcaCycles).toHaveLength(1)
    w.unmount()
  })

  it('关联候选按创建时间倒序，并排除已完成任务', async () => {
    const current = data.taskById(taskId)!
    const candidates = data.tasks.filter((item) => item.id !== taskId)
    expect(candidates.length).toBeGreaterThanOrEqual(3)
    candidates.forEach((item) => { item.createdAt = null })
    Object.assign(candidates[0]!, {
      title: '较早的关联候选', status: '待办', createdAt: '2026-08-18T09:00:00',
    })
    Object.assign(candidates[1]!, {
      title: '最近的关联候选', status: '进行中', createdAt: '2026-08-20T09:00:00',
    })
    Object.assign(candidates[2]!, {
      title: '已经完成的关联候选', status: '已完成', createdAt: '2026-08-21T09:00:00',
    })

    const docs = Object.values(data.docs)
    docs.forEach((doc) => { doc.created = '历史时间未知' })
    Object.assign(docs[0]!, { title: '较早知识', created: '2026-08-17T09:00:00+08:00' })
    Object.assign(docs[1]!, { title: '最近知识', created: '2026-08-19T09:00:00+08:00' })
    data.files.splice(0, data.files.length,
      {
        id: 'file-old', name: '较早文件', size: '1 KB', icon: 'ri-file-line', time: '',
        createdAt: '2026-08-16T09:00:00', by: 'wg', link: '/api/files/file-old/download',
      },
      {
        id: 'file-new', name: '最近文件', size: '2 KB', icon: 'ri-file-line', time: '',
        createdAt: '2026-08-20T10:00:00+08:00', by: 'wg', link: '/api/files/file-new/download',
      },
    )
    const nativeDateParse = Date.parse.bind(Date)
    const dateParse = vi.spyOn(Date, 'parse').mockImplementation((value) => nativeDateParse(value))

    ui.openTask(current.id)
    const w = mountDrawer()
    await w.vm.$nextTick()
    await w.findAll('.text-btn').find((button) => button.text().includes('关联知识、任务或文件'))!.trigger('click')

    const allRows = w.findAll('.relation-row').map((row) => row.text())
    expect(dateParse).toHaveBeenCalledWith('2026-08-20T09:00:00+08:00')
    dateParse.mockRestore()
    expect(allRows.slice(0, 6).map((row) =>
      ['最近文件', '最近的关联候选', '最近知识', '较早的关联候选', '较早知识', '较早文件']
        .find((name) => row.includes(name)),
    )).toEqual(['最近文件', '最近的关联候选', '最近知识', '较早的关联候选', '较早知识', '较早文件'])

    await w.find('.relation-picker .search input').setValue('关联候选')
    expect(w.findAll('.relation-row').map((row) => row.text())).toHaveLength(2)
    expect(w.findAll('.relation-row')[0]!.text()).toContain('最近的关联候选')
    await w.find('.relation-picker .search input').setValue('')

    const switchType = async (label: string) => {
      await w.findAll('.relation-picker nav button').find((button) => button.text().startsWith(label))!.trigger('click')
      return w.findAll('.relation-row').map((row) => row.text())
    }
    const taskRows = await switchType('任务')
    expect(taskRows.findIndex((row) => row.includes('最近的关联候选')))
      .toBeLessThan(taskRows.findIndex((row) => row.includes('较早的关联候选')))
    expect(taskRows.join('')).not.toContain('已经完成的关联候选')

    const docRows = await switchType('知识')
    expect(docRows[0]).toContain('最近知识')
    expect(docRows[1]).toContain('较早知识')

    const fileRows = await switchType('文件')
    expect(fileRows[0]).toContain('最近文件')
    expect(fileRows[1]).toContain('较早文件')
    w.unmount()
  })

  it('关联选择器使用真实知识、任务和文件，并支持再次点击解除', async () => {
    const current = data.taskById(taskId)!
    const doc = Object.values(data.docs).find((item) => !current.docs.includes(item.id))!
    const linkedTask = data.tasks.find((item) => item.id !== taskId && !current.linkTasks.some((link) => link.id === item.id))!
    data.files.unshift({
      id: 'file-real-1', name: '真实验收记录.pdf', size: '12 KB', icon: 'ri-file-pdf-2-line',
      time: '刚刚', by: '娃哥', link: '/api/files/file-real-1/raw',
    })
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    await w.findAll('.text-btn').find((button) => button.text().includes('关联知识、任务或文件'))!.trigger('click')
    expect(w.find('.relation-picker').exists()).toBe(true)

    const clickCandidate = async (text: string) => {
      const row = w.findAll('.relation-row').find((item) => item.text().includes(text))!
      await row.trigger('click')
      await w.vm.$nextTick()
    }
    await clickCandidate(doc.title)
    await clickCandidate(linkedTask.title)
    await clickCandidate('真实验收记录.pdf')

    expect(current.docs).toContain(doc.id)
    expect(current.linkTasks.map((item) => item.id)).toContain(linkedTask.id)
    expect(current.files.map((item) => item.id)).toContain('file-real-1')

    await clickCandidate('真实验收记录.pdf')
    expect(current.files.map((item) => item.id)).not.toContain('file-real-1')
    w.unmount()
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

  it('空的 AI 记录轮询不会反复插入加载区而推挤评论', async () => {
    const { fetchTaskAiRuns } = await import('@/api/ai')
    const fetchRuns = vi.mocked(fetchTaskAiRuns)
    // 首次打开和下一次五秒轮询都没有记录；其后的测试仍使用默认 mock 返回值。
    fetchRuns.mockResolvedValueOnce([]).mockResolvedValueOnce([])
    vi.useFakeTimers()
    try {
      ui.openTask(taskId)
      const w = mountDrawer()
      await flushPromises()
      expect(w.find('.ai-archive').exists()).toBe(false)

      await vi.advanceTimersByTimeAsync(5_000)
      await flushPromises()
      expect(w.find('.ai-archive').exists()).toBe(false)
      w.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('运行中提供停止入口，并在确认后请求停止当前运行', async () => {
    const { pauseAiRun } = await import('@/api/ai')
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    ui.openTask(taskId)
    const w = mountDrawer()
    await flushPromises()

    await w.find('.pause-ai-btn').trigger('click')

    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('未提交结果可能丢失'))
    expect(pauseAiRun).toHaveBeenCalledWith('ar1')
    confirm.mockRestore()
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
    const subActions = w.findAll('.sub-detail-action')
    expect(subActions).toHaveLength(2)
    expect(subActions[0]!.text()).toContain('复制该子任务分享链接')
    expect(subActions[1]!.classes()).toContain('danger')
    expect(subActions[1]!.text()).toContain('删除子任务')
    expect(data.subById(taskId, subId)).toBeDefined()
  })

  it('删除子任务必须二次确认，取消时保留原数据', async () => {
    const subId = data.addSub(taskId, '不能误删的子任务')!
    const confirm = vi.spyOn(window, 'confirm')
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true)
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()
    await w.find('.sub-row').trigger('click')
    const button = w.find('.sub-detail-action.danger')

    await button.trigger('click')
    expect(data.subById(taskId, subId)).toBeDefined()
    await button.trigger('click')

    expect(confirm.mock.calls[0]?.[0]).toContain('执行记录关联也会一并移除')
    expect(data.subById(taskId, subId)).toBeUndefined()
    confirm.mockRestore()
    w.unmount()
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

  it('属性栏里能改开始与截止日期，也能清空', async () => {
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    const inputs = w.findAll('.props .date-input')
    expect(inputs).toHaveLength(2)

    await inputs[0]!.setValue('2026-07-20')
    await inputs[1]!.setValue('2026-08-01')

    const t = data.taskById(taskId)!
    expect(t.start).toBe('2026-07-20')
    expect(t.due).toBe('2026-08-01')
    expect(t.activity[0]!.text).toContain('截止日期')

    await w.findAll('.props .date-input')[0]!.setValue('')

    expect(data.taskById(taskId)!.start).toBe('')
    expect(data.taskById(taskId)!.activity[0]!.text).toContain('清空了开始日期')
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
    const { createAiRuns, fetchTaskAiRuns } = await import('@/api/ai')
    // 没有活动运行时才能再次派整条任务；默认 mock 的 ar1 是正在跑的档案。
    vi.mocked(fetchTaskAiRuns).mockResolvedValueOnce([])
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

    expect(createAiRuns).toHaveBeenCalledWith([taskId], {
      subId: null, engine: 'claude', runnerId: null, accountId: null,
      model: null, reasoningEffort: null, planMode: false,
    })
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

    expect(createAiRuns).toHaveBeenCalledWith([taskId], {
      subId, engine: 'codex', runnerId: null, accountId: null,
      model: null, reasoningEffort: null, planMode: true,
    })
    w.unmount()
  })

  it('Codex 默认 Plan 仍可手动关闭并随派工请求提交', async () => {
    const { createAiRuns, fetchTaskAiRuns } = await import('@/api/ai')
    vi.mocked(createAiRuns).mockClear()
    vi.mocked(fetchTaskAiRuns).mockResolvedValueOnce([])
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    await w.find('.ai-run-btn').trigger('click')
    await flushPromises()
    await w.find('.plan-mode').trigger('click')
    await w.find('.launcher footer .primary').trigger('click')
    await flushPromises()

    expect(createAiRuns).toHaveBeenCalledWith([taskId], expect.objectContaining({
      engine: 'codex', planMode: false,
    }))
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

    expect(w.find('.ai-continue > button').text()).toContain('继续优化')
    w.unmount()
  })

  it('运行中直接显示继续输入入口，并把补充发送到当前线程', async () => {
    const { continueAiRun, fetchTaskAiRuns } = await import('@/api/ai')
    vi.mocked(fetchTaskAiRuns).mockResolvedValueOnce([
      aiRun({ id: 'ar-live', taskId, status: 'running', runnerId: 'rn1', runnerName: '娃哥电脑' }),
    ])
    ui.openTask(taskId)
    const w = mountDrawer()
    await flushPromises()

    expect(w.find('.ai-continue-head').text()).toContain('执行中继续输入')
    expect(w.find('.ai-continue > button').text()).toContain('发送到当前线程')
    w.find('.ai-continue-input').findComponent({ name: 'MarkdownEditor' })
      .vm.$emit('update:modelValue', '先修手机端跳动')
    await w.vm.$nextTick()
    await w.find('.ai-continue > button').trigger('click')
    await flushPromises()

    expect(continueAiRun).toHaveBeenCalledWith('ar-live', '先修手机端跳动')
    w.unmount()
  })

  it('上一条补充等待执行电脑接收时显示投递状态且不能覆盖', async () => {
    const { fetchTaskAiRuns } = await import('@/api/ai')
    vi.mocked(fetchTaskAiRuns).mockResolvedValueOnce([
      aiRun({ id: 'ar-live', taskId, status: 'running', runnerId: 'rn1', runnerName: '娃哥电脑',
        continuationPending: true }),
    ])
    ui.openTask(taskId)
    const w = mountDrawer()
    await flushPromises()

    expect(w.find('.ai-continue').classes()).toContain('pending')
    expect(w.find('.ai-continue-head').text()).toContain('正在等待 娃哥电脑 接收')
    expect(w.find('.ai-continue > button').attributes('disabled')).toBeDefined()
    expect(w.find('.ai-continue > button').text()).toContain('等待接收')
    w.unmount()
  })

  it('AI 未报告任务状态（退出码 3）时显示「结果待确认」而不是「执行失败」', async () => {
    const { fetchTaskAiRuns } = await import('@/api/ai')
    vi.mocked(fetchTaskAiRuns).mockResolvedValueOnce([
      aiRun({
        id: 'ar10', taskId, status: 'failed', exitCode: 3, engine: 'qoder',
        runnerId: 'rn1', sessionId: 'thread-10',
        resultSummary: 'Qoder 已结束会话，但最终答复没有按约定报告任务状态',
      }),
    ])
    ui.openTask(taskId)
    const w = mountDrawer()
    await flushPromises()

    expect(w.find('.ai-run-state').text()).toBe('结果待确认')
    expect(w.find('.ai-run-state').classes()).toContain('state-unconfirmed')
    expect(w.find('.ai-final-result b').text()).toContain('结果待确认（AI 未报告任务状态）')
    // 真正的失败（其它退出码）仍然明确说失败，两种情况不能混。
    vi.mocked(fetchTaskAiRuns).mockResolvedValueOnce([
      aiRun({ id: 'ar11', taskId, status: 'failed', exitCode: 1, resultSummary: '构建失败' }),
    ])
    ui.closeTask()
    await flushPromises()
    ui.openTask(taskId)
    await flushPromises()
    expect(w.find('.ai-run-state').text()).toBe('执行失败')
    expect(w.find('.ai-final-result b').text()).toBe('失败原因')
    w.unmount()
  })

  it('执行记录里的用量展示 输入 / 输出 / 缓存 / 合计 明细，而不只有汇总', async () => {
    const { fetchTaskAiRuns } = await import('@/api/ai')
    vi.mocked(fetchTaskAiRuns).mockResolvedValueOnce([
      aiRun({
        id: 'ar9', taskId, status: 'succeeded', engine: 'codex',
        requestedModel: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'default',
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
    expect(w.find('.ai-run-parameters').text()).toContain('gpt-5.6-sol')
    expect(w.find('.ai-run-parameters').text()).toContain('推理高')
    expect(w.find('.ai-run-parameters').text()).toContain('标准速度（Fast 未开启）')
    expect(usage.exists()).toBe(true)
    expect(usage.text()).toContain('输入 2')
    expect(usage.text()).toContain('输出 4')
    expect(usage.text()).toContain('缓存 19K')
    expect(usage.text()).toContain('合计 19K')
    expect(usage.text()).toContain('$0.09')
    w.unmount()
  })

  it('运行生成持久化报告后提供可分享的新窗口入口', async () => {
    const { fetchTaskAiRuns } = await import('@/api/ai')
    vi.mocked(fetchTaskAiRuns).mockResolvedValueOnce([
      aiRun({
        id: 'ar-report', taskId, status: 'succeeded', resultSummary: '分析已完成',
        reportUrl: '/api/pages/ai-run-ar-report',
      }),
    ])
    ui.openTask(taskId)
    const w = mountDrawer()
    await flushPromises()

    const report = w.find('.ai-run-report')
    expect(report.text()).toContain('持久化报告')
    expect(report.attributes('href')).toBe('/api/pages/ai-run-ar-report')
    expect(report.attributes('target')).toBe('_blank')
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

  /**
   * 手机上点子任务要新开一整页。
   *
   * 原来子任务详情排在正文下面，手机窗口小，点开还得往下滑一段才找得到，
   * 滑到了也认不出看的是哪一条。
   */
  it('手机上点子任务盖成一整页，可直接把当前子任务交给 AI 并能返回', async () => {
    const original = window.matchMedia
    window.matchMedia = ((query: string) => ({
      matches: true, media: query, onchange: null,
      addEventListener: () => {}, removeEventListener: () => {},
      addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia
    try {
      data.addSub(taskId, '归档到 NAS')
      data.addSub(taskId, '扫描身份证')
      ui.openTask(taskId)
      const w = mountDrawer()
      await w.vm.$nextTick()

      await w.findAll('.sub-row')[0]!.trigger('click')

      const page = w.find('.props.sub-page')
      expect(page.exists()).toBe(true)
      expect(page.find('.sub-page-title b').text()).toBe('子任务 1 / 2')
      // 桌面版的「返回任务属性」在手机上换成顶栏返回键，不占正文空间
      expect(w.find('.sub-back').exists()).toBe(false)

      const aiButton = page.find('.sub-page-ai')
      expect(aiButton.text()).toContain('交给 AI 跑')
      await aiButton.trigger('click')
      await flushPromises()
      expect(w.find('.launcher .scope').text()).toContain('扫描身份证')
      await w.find('.launcher .close').trigger('click')

      await page.find('.sub-page-back').trigger('click')
      expect(w.find('.props.sub-page').exists()).toBe(false)
      w.unmount()
    } finally {
      window.matchMedia = original
    }
  })

  it('属性栏能改任务分组——手机上拖不动，只能从这里挪', async () => {
    ui.openTask(taskId)
    await data.setTaskGroups(data.tasks[0]!.project, [{ id: 'g1', name: '证件' }, { id: 'g2', name: '装修' }])
    const w = mountDrawer()
    await w.vm.$nextTick()

    const groupRow = w.findAll('.prop-row').find((r) => r.text().startsWith('分组'))!
    await groupRow.find('.owner-btn').trigger('click')
    await groupRow.findAll('.pmenu-item')[1]!.trigger('click')

    expect(data.taskById(taskId)!.groupId).toBe('g2')
  })
})
