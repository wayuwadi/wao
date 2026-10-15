import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import TaskDrawer from '@/components/task/TaskDrawer.vue'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
import { useToastHost } from '@/composables/toast'
import type { AiRun } from '@/types'

enableAutoUnmount(afterEach)
// 预先完成重型编辑器的模块转换，DOM 就绪断言不承担构建耗时。
beforeAll(async () => { await import('@/components/ui/MarkdownEditor.vue') }, 30_000)

vi.mock('@/utils/clipboard', () => ({
  copyToClipboard: vi.fn<() => Promise<boolean>>().mockResolvedValue(true),
}))

vi.mock('@/api/board', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/board')>()),
  notifyTaskManually: vi.fn<() => Promise<boolean>>().mockResolvedValue(true),
}))

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
    workspaceDir: 'wao', defaultEngine: 'codex', preferredRunnerId: null, maxParallelRuns: 2, failureRetryCount: 2,
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

// AI Run 入口已隐藏但代码保留；本文件覆盖保留下来的实现，所以在这里打开开关。
vi.mock('@/config/features', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/config/features')>()),
  AI_RUN_ENABLED: true,
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
 * 所以这里等待实际 DOM 就绪，留出并行测试下动态模块转换的时间；就绪后立即返回。
 */
async function waitFor(w: ReturnType<typeof mountDrawer>, selector: string) {
  await vi.waitFor(async () => {
    await flushPromises()
    expect(w.find(selector).exists()).toBe(true)
  }, { timeout: 4_000, interval: 25 })
  return true
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
    useToastHost().items.splice(0)
  })

  it('没有打开的任务时不渲染抽屉', () => {
    expect(mountDrawer().find('.drawer').exists()).toBe(false)
  })

  it('长描述下概览直接显示子任务，切换描述不重建编辑器', async () => {
    data.taskById(taskId)!.desc = '# 真实草稿\n\n长内容'.repeat(200)
    data.addSub(taskId, '需要直接看到的下一步')
    ui.openTask(taskId)
    const w = mountDrawer()
    await waitFor(w, '.md-body')
    expect(w.get('.inline-subs').isVisible()).toBe(true)
    expect(w.get('[data-section="subtasks"]').text()).toBe('子任务 1')
    const editor = w.get('.md-body').element
    const input = w.get('.inline-subs form input')
    await input.setValue('没有回车的新增草稿')
    await w.get('[data-section="description"]').trigger('click')
    expect(w.get('.md-body').isVisible()).toBe(true)
    await w.get('[data-section="subtasks"]').trigger('click')
    expect(w.get('.md-body').element).toBe(editor)
    expect((w.get('.inline-subs form input').element as HTMLInputElement).value).toBe('没有回车的新增草稿')
    expect(data.taskById(taskId)!.desc).toContain('真实草稿')
  })

  it('从下级返回保留父列表新增输入与分区，数量只统计当前下级', async () => {
    const subId = data.addSub(taskId, '父子任务')!
    data.addSub(taskId, '孙子任务', subId)
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()
    await w.get('[data-section="subtasks"]').trigger('click')
    await w.get('.inline-subs form input').setValue('父列表未提交输入')
    ui.openTask(taskId, subId)
    await flushPromises()
    expect(w.get('[data-section="subtasks"]').text()).toBe('子任务 1')
    expect((w.get('.inline-subs form input').element as HTMLInputElement).value).toBe('')
    await w.findAll('.crumb-item')[0]!.trigger('click')
    await flushPromises()
    expect(w.get('[data-section="subtasks"]').attributes('aria-selected')).toBe('true')
    expect(w.get('[data-section="subtasks"]').text()).toBe('子任务 2')
    expect((w.get('.inline-subs form input').element as HTMLInputElement).value).toBe('父列表未提交输入')
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

  it('属性栏与子任务一样显示井号编号，点击后复制任务稳定标识', async () => {
    const { copyToClipboard } = await import('@/utils/clipboard')
    data.taskById(taskId)!.number = 1066
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    const jobId = w.get('.job-id-copy')
    expect(jobId.text()).toBe('#1066')
    expect(jobId.attributes('title')).toContain(taskId)
    await jobId.trigger('click')
    await flushPromises()

    expect(copyToClipboard).toHaveBeenCalledWith(taskId)
    expect(useToastHost().items.some((item) => item.text === `JOBID ${taskId} 已复制`)).toBe(true)
    w.unmount()
  })

  it('人工通知按钮只在后端确认真实发送后提示成功', async () => {
    const { notifyTaskManually } = await import('@/api/board')
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    await w.get('[title="通知到 WAO"]').trigger('click')
    await flushPromises()

    expect(notifyTaskManually).toHaveBeenCalledWith(taskId)
    expect(useToastHost().items.some((item) => item.text === '任务已通知到 WAO')).toBe(true)
  })

  it('从真实成员目录增加和移除任务参与人', async () => {
    const session = useSessionStore()
    session.members = [
      { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' },
      { id: 'wd', name: '娃弟', initial: '弟', color: '#2C8A5B', role: '成员' },
    ]
    data.taskById(taskId)!.parts = []
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    const participantRow = w.findAll('.prop-row').find((row) => row.text().startsWith('参与人'))!
    expect(participantRow.text()).toContain('未添加')
    await participantRow.get('.add-part').trigger('click')
    await participantRow.findAll('.participant-menu .pmenu-item')[1]!.trigger('click')

    expect(data.taskById(taskId)!.parts).toEqual(['wd'])
    expect(participantRow.text()).toContain('弟')

    await participantRow.findAll('.participant-menu .pmenu-item')[1]!.trigger('click')
    expect(data.taskById(taskId)!.parts).toEqual([])
    expect(participantRow.text()).toContain('未添加')
  })

  it('JOBID 复制失败时保留可手动选择的真实标识', async () => {
    const { copyToClipboard } = await import('@/utils/clipboard')
    vi.mocked(copyToClipboard).mockResolvedValueOnce(false)
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    await w.get('.job-id-copy').trigger('click')
    await flushPromises()

    expect(useToastHost().items.some((item) => item.text.includes('复制失败') && item.text.includes(taskId))).toBe(true)
    w.unmount()
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

  it('待验证与已完成任务都不再展示升级项目入口', async () => {
    const current = data.taskById(taskId)!
    current.status = '待验证'
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    expect(w.find('.promotion-card').exists()).toBe(false)
    expect(w.text()).not.toContain('升级为项目')
    current.status = '已完成'
    await w.vm.$nextTick()

    expect(w.find('.promotion-card').exists()).toBe(false)
    expect(w.text()).not.toContain('继续长期迭代')
    w.unmount()
  })

  it('只补齐缺失的验收记录字段且不写伪造结果', async () => {
    const current = data.taskById(taskId)!
    current.custom = [
      { name: '成功条件', value: '登录成员可以筛选' },
      { name: '通知结果', value: '' },
      { name: '原有属性', value: '保留' },
    ]
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    const button = w.get('.acceptance-template-btn')
    expect(button.text()).toContain('补齐验收记录')
    await button.trigger('click')

    expect(current.custom.map((item) => item.name)).toEqual([
      '成功条件', '通知结果', '原有属性', '验收证据', '端到端耗时', '异常与恢复',
    ])
    expect(current.custom.slice(-3).map((item) => item.value)).toEqual(['', '', ''])
    expect(w.get('.acceptance-template-btn').attributes('disabled')).toBeDefined()
    expect(useToastHost().items.some((item) => item.text.includes('已补齐 3 个验收字段'))).toBe(true)
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
    await w.findAll('.text-btn').find((button) => button.text().includes('关联知识、任务、文件或 HTML'))!.trigger('click')

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

  it('关联选择器使用真实知识、任务、文件和人工 HTML，并在详情中明确解除', async () => {
    const current = data.taskById(taskId)!
    const doc = Object.values(data.docs).find((item) => !current.docs.includes(item.id))!
    const linkedTask = data.tasks.find((item) => item.id !== taskId && !current.linkTasks.some((link) => link.id === item.id))!
    data.files.unshift({
      id: 'file-real-1', name: '真实验收记录.pdf', size: '12 KB', icon: 'ri-file-pdf-2-line',
      time: '刚刚', by: '娃哥', link: '/api/files/file-real-1/raw',
    })
    data.hostedPages = [
      {
        id: 'page-manual-1', slug: 'manual-check', title: '人工验收页面', fileName: 'check.html',
        sourceType: 'MANUAL_UPLOAD', hasIcon: false, updatedAt: '9月3日 10:00',
        url: '/api/pages/manual-check', projectId: 'p1',
      },
      {
        id: 'run-report-ar1', slug: 'ai-run-ar1', title: 'AI Run 报告', fileName: 'report.html',
        sourceType: 'AI_RUN_REPORT', hasIcon: false, updatedAt: '9月3日 09:00',
        url: '/api/pages/ai-run-ar1', projectId: 'p1',
      },
    ]
    data.hostedPageLoadState = 'loaded'
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    await w.findAll('.text-btn').find((button) => button.text().includes('关联知识、任务、文件或 HTML'))!.trigger('click')
    expect(w.find('.relation-picker').exists()).toBe(true)

    const clickCandidate = async (text: string) => {
      const row = w.findAll('.relation-row').find((item) => item.text().includes(text))!
      await row.trigger('click')
      await w.vm.$nextTick()
    }
    await clickCandidate(doc.title)
    await clickCandidate(linkedTask.title)
    await clickCandidate('真实验收记录.pdf')
    const htmlTab = w.findAll('.relation-picker nav button').find((button) => button.text().startsWith('HTML'))!
    await htmlTab.trigger('click')
    expect(w.findAll('.relation-row').map((row) => row.text()).join('')).not.toContain('AI Run 报告')
    await clickCandidate('人工验收页面')

    expect(current.docs).toContain(doc.id)
    expect(current.linkTasks.map((item) => item.id)).toContain(linkedTask.id)
    expect(current.files.map((item) => item.id)).toContain('file-real-1')
    expect(current.hostedPages).toEqual(['page-manual-1'])

    await w.get('.relation-picker footer button').trigger('click')
    await w.get('[aria-label="取消关联文件 真实验收记录.pdf"]').trigger('click')
    await w.get('[aria-label="取消关联 HTML 人工验收页面"]').trigger('click')
    expect(current.files.map((item) => item.id)).not.toContain('file-real-1')
    expect(current.hostedPages).toEqual([])
    w.unmount()
  })

  it('人工 HTML 读取失败时保留重试入口且不改动既有关联', async () => {
    const current = data.taskById(taskId)!
    current.hostedPages = ['page-existing']
    data.hostedPages = []
    data.hostedPageLoadState = 'error'
    const refresh = vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(false)
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    await w.findAll('.text-btn').find((button) => button.text().includes('关联知识、任务、文件或 HTML'))!.trigger('click')
    await w.findAll('.relation-picker nav button').find((button) => button.text().startsWith('HTML'))!.trigger('click')

    expect(w.get('.relation-picker .empty').text()).toContain('HTML 读取失败')
    expect(current.hostedPages).toEqual(['page-existing'])
    await w.get('.relation-picker .retry').trigger('click')
    expect(refresh).toHaveBeenCalledTimes(2)
    expect(current.hostedPages).toEqual(['page-existing'])
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
    expect(w.find('.comment-input .send').attributes('aria-label')).toBe('发送中')
    // 评论已挪到右侧属性栏
    expect(w.find('.props .comment-input').exists()).toBe(true)
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
    // 首次打开和下一次空闲轮询都没有记录；其后的测试仍使用默认 mock 返回值。
    fetchRuns.mockResolvedValueOnce([]).mockResolvedValueOnce([])
    vi.useFakeTimers()
    try {
      ui.openTask(taskId)
      const w = mountDrawer()
      await flushPromises()
      expect(w.find('.ai-archive').exists()).toBe(false)

      await vi.advanceTimersByTimeAsync(15_000)
      await flushPromises()
      expect(w.find('.ai-archive').exists()).toBe(false)
      w.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('活动运行的慢请求结束后才安排下一轮，避免轮询重叠', async () => {
    const { fetchTaskAiRuns } = await import('@/api/ai')
    const fetchRuns = vi.mocked(fetchTaskAiRuns)
    fetchRuns.mockClear()
    let finishSecondRequest!: (runs: AiRun[]) => void
    fetchRuns
      .mockResolvedValueOnce([aiRun({ id: 'ar-live', taskId, status: 'running' })])
      .mockImplementationOnce(() => new Promise((resolve) => { finishSecondRequest = resolve }))
      .mockResolvedValueOnce([aiRun({ id: 'ar-live', taskId, status: 'running' })])
    vi.useFakeTimers()
    try {
      ui.openTask(taskId)
      const w = mountDrawer()
      await flushPromises()
      expect(fetchRuns).toHaveBeenCalledTimes(1)

      await vi.advanceTimersByTimeAsync(5_000)
      expect(fetchRuns).toHaveBeenCalledTimes(2)
      await vi.advanceTimersByTimeAsync(15_000)
      expect(fetchRuns).toHaveBeenCalledTimes(2)

      finishSecondRequest([aiRun({ id: 'ar-live', taskId, status: 'running' })])
      await flushPromises()
      await vi.advanceTimersByTimeAsync(5_000)
      expect(fetchRuns).toHaveBeenCalledTimes(3)
      w.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('页面隐藏时暂停空闲轮询，重新可见后立即同步', async () => {
    const { fetchTaskAiRuns } = await import('@/api/ai')
    const fetchRuns = vi.mocked(fetchTaskAiRuns)
    fetchRuns.mockClear()
    fetchRuns.mockResolvedValueOnce([]).mockResolvedValueOnce([])
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    vi.useFakeTimers()
    try {
      ui.openTask(taskId)
      const w = mountDrawer()
      await flushPromises()
      expect(fetchRuns).toHaveBeenCalledTimes(1)

      hidden.mockReturnValue(true)
      document.dispatchEvent(new Event('visibilitychange'))
      await vi.advanceTimersByTimeAsync(60_000)
      expect(fetchRuns).toHaveBeenCalledTimes(1)

      hidden.mockReturnValue(false)
      document.dispatchEvent(new Event('visibilitychange'))
      await flushPromises()
      expect(fetchRuns).toHaveBeenCalledTimes(2)
      w.unmount()
    } finally {
      hidden.mockRestore()
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

  it('顶部删除入口确认当前对象，失败可重试且不关闭详情，取消不删', async () => {
    ui.openTask(taskId)
    const remove = vi.spyOn(data, 'deleteTask').mockResolvedValueOnce(false).mockResolvedValue(true)
    const w = mountDrawer(); await flushPromises()
    await w.get('[aria-label="删除当前任务"]').trigger('click')
    expect(document.querySelector('.settings-dialog-shell')).not.toBeNull()
    expect(remove).not.toHaveBeenCalled()
    document.querySelector<HTMLButtonElement>('.settings-dialog-shell .wao-button--secondary')!.click(); await flushPromises()
    expect(remove).not.toHaveBeenCalled()
    await w.get('[aria-label="删除当前任务"]').trigger('click')
    document.querySelector<HTMLButtonElement>('.settings-dialog-shell .wao-button--danger')!.click(); await flushPromises()
    expect(remove).toHaveBeenCalledWith(taskId)
    expect(document.querySelector('.settings-dialog-shell')!.textContent).toContain('删除失败')
    expect(ui.openTaskId).toBe(taskId)
    document.querySelector<HTMLButtonElement>('.settings-dialog-shell .wao-button--danger')!.click(); await flushPromises()
    expect(ui.openTaskId).toBeNull()
    remove.mockRestore()
  })
  it('顶部子任务删除只移除确认的子任务并保留父任务和其他子任务', async () => {
    const first = data.addSub(taskId, '待删除子任务')!
    const second = data.addSub(taskId, '保留子任务')!
    ui.openTask(taskId, first)
    const removeTask = vi.spyOn(data, 'deleteTask')
    const w = mountDrawer(); await flushPromises()
    await w.get('[aria-label="删除当前子任务"]').trigger('click')
    expect(document.querySelector('.settings-dialog-shell')!.textContent).toContain('待删除子任务')
    document.querySelector<HTMLButtonElement>('.settings-dialog-shell .wao-button--danger')!.click(); await flushPromises()
    expect(data.subById(taskId, first)).toBeUndefined()
    expect(data.subById(taskId, second)?.text).toBe('保留子任务')
    expect(data.taskById(taskId)).toBeDefined()
    expect(removeTask).not.toHaveBeenCalled()
    expect(ui.openTaskId).toBe(taskId)
    expect(ui.openTaskSubId).toBeNull()
    removeTask.mockRestore()
  })
  it('类型选择器使用带图标的菜单，保存到原任务字段', async () => {
    ui.openTask(taskId)
    const w = mountDrawer(); await flushPromises()
    await w.get('[aria-label="工作项类型"]').trigger('click')
    const options = [...document.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]')]
    expect(options.find(button => button.getAttribute('aria-checked') === 'true')?.textContent).toBe('任务')
    expect(document.querySelector('[role="menuitemradio"] .ri-check-line')).toBeNull()
    expect(options.every(button => button.querySelector('.work-item-type-label'))).toBe(true)
    const requirement = options.find(button => button.textContent?.includes('需求'))!
    requirement.click(); await flushPromises()
    expect(data.taskById(taskId)!.workItemType).toBe('requirement')
    expect(w.get('[aria-label="工作项类型"]').text()).toContain('需求')
  })
  it('默认展示概览并展开属性，点击子任务后在弹框主区切成子任务详情', async () => {
    const subId = data.addSub(taskId, '扫描身份证')!
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    expect(w.get('.detail-properties').isVisible()).toBe(true)
    await waitFor(w, '.overview-description .md-body')
    expect(w.get('.overview-description .md-body').isVisible()).toBe(true)
    // 只看右侧栏——「自定义属性」这几个字也出现在种子数据的评论里，全局断言会误判
    expect(w.find('.props').text()).toContain('自定义属性')
    expect(w.find('.type-line').text()).toContain('任务')
    expect(w.findAll('.crumb-item')).toHaveLength(1)

    await w.find('.inline-sub .sub-open').trigger('click')

    expect(w.find('.type-line').text()).toContain('子任务')
    expect((w.find('.main .title-input').element as HTMLTextAreaElement).value).toBe('扫描身份证')
    expect(w.findAll('.crumb-item')).toHaveLength(2)
    expect(w.find('.props').text()).toContain('子任务属性')
    expect(w.find('.props').text()).not.toContain('自定义属性')
    const subActions = w.findAll('.sub-detail-actions .sub-detail-action')
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
    await w.find('.inline-sub .sub-open').trigger('click')
    const button = w.find('.sub-detail-action.danger')

    await button.trigger('click')
    expect(data.subById(taskId, subId)).toBeDefined()
    await button.trigger('click')

    expect(confirm.mock.calls[0]?.[0]).toContain('执行记录关联也会一并移除')
    expect(data.subById(taskId, subId)).toBeUndefined()
    // 删完回到主任务
    expect(w.find('.type-line').text()).not.toContain('子任务')
    confirm.mockRestore()
    w.unmount()
  })

  it('子任务详情里改名会写回 store', async () => {
    const subId = data.addSub(taskId, '旧名字')!
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()
    await w.find('.inline-sub .sub-open').trigger('click')

    const input = w.find('.main .title-input')
    ;(input.element as HTMLTextAreaElement).value = '新名字'
    await input.trigger('input')

    expect(data.subById(taskId, subId)?.text).toBe('新名字')
  })


  it('点子任务的状态胶囊弹出三态菜单，不展开详情', async () => {
    const subId = data.addSub(taskId, '扫描身份证')!
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    await w.find('.inline-sub .sub-state').trigger('click')
    const pick = (label: string) => [...document.querySelectorAll<HTMLButtonElement>('.cell-menu button')]
      .find((node) => node.textContent?.includes(label))!
    pick('进行中').click()
    await flushPromises()
    expect(data.subById(taskId, subId)).toMatchObject({ done: false, status: '进行中' })
    expect(w.find('.inline-sub .sub-state').text()).toBe('进行中')

    await w.find('.inline-sub .sub-state').trigger('click')
    pick('已完成').click()
    await flushPromises()
    expect(data.subById(taskId, subId)?.done).toBe(true)
    expect(w.find('.inline-sub .sub-state').text()).toBe('已完成')
    expect(w.find('.props').text()).toContain('自定义属性')
  })

  it('子任务在属性栏里有自己的评论和关联文档', async () => {
    const subId = data.addSub(taskId, '扫描身份证')!
    const docId = Object.keys(data.docs)[0]
    ui.openTask(taskId, subId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    const props = w.get('.props')
    expect(props.text()).toContain('关联')
    expect(props.text()).toContain('评论')
    await props.get('.comment-input input').setValue('子任务上的讨论')
    await props.get('.comment-input .send').trigger('click')
    expect(data.subById(taskId, subId)?.comments?.map((item) => item.text)).toEqual(['子任务上的讨论'])
    expect(data.taskById(taskId)!.comments.some((item) => item.text === '子任务上的讨论')).toBe(false)
    expect(w.get('.props').text()).toContain('子任务上的讨论')

    expect(docId).toBeTruthy()
    data.updateSub(taskId, subId, { docs: [docId!] })
    await w.vm.$nextTick()
    expect(w.findAll('.props .side-relations .attach')).toHaveLength(1)
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

  it('已完成子任务原位划线显示，开关可一键隐藏', async () => {
    data.addSub(taskId, '未完成的')
    const doneId = data.addSub(taskId, '已完成的')!
    data.updateSub(taskId, doneId, { done: true })
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    expect(w.findAll('.inline-sub')).toHaveLength(2)
    expect(w.find('.list-count').text()).toBe('1/2')

    const toggle = w.find('.done-switch')
    expect(toggle.attributes('aria-checked')).toBe('true')
    await toggle.trigger('click')

    expect(w.findAll('.inline-sub')).toHaveLength(1)
    expect(w.find('.inline-sub').text()).toContain('未完成的')
    expect(w.find('.done-switch').attributes('aria-checked')).toBe('false')
  })


  it('属性栏里能改开始与截止日期，也能清空', async () => {
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    const inputs = w.findAll('.props .date-input')
    expect(inputs).toHaveLength(2)

    await w.get('.props .date-input[title^="开始"]').setValue('2026-07-20')
    await w.get('.props .date-input.due').setValue('2026-08-01')

    const t = data.taskById(taskId)!
    expect(t.start).toBe('2026-07-20')
    expect(t.due).toBe('2026-08-01')
    expect(t.activity[0]!.text).toContain('截止日期')

    await w.get('.props .date-input[title^="开始"]').setValue('')

    expect(data.taskById(taskId)!.start).toBe('')
    expect(data.taskById(taskId)!.activity[0]!.text).toContain('清空了开始日期')
  })

  it('编辑时段和清空安排保持独立截止日期', async () => {
    const t = data.taskById(taskId)!
    t.due = '2026-10-09'; t.startAt = null; t.endAt = null
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.get('[aria-label="执行时段开始"]').setValue('2026-10-07T23:00')
    await w.get('[aria-label="执行时段结束"]').setValue('2026-10-08T02:00')
    expect(t.due).toBe('2026-10-09')
    expect(t.start).toBe('2026-10-07')
    await w.findAll('button').find(b => b.text() === '清除执行安排')!.trigger('click')
    expect([t.start, t.startAt, t.endAt, t.due]).toEqual(['', null, null, '2026-10-09'])
  })

  it('没有已完成子任务时不显示隐藏开关', async () => {
    data.addSub(taskId, '只有这一条')
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    expect(w.find('.done-switch').exists()).toBe(false)
  })


  it('面包屑按层级展示子任务，点主任务即可返回', async () => {
    const parentId = data.addSub(taskId, '父子任务')!
    const childId = data.addSub(taskId, '孙子任务', parentId)!
    ui.openTask(taskId, childId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    const crumbs = w.findAll('.crumb-item')
    expect(crumbs).toHaveLength(3)
    expect(crumbs[2]!.classes()).toContain('current')
    expect((w.find('.main .title-input').element as HTMLTextAreaElement).value).toBe('孙子任务')

    await crumbs[1]!.trigger('click')
    expect((w.find('.main .title-input').element as HTMLTextAreaElement).value).toBe('父子任务')
    // 父子任务的详情里列出自己的下级
    expect(w.find('.main .inline-subs').text()).toContain('孙子任务')

    await w.findAll('.crumb-item')[0]!.trigger('click')
    expect(w.find('.props').text()).toContain('自定义属性')
  })


  it('子任务标题用可撑高的 textarea，长标题不会被截断', async () => {
    data.addSub(taskId, '一个特别长的子任务标题'.repeat(6))
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()
    await w.find('.inline-sub .sub-open').trigger('click')

    const title = w.find('.main .title-input')
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
      model: null, reasoningEffort: null, planMode: false, goalMode: false,
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

    await w.find('.inline-sub .sub-open').trigger('click')
    await w.findAll('.props .sub-detail-action').find((b) => b.text().includes('交给 AI 跑'))!.trigger('click')
    await flushPromises()

    expect(w.find('.launcher .scope').text()).toContain('子任务也能单独跑')
    expect(w.find('.launcher .scope').text()).toContain('带主任务与已完成子任务历史')

    await w.find('.launcher footer .primary').trigger('click')
    await flushPromises()

    expect(createAiRuns).toHaveBeenCalledWith([taskId], {
      subId, engine: 'codex', runnerId: null, accountId: null,
      model: null, reasoningEffort: null, planMode: true, goalMode: false,
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
    await w.find('.direct-mode').trigger('click')
    await w.find('.launcher footer .primary').trigger('click')
    await flushPromises()

    expect(createAiRuns).toHaveBeenCalledWith([taskId], expect.objectContaining({
      engine: 'codex', planMode: false, goalMode: false,
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

    await w.find('.inline-sub .sub-open').trigger('click')
    const actions = w.findAll('.props .sub-detail-action')
    expect(actions.find((b) => b.text().includes('已派给 AI'))!.attributes('disabled')).toBeDefined()
    expect(actions.some((b) => b.text().includes('交给 AI 跑'))).toBe(false)
    w.unmount()
  })

  it('子任务详情展示自己的 AI 运行记录，不混入主任务和其它子任务', async () => {
    const { fetchTaskAiRuns } = await import('@/api/ai')
    const firstSubId = data.addSub(taskId, '第一条子任务')!
    const secondSubId = data.addSub(taskId, '第二条子任务')!
    vi.mocked(fetchTaskAiRuns).mockResolvedValueOnce([
      aiRun({ id: 'ar-first', taskId, subId: firstSubId, subTitle: '第一条子任务', latestProgress: '正在实现第一条' }),
      aiRun({ id: 'ar-second', taskId, subId: secondSubId, subTitle: '第二条子任务', latestProgress: '正在实现第二条' }),
      aiRun({ id: 'ar-task', taskId, latestProgress: '正在实现整条任务' }),
    ])
    ui.openTask(taskId)
    const w = mountDrawer()
    await flushPromises()

    const firstRow = w.findAll('.inline-sub').find((row) => row.text().includes('第一条子任务'))!
    await firstRow.find('.sub-open').trigger('click')

    const archive = w.find('.sub-ai-archive')
    expect(archive.exists()).toBe(true)
    expect(archive.findAll('.ai-run-entry')).toHaveLength(1)
    expect(archive.text()).toContain('正在实现第一条')
    expect(archive.text()).not.toContain('正在实现第二条')
    expect(archive.text()).not.toContain('正在实现整条任务')
    w.unmount()
  })

  it('子任务派工成功后立即展示接口返回的真实运行记录', async () => {
    const { createAiRuns, fetchTaskAiRuns } = await import('@/api/ai')
    const subId = data.addSub(taskId, '立即看到运行')!
    const created = aiRun({
      id: 'ar-created', taskId, subId, subTitle: '立即看到运行', status: 'queued', latestProgress: '等待执行电脑领取',
    })
    vi.mocked(fetchTaskAiRuns).mockResolvedValueOnce([])
      .mockImplementationOnce(() => new Promise(() => {}))
    vi.mocked(createAiRuns).mockResolvedValueOnce([created])
    ui.openTask(taskId)
    const w = mountDrawer()
    await flushPromises()

    await w.find('.inline-sub .sub-open').trigger('click')
    await w.findAll('.props .sub-detail-action').find((b) => b.text().includes('交给 AI 跑'))!.trigger('click')
    await flushPromises()
    await w.find('.launcher footer .primary').trigger('click')
    await flushPromises()

    const archive = w.find('.sub-ai-archive')
    expect(archive.text()).toContain('等待执行电脑领取')
    expect(archive.find('.ai-run-state').text()).toBe('等待领取')
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

  it('执行记录区分领取至完成总耗时与 Runner 实测 AI 执行耗时', async () => {
    const { fetchTaskAiRuns } = await import('@/api/ai')
    vi.mocked(fetchTaskAiRuns).mockResolvedValueOnce([
      aiRun({
        id: 'ar-duration', taskId, status: 'succeeded',
        claimedAt: '2026-08-31T09:00:00+08:00',
        startedAt: '2026-08-31T09:00:00+08:00',
        finishedAt: '2026-08-31T10:02:03+08:00',
        aiExecutionDurationMs: 12 * 60_000 + 34_000,
      }),
    ])
    ui.openTask(taskId)
    const w = mountDrawer()
    await flushPromises()

    const timing = w.find('.ai-run-timing')
    expect(timing.text()).toContain('总耗时 1 小时 2 分')
    expect(timing.text()).toContain('AI 实际执行 12 分 34 秒')
    w.unmount()
  })

  it('旧运行没有 Runner 实测值时不伪造 AI 执行耗时', async () => {
    const { fetchTaskAiRuns } = await import('@/api/ai')
    vi.mocked(fetchTaskAiRuns).mockResolvedValueOnce([
      aiRun({ id: 'ar-old-duration', taskId, status: 'succeeded',
        startedAt: '2026-08-31T09:00:00+08:00', finishedAt: '2026-08-31T09:03:00+08:00' }),
    ])
    ui.openTask(taskId)
    const w = mountDrawer()
    await flushPromises()

    expect(w.find('.ai-run-timing').text()).toContain('AI 实际执行 旧记录未记录')
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
    expect(report.text()).toContain('HTML 报告')
    expect(report.text()).toContain('公开 AI Plan')
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
    await w.find('.inline-sub .sub-open').trigger('click')

    await w.find('.share-wrap .icon-btn').trigger('click')

    expect(w.find('.share-menu').text()).toContain('当前子任务')
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
