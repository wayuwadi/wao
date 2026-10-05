import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { expect, it, vi } from 'vitest'
import ClientDownloadView from '@/views/ClientDownloadView.vue'
const downloads = vi.hoisted(() => ({ fetchMobileDownloads: vi.fn(), fetchWindowsDownload: vi.fn(), fetchMacosDownload: vi.fn() }))
vi.mock('@/api/clientDownloads', () => downloads)
async function render(hash = '') {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/download', component: ClientDownloadView }, { path: '/download/archive', component: { template: '<div>历史包</div>' } },
  ] })
  await router.push('/download' + hash)
  const wrapper = mount(ClientDownloadView, { global: { plugins: [router], stubs: { SiteFooter: true, WaoLogo: true } } }); await flushPromises()
  return { wrapper, router }
}
it('默认引导浏览器，不加载安装包接口，历史入口仍可打开', async () => {
  const { wrapper, router } = await render()
  expect(wrapper.get('.primary').attributes('href')).toBe('/')
  expect(downloads.fetchMobileDownloads).not.toHaveBeenCalled(); expect(downloads.fetchWindowsDownload).not.toHaveBeenCalled(); expect(downloads.fetchMacosDownload).not.toHaveBeenCalled()
  await wrapper.get('.history a').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/download/archive'); wrapper.unmount()
})
it('保留旧平台与安装说明书签的目的地', async () => {
  for (const hash of ['#mobile', '#desktop', '#install', '#mac-install']) {
    const { wrapper, router } = await render(hash)
    expect(router.currentRoute.value.fullPath).toBe('/download/archive' + hash); wrapper.unmount()
  }
})
