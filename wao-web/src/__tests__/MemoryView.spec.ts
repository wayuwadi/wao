import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MemoryView from '@/views/MemoryView.vue'
import {
  createPersonalMemory,
  fetchPersonalMemories,
  uploadPersonalMemory,
  type PersonalMemory,
} from '@/api/memory'

vi.mock('@/api/memory', async () => {
  const actual = await vi.importActual<typeof import('@/api/memory')>('@/api/memory')
  return {
    ...actual,
    fetchPersonalMemories: vi.fn<(typeof actual)['fetchPersonalMemories']>(),
    createPersonalMemory: vi.fn<(typeof actual)['createPersonalMemory']>(),
    updatePersonalMemory: vi.fn<(typeof actual)['updatePersonalMemory']>(),
    uploadPersonalMemory: vi.fn<(typeof actual)['uploadPersonalMemory']>(),
    deletePersonalMemory: vi.fn<(typeof actual)['deletePersonalMemory']>(),
  }
})

const memory = (overrides: Partial<PersonalMemory> = {}): PersonalMemory => ({
  id: 'mem1', title: '晨跑偏好', content: '喜欢日出前跑步', category: 'preference',
  captureMode: 'manual', mediaType: 'text', createdAt: '2026-08-18T08:00:00',
  updatedAt: '2026-08-18T08:00:00', ...overrides,
})

describe('MemoryView', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    vi.mocked(fetchPersonalMemories).mockResolvedValue([])
  })

  it('区分人工、AI 与多模态记忆并展示账号私有边界', async () => {
    vi.mocked(fetchPersonalMemories).mockResolvedValue([
      memory(),
      memory({ id: 'mem2', title: '长期职责', captureMode: 'ai', category: 'responsibility' }),
      memory({ id: 'mem3', title: '海边旅行', mediaType: 'image', category: 'experience',
        fileName: '海边.png', byteSize: 2048, downloadUrl: '/api/memory/entries/mem3/attachment' }),
    ])

    const wrapper = mount(MemoryView)
    await flushPromises()

    expect(wrapper.text()).toContain('当前账号私有')
    expect(wrapper.text()).toContain('人工采集')
    expect(wrapper.text()).toContain('AI 采集')
    expect(wrapper.text()).toContain('海边.png')
    expect(wrapper.get('a.attachment').attributes('href')).toContain('mem3/attachment')
  })

  it('恢复本机草稿并成功保存真实文字记忆', async () => {
    localStorage.setItem('wao-personal-memory-draft-v1', JSON.stringify({
      title: '未完成的经历', content: '稍后继续写', category: 'experience',
    }))
    vi.mocked(createPersonalMemory).mockResolvedValue(memory({
      title: '未完成的经历', content: '稍后继续写', category: 'experience',
    }))
    const wrapper = mount(MemoryView)
    await flushPromises()

    await wrapper.get('.memory-hero > button').trigger('click')
    expect(wrapper.text()).toContain('已恢复上次未保存的文字草稿')
    expect((wrapper.get('.composer input').element as HTMLInputElement).value).toBe('未完成的经历')
    await wrapper.get('.composer footer .primary').trigger('click')
    await flushPromises()

    expect(createPersonalMemory).toHaveBeenCalledWith({
      title: '未完成的经历', content: '稍后继续写', category: 'experience',
    })
    expect(localStorage.getItem('wao-personal-memory-draft-v1')).toBeNull()
  })

  it('读取失败时保留明确重试入口', async () => {
    vi.mocked(fetchPersonalMemories).mockRejectedValueOnce(new Error('网络暂不可用')).mockResolvedValueOnce([])
    const wrapper = mount(MemoryView)
    await flushPromises()

    expect(wrapper.text()).toContain('网络暂不可用')
    await wrapper.get('.state-card.error button').trigger('click')
    await flushPromises()

    expect(fetchPersonalMemories).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('还没有个人记忆')
  })

  it('选择图片后走多模态上传而不是文字接口', async () => {
    const uploaded = memory({ id: 'mem-img', title: '旅行照', mediaType: 'image', fileName: '旅行.png' })
    vi.mocked(uploadPersonalMemory).mockResolvedValue(uploaded)
    const wrapper = mount(MemoryView)
    await flushPromises()
    await wrapper.get('.memory-hero > button').trigger('click')
    const input = wrapper.get('input[type="file"]')
    const file = new File(['image'], '旅行.png', { type: 'image/png' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await wrapper.get('.composer textarea').setValue('海边的一天')
    await wrapper.get('.composer footer .primary').trigger('click')
    await flushPromises()

    expect(uploadPersonalMemory).toHaveBeenCalledWith({
      title: '旅行', content: '海边的一天', category: 'experience',
    }, file)
    expect(createPersonalMemory).not.toHaveBeenCalled()
  })
})
