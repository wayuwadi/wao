import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import * as preferenceApi from '@/api/favorites'
import QuickLinks from '@/components/nav/QuickLinks.vue'
import {
  canonicalizeQuickLinks,
  parseQuickLinksPreference,
  useQuickLinks,
} from '@/composables/quickLinks'

vi.mock('@/api/favorites', () => ({
  fetchPreference: vi.fn<() => Promise<string>>(),
  savePreference: vi.fn<() => Promise<void>>(),
}))

const customLink = {
  id: 'custom-docs',
  label: '文档站',
  subtitle: '内部文档入口',
  icon: 'ri-book-line',
  url: 'https://docs.example.com/',
  keywords: ['docs'],
}

describe('左下角快捷链接配置', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(preferenceApi.fetchPreference).mockResolvedValue('')
    vi.mocked(preferenceApi.savePreference).mockResolvedValue(undefined)
  })

  it('默认提供桌面宠物和 Jenkins，不再依赖业务页面', () => {
    const links = parseQuickLinksPreference('')
    expect(links).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: 'wao-desktop-pet', url: '/desktop' }),
      expect.objectContaining({ id: 'wao-jenkins', url: '/jenkins/job/wao-main-cd/' }),
    ]))
  })

  it('旧数组配置只迁移一次，v2 中用户明确删除后不会恢复默认项', () => {
    const migrated = parseQuickLinksPreference(JSON.stringify([customLink]))
    expect(migrated.map((link) => link.id)).toEqual([
      'custom-docs',
      'wao-desktop-pet',
      'wao-jenkins',
    ])

    const current = parseQuickLinksPreference(JSON.stringify({ version: 2, links: [customLink] }))
    expect(current.map((link) => link.id)).toEqual(['custom-docs'])
  })

  it('拒绝重复标识和非 HTTPS 外部地址', () => {
    expect(() => canonicalizeQuickLinks([customLink, customLink])).toThrow('不能重复')
    expect(() => canonicalizeQuickLinks([{ ...customLink, url: 'javascript:alert(1)' }]))
      .toThrow('HTTPS')
  })

  it('真实弹层展示 Jenkins，并以新窗口打开受保护公网路径', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/settings', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const wrapper = mount(QuickLinks, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.get('.ql-trigger').trigger('click')
    const jenkins = wrapper.findAll('.ql-item').find((item) => item.text().includes('Jenkins 部署中心'))
    expect(jenkins).toBeDefined()
    await jenkins!.trigger('click')

    expect(open).toHaveBeenCalledWith('/jenkins/job/wao-main-cd/', '_blank', 'noopener')
    wrapper.unmount()
  })

  it('保存失败时不覆盖当前正在使用的入口', async () => {
    const quickLinks = useQuickLinks()
    const before = quickLinks.links.value.map((link) => link.id)
    vi.mocked(preferenceApi.savePreference).mockRejectedValueOnce(new Error('网络不可用'))

    await expect(quickLinks.save([customLink])).rejects.toThrow('网络不可用')
    expect(quickLinks.links.value.map((link) => link.id)).toEqual(before)
  })
})
