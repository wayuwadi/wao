import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TianrenVisualCard from '@/components/tianren/TianrenVisualCard.vue'
import { fetchTianrenImage, fetchTianrenVisual, generateTianrenImage, type TianrenVisual } from '@/api/tianren'
vi.mock('@/api/tianren', () => ({ fetchTianrenVisual: vi.fn(), fetchTianrenImage: vi.fn(), generateTianrenImage: vi.fn() }))
const base: TianrenVisual = { interpretationRunId: 'run1', state: 'SUCCEEDED', title: '今天的理解', change: '调整节奏', keep: '保持诚意', imageAttemptId: 'image1', message: '配图已保存', retryable: true }
const props = { readingId: 'reading1', today: true, visible: true, refreshKey: 0, interpreting: false }
describe('TianrenVisualCard', () => {
  beforeEach(() => {
    vi.mocked(fetchTianrenVisual).mockResolvedValue(base)
    vi.mocked(fetchTianrenImage).mockResolvedValue(new Blob(['image'], { type: 'image/png' }))
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: vi.fn().mockReturnValue('blob:private1') })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
  })
  afterEach(() => { vi.clearAllMocks(); vi.useRealTimers(); document.body.innerHTML = '' })
  it('读取私有 Blob、放大，并在切换记录和卸载时释放 ObjectURL', async () => {
    const wrapper = mount(TianrenVisualCard, { props, attachTo: document.body }); await flushPromises()
    expect(fetchTianrenImage).toHaveBeenCalledWith('reading1', 'image1')
    expect(wrapper.get('img').attributes('src')).toBe('blob:private1')
    await wrapper.get('.image-button').trigger('click'); await flushPromises()
    expect(document.body.querySelector('[role="dialog"]')).not.toBeNull()
    await wrapper.setProps({ readingId: 'reading2' }); await flushPromises()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:private1')
    wrapper.unmount()
    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(2)
  })
  it('后台活动状态恢复仅查询，五秒继续读取并在终态停止', async () => {
    vi.useFakeTimers()
    vi.mocked(fetchTianrenVisual).mockResolvedValueOnce({ ...base, state: 'RUNNING', imageAttemptId: null, message: '正在生成' }).mockResolvedValue(base)
    const wrapper = mount(TianrenVisualCard, { props }); await flushPromises()
    expect(wrapper.text()).toContain('关闭页面后仍会继续')
    expect(generateTianrenImage).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(5000); await flushPromises()
    expect(wrapper.find('img').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(10000)
    expect(fetchTianrenVisual).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('配图失败只重试图片，生成期间和失败后保留同版本旧图', async () => {
    vi.useFakeTimers()
    vi.mocked(fetchTianrenVisual).mockResolvedValue({ ...base, state: 'FAILED', message: '生成失败' })
    vi.mocked(generateTianrenImage).mockResolvedValue({ ...base, state: 'QUEUED', message: '等待绘图' })
    const wrapper = mount(TianrenVisualCard, { props }); await flushPromises()
    const retry = wrapper.findAll('button').find(button => button.text() === '仅重试配图')!
    await retry.trigger('click'); await flushPromises()
    expect(generateTianrenImage).toHaveBeenCalledWith('reading1', 'run1', expect.stringMatching(/^[a-f0-9-]{36}$/))
    expect(wrapper.get('img').attributes('src')).toBe('blob:private1')
    expect(wrapper.text()).toContain('等待绘图')
    await vi.advanceTimersByTimeAsync(5000); await flushPromises()
    expect(wrapper.get('img').attributes('src')).toBe('blob:private1')
    expect(wrapper.text()).toContain('仅重试配图')
    wrapper.unmount()
  })
  it('历史无图明确空状态且绝不创建配图', async () => {
    vi.mocked(fetchTianrenVisual).mockResolvedValue({ ...base, state: 'NOT_GENERATED', imageAttemptId: null, retryable: true })
    const wrapper = mount(TianrenVisualCard, { props: { ...props, today: false } }); await flushPromises()
    expect(wrapper.text()).toContain('当日没有保存配图')
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(generateTianrenImage).not.toHaveBeenCalled(); wrapper.unmount()
  })
  it('新解读版本失败也不使用旧版本图；记录切换丢弃迟到图片', async () => {
    vi.mocked(fetchTianrenVisual).mockResolvedValueOnce(base).mockResolvedValue({ ...base, interpretationRunId: 'run2', state: 'FAILED', imageAttemptId: null })
    const wrapper = mount(TianrenVisualCard, { props }); await flushPromises()
    await wrapper.setProps({ refreshKey: 1 }); await flushPromises()
    expect(wrapper.find('img').exists()).toBe(false)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:private1')
    wrapper.unmount()
    let resolve!: (blob: Blob) => void
    vi.mocked(fetchTianrenVisual).mockResolvedValueOnce(base).mockResolvedValue({ ...base, imageAttemptId: null })
    vi.mocked(fetchTianrenImage).mockReturnValue(new Promise(result => { resolve = result }))
    vi.mocked(URL.createObjectURL).mockClear()
    const other = mount(TianrenVisualCard, { props }); await flushPromises()
    await other.setProps({ readingId: 'another' }); await flushPromises()
    resolve(new Blob(['late'])); await flushPromises()
    expect(URL.createObjectURL).not.toHaveBeenCalled(); other.unmount()
  })
  it('网络未确认的重试沿用 requestId，快速连点只提交一次', async () => {
    vi.mocked(fetchTianrenVisual).mockResolvedValue({ ...base, state: 'FAILED' })
    let reject!: (error: Error) => void
    vi.mocked(generateTianrenImage).mockReturnValueOnce(new Promise((_, failure) => { reject = failure })).mockResolvedValue({ ...base, state: 'QUEUED' })
    const wrapper = mount(TianrenVisualCard, { props }); await flushPromises()
    const retry = wrapper.findAll('button').find(button => button.text() === '仅重试配图')!
    await retry.trigger('click'); await retry.trigger('click')
    expect(generateTianrenImage).toHaveBeenCalledTimes(1)
    reject(new Error('连接中断')); await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '仅重试配图')!.trigger('click'); await flushPromises()
    expect(vi.mocked(generateTianrenImage).mock.calls[1]?.[2]).toBe(vi.mocked(generateTianrenImage).mock.calls[0]?.[2]); wrapper.unmount()
  })
})
