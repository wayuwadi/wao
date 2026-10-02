import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { defineComponent } from 'vue'
import HomeTianrenGlance from '@/components/home/HomeTianrenGlance.vue'
import { fetchTodayTianrenReading, type TianrenReading } from '@/api/tianren'
import { fetchAssistantConversation } from '@/api/assistant'

vi.mock('@/api/tianren', () => ({ fetchTodayTianrenReading: vi.fn() }))
vi.mock('@/api/assistant', () => ({ fetchAssistantConversation: vi.fn() }))
const PopoverStub = defineComponent({ props: ['open'], emits: ['openChange'], template: '<div><div @click="$emit(\'openChange\', !open)"><slot /></div><div v-if="open"><slot name="content" /></div></div>' })
const record: TianrenReading = {
  id: 'reading', date: '2026-10-03', lines: [8, 7, 7, 8, 7, 7], changingLines: [],
  primary: { name: '巽', number: 57, symbol: '䷸', upper: { name: '巽', nature: '风', symbol: '☴' }, lower: { name: '巽', nature: '风', symbol: '☴' } },
  relating: null, conversationId: 'conversation', createdAt: '2026-10-03T09:00:00',
}
const wrappers: ReturnType<typeof mount>[] = []
function setup() {
  const wrapper = mount(HomeTianrenGlance, { props: { integrated: true }, attachTo: document.body, global: { stubs: { APopover: PopoverStub, Popover: PopoverStub, RouterLink: RouterLinkStub } } })
  wrappers.push(wrapper)
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(fetchTodayTianrenReading).mockResolvedValue(record)
  vi.mocked(fetchAssistantConversation).mockResolvedValue({ conversation: { id: 'conversation', title: '今日', updatedAt: '' }, messages: [{ id: 'message', role: 'assistant', content: '## 已存解读\n保持节奏。', createdAt: '' }] })
})
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))
describe('首页卦象预览', () => {
  it('首页只读取卦象，点击才加载真实摘要，并由独立链接进入完整页面', async () => {
    const wrapper = setup()
    await flushPromises()
    expect(fetchAssistantConversation).not.toHaveBeenCalled()
    expect(wrapper.findComponent(RouterLinkStub).exists()).toBe(false)
    await wrapper.get('.tianren-link').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('保持节奏。')
    expect(wrapper.getComponent(RouterLinkStub).props('to')).toBe('/tianren')
    expect(wrapper.findAll('.preview-lines > div')).toHaveLength(6)
    await wrapper.get('[aria-label="关闭卦象预览"]').trigger('click')
    expect(wrapper.get('.tianren-link').attributes('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(wrapper.get('.tianren-link').element)
  })
  it('空状态不编造解读，Escape 关闭并归还焦点', async () => {
    vi.mocked(fetchTodayTianrenReading).mockResolvedValue(null)
    const wrapper = setup()
    await flushPromises()
    await wrapper.get('.tianren-link').trigger('click')
    expect(wrapper.text()).toContain('今天还没有起卦')
    expect(fetchAssistantConversation).not.toHaveBeenCalled()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(wrapper.get('.tianren-link').attributes('aria-expanded')).toBe('false')
  })
  it('摘要读取失败可重试，保留真实卦象', async () => {
    vi.mocked(fetchAssistantConversation).mockRejectedValueOnce(new Error('解读读取失败'))
    const wrapper = setup()
    await flushPromises()
    await wrapper.get('.tianren-link').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('解读读取失败')
    expect(wrapper.text()).toContain('第 57 卦')
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('保持节奏。')
  })
})
