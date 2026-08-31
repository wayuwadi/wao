import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import CrawlerCapabilitiesView from '@/views/CrawlerCapabilitiesView.vue'
import type { CrawlerCapability, SaveCrawlerCapability } from '@/api/crawlerCapabilities'

const api = vi.hoisted(() => ({
  fetchCrawlerCapabilities: vi.fn<() => Promise<CrawlerCapability[]>>(),
  createCrawlerCapability: vi.fn<(body: SaveCrawlerCapability) => Promise<CrawlerCapability>>(),
  updateCrawlerCapability:
    vi.fn<(id: string, body: SaveCrawlerCapability) => Promise<CrawlerCapability>>(),
  deleteCrawlerCapability: vi.fn<(id: string, revision: number) => Promise<null>>(),
}))

vi.mock('@/api/crawlerCapabilities', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/crawlerCapabilities')>()),
  ...api,
}))

vi.mock('@/composables/toast', () => ({
  toast: vi.fn<(message: string, icon?: string) => void>(),
}))

function capability(): CrawlerCapability {
  return {
    id: 'cc1',
    name: '公开页面文章采集',
    description: '读取公开文章并返回标题与正文',
    language: 'PYTHON',
    sourceCode: "print('real')\nprint('done')",
    enabled: true,
    revision: 2,
    createdBy: 'wg',
    updatedBy: 'wg',
    createdAt: '2026-08-31T10:00:00',
    updatedAt: '2026-08-31T11:00:00',
  }
}

describe('CrawlerCapabilitiesView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('展示真实持久化能力并明确不直接执行源码', async () => {
    api.fetchCrawlerCapabilities.mockResolvedValueOnce([capability()])
    const wrapper = mount(CrawlerCapabilitiesView)
    await flushPromises()

    expect(wrapper.text()).toContain('公开页面文章采集')
    expect(wrapper.text()).toContain('2 行源码')
    expect(wrapper.text()).toContain('不会在 WAO 服务端直接执行')
    expect(wrapper.text()).not.toContain("print('real')")
  })

  it('空数据时可以新增并在服务端成功后更新列表', async () => {
    api.fetchCrawlerCapabilities.mockResolvedValueOnce([])
    api.createCrawlerCapability.mockResolvedValueOnce(capability())
    const wrapper = mount(CrawlerCapabilitiesView)
    await flushPromises()

    expect(wrapper.text()).toContain('还没有爬虫能力')
    await wrapper.find('.state-card button').trigger('click')
    await wrapper.find('input[placeholder*="公开页面"]').setValue('公开页面文章采集')
    await wrapper.find('.code-field textarea').setValue("print('real')")
    await wrapper.find('.editor').trigger('submit')
    await flushPromises()

    expect(api.createCrawlerCapability).toHaveBeenCalledWith(
      expect.objectContaining({ name: '公开页面文章采集', sourceCode: "print('real')" }),
    )
    expect(wrapper.find('.editor').exists()).toBe(false)
    expect(wrapper.text()).toContain('公开页面文章采集')
  })

  it('保存失败时保留输入与本机草稿并提供重试状态', async () => {
    api.fetchCrawlerCapabilities.mockResolvedValueOnce([])
    api.createCrawlerCapability.mockRejectedValueOnce(new Error('服务暂不可用'))
    const wrapper = mount(CrawlerCapabilitiesView)
    await flushPromises()

    await wrapper.find('.state-card button').trigger('click')
    await wrapper.find('input[placeholder*="公开页面"]').setValue('未保存能力')
    await wrapper.find('.code-field textarea').setValue("print('draft')")
    await wrapper.find('.editor').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.save-error').text()).toContain('服务暂不可用')
    expect((wrapper.find('.code-field textarea').element as HTMLTextAreaElement).value).toBe(
      "print('draft')",
    )
    expect(localStorage.getItem('wao-crawler-capability-draft:new')).toContain('未保存能力')
    expect(wrapper.find('.editor .primary').text()).toContain('重试保存')
  })

  it('编辑时携带 revision，避免覆盖其他成员的新版本', async () => {
    api.fetchCrawlerCapabilities.mockResolvedValueOnce([capability()])
    api.updateCrawlerCapability.mockResolvedValueOnce({ ...capability(), revision: 3 })
    const wrapper = mount(CrawlerCapabilitiesView)
    await flushPromises()

    await wrapper.find('.capability-card footer button').trigger('click')
    await wrapper.find('input[placeholder*="公开页面"]').setValue('更新后的能力')
    await wrapper.find('.editor').trigger('submit')
    await flushPromises()

    expect(api.updateCrawlerCapability).toHaveBeenCalledWith(
      'cc1',
      expect.objectContaining({ revision: 2, name: '更新后的能力' }),
    )
  })
})
