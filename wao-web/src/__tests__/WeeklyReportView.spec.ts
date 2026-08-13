import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import WeeklyReportView from '@/views/WeeklyReportView.vue'
import { fetchAssistantStatus } from '@/api/assistant'
import { generateWeeklyReport, type WeeklyReport } from '@/api/weeklyReport'
import { copyText } from '@/utils/clipboard'

vi.mock('@/api/assistant', () => ({
  fetchAssistantStatus: vi.fn<(typeof import('@/api/assistant'))['fetchAssistantStatus']>(),
}))
vi.mock('@/api/weeklyReport', () => ({
  generateWeeklyReport: vi.fn<(typeof import('@/api/weeklyReport'))['generateWeeklyReport']>(),
}))
vi.mock('@/utils/clipboard', () => ({
  copyText: vi.fn<(typeof import('@/utils/clipboard'))['copyText']>(),
}))

const generated: WeeklyReport = {
  completed: '- 修复登录白屏',
  progress: '【待补充：进展与结果】',
  risks: '【待补充：问题风险】',
  nextPlan: '【待补充：下周计划】',
}

describe('WeeklyReportView', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(fetchAssistantStatus).mockResolvedValue({
      configured: true,
      message: '已连接本地 Codex',
    })
    vi.mocked(generateWeeklyReport).mockResolvedValue(generated)
    vi.mocked(copyText).mockResolvedValue(undefined)
  })

  it('一句话生成四段周报并保留原始记录供核对', async () => {
    const wrapper = mount(WeeklyReportView)
    await flushPromises()

    await wrapper.get('.source-card textarea').setValue('修复登录白屏')
    await wrapper.get('.generate-button').trigger('click')
    await flushPromises()

    expect(generateWeeklyReport).toHaveBeenCalledWith('修复登录白屏')
    expect(wrapper.get<HTMLTextAreaElement>('.source-card textarea').element.value).toBe(
      '修复登录白屏',
    )
    expect(wrapper.findAll('.report-section')).toHaveLength(4)
    expect(wrapper.text()).toContain('待补充')
  })

  it('多条原始记录保持完整内容发送给生成接口', async () => {
    const wrapper = mount(WeeklyReportView)
    await flushPromises()
    const records = '修复登录白屏\n报表接口仍在联调\n下周继续联调'

    await wrapper.get('.source-card textarea').setValue(records)
    await wrapper.get('.generate-button').trigger('click')
    await flushPromises()

    expect(generateWeeklyReport).toHaveBeenCalledWith(records)
    expect(wrapper.get<HTMLTextAreaElement>('.source-card textarea').element.value).toBe(records)
  })

  it('逐段修改后复制最终版本而不是模型原稿', async () => {
    const wrapper = mount(WeeklyReportView)
    await flushPromises()
    await wrapper.get('.source-card textarea').setValue('修复登录白屏')
    await wrapper.get('.generate-button').trigger('click')
    await flushPromises()

    const sections = wrapper.findAll<HTMLTextAreaElement>('.report-section textarea')
    await sections[2]!.setValue('- 联调环境偶发超时，正在定位')
    await wrapper.get('.result-title button').trigger('click')

    expect(copyText).toHaveBeenCalledWith(
      expect.stringContaining('## 问题风险\n\n- 联调环境偶发超时，正在定位'),
      '周报全文已复制',
    )
  })

  it('重复生成先展示候选版本且不覆盖未保存编辑', async () => {
    const next: WeeklyReport = { ...generated, completed: '- 新候选内容' }
    vi.mocked(generateWeeklyReport).mockResolvedValueOnce(generated).mockResolvedValueOnce(next)
    const wrapper = mount(WeeklyReportView)
    await flushPromises()
    await wrapper.get('.source-card textarea').setValue('第一条记录')
    await wrapper.get('.generate-button').trigger('click')
    await flushPromises()

    const firstSection = wrapper.get<HTMLTextAreaElement>('.report-section textarea')
    await firstSection.setValue('- 我手工修改的内容')
    await wrapper.get('.generate-button').trigger('click')
    await flushPromises()

    expect(firstSection.element.value).toBe('- 我手工修改的内容')
    expect(wrapper.text()).toContain('新结果已就绪，当前编辑没有被覆盖')
    expect(wrapper.text()).toContain('新候选内容')
  })

  it('生成失败时保留原始输入并提供原地重试', async () => {
    vi.mocked(generateWeeklyReport).mockRejectedValue(new Error('Runner 暂时离线'))
    const wrapper = mount(WeeklyReportView)
    await flushPromises()
    await wrapper.get('.source-card textarea').setValue('联调报表接口')
    await wrapper.get('.generate-button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Runner 暂时离线')
    expect(wrapper.text()).toContain('保留记录并重试')
    expect(wrapper.get<HTMLTextAreaElement>('.source-card textarea').element.value).toBe(
      '联调报表接口',
    )
  })

  it('刷新后恢复按成员隔离的本机草稿', async () => {
    localStorage.setItem(
      'wao-weekly-report-draft-v1:unknown',
      JSON.stringify({
        records: '已恢复的原始记录',
        report: generated,
        candidate: null,
        updatedAt: '2026-08-14T08:00:00Z',
      }),
    )
    const wrapper = mount(WeeklyReportView)
    await flushPromises()

    expect(wrapper.get<HTMLTextAreaElement>('.source-card textarea').element.value).toBe(
      '已恢复的原始记录',
    )
    expect(wrapper.findAll('.report-section')).toHaveLength(4)
    expect(wrapper.text()).toContain('草稿已保存')
  })
})
