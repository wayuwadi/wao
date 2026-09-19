import { beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import GlobalSearch from '@/components/overlays/GlobalSearch.vue'
import { DEFAULT_BUILTIN_MENUS } from '@/config/nav'
const searchData = vi.hoisted(() => ({ ensureDocsLoaded: vi.fn(), projects: [], tasks: [] as import('@/types').Task[], docs: {}, kbs: [], projectById: vi.fn() }))
vi.mock('@/stores/data', () => ({ useDataStore: () => searchData }))
vi.mock('@/api/navigationLayout', () => ({ fetchNavigationLayout: vi.fn().mockResolvedValue({ revision: 'none', layout: null }) }))
vi.mock('@/api/system', () => ({
  fetchBuiltinMenuRegistry: vi.fn(async () => ({ version: 1, menus: DEFAULT_BUILTIN_MENUS.map(menu => menu.id === 'projects' ? { ...menu, label: '研发空间', visible: false } : menu) })),
  fetchExternalAppRegistry: vi.fn(async () => ({ version: 1, apps: [] })),
}))
beforeEach(() => { searchData.tasks = []; localStorage.clear(); setActivePinia(createPinia()) })
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

it('通过子任务原文找到父任务，点击直接展开匹配的子任务', async () => {
  searchData.tasks = [{ id: 't-find', title: '开发计划', project: 'p1', desc: '', status: '待办', subs: [{ id: 'sub-find', text: '快捷键设计', done: false }] } as import('@/types').Task]
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/notes')
  const wrapper = mount(GlobalSearch, { global: { plugins: [router] } })
  await flushPromises()
  await wrapper.get('input').setValue('快捷键设计')
  expect(wrapper.text()).toContain('正文或子任务匹配')
  await wrapper.get('.result').trigger('click')
  expect(useUiStore().openTaskId).toBe('t-find')
  expect(useUiStore().openTaskSubId).toBe('sub-find')
  wrapper.unmount()
})

 it.each([
  ['文件库', '管理真实文件、图片和分享地址。', '/files'],
  ['测试麦克风', '检测本机摄像头和麦克风', '/devices'],
  ['终端设备', '检测本机摄像头和麦克风', '/devices'],
  ['共享账号', '团队共用的平台账号', '/account-resources'],
  ['AI任务', '创建 AI 任务、查看执行结果', '/ai-sessions'],
  ['定时任务', '配置自动任务并查看运行结果', '/automation'],
 ])('日常叫法 %s 能找到用途明确的功能并用键盘打开', async (query, description, path) => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/navigation')
  const wrapper = mount(GlobalSearch, { global: { plugins: [router] } })
  await flushPromises()
  await wrapper.get('input').setValue(query)
  expect(wrapper.findAll('.result')).toHaveLength(1)
  expect(wrapper.get('.result').text()).toContain(description)
  await wrapper.get('input').trigger('keydown', { key: 'Enter' })
  await flushPromises()
  expect(router.currentRoute.value.path).toBe(path)
  wrapper.unmount()
 })
