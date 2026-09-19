import { flushPromises, mount, enableAutoUnmount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSessionStore } from '@/stores/session'
import TextPolishView from '@/views/TextPolishView.vue'
import {
  fetchTextPolishWorkspace,
  runTextPolish,
  saveTextPolishPrompt,
  type TextPolishRecord,
} from '@/api/textPolish'

vi.mock('@/stores/session', async () => {
  const { reactive } = await import('vue')
  const session = reactive({ userId: 'member-a' })
  return { useSessionStore: () => session }
})
enableAutoUnmount(afterEach)
function setMember(userId: string) {
  (useSessionStore() as unknown as { userId: string }).userId = userId
}

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
    setMember('member-a')
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
    localStorage.setItem('wao-text-polish-prompt-draft-v2:member-a', '先写结论，再列步骤。')
    localStorage.setItem('wao-text-polish-original-draft-v2:member-a', '这个事情吧大概比较绕。')
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
    expect(localStorage.getItem('wao-text-polish-prompt-draft-v2:member-a')).toBeNull()
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
    expect(localStorage.getItem('wao-text-polish-prompt-draft-v2:member-a')).toBe('新的表达方法')

    await wrapper.get('.inline-error button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已保存')
    expect(localStorage.getItem('wao-text-polish-prompt-draft-v2:member-a')).toBeNull()
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
  it('同页切换账号隔离原文、提示词、结果和历史，切回恢复本人草稿', async () => {
    const wrapper = mount(TextPolishView)
    await flushPromises()
    await wrapper.get('[aria-label="待润色原文"]').setValue('甲的原文')
    await wrapper.get('.run-button').trigger('click')
    await flushPromises()
    await wrapper.get('[aria-label="润色提示词"]').setValue('甲的提示词草稿')
    vi.mocked(fetchTextPolishWorkspace).mockResolvedValue({ prompt: '乙的云端提示词', records: [] })
    setMember('member-b')
    await flushPromises()
    expect(wrapper.find('.result-text').exists()).toBe(false)
    expect(wrapper.findAll('.history-list details')).toHaveLength(0)
    expect(wrapper.get('[aria-label="待润色原文"]').element).toHaveProperty('value', '')
    expect(wrapper.get('[aria-label="润色提示词"]').element).toHaveProperty('value', '乙的云端提示词')
    await wrapper.get('[aria-label="待润色原文"]').setValue('乙的原文')
    setMember('member-a')
    await flushPromises()
    expect(wrapper.get('[aria-label="待润色原文"]').element).toHaveProperty('value', '甲的原文')
    expect(wrapper.get('[aria-label="润色提示词"]').element).toHaveProperty('value', '甲的提示词草稿')
    expect(localStorage.getItem('wao-text-polish-original-draft-v2:member-b')).toBe('乙的原文')
  })
  it('不认领也不删除无法确认归属的旧版草稿', async () => {
    localStorage.setItem('wao-text-polish-prompt-draft-v1', '旧版私密提示词')
    localStorage.setItem('wao-text-polish-original-draft-v1', '旧版私密原文')
    const wrapper = mount(TextPolishView)
    await flushPromises()
    expect(wrapper.get('[aria-label="待润色原文"]').element).toHaveProperty('value', '')
    expect(wrapper.get('[aria-label="润色提示词"]').element).toHaveProperty('value', defaultPrompt)
    expect(localStorage.getItem('wao-text-polish-original-draft-v1')).toBe('旧版私密原文')
  })
  it('旧账号加载回包不覆盖新账号', async () => {
    let finish!: (value: { prompt: string; records: TextPolishRecord[] }) => void
    vi.mocked(fetchTextPolishWorkspace).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mount(TextPolishView)
    setMember('member-b')
    await flushPromises()
    finish({ prompt: '甲的提示词', records: [record] })
    await flushPromises()
    expect(wrapper.get('[aria-label="润色提示词"]').element).toHaveProperty('value', defaultPrompt)
    expect(wrapper.findAll('.history-list details')).toHaveLength(0)
  })
  it('旧账号润色回包不进入新账号结果或历史', async () => {
    let finish!: (value: TextPolishRecord) => void
    vi.mocked(runTextPolish).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mount(TextPolishView)
    await flushPromises()
    await wrapper.get('[aria-label="待润色原文"]').setValue('甲的原文')
    await wrapper.get('.run-button').trigger('click')
    setMember('member-b')
    await flushPromises()
    finish(record)
    await flushPromises()
    expect(wrapper.find('.result-text').exists()).toBe(false)
    expect(wrapper.findAll('.history-list details')).toHaveLength(0)
  })
  it('旧账号保存返回不清除新账号草稿或继续润色', async () => {
    let finish!: (value: string) => void
    vi.mocked(saveTextPolishPrompt).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mount(TextPolishView)
    await flushPromises()
    await wrapper.get('[aria-label="润色提示词"]').setValue('甲的新方法')
    await wrapper.get('[aria-label="待润色原文"]').setValue('甲的原文')
    await wrapper.get('.run-button').trigger('click')
    setMember('member-b')
    await flushPromises()
    await wrapper.get('[aria-label="润色提示词"]').setValue('乙的草稿')
    finish('甲的新方法')
    await flushPromises()
    expect(localStorage.getItem('wao-text-polish-prompt-draft-v2:member-b')).toBe('乙的草稿')
    expect(wrapper.get('[aria-label="润色提示词"]').element).toHaveProperty('value', '乙的草稿')
    expect(runTextPolish).not.toHaveBeenCalled()
  })
  it('保存期间新输入保留且不重复启动', async () => {
    let finish!: (value: string) => void
    vi.mocked(saveTextPolishPrompt).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mount(TextPolishView)
    await flushPromises()
    await wrapper.get('[aria-label="润色提示词"]').setValue('第一版')
    await wrapper.get('[aria-label="待润色原文"]').setValue('提交原文')
    await wrapper.get('.run-button').trigger('click')
    expect(wrapper.get('.run-button').attributes('disabled')).toBeDefined()
    await wrapper.get('[aria-label="润色提示词"]').setValue('第二版')
    finish('第一版')
    await flushPromises()
    expect(wrapper.get('[aria-label="润色提示词"]').element).toHaveProperty('value', '第二版')
    expect(localStorage.getItem('wao-text-polish-prompt-draft-v2:member-a')).toBe('第二版')
    expect(wrapper.text()).toContain('请确认后重新润色')
    expect(runTextPolish).not.toHaveBeenCalled()
  })
  it('本机存储失败提示备份和重试', async () => {
    const wrapper = mount(TextPolishView)
    await flushPromises()
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('存储已满') })
    await wrapper.get('[aria-label="待润色原文"]').setValue('不能丢失的输入')
    expect(wrapper.get('[role="alert"]').text()).toContain('复制原文备份')
    storage.mockRestore()
    await wrapper.get('[role="alert"] button').trigger('click')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(localStorage.getItem('wao-text-polish-original-draft-v2:member-a')).toBe('不能丢失的输入')
  })
  it('退出登录清空可见内容，不写入无身份草稿', async () => {
    const wrapper = mount(TextPolishView)
    await flushPromises()
    await wrapper.get('[aria-label="待润色原文"]').setValue('甲的原文')
    setMember('')
    await flushPromises()
    expect(wrapper.find('[aria-label="待润色原文"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('请先登录')
    expect(localStorage.getItem('wao-text-polish-original-draft-v2:member-a')).toBe('甲的原文')
    expect(localStorage.getItem('wao-text-polish-original-draft-v2:')).toBeNull()
  })

  it('清空提示词的草稿不会在刷新时被云端旧值替换', async () => {
    const first = mount(TextPolishView)
    await flushPromises()
    await first.get('[aria-label="润色提示词"]').setValue('')
    first.unmount()
    const restored = mount(TextPolishView)
    await flushPromises()
    expect(restored.get('[aria-label="润色提示词"]').element).toHaveProperty('value', '')
    expect(restored.get('.run-button').attributes('disabled')).toBeDefined()
  })

})
