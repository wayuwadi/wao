import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import FilesView from '@/views/FilesView.vue'
import { useDataStore } from '@/stores/data'
import { fetchFileSettings } from '@/api/system'

vi.mock('@/api/system', () => ({
  fetchFileSettings: vi.fn<(typeof import('@/api/system'))['fetchFileSettings']>(),
  saveFileSettings: vi.fn<(typeof import('@/api/system'))['saveFileSettings']>(),
}))

describe('FilesView', () => {
  beforeEach(() => {
    vi.mocked(fetchFileSettings).mockResolvedValue({
      fileMaxMb: 12,
      imageMaxMb: 4,
      downloadCaptchaEnabled: false,
    })
  })

  it('展示真实上传限制并将网页托管默认分页为10条', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    data.hostedPages = Array.from({ length: 11 }, (_, index) => ({
      id: `page-${index + 1}`,
      slug: `page-${index + 1}`,
      title: `托管页 ${index + 1}`,
      fileName: `page-${index + 1}.html`,
      hasIcon: false,
      updatedAt: '2026-08-26T08:00:00',
      url: `/api/pages/page-${index + 1}`,
    }))
    const wrapper = mount(FilesView, {
      global: {
        plugins: [pinia],
        stubs: { DemoBanner: true, FileSettingsModal: true },
      },
    })
    await flushPromises()

    await wrapper.findAll('.seg-btn')[2]!.trigger('click')
    expect(wrapper.findAll('.file-row')).toHaveLength(10)
    expect(wrapper.text()).toContain('第 1 / 2 页')
    expect(wrapper.text()).not.toContain('托管页 11')

    await wrapper.find('.pagination').findAll('button')[1]!.trigger('click')
    expect(wrapper.findAll('.file-row')).toHaveLength(1)
    expect(wrapper.text()).toContain('托管页 11')

    await wrapper.findAll('.seg-btn')[0]!.trigger('click')
    expect(wrapper.text()).toContain('12MB')
    expect(wrapper.text()).toContain('一次性票据')
  })
})
