import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UpdateDiaryView from '@/views/UpdateDiaryView.vue'
import { fetchUpdateDiary, type UpdateDiaryPage } from '@/api/updateDiary'

vi.mock('@/api/updateDiary', () => ({
  fetchUpdateDiary: vi.fn<(typeof import('@/api/updateDiary'))['fetchUpdateDiary']>(),
}))

const page: UpdateDiaryPage = {
  days: [
    {
      date: '2026-09-08',
      entries: [
        { scope: 'updates', kind: 'FEATURE', title: '新增更新日记' },
        { scope: 'notes', kind: 'FIX', title: '修复小记保存失败' },
      ],
    },
    {
      date: '2026-09-06',
      entries: [{ scope: 'home', kind: 'PERFORMANCE', title: '缩短首页加载时间' }],
    },
  ],
  totalDays: 2,
  page: 1,
  size: 12,
}

describe('UpdateDiaryView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchUpdateDiary).mockResolvedValue(page)
    vi.stubGlobal('scrollTo', vi.fn())
  })

  it('按有更新的日期展示已验收功能结果，而不是任务流水', async () => {
    const wrapper = mount(UpdateDiaryView)
    await flushPromises()

    expect(wrapper.findAll('.timeline-day')).toHaveLength(2)
    expect(wrapper.text()).toContain('2026 年 9 月 8 日')
    expect(wrapper.text()).toContain('更新日记')
    expect(wrapper.text()).toContain('新功能')
    expect(wrapper.text()).toContain('问题修复')
    expect(wrapper.text()).toContain('体验优化')
    expect(wrapper.text()).toContain('新增更新日记')
    expect(wrapper.text()).not.toContain('feat(updates)')
  })

  it('真实空数据时不生成今天的占位记录', async () => {
    vi.mocked(fetchUpdateDiary).mockResolvedValue({ ...page, days: [], totalDays: 0 })
    const wrapper = mount(UpdateDiaryView)
    await flushPromises()

    expect(wrapper.find('.timeline').exists()).toBe(false)
    expect(wrapper.text()).toContain('还没有可展示的更新')
    expect(wrapper.text()).toContain('首次成功交付后')
  })

  it('初次加载失败后可原地重试', async () => {
    vi.mocked(fetchUpdateDiary)
      .mockRejectedValueOnce(new Error('网络中断'))
      .mockResolvedValueOnce(page)
    const wrapper = mount(UpdateDiaryView)
    await flushPromises()

    expect(wrapper.text()).toContain('网络中断')
    await wrapper.get('.state-card button').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.timeline-day')).toHaveLength(2)
  })
})
