import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TextPolishView from '@/views/TextPolishView.vue'
import {
  fetchTextPolishWorkspace,
  runTextPolish,
  saveTextPolishPrompt,
  type TextPolishRecord,
} from '@/api/textPolish'

vi.mock('@/api/textPolish', () => ({
  fetchTextPolishWorkspace:
    vi.fn<(typeof import('@/api/textPolish'))['fetchTextPolishWorkspace']>(),
  runTextPolish: vi.fn<(typeof import('@/api/textPolish'))['runTextPolish']>(),
  saveTextPolishPrompt: vi.fn<(typeof import('@/api/textPolish'))['saveTextPolishPrompt']>(),
}))

const defaultPrompt = '看明白，再想明白、说明白、干明白。'
const record: TextPolishRecord = {
  id: 'tp1',
  promptSnapshot: defaultPrompt,
  originalText: '这个事情吧大概比较绕。',
  polishedText: '这件事较为复杂，需要先梳理背景。',
  createdAt: '2026-08-28T12:00:00',
}

describe('TextPolishView', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(fetchTextPolishWorkspace).mockReset().mockResolvedValue({
      prompt: defaultPrompt,
      records: [],
    })
    vi.mocked(saveTextPolishPrompt).mockReset().mockResolvedValue(defaultPrompt)
    vi.mocked(runTextPolish).mockReset().mockResolvedValue(record)
  })

  it('首次加载真实提示词并明确展示无历史空状态', async () => {
    const wrapper = mount(TextPolishView)
    await flushPromises()

    expect(wrapper.get('[aria-label="润色提示词"]').element).toHaveProperty('value', defaultPrompt)
    expect(wrapper.text()).toContain('已同步')
    expect(wrapper.text()).not.toContain('未保存')
    expect(wrapper.text()).toContain('还没有润色记录')
    expect(wrapper.text()).toContain('不会填充演示内容')
  })

  it('本地未保存草稿优先于服务端旧值，润色前先保存可见提示词', async () => {
    localStorage.setItem('wao-text-polish-prompt-draft-v1', '先写结论，再列步骤。')
    localStorage.setItem('wao-text-polish-original-draft-v1', '这个事情吧大概比较绕。')
    vi.mocked(saveTextPolishPrompt).mockResolvedValue('先写结论，再列步骤。')

    const wrapper = mount(TextPolishView)
    await flushPromises()
    expect(wrapper.text()).toContain('未保存')

    await wrapper.get('.run-button').trigger('click')
    await flushPromises()

    expect(saveTextPolishPrompt).toHaveBeenCalledWith('先写结论，再列步骤。')
    expect(runTextPolish).toHaveBeenCalledWith('这个事情吧大概比较绕。')
    expect(wrapper.get('.result-text').text()).toContain('需要先梳理背景')
    expect(wrapper.findAll('.history-list details')).toHaveLength(1)
    expect(localStorage.getItem('wao-text-polish-prompt-draft-v1')).toBeNull()
  })

  it('提示词保存失败保留草稿并提供原地重试', async () => {
    vi.mocked(saveTextPolishPrompt)
      .mockRejectedValueOnce(new Error('网络已断开'))
      .mockResolvedValueOnce('新的表达方法')
    const wrapper = mount(TextPolishView)
    await flushPromises()
    await wrapper.get('[aria-label="润色提示词"]').setValue('新的表达方法')
    await wrapper.get('.secondary-button').trigger('click')
    await flushPromises()

    expect(wrapper.get('.inline-error').text()).toContain('网络已断开')
    expect(localStorage.getItem('wao-text-polish-prompt-draft-v1')).toBe('新的表达方法')

    await wrapper.get('.inline-error button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已保存')
    expect(localStorage.getItem('wao-text-polish-prompt-draft-v1')).toBeNull()
  })

  it('真实 AI 失败时保留原文且不生成历史占位', async () => {
    vi.mocked(runTextPolish).mockRejectedValueOnce(new Error('本地 Codex 暂时离线'))
    const wrapper = mount(TextPolishView)
    await flushPromises()
    await wrapper.get('[aria-label="待润色原文"]').setValue('不能丢失的原文')
    await wrapper.get('.run-button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('本地 Codex 暂时离线')
    expect(wrapper.get('[aria-label="待润色原文"]').element).toHaveProperty(
      'value',
      '不能丢失的原文',
    )
    expect(wrapper.findAll('.history-list details')).toHaveLength(0)
    expect(wrapper.text()).toContain('还没有润色记录')
  })
})
