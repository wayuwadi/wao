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
const searchData = vi.hoisted(() => ({ ensureDocsLoaded: vi.fn(), files: [] as import('@/types').FileItem[], loadStates: { tasks: 'loaded', files: 'loaded', projects: 'loaded' }, taskLoadState: 'loaded', areaColor: () => '', tasksByProject: () => [], projects: [] as import('@/types').Project[], tasks: [] as import('@/types').Task[], docs: {}, kbs: [], projectById: vi.fn() }))
vi.mock('@/stores/data', () => ({ useDataStore: () => searchData }))
vi.mock('@/api/navigationLayout', () => ({ fetchNavigationLayout: vi.fn().mockResolvedValue({ revision: 'none', layout: null }) }))
vi.mock('@/api/system', () => ({
  fetchBuiltinMenuRegistry: vi.fn(async () => ({ version: 1, menus: DEFAULT_BUILTIN_MENUS.map(menu => menu.id === 'todo' ? { ...menu, label: '研发空间', visible: false } : menu) })),
  fetchExternalAppRegistry: vi.fn(async () => ({ version: 1, apps: [] })),
}))
beforeEach(() => { vi.clearAllMocks(); searchData.files = []; searchData.projects = []; searchData.tasks = []; localStorage.clear(); setActivePinia(createPinia()); vi.mocked(fetchSearchDocuments).mockResolvedValue([]); vi.mocked(fetchKbs).mockResolvedValue([]) })

