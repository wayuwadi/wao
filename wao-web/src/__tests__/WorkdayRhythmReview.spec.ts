import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Review from '@/components/workday/WorkdayRhythmReview.vue'
const api = vi.hoisted(() => ({ fetchRhythmReview: vi.fn(), saveRhythmReview: vi.fn() }))
vi.mock('@/api/workdayRhythm', () => api)
const props = { date: '2026-09-09', memberId: 'm1' }
const key = 'wao-rhythm-review-draft:m1:2026-09-09'

describe('今日复盘', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    api.fetchRhythmReview.mockResolvedValue({ revision: 0, entries: [] })
    api.saveRhythmReview.mockImplementation((_date, body) =>
      Promise.resolve({ ...body, revision: body.revision + 1 }),
    )
  })
  it('默认三项，追加、恢复草稿、保存后清理并回读完整原文', async () => {
    let wrapper = mount(Review, { props })
    await flushPromises()
    expect(wrapper.findAll('textarea')).toHaveLength(3)
    await wrapper.get('textarea').setValue('做得好\n继续保持')
    await wrapper.get('.review-add').trigger('click')
    await wrapper.findAll('textarea')[3]!.setValue('追加记录')
    wrapper.unmount()
    wrapper = mount(Review, { props })
    await flushPromises()
    expect(wrapper.findAll('textarea')[3]!.element.value).toBe('追加记录')
    expect(wrapper.get('textarea').element.value).toBe('做得好\n继续保持')
    await wrapper.get('header button').trigger('click')
    await flushPromises()
    expect(api.saveRhythmReview).toHaveBeenCalledWith(props.date, {
      revision: 0,
      entries: ['做得好\n继续保持', '', '', '追加记录'],
    })
    expect(localStorage.getItem(key)).toBeNull()
    expect(wrapper.text()).toContain('复盘已保存到账号')
    wrapper.unmount()
  })
  it('保存失败保留草稿，冲突显示最新内容且要求核对后才能保存', async () => {
    const wrapper = mount(Review, { props })
    await flushPromises()
    await wrapper.get('textarea').setValue('本机草稿')
    api.saveRhythmReview.mockRejectedValueOnce(new Error('其他页面更新'))
    await wrapper.get('header button').trigger('click')
    await flushPromises()
    expect(localStorage.getItem(key)).toContain('本机草稿')
    api.fetchRhythmReview.mockResolvedValue({ revision: 2, entries: ['另一页面内容'] })
    await wrapper.get('.review-error button').trigger('click')
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toBe('本机草稿')
    expect(wrapper.get('.review-conflict').text()).toContain('另一页面内容')
    expect(wrapper.get('header button').attributes('disabled')).toBeDefined()
    await wrapper.get('.review-conflict button').trigger('click')
    await wrapper.get('header button').trigger('click')
    await flushPromises()
    expect(api.saveRhythmReview).toHaveBeenLastCalledWith(props.date, {
      revision: 2,
      entries: ['本机草稿', '', ''],
    })
    wrapper.unmount()
  })
  it('读取失败可重试，保存中禁用输入，离开后的迟到结果不清理草稿', async () => {
    api.fetchRhythmReview.mockRejectedValueOnce(new Error('网络断开'))
    const wrapper = mount(Review, { props })
    await flushPromises()
    expect(wrapper.text()).toContain('网络断开')
    await wrapper.get('.review-error button').trigger('click')
    await flushPromises()
    await wrapper.get('textarea').setValue('尚未确认')
    let finish!: (value: unknown) => void
    api.saveRhythmReview.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    await wrapper.get('header button').trigger('click')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
    wrapper.unmount()
    finish({ revision: 1, entries: ['尚未确认', '', ''] })
    await flushPromises()
    expect(localStorage.getItem(key)).toContain('尚未确认')
    const other = mount(Review, { props: { ...props, memberId: 'm2' } })
    await flushPromises()
    expect(other.get('textarea').element.value).toBe('')
    other.unmount()
    const tomorrow = mount(Review, { props: { ...props, date: '2026-09-10' } })
    await flushPromises()
    expect(tomorrow.get('textarea').element.value).toBe('')
    tomorrow.unmount()
  })
  it('迟到保存不得清理另一页面草稿，离开页面重新落下当前未提交输入', async () => {
    const first = mount(Review, { props })
    await flushPromises()
    const second = mount(Review, { props })
    await flushPromises()
    await first.get('textarea').setValue('页面一提交')
    let finish!: (value: unknown) => void
    api.saveRhythmReview.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    await first.get('header button').trigger('click')
    await second.get('textarea').setValue('页面二尚未提交')
    finish({ revision: 1, entries: ['页面一提交', '', ''] })
    await flushPromises()
    expect(localStorage.getItem(key)).toContain('页面二尚未提交')
    // 其他页面写入了共享本机键后，当前页面离开仍保存本人正在编辑的文字。
    localStorage.setItem(key, JSON.stringify({ revision: 1, entries: ['其他页面草稿'] }))
    second.unmount()
    expect(localStorage.getItem(key)).toContain('页面二尚未提交')
    first.unmount()
  })
})
