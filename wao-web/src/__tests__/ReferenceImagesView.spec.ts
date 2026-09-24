import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ReferenceImagesView from '@/views/ReferenceImagesView.vue'

const api = vi.hoisted(() => ({
  list: vi.fn(), upload: vi.fn(), status: vi.fn(), conversation: vi.fn(), stream: vi.fn(),
}))
vi.mock('@/api/referenceImages', () => ({ listReferenceImages: api.list, uploadReferenceImage: api.upload }))
vi.mock('@/api/assistant', () => ({
  fetchAssistantStatus: api.status,
  createAssistantConversation: api.conversation,
  streamChat: api.stream,
}))
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ userId: 'member-test' }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

const picture = {
  id: 'a'.repeat(32), name: '项目首页.png', contentType: 'image/png', byteSize: 100,
  createdAt: '2026-09-24T12:00:00', url: `/api/reference-images/${'a'.repeat(32)}/raw`,
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  api.list.mockResolvedValue([])
  api.upload.mockResolvedValue(picture)
  api.status.mockResolvedValue({ configured: true, message: '可用' })
  api.conversation.mockResolvedValue({ id: 'conversation-1' })
  api.stream.mockImplementation(async function* () { yield '看到了项目概览的层级。' })
})

describe('ReferenceImagesView', () => {
  it('uploads a screenshot without a form and sends its real id with one sentence', async () => {
    const wrapper = mount(ReferenceImagesView)
    await flushPromises()
    const input = wrapper.get('input[type="file"]')
    const file = new File([new Uint8Array([137, 80, 78, 71])], '项目首页.png', { type: 'image/png' })
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
    await input.trigger('change')
    await flushPromises()
    expect(api.upload).toHaveBeenCalledWith(file)
    expect(wrapper.findAll('.image-card')).toHaveLength(1)
    await wrapper.get('textarea').setValue('参考这个首页，设计我的项目页')
    await wrapper.get('.compose-row button').trigger('click')
    await flushPromises()
    expect(api.stream).toHaveBeenCalledWith('参考这个首页，设计我的项目页', [], undefined,
      'conversation-1', undefined, 'local-codex', [picture.id])
    expect(wrapper.text()).toContain('看到了项目概览的层级。')
  })

  it('keeps the image and draft when the model is unavailable', async () => {
    api.list.mockResolvedValue([picture])
    api.status.mockResolvedValue({ configured: false, message: 'Runner 离线' })
    const wrapper = mount(ReferenceImagesView)
    await flushPromises()
    await wrapper.get('.image-card').trigger('click')
    await wrapper.get('textarea').setValue('帮我参考这张图')
    await wrapper.get('.compose-row button').trigger('click')
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toBe('帮我参考这张图')
    expect(wrapper.get('.image-card').attributes('aria-pressed')).toBe('true')
    expect(wrapper.text()).toContain('Runner 离线')
    expect(api.conversation).not.toHaveBeenCalled()
  })
})