it('搜索完整正文并区分小记，不泄漏目录外内容；组合输入不打开结果', async () => {
  const item = { id: 'note1', title: '旅行记录', blocks: [{ p: '很长的前文'.repeat(100) + '桂花路线在桥后' }], kbId: 'notes', links: [], backlinks: [] } as unknown as Doc
  vi.mocked(fetchSearchDocuments).mockResolvedValue([item, { ...item, id: 'outside', title: '不可见的桂花' }])
  vi.mocked(fetchKbs).mockResolvedValue([{ id: 'notes', name: '小记', notes: true, folders: [{ id: 'f', docs: ['note1'] }] } as KnowledgeBase])
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/todo')
  const w = mount(GlobalSearch, { global: { plugins: [router], stubs: { teleport: true } } }); await flushPromises()
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
  const w = mount(GlobalSearch, { global: { plugins: [router], stubs: { teleport: true } } })
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
  const wrapper = mount(GlobalSearch, { global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
  await wrapper.get('input').setValue('待办')
  const result = wrapper.findAll('.result').find(item => item.find('.r-title').text() === '待办')!
  expect(result.text()).not.toContain('研发空间')
  await result.trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/todo')
  wrapper.unmount()
})

it('全局搜索找到子任务并打开对应详情', async () => {
  searchData.tasks = [{ id: 't-find', title: '开发计划', project: 'p1', desc: '', status: '待办', subs: [{ id: 'sub-find', text: '快捷键设计', done: false }] } as import('@/types').Task]
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/notes')
  const wrapper = mount(GlobalSearch, { global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
  await wrapper.get('input').setValue('快捷键设计')
  expect(wrapper.findAll('.result')).toHaveLength(1)
  await wrapper.get('.result').trigger('click'); await flushPromises()
  expect(useUiStore().openTaskId).toBe('t-find')
  expect(useUiStore().openTaskSubId).toBe('sub-find')
  wrapper.unmount()
})

it('全局搜索匹配任务正文且不执行其中的 HTML', async () => {
  searchData.tasks = [{ id: 'body-find', title: '开发计划', project: 'p1', desc: '上线前检查 <img onerror=alert(1)> 桂花路线，再核对结果', status: '待办', subs: [{ id: 'sub-find', text: '桂花路线', done: false }] } as import('@/types').Task]
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/notes')
  const wrapper = mount(GlobalSearch, { global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
  await wrapper.get('input').setValue('桂花路线')
  expect(wrapper.findAll('.result')).toHaveLength(2)
  expect(wrapper.find('.r-snippet img').exists()).toBe(false)
  wrapper.unmount()
})

 it.each([
  ['文件库', '管理真实文件、图片和分享地址。', '/files'],
  ['清单', '集中处理个人待办', '/todo'],
  ['定时任务', '配置自动任务并查看运行结果', '/automation'],
 ])('日常叫法 %s 能找到用途明确的功能并用键盘打开', async (query, description, path) => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/navigation')
  const wrapper = mount(GlobalSearch, { global: { plugins: [router], stubs: { teleport: true } } })
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
  const w = mount(GlobalSearch, { global: { plugins: [router], stubs: { teleport: true } } })
  vi.mocked(fetchSearchDocuments).mockResolvedValue(docs)
  useSessionStore().account = { member: { id: 'new' } } as import('@/api/auth').AuthSession
  await flushPromises()
  resolveOld([{ ...docs[0]!, id: 'old', title: '银杏旧成员内容' }]); await flushPromises()
  await w.get('input').setValue('银杏')
  // Large document indexes deliberately yield between batches; wait for the visible result.
  await vi.waitFor(() => expect(w.findAll('.result')).toHaveLength(50))
  expect(w.text()).not.toContain('旧成员')
  await w.findAll('button').find(b => b.text().startsWith('显示更多内容'))!.trigger('click')
  expect(w.findAll('.result')).toHaveLength(51)
  await w.findAll('.result')[50]!.trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/kb/k')
  expect(router.currentRoute.value.query).toEqual(expect.objectContaining({ doc: 'd50', find: '银杏', match: 'title' }))
  w.unmount()
})

it('文件名称检索直达文件位置，范围筛选不混入任务', async () => {
  searchData.files = [{ id: 'f1', name: '计划.md', displayName: '季度计划', size: '1 KB', icon: 'ri-file-line' } as import('@/types').FileItem]
  searchData.tasks = [{ id: 't1', title: '季度计划', project: '', desc: '', subs: [] } as unknown as import('@/types').Task]
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/notes')
  const w = mount(GlobalSearch, { global: { plugins: [router], stubs: { teleport: true } } }); await flushPromises()
  await w.get('input').setValue('季度计划')
  expect(w.findAll('.result')).toHaveLength(2)
  await w.get('select').setValue('files')
  expect(w.findAll('.result')).toHaveLength(1)
  await w.get('input').trigger('keydown', { key: 'Enter' }); await flushPromises()
  expect(router.currentRoute.value.query.file).toBe('f1')
  w.unmount()
})
it('重复打开复用正文缓存；键盘选中自动滚动并在关闭后恢复焦点', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/todo')
  const trigger = document.createElement('button'); document.body.append(trigger); trigger.focus()
  const scroll = vi.fn(); HTMLElement.prototype.scrollIntoView = scroll
  const options = { attachTo: document.body, global: { plugins: [router], stubs: { teleport: true } } }
  const first = mount(GlobalSearch, options); await flushPromises(); first.unmount()
  expect(document.activeElement).toBe(trigger)
  const second = mount(GlobalSearch, options); await flushPromises()
  expect(fetchSearchDocuments).toHaveBeenCalledTimes(1)
  await second.get('input').trigger('keydown', { key: 'ArrowDown' }); await flushPromises()
  expect(scroll).toHaveBeenCalledWith({ block: 'nearest' })
  const buttons = second.findAll('button'); const last = buttons[buttons.length - 1]!
  last.element.focus(); await last.trigger('keydown', { key: 'Tab' })
  expect(document.activeElement).toBe(second.get('[aria-label="关闭搜索功能与内容"]').element)
  second.unmount(); trigger.remove()
})

it('输入法选择候选词时保留方向键和回车的默认行为', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/notes')
  const wrapper = mount(GlobalSearch, { global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
  await wrapper.get('input').setValue('待办')
  const input = wrapper.get('input').element
  for (const key of ['ArrowDown', 'ArrowUp', 'Enter']) {
    const event = new KeyboardEvent('keydown', { key, isComposing: true, bubbles: true, cancelable: true })
    input.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(false)
  }
  const legacyIme = new KeyboardEvent('keydown', { key: 'Enter', keyCode: 229, bubbles: true, cancelable: true })
  input.dispatchEvent(legacyIme)
  expect(legacyIme.defaultPrevented).toBe(false)
  expect(router.currentRoute.value.path).toBe('/notes')
  await wrapper.get('input').trigger('keydown', { key: 'Enter' }); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/todo')
  wrapper.unmount()
})

it('大量结果通过输入框方向键访问，Tab 不逐条穿越，改词和清除回到顶部', async () => {
  searchData.tasks = Array.from({ length: 70 }, (_, n) => ({ id: `t${n}`, title: `季度计划 ${n}`, project: '', desc: '', subs: [] } as unknown as import('@/types').Task))
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/notes')
  const wrapper = mount(GlobalSearch, { attachTo: document.body, global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
  await wrapper.get('select').setValue('tasks')
  await wrapper.get('input').setValue('季度')
  expect(wrapper.findAll('.result')).toHaveLength(50)
  expect(wrapper.findAll('.result').every(result => result.attributes('tabindex') === '-1')).toBe(true)
  await wrapper.get('input').trigger('keydown', { key: 'ArrowDown' })
  expect(wrapper.get('input').attributes('aria-activedescendant')).toBe('search-result-1')
  const list = wrapper.get<HTMLElement>('.results').element
  list.scrollTop = 400
  await wrapper.get('input').setValue('季度计划 6'); await flushPromises()
  expect(wrapper.get<HTMLElement>('.results').element.scrollTop).toBe(0)
  await wrapper.get('[aria-label="清除搜索关键词"]').trigger('click'); await flushPromises()
  expect(wrapper.get<HTMLInputElement>('input').element.value).toBe('')
  expect(document.activeElement).toBe(wrapper.get('input').element)
  expect(wrapper.get('select').element.value).toBe('tasks')
  expect(wrapper.get('.empty').text()).toContain('输入关键词开始搜索')
  wrapper.unmount()
})

it('范围内没有结果时可以扩大搜索，保留原关键词', async () => {
  searchData.files = [{ id: 'f1', name: '会议纪要.pdf', size: '1 KB' } as import('@/types').FileItem]
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/notes')
  const wrapper = mount(GlobalSearch, { attachTo: document.body, global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
  await wrapper.get('select').setValue('tasks')
  await wrapper.get('input').setValue('会议纪要')
  await wrapper.findAll('button').find(button => button.text() === '搜索全部内容')!.trigger('click')
  expect(wrapper.get<HTMLInputElement>('input').element.value).toBe('会议纪要')
  expect(document.activeElement).toBe(wrapper.get('input').element)
  expect(wrapper.get('.result').text()).toContain('会议纪要.pdf')
  wrapper.unmount()
})

it('输入法候选期间 Esc 不关闭搜索，组合输入结束后仍能正常关闭', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/notes')
  const close = vi.spyOn(useUiStore(), 'closeSearch')
  const wrapper = mount(GlobalSearch, { attachTo: document.body, global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
  await wrapper.get('input').trigger('compositionstart')
  const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
  wrapper.get('input').element.dispatchEvent(escape)
  expect(escape.defaultPrevented).toBe(false)
  expect(close).not.toHaveBeenCalled()
  await wrapper.get('input').trigger('compositionend')
  await wrapper.get('input').trigger('keydown', { key: 'Escape' })
  expect(close).toHaveBeenCalledOnce()
  wrapper.unmount()
})
