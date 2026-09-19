import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import * as preferenceApi from '@/api/favorites'
import QuickLinks from '@/components/nav/QuickLinks.vue'
import QuickLinkView from '@/views/QuickLinkView.vue'
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
  openMode: 'NEW_PAGE' as const,
  keywords: ['docs'],
}

describe('左下角快捷链接配置', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(preferenceApi.fetchPreference).mockResolvedValue('')
    vi.mocked(preferenceApi.savePreference).mockResolvedValue(undefined)
    setActivePinia(createPinia())
    document.body.innerHTML = ''
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
    expect(parseQuickLinksPreference(JSON.stringify({
      version: 2,
      links: [{ ...customLink, openMode: undefined }],
    }))[0]?.openMode).toBe('NEW_PAGE')
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
    const wrapper = mount(QuickLinks, { attachTo: document.body, global: { plugins: [router] } })
    await flushPromises()

    await wrapper.get('.ql-trigger').trigger('click')
    const jenkins = [...document.body.querySelectorAll<HTMLButtonElement>('.ql-item')]
      .find((item) => item.textContent?.includes('Jenkins 部署中心'))
    expect(jenkins).toBeDefined()
    jenkins!.click()

    expect(open).toHaveBeenCalledWith('/jenkins/job/wao-main-cd/', '_blank', 'noopener')
    wrapper.unmount()
  })

  it('TAB 模式从弹层进入 WAO 页签，并用真实成员链接渲染 iframe', async () => {
    const tabLink = { ...customLink, openMode: 'TAB' as const }
    const quickLinks = useQuickLinks()
    await quickLinks.save([tabLink])
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/quick-links/:linkId', component: QuickLinkView, meta: { tabKind: 'quick-link' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(QuickLinks, { attachTo: document.body, global: { plugins: [router] } })
    await flushPromises()

    await wrapper.get('.ql-trigger').trigger('click')
    const docs = [...document.body.querySelectorAll<HTMLButtonElement>('.ql-item')]
      .find((item) => item.textContent?.includes('文档站'))
    docs!.click()
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/quick-links/custom-docs')
    wrapper.unmount()

    const page = mount(QuickLinkView, { global: { plugins: [createPinia(), router] } })
    await flushPromises()
    expect(page.get('iframe').attributes('src')).toBe('https://docs.example.com/')
    expect(page.get('iframe').attributes('title')).toBe('文档站')
    await page.get('iframe').trigger('load')
    expect(page.text()).toContain('「文档站」页面已载入')
    await page.get('iframe').trigger('error')
    expect(page.get('[role="alert"]').text()).toContain('页面未能载入')
    await page.get('[role="alert"] button').trigger('click')
    expect(page.find('.overlay').exists()).toBe(true)
    page.unmount()
  })

  it('保存失败时不覆盖当前正在使用的入口', async () => {
    const quickLinks = useQuickLinks()
    const before = quickLinks.links.value.map((link) => link.id)
    vi.mocked(preferenceApi.savePreference).mockRejectedValueOnce(new Error('网络不可用'))

    await expect(quickLinks.save([customLink])).rejects.toThrow('网络不可用')
    expect(quickLinks.links.value.map((link) => link.id)).toEqual(before)
  })
})
