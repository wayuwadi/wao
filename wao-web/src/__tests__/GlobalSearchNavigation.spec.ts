import { beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import GlobalSearch from '@/components/overlays/GlobalSearch.vue'
import { DEFAULT_BUILTIN_MENUS } from '@/config/nav'
vi.mock('@/stores/data', () => ({ useDataStore: () => ({ ensureDocsLoaded: vi.fn(), projects: [], tasks: [], docs: {}, kbs: [] }) }))
vi.mock('@/api/navigationLayout', () => ({ fetchNavigationLayout: vi.fn().mockResolvedValue({ revision: 'none', layout: null }) }))
vi.mock('@/api/system', () => ({
  fetchBuiltinMenuRegistry: vi.fn(async () => ({ version: 1, menus: DEFAULT_BUILTIN_MENUS.map(menu => menu.id === 'projects' ? { ...menu, label: '研发空间', visible: false } : menu) })),
  fetchExternalAppRegistry: vi.fn(async () => ({ version: 1, apps: [] })),
}))
beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()) })
it('全局搜索可用原名找到隐藏且改名的功能，并用 Enter 打开原路由', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/notes')
  const wrapper = mount(GlobalSearch, { global: { plugins: [router] } })
  await flushPromises()
  await wrapper.get('input').setValue('项目')
  const result = wrapper.findAll('.result').find(item => item.text().includes('研发空间'))!
  expect(result.text()).toContain('未固定')
  await wrapper.get('input').setValue('研发空间')
  await wrapper.get('input').trigger('keydown', { key: 'Enter' })
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/projects')
  wrapper.unmount()
})
