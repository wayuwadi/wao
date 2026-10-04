import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TianrenNotificationStatus from '@/components/workday/TianrenNotificationStatus.vue'
import { fetchTianrenNotification, retryTianrenNotification } from '@/api/tianren'

vi.mock('@/api/tianren', () => ({ fetchTianrenNotification: vi.fn(), retryTianrenNotification: vi.fn() }))
const state = (value: 'UNKNOWN' | 'SENT' | 'PENDING' | 'WAITING' | 'INTERPRETATION_FAILED') => ({ state: value, targetName: '原群', message: value, retryable: value === 'UNKNOWN' })
const mountStatus = () => mount(TianrenNotificationStatus, { props: { readingId: 'reading-1', interpreting: false }, global: { stubs: { RouterLink: true } } })

describe('卦象通知状态', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.resetAllMocks() })
  afterEach(() => { vi.useRealTimers() })
  it('聚合设置中直接显示投递详情，不再提供独立配置或折叠按钮', async () => {
    vi.mocked(fetchTianrenNotification).mockResolvedValue(state('SENT'))
    const wrapper = mount(TianrenNotificationStatus, { props: { readingId: 'reading-1', interpreting: false, embedded: true }, global: { stubs: { RouterLink: true } } })
    await flushPromises()
    expect(wrapper.find('.notice-trigger').exists()).toBe(false)
    expect(wrapper.find('.notice-settings').exists()).toBe(false)
    expect(wrapper.find('.notice-details').exists()).toBe(true)
    expect(wrapper.text()).toContain('已发送'); wrapper.unmount()
  })

  it('未知接收结果确认后才能重试并保留群名', async () => {
    vi.mocked(fetchTianrenNotification).mockResolvedValue(state('UNKNOWN'))
    vi.mocked(retryTianrenNotification).mockResolvedValue(state('PENDING'))
    const wrapper = mountStatus(); await flushPromises()
    await wrapper.get('.notice-trigger').trigger('click')
    const retry = wrapper.findAll('.notice-actions button')[0]!
    expect(retry.attributes('disabled')).toBeDefined()
    await wrapper.get('input').setValue(true)
    await retry.trigger('click'); await flushPromises()
    expect(retryTianrenNotification).toHaveBeenCalledWith('reading-1', true)
    expect(wrapper.text()).toContain('原群')
    expect(wrapper.text()).toContain('待发送')
    wrapper.unmount()
  })

  it('终态停止刷新且不能重复发送', async () => {
    vi.mocked(fetchTianrenNotification).mockResolvedValue(state('SENT'))
    const wrapper = mountStatus(); await flushPromises()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(fetchTianrenNotification).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.notice-details').exists()).toBe(false)
    await wrapper.get('.notice-trigger').trigger('click')
    expect(wrapper.text()).not.toContain('重试发送')
    expect(wrapper.text()).toContain('原群')
    await wrapper.get('.notice-close').trigger('click')
    expect(wrapper.find('.notice-details').exists()).toBe(false)
    wrapper.unmount()
  })

  it('切换日期忽略旧响应并卸载后停止计时', async () => {
    let oldResolve!: (value: ReturnType<typeof state>) => void
    vi.mocked(fetchTianrenNotification).mockImplementationOnce(() => new Promise(resolve => { oldResolve = resolve }))
      .mockResolvedValue(state('SENT'))
    const wrapper = mountStatus()
    await wrapper.setProps({ readingId: 'reading-2' }); await flushPromises()
    oldResolve(state('UNKNOWN')); await flushPromises()
    expect(wrapper.text()).toContain('已发送')
    expect(wrapper.text()).not.toContain('接收结果待确认')
    wrapper.unmount(); await vi.advanceTimersByTimeAsync(30_000)
    expect(fetchTianrenNotification).toHaveBeenCalledTimes(2)
  })

  it('详情可按 Escape 或点击外部关闭，折叠期间继续读取发送状态', async () => {
    vi.mocked(fetchTianrenNotification).mockResolvedValueOnce(state('PENDING')).mockResolvedValue(state('SENT'))
    const wrapper = mountStatus(); await flushPromises()
    const trigger = wrapper.get('.notice-trigger')
    expect(trigger.attributes('aria-expanded')).toBe('false')
    await trigger.trigger('click')
    expect(wrapper.find('.notice-details').exists()).toBe(true)
    await trigger.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('.notice-details').exists()).toBe(false)
    await trigger.trigger('click')
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.notice-details').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(5000)
    expect(trigger.text()).toContain('已发送')
    wrapper.unmount()
  })

  it('后台解读失败显示真实阶段且不提供卡片重发操作', async () => {
    vi.mocked(fetchTianrenNotification).mockResolvedValue(state('INTERPRETATION_FAILED'))
    const wrapper = mountStatus(); await flushPromises()
    expect(wrapper.get('.notice-trigger').text()).toContain('解读失败')
    await wrapper.get('.notice-trigger').trigger('click')
    expect(wrapper.text()).not.toContain('重试发送')
    wrapper.unmount()
  })

  it('读取失败有原位重试且不伪报成功', async () => {
    vi.mocked(fetchTianrenNotification).mockRejectedValueOnce(new Error('网络失败')).mockResolvedValue(state('SENT'))
    const wrapper = mountStatus(); await flushPromises()
    expect(wrapper.get('.notice-trigger').text()).toContain('读取失败')
    await wrapper.get('.notice-trigger').trigger('click')
    expect(wrapper.get('[role=alert]').text()).toBe('网络失败')
    await wrapper.get('.notice-actions button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('已发送')
    wrapper.unmount()
  })
})
