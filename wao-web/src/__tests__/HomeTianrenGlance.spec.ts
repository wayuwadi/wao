import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import HomeTianrenGlance from '@/components/home/HomeTianrenGlance.vue'
import { fetchSharedTodayTianrenReading, type TianrenReading } from '@/api/tianren'

vi.mock('@/api/tianren', () => ({ fetchSharedTodayTianrenReading: vi.fn() }))
const PopoverStub = defineComponent({ props: ['open'], emits: ['openChange'], template: '<div><div @click="$emit(\'openChange\', !open)"><slot /></div><div v-if="open"><slot name="content" /></div></div>' })
const record: TianrenReading = {
  id: 'reading', date: '2026-10-03', lines: [8, 7, 7, 8, 7, 7], changingLines: [],
  primary: { name: '巽', number: 57, symbol: '䷸', upper: { name: '巽', nature: '风', symbol: '☴' }, lower: { name: '巽', nature: '风', symbol: '☴' } },
  relating: null, conversationId: 'conversation', createdAt: '2026-10-03T09:00:00',
}
const wrappers: ReturnType<typeof mount>[] = []
function setup() {
  const wrapper = mount(HomeTianrenGlance, { props: { integrated: true }, attachTo: document.body, global: { stubs: { APopover: PopoverStub, Popover: PopoverStub } } })
  wrappers.push(wrapper)
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(fetchSharedTodayTianrenReading).mockResolvedValue(record)
})
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))
describe('首页卦象预览', () => {
  it('首页直接显示共享卦象，预览中显示同一结果且没有主动起卦入口', async () => {
    const wrapper = setup()
    await flushPromises()
    expect(fetchSharedTodayTianrenReading).toHaveBeenCalledOnce()
    expect(wrapper.get('.tianren-link').text()).toContain('巽卦')
    await wrapper.get('.tianren-link').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('本卦为巽卦')
    expect(wrapper.text()).not.toContain('算一卦')
    expect(wrapper.find('a[href="/tianren"]').exists()).toBe(false)
    expect(wrapper.findAll('.preview-lines > div')).toHaveLength(6)
    await wrapper.get('[aria-label="关闭卦象预览"]').trigger('click')
    expect(wrapper.get('.tianren-link').attributes('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(wrapper.get('.tianren-link').element)
  })
  it('8 点前空状态不提示主动起卦，Escape 关闭并归还焦点', async () => {
    vi.mocked(fetchSharedTodayTianrenReading).mockResolvedValue(null)
    const wrapper = setup()
    await flushPromises()
    await wrapper.get('.tianren-link').trigger('click')
    expect(wrapper.text()).toContain('每天 8 点生成')
    expect(wrapper.text()).not.toContain('主动开始')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(wrapper.get('.tianren-link').attributes('aria-expanded')).toBe('false')
  })
  it('读取失败可重试并显示共享卦象', async () => {
    vi.mocked(fetchSharedTodayTianrenReading).mockRejectedValueOnce(new Error('读取失败'))
    const wrapper = setup()
    await flushPromises()
    expect(wrapper.find('.tianren-retry').exists()).toBe(true)
    await wrapper.get('.tianren-retry').trigger('click')
    await flushPromises()
    expect(wrapper.get('.tianren-link').text()).toContain('巽卦')
  })
})
