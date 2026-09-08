import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import AiRunArchive from '@/components/ai/AiRunArchive.vue'
import AiRunProgressTimeline from '@/components/ai/AiRunProgressTimeline.vue'
import { continueAiRun, fetchAiRunConversation } from '@/api/ai'
import type { AiRun } from '@/types'

vi.mock('@/api/ai', () => ({ continueAiRun: vi.fn(), fetchAiRunConversation: vi.fn() }))
vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))

const run = { id: 'ar1', taskId: 't1', projectId: 'p1', engine: 'codex', status: 'succeeded',
  runnerId: 'rn1', runnerName: 'Linux', sessionId: 'thread-retained', progress: [],
  createdAt: '2026-09-08T10:00:00' } as unknown as AiRun
const stubs = {
  MarkdownEditor: { props: ['modelValue', 'readonly'], emits: ['update:modelValue'],
    template: '<textarea :value="modelValue" :readonly="readonly" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  AiRunMemoryReferences: true, AiRunSkillCapabilities: true,
}

describe('AiRun 持续对话', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
    setActivePinia(createPinia())
    useSessionStore().account = { member: { id: 'member1' } } as never
  })

  it('关闭重新打开保留草稿，发送失败可重试，成功才清理', async () => {
    let wrapper = mount(AiRunArchive, { props: { runs: [run] }, global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('请继续优化窄屏布局')
    wrapper.unmount()
    wrapper = mount(AiRunArchive, { props: { runs: [{ ...run }] }, global: { stubs } })
    await flushPromises()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('请继续优化窄屏布局')
    vi.mocked(continueAiRun).mockRejectedValueOnce(new Error('网络失败，请重试'))
    await wrapper.get('.ai-continue > button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('网络失败，请重试')
    expect(localStorage.getItem('wao:airun-draft:member1:ar1')).toBe('请继续优化窄屏布局')
    vi.mocked(continueAiRun).mockResolvedValueOnce({ ...run, status: 'queued' })
    await wrapper.get('.ai-continue > button').trigger('click')
    await flushPromises()
    expect(localStorage.getItem('wao:airun-draft:member1:ar1')).toBeNull()
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    wrapper.unmount()
  })

  it('轮询不覆盖输入，切换用户不泄露其他用户草稿', async () => {
    const wrapper = mount(AiRunArchive, { props: { runs: [run] }, global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('尚未发送')
    await wrapper.setProps({ runs: [{ ...run, latestProgress: '新进展' }] })
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('尚未发送')
    useSessionStore().account = { member: { id: 'member2' } } as never
    await flushPromises()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('')
    wrapper.unmount()
  })

  it('暂停记录仍显示精确会话和继续入口', async () => {
    const wrapper = mount(AiRunArchive, { props: { runs: [{ ...run, status: 'cancelled' }] }, global: { stubs } })
    await flushPromises()
    expect(wrapper.get('.ai-session').text()).toContain('thread-retained')
    expect(wrapper.get('.ai-continue > button').text()).toContain('继续对话')
    wrapper.unmount()
  })

  it('历史按游标补读，区分用户与 AI；读取失败保持已有内容', async () => {
    const progress = Array.from({ length: 50 }, (_, i) => ({ id: `p${i.toString().padStart(2, '0')}`,
      kind: 'message' as const, content: `AI 消息 ${i}`, createdAt: '2026-09-08T10:00:00' }))
    const wrapper = mount(AiRunProgressTimeline, { props: { run: { ...run, progress } } })
    vi.mocked(fetchAiRunConversation).mockRejectedValueOnce(new Error('读取失败，重试'))
    await wrapper.get('.history-button').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('li')).toHaveLength(50)
    expect(wrapper.text()).toContain('读取失败，重试')
    vi.mocked(fetchAiRunConversation).mockResolvedValueOnce({ items: [
      { id: 'old', kind: 'user', content: '最初的补充\n请保留上下文', createdAt: '2026-09-07T10:00:00' },
    ], nextCursor: null })
    await wrapper.get('.history-button').trigger('click')
    await flushPromises()
    expect(fetchAiRunConversation).toHaveBeenLastCalledWith('ar1', 'p00')
    expect(wrapper.findAll('li')).toHaveLength(51)
    expect(wrapper.get('.from-user').text()).toContain('最初的补充')
    expect(wrapper.find('.history-button').exists()).toBe(false)
  })
})
