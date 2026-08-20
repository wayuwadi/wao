import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ShareDocView from '@/views/ShareDocView.vue'
import { apiShareDoc } from '@/api/board'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'd-sized' } }),
}))

vi.mock('@/api/board', () => ({
  apiShareDoc: vi.fn<(typeof import('@/api/board'))['apiShareDoc']>(),
}))

describe('ShareDocView', () => {
  beforeEach(() => vi.clearAllMocks())

  it('公开分享页按服务端 Markdown 中保存的图片尺寸展示', async () => {
    vi.mocked(apiShareDoc).mockResolvedValue({
      id: 'd-sized',
      title: '带尺寸的文档',
      note: false,
      kbId: 'k1',
      updated: '2026-08-20T12:00:00',
      md: '![架构图](/api/images/img1/raw "wao-image-size:75%xauto")',
    })

    const wrapper = mount(ShareDocView)
    await flushPromises()

    expect(apiShareDoc).toHaveBeenCalledWith('d-sized')
    expect(wrapper.get('.doc-body img').attributes('style')).toBe('width:75%;height:auto')
    expect(wrapper.get('.doc-body img').attributes('title')).toBeUndefined()
    wrapper.unmount()
  })
})
