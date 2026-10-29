import { beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import GlobalSearch from '@/components/overlays/GlobalSearch.vue'
import { DEFAULT_BUILTIN_MENUS } from '@/config/nav'
import { fetchSearchDocuments, fetchKbs } from '@/api/board'
import type { Doc, KnowledgeBase } from '@/types'
vi.mock('@/api/board', () => ({ fetchSearchDocuments: vi.fn(async () => []), fetchKbs: vi.fn(async () => []) }))
const searchData = vi.hoisted(() => ({ ensureDocsLoaded: vi.fn(), projects: [], tasks: [] as import('@/types').Task[], docs: {}, kbs: [], projectById: vi.fn() }))
vi.mock('@/stores/data', () => ({ useDataStore: () => searchData }))
vi.mock('@/api/navigationLayout', () => ({ fetchNavigationLayout: vi.fn().mockResolvedValue({ revision: 'none', layout: null }) }))
vi.mock('@/api/system', () => ({
  fetchBuiltinMenuRegistry: vi.fn(async () => ({ version: 1, menus: DEFAULT_BUILTIN_MENUS.map(menu => menu.id === 'projects' ? { ...menu, label: '研发空间', visible: false } : menu) })),
  fetchExternalAppRegistry: vi.fn(async () => ({ version: 1, apps: [] })),
}))
beforeEach(() => { searchData.tasks = []; localStorage.clear(); setActivePinia(createPinia()); vi.mocked(fetchSearchDocuments).mockResolvedValue([]); vi.mocked(fetchKbs).mockResolvedValue([]) })

it('搜索完整正文并区分小记，不泄漏目录外内容；组合输入不打开结果', async () => {
  const item = { id: 'note1', title: '旅行记录', blocks: [{ p: '很长的前文'.repeat(100) + '桂花路线在桥后' }], kbId: 'notes', links: [], backlinks: [] } as unknown as Doc
  vi.mocked(fetchSearchDocuments).mockResolvedValue([item, { ...item, id: 'outside', title: '不可见的桂花' }])
  vi.mocked(fetchKbs).mockResolvedValue([{ id: 'notes', name: '小记', notes: true, folders: [{ id: 'f', docs: ['note1'] }] } as KnowledgeBase])
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/todo')
  const w = mount(GlobalSearch, { global: { plugins: [router] } }); await flushPromises()
  await w.get('input').setValue('桂花')
  expect(w.findAll('.result')).toHaveLength(1)
  expect(w.get('.result').text()).toContain('小记 · 小记 · 正文命中')
  expect(w.get('mark').text()).toBe('桂花')
  expect(w.text()).not.toContain('不可见')
  await w.get('input').trigger('compositionstart')
  await w.get('input').trigger('keydown', { key: 'Enter', isComposing: true })
  expect(router.currentRoute.value.path).toBe('/todo')
  await w.get('input').trigger('compositionend')
  await w.get('input').trigger('keydown', { key: 'Enter' }); await flushPromises()
  expect(router.currentRoute.value.fullPath).toContain('/notes?note=note1&find=')
  expect(router.currentRoute.value.query.match).toBe('body')
  expect(searchData.docs).toEqual({})
  w.unmount()
})

it('正文读取期间和失败不伪报无匹配，重试后显示真实空结果', async () => {
  let reject!: (error: Error) => void
  vi.mocked(fetchSearchDocuments).mockImplementationOnce(() => new Promise((_resolve, fail) => { reject = fail }))
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/todo')
  const w = mount(GlobalSearch, { global: { plugins: [router] } })
  await w.get('input').setValue('没有这个词')
  expect(w.text()).toContain('正在读取可访问')
  expect(w.find('.empty').exists()).toBe(false)
  reject(new Error('断网')); await flushPromises()
  expect(w.text()).toContain('尚不能确认是否有匹配')
  expect(w.find('.empty').exists()).toBe(false)
  await w.findAll('button').find(b => b.text() === '重试正文读取')!.trigger('click'); await flushPromises()
  expect(w.get('.empty').text()).toContain('没有匹配结果')
  w.unmount()
})
it('全局搜索忽略旧全局改名并打开原路由', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/notes')
  const wrapper = mount(GlobalSearch, { global: { plugins: [router] } })
  await flushPromises()
  await wrapper.get('input').setValue('项目')
  const result = wrapper.findAll('.result').find(item => item.find('.r-title').text() === '项目')!
  expect(result.text()).not.toContain('研发空间')
  await result.trigger('click')
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
  expect(wrapper.text()).toContain('子任务标题命中')
  expect(wrapper.get('mark').text()).toBe('快捷键设计')
  await wrapper.get('.result').trigger('click')
  expect(useUiStore().openTaskId).toBe('t-find')
  expect(useUiStore().openTaskSubId).toBe('sub-find')
  wrapper.unmount()
})

it('任务正文命中显示原文并安全转义，点击不误开同词子任务', async () => {
  searchData.tasks = [{ id: 'body-find', title: '开发计划', project: 'p1', desc: '上线前检查 <img onerror=alert(1)> 桂花路线，再核对结果', status: '待办', subs: [{ id: 'sub-find', text: '桂花路线', done: false }] } as import('@/types').Task]
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/notes')
  const wrapper = mount(GlobalSearch, { global: { plugins: [router] } })
  await flushPromises()
  await wrapper.get('input').setValue('桂花路线')
  expect(wrapper.get('.result').text()).toContain('任务正文命中')
  expect(wrapper.get('mark').text()).toBe('桂花路线')
  expect(wrapper.get('.r-snippet').text()).toContain('上线前检查')
  expect(wrapper.find('.r-snippet img').exists()).toBe(false)
  await wrapper.get('.result').trigger('click')
  expect(useUiStore().openTaskId).toBe('body-find')
  expect(useUiStore().openTaskSubId).toBeNull()
  wrapper.unmount()
})

 it.each([
  ['文件库', '管理真实文件、图片和分享地址。', '/files'],
  ['团队', '管理项目、任务和成员', '/projects'],
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

it('切换成员丢弃迟到正文，完整结果可分页且标题命中进入正确文档', async () => {
  let resolveOld!: (docs: Doc[]) => void
  vi.mocked(fetchSearchDocuments).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
  const docs = Array.from({ length: 51 }, (_, n) => ({ id: `d${n}`, title: `银杏标题${n}`, blocks: [{ p: '普通内容' }], links: [], backlinks: [] } as unknown as Doc))
  vi.mocked(fetchKbs).mockResolvedValue([{ id: 'k', name: '资料', folders: [{ id: 'f', docs: [...docs.map(d => d.id), 'old'] }] } as KnowledgeBase])
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/todo')
  const w = mount(GlobalSearch, { global: { plugins: [router] } })
  vi.mocked(fetchSearchDocuments).mockResolvedValue(docs)
  useSessionStore().account = { member: { id: 'new' } } as import('@/api/auth').AuthSession
  await flushPromises()
  resolveOld([{ ...docs[0]!, id: 'old', title: '银杏旧成员内容' }]); await flushPromises()
  await w.get('input').setValue('银杏')
  expect(w.findAll('.result')).toHaveLength(50)
  expect(w.text()).not.toContain('旧成员')
  await w.findAll('button').find(b => b.text().startsWith('显示更多内容'))!.trigger('click')
  expect(w.findAll('.result')).toHaveLength(51)
  await w.findAll('.result')[50]!.trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/kb/k')
  expect(router.currentRoute.value.query).toEqual(expect.objectContaining({ doc: 'd50', find: '银杏', match: 'title' }))
  w.unmount()
})
