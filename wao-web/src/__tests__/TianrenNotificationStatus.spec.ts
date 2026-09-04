import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TianrenNotificationStatus from '@/components/workday/TianrenNotificationStatus.vue'
import { fetchTianrenNotification, retryTianrenNotification } from '@/api/tianren'

vi.mock('@/api/tianren', () => ({ fetchTianrenNotification: vi.fn(), retryTianrenNotification: vi.fn() }))
const state = (value: 'UNKNOWN' | 'SENT' | 'PENDING' | 'WAITING') => ({ state: value, targetName: '原群', message: value, retryable: value === 'UNKNOWN' })
const mountStatus = () => mount(TianrenNotificationStatus, { props: { readingId: 'reading-1', interpreting: false }, global: { stubs: { RouterLink: true } } })

describe('卦象通知状态', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.resetAllMocks() })
  afterEach(() => { vi.useRealTimers() })

  it('未知接收结果确认后才能重试并保留群名', async () => {
    vi.mocked(fetchTianrenNotification).mockResolvedValue(state('UNKNOWN'))
    vi.mocked(retryTianrenNotification).mockResolvedValue(state('PENDING'))
    const wrapper = mountStatus(); await flushPromises()
    const retry = wrapper.findAll('button')[0]!
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
    expect(wrapper.text()).not.toContain('重试发送')
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

  it('读取失败有原位重试且不伪报成功', async () => {
    vi.mocked(fetchTianrenNotification).mockRejectedValueOnce(new Error('网络失败')).mockResolvedValue(state('SENT'))
    const wrapper = mountStatus(); await flushPromises()
    expect(wrapper.get('[role=alert]').text()).toBe('网络失败')
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('已发送')
    wrapper.unmount()
  })
})
