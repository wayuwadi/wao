import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MaterialLibrary from './MaterialLibrary.vue'
import { listMedia, uploadMedia } from '@/api/publish'
import type { MediaAsset } from '@/types'

vi.mock('@/api/publish', () => ({
  listMedia: vi.fn(),
  uploadMedia: vi.fn(),
}))

const asset: MediaAsset = {
  id: 'asset-1',
  kind: 'IMAGE',
  originalName: '真实封面.png',
  contentType: 'image/png',
  byteSize: 2048,
  createdAt: '2026-08-27T10:00:00',
}

describe('素材管理', () => {
  beforeEach(() => vi.clearAllMocks())

  it('展示当前工作区真实素材，上传后刷新持久化列表', async () => {
    vi.mocked(listMedia).mockResolvedValueOnce([]).mockResolvedValueOnce([asset])
    vi.mocked(uploadMedia).mockResolvedValue(asset)
    const wrapper = mount(MaterialLibrary)
    await flushPromises()
    expect(wrapper.text()).toContain('还没有素材')

    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [new File(['image'], '真实封面.png', { type: 'image/png' })],
    })
    await input.trigger('change')
    await flushPromises()

    expect(uploadMedia).toHaveBeenCalledWith(expect.objectContaining({ name: '真实封面.png' }))
    expect(wrapper.text()).toContain('已上传「真实封面.png」')
    expect(wrapper.text()).toContain('2.0 KB')
    expect(listMedia).toHaveBeenCalledTimes(2)
  })
})
