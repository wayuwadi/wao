import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import DevelopmentPanel from '@/components/development/DevelopmentPanel.vue'
import * as api from '@/api/development'
import { useSessionStore } from '@/stores/session'
import { developmentOverview, developmentRun } from './fixtures/development'
import { developmentStatusLabel } from '@/utils/developmentStatus'

vi.mock('@/api/development', () => ({
  fetchDevelopmentOverview: vi.fn(),
  fetchDevelopmentRun: vi.fn(),
  saveDevelopmentPolicy: vi.fn(),
  createDevelopmentRun: vi.fn(),
  replyDevelopmentDecision: vi.fn(),
  retryDevelopmentRun: vi.fn(),
}))
const wrappers: VueWrapper[] = []
function account(id: string) {
  return {
    accountId: id,
    member: { id, name: id, initial: id, role: 'admin', color: '' },
    identities: [],
    createdAt: '',
  }
}
function view(runId?: string) {
  const pinia = createPinia()
  const session = useSessionStore(pinia)
  session.account = account('member-a')
  const wrapper = mount(DevelopmentPanel, {
    props: { runId },
    global: {
      plugins: [pinia],
      stubs: {
        DigitalAssetImage: {
          props: ['assetId', 'alt'],
          template: '<span class="private-asset" :data-asset-id="assetId">{{alt}}</span>',
        },
      },
    },
  })
  wrappers.push(wrapper)
  return { wrapper, session }
}
function button(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((item) => item.text().includes(text))!
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  localStorage.clear()
  vi.mocked(api.fetchDevelopmentOverview).mockResolvedValue(developmentOverview())
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.useRealTimers()
})

describe('自主建设真实记录', () => {
  it('链接指向概览外旧记录时单独读取，不把第一条冒充目标', async () => {
    vi.mocked(api.fetchDevelopmentOverview).mockResolvedValue(
      developmentOverview({ runs: [developmentRun({ id: 'another-run', title: '其他记录' })] }),
    )
    let resolve!: (value: api.DevelopmentRun) => void
    vi.mocked(api.fetchDevelopmentRun).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    const { wrapper } = view('older-run')
    await flushPromises()
    expect(api.fetchDevelopmentRun).toHaveBeenCalledWith('older-run')
    expect(wrapper.text()).toContain('正在读取链接指定的建设记录')
    expect(wrapper.find('.run-detail').exists()).toBe(false)
    resolve(developmentRun({ id: 'older-run', title: '链接指定的旧记录' }))
    await flushPromises()
    expect(wrapper.get('.run-detail h3').text()).toBe('链接指定的旧记录')
    expect(wrapper.findAll('.run-item')).toHaveLength(2)
  })
  it('指定记录拒绝读取时显示错误并允许重试，不提供另一项的决策', async () => {
    vi.mocked(api.fetchDevelopmentOverview).mockResolvedValue(
      developmentOverview({ runs: [developmentRun({ id: 'another-run' })] }),
    )
    vi.mocked(api.fetchDevelopmentRun).mockRejectedValueOnce(new Error('无权读取此记录'))
    const { wrapper } = view('private-run')
    await flushPromises()
    expect(wrapper.get('.detail-feedback').text()).toContain('无权读取此记录')
    expect(wrapper.find('.decision-section').exists()).toBe(false)
    vi.mocked(api.fetchDevelopmentRun).mockResolvedValue(
      developmentRun({ id: 'private-run', title: '已恢复读取的记录' }),
    )
    await button(wrapper, '重新读取此记录').trigger('click')
    await flushPromises()
    expect(wrapper.get('.run-detail h3').text()).toBe('已恢复读取的记录')
  })
  it('仅使用同agent实际绑定的数字图片，未绑定协作者仍用装饰伙伴', async () => {
    const run = developmentRun()
    run.agents[0]!.identity = {
      subjectId: 'subject-a',
      versionId: 'version-confirmed',
      assetId: 'private-asset-a',
      name: '已确认数字形象',
    }
    vi.mocked(api.fetchDevelopmentOverview).mockResolvedValue(developmentOverview({ runs: [run] }))
    const { wrapper } = view()
    await flushPromises()
    expect(wrapper.get('.private-asset').attributes('data-asset-id')).toBe('private-asset-a')
    expect(wrapper.findAll('.companion-image')).toHaveLength(1)
    expect(wrapper.text()).toContain('形象：已确认数字形象')
    expect(wrapper.text()).toContain('分析实际体验')
  })
  it('读取中与读取失败不显示假数据；重新读取后才展示真实空状态', async () => {
    let fail!: (error: Error) => void
    vi.mocked(api.fetchDevelopmentOverview).mockImplementationOnce(
      () =>
        new Promise((_resolve, reject) => {
          fail = reject
        }),
    )
    const { wrapper } = view()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('正在读取建设计划与真实运行记录')
    expect(wrapper.text()).not.toContain('暂无建设记录')
    fail(new Error('概览暂时不可用'))
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('概览暂时不可用')
    expect(wrapper.text()).not.toContain('暂无建设记录')
    await button(wrapper, '重试读取').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('暂无建设记录')
    expect(wrapper.text()).toContain('执行电脑离线')
    expect(wrapper.find('.run-item').exists()).toBe(false)
  })
  it('20条实际记录可筛选、选择和深链定位，阶段与结论分开', async () => {
    const runs = Array.from({ length: 20 }, (_value, index) =>
      developmentRun({
        id: `run-${index}`,
        title: `真实功能 ${index}`,
        status: index < 10 ? 'IMPLEMENTING' : index === 10 ? 'RELEASED' : 'WAITING_DECISION',
      }),
    )
    vi.mocked(api.fetchDevelopmentOverview).mockResolvedValue(developmentOverview({ runs }))
    const { wrapper } = view('run-19')
    await flushPromises()
    expect(wrapper.findAll('.run-item')).toHaveLength(20)
    expect(wrapper.get('.run-detail h3').text()).toBe('真实功能 19')
    expect(wrapper.text()).toContain('当前阶段：比较方案')
    expect(wrapper.text()).toContain('实际结论：待确认')
    await button(wrapper, '进行中').trigger('click')
    expect(wrapper.findAll('.run-item')).toHaveLength(10)
    await button(wrapper, '待验收').trigger('click')
    expect(wrapper.findAll('.run-item')).toHaveLength(1)
    expect(wrapper.text()).toContain('实际结论：已上线待验收')
    expect(wrapper.text()).not.toContain('已完成')
  })
  it('证据只展示真实字段，危险URL不成为可点击链接', async () => {
    vi.mocked(api.fetchDevelopmentOverview).mockResolvedValue(
      developmentOverview({
        runs: [
          developmentRun({
            status: 'MERGED',
            evidence: {
              validation: ['本地草稿恢复验证通过'],
              prUrl: 'https://github.com/example/wao/pull/1',
              mergeSha: 'abc123',
              publicVersion: null,
              releaseVerified: false,
              artifacts: [
                { label: '非法链接', url: 'javascript:alert(1)' },
                { label: '实际报告', url: 'https://example.com/report' },
              ],
            },
          }),
        ],
      }),
    )
    const { wrapper } = view()
    await flushPromises()
    expect(wrapper.text()).toContain('已合并待发布')
    expect(wrapper.text()).toContain('尚未确认公网发布结果')
    expect(wrapper.text()).toContain('abc123')
    expect(wrapper.findAll('a')).toHaveLength(2)
    expect(wrapper.find('a[href^="javascript:"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('依赖：分析实际体验')
    expect(wrapper.findAll('.companion-image')).toHaveLength(2)
  })
  it('非管理会话保留阅读，但没有发起建设或决定入口', async () => {
    vi.mocked(api.fetchDevelopmentOverview).mockResolvedValue(
      developmentOverview({ canManage: false, runs: [developmentRun()] }),
    )
    const { wrapper } = view()
    await flushPromises()
    expect(wrapper.text()).toContain('当前会话仅可查看')
    expect(button(wrapper, '发起建设')).toBeUndefined()
    expect(button(wrapper, '同意并继续')).toBeUndefined()
    expect(wrapper.get('.save-row button').attributes('disabled')).toBeDefined()
  })
  it('维护权限完全来自canManage，已授权普通成员可以发起', async () => {
    const { wrapper, session } = view()
    session.account!.member.role = 'member'
    await flushPromises()
    expect(button(wrapper, '发起建设').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).not.toContain('当前会话仅可查看')
  })
})

describe('草稿与版本决定', () => {
  it('未通过校验的空输入刷新后仍保留，不能静默还原成账号值', async () => {
    const first = view()
    await flushPromises()
    await first.wrapper.findAll('input[type="number"]')[0]!.setValue('')
    await button(first.wrapper, '保存建设设置').trigger('click')
    expect(api.saveDevelopmentPolicy).not.toHaveBeenCalled()
    first.wrapper.unmount()
    wrappers.splice(wrappers.indexOf(first.wrapper), 1)
    const restored = view()
    await flushPromises()
    expect(
      (restored.wrapper.findAll('input[type="number"]')[0]!.element as HTMLInputElement).value,
    ).toBe('')
    expect(restored.wrapper.text()).toContain('草稿未保存')
  })
  it('轮询不覆盖设置草稿；明确核对新版本后提交CAS', async () => {
    const { wrapper } = view()
    await flushPromises()
    await wrapper.findAll('input[type="number"]')[0]!.setValue(8)
    const next = developmentOverview({
      policy: { enabled: true, version: 2, intervalHours: 12, maxParallel: 2 },
    })
    vi.mocked(api.fetchDevelopmentOverview).mockResolvedValue(next)
    await vi.advanceTimersByTimeAsync(10_000)
    expect((wrapper.findAll('input[type="number"]')[0]!.element as HTMLInputElement).value).toBe(
      '8',
    )
    expect(wrapper.text()).toContain('账号设置已有新版本')
    expect(button(wrapper, '保存建设设置').attributes('disabled')).toBeDefined()
    await button(wrapper, '保留草稿，基于最新版本保存').trigger('click')
    vi.mocked(api.saveDevelopmentPolicy).mockResolvedValue({
      enabled: false,
      version: 3,
      intervalHours: 8,
      maxParallel: 3,
    })
    vi.mocked(api.fetchDevelopmentOverview).mockResolvedValue(
      developmentOverview({
        policy: { enabled: false, version: 3, intervalHours: 8, maxParallel: 3 },
      }),
    )
    await button(wrapper, '保存建设设置').trigger('click')
    await flushPromises()
    expect(api.saveDevelopmentPolicy).toHaveBeenCalledWith({
      enabled: false,
      version: 2,
      intervalHours: 8,
      maxParallel: 3,
    })
    expect(wrapper.text()).toContain('设置已保存')
  })
  it('保存失败保留本机草稿，切换成员不会覆盖另一成员草稿', async () => {
    localStorage.setItem(
      'wao-development-draft:member-b',
      JSON.stringify({
        policy: { enabled: true, version: 1, intervalHours: 9, maxParallel: 2 },
        replies: {},
      }),
    )
    const { wrapper, session } = view()
    await flushPromises()
    await wrapper.findAll('input[type="number"]')[0]!.setValue(7)
    vi.mocked(api.saveDevelopmentPolicy).mockRejectedValue(new Error('网络中断'))
    await button(wrapper, '保存建设设置').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('草稿已保留')
    expect(
      JSON.parse(localStorage.getItem('wao-development-draft:member-a')!).policy.intervalHours,
    ).toBe(7)
    session.account = account('member-b')
    await flushPromises()
    expect((wrapper.findAll('input[type="number"]')[0]!.element as HTMLInputElement).value).toBe(
      '9',
    )
    expect(
      JSON.parse(localStorage.getItem('wao-development-draft:member-a')!).policy.intervalHours,
    ).toBe(7)
    expect(
      JSON.parse(localStorage.getItem('wao-development-draft:member-b')!).policy.intervalHours,
    ).toBe(9)
  })
  it('明确回复随决策版本提交；等待时禁止重复提交，失败保留回复', async () => {
    vi.mocked(api.fetchDevelopmentOverview).mockResolvedValue(
      developmentOverview({ runs: [developmentRun()] }),
    )
    const { wrapper } = view()
    await flushPromises()
    expect(button(wrapper, '同意并继续').attributes('disabled')).toBeDefined()
    await wrapper.get('textarea').setValue('同意保留留白，不增加内容。')
    let reject!: (error: Error) => void
    vi.mocked(api.replyDevelopmentDecision).mockImplementationOnce(
      () =>
        new Promise((_resolve, fail) => {
          reject = fail
        }),
    )
    await button(wrapper, '同意并继续').trigger('click')
    expect(button(wrapper, '拒绝此方案').attributes('disabled')).toBeDefined()
    await button(wrapper, '同意并继续').trigger('click')
    expect(api.replyDevelopmentDecision).toHaveBeenCalledExactlyOnceWith(
      'decision-1',
      3,
      'approve',
      '同意保留留白，不增加内容。',
    )
    reject(new Error('回复未保存'))
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toBe('同意保留留白，不增加内容。')
    expect(wrapper.text()).toContain('回复未保存')
  })
  it('新问题不会静默沿用旧版本回复；核对后才允许拒绝', async () => {
    vi.mocked(api.fetchDevelopmentOverview).mockResolvedValue(
      developmentOverview({ runs: [developmentRun()] }),
    )
    const { wrapper } = view()
    await flushPromises()
    await wrapper.get('textarea').setValue('先不采用，继续收集反馈。')
    const run = developmentRun()
    run.decision!.revision = 4
    run.decision!.question = '新增范围是否也采用？'
    vi.mocked(api.fetchDevelopmentOverview).mockResolvedValue(developmentOverview({ runs: [run] }))
    await vi.advanceTimersByTimeAsync(10_000)
    expect(wrapper.get('textarea').element.value).toBe('先不采用，继续收集反馈。')
    expect(wrapper.text()).toContain('问题已更新')
    expect(button(wrapper, '拒绝此方案').attributes('disabled')).toBeDefined()
    await button(wrapper, '我已核对新问题').trigger('click')
    const rejected = developmentRun({
      status: 'PAUSED',
      decision: { ...run.decision!, status: 'REJECTED', reply: '先不采用，继续收集反馈。' },
    })
    vi.mocked(api.replyDevelopmentDecision).mockResolvedValue(rejected)
    vi.mocked(api.fetchDevelopmentOverview).mockResolvedValue(
      developmentOverview({ runs: [rejected] }),
    )
    await button(wrapper, '拒绝此方案').trigger('click')
    await flushPromises()
    expect(api.replyDevelopmentDecision).toHaveBeenCalledWith(
      'decision-1',
      4,
      'reject',
      '先不采用，继续收集反馈。',
    )
    expect(wrapper.text()).toContain('已拒绝')
    expect(wrapper.find('textarea').exists()).toBe(false)
  })
  it('失败重试携带运行版本，不把请求成功叫作建设完成', async () => {
    const failed = developmentRun({ status: 'FAILED', error: '验证未通过' })
    vi.mocked(api.fetchDevelopmentOverview).mockResolvedValue(
      developmentOverview({ runs: [failed] }),
    )
    vi.mocked(api.retryDevelopmentRun).mockResolvedValue(developmentRun({ status: 'QUEUED' }))
    const { wrapper } = view()
    await flushPromises()
    await button(wrapper, '重试此项建设').trigger('click')
    await flushPromises()
    expect(api.retryDevelopmentRun).toHaveBeenCalledWith('run-1', 5)
    expect(wrapper.text()).toContain('重试请求已返回')
    expect(wrapper.text()).not.toContain('已完成')
  })
})

it('所有运行状态有独立中文结论，未知状态保留原文', () => {
  expect(developmentStatusLabel('PR_READY')).toBe('PR 待合并')
  expect(developmentStatusLabel('MERGED')).toBe('已合并待发布')
  expect(developmentStatusLabel('RELEASED')).toBe('已上线待验收')
  expect(developmentStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
})
