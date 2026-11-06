import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import Workspace from '@/views/KnowledgeWorkspaceView.vue'
import { seedDataStore } from './helpers/seedDataStore'
import { useNavStore } from '@/stores/nav'
import { useSessionStore } from '@/stores/session'
import KbDetailView from '@/views/KbDetailView.vue'
import KnowledgeDirectoryTree from '@/components/knowledge/KnowledgeDirectoryTree.vue'
import type { Doc, KnowledgeBase } from '@/types'
vi.mock('@/views/KbDetailView.vue', () => ({ default: { template: '<textarea class="editor" />' } }))
enableAutoUnmount(afterEach)
describe('知识库工作区', () => {
  let mobile = false
  const mediaListeners = new Set<(event: MediaQueryListEvent) => void>()
  const originalMatchMedia = window.matchMedia
  beforeEach(() => {
    localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia())
    mobile = false; mediaListeners.clear()
    window.matchMedia = ((query: string) => ({
      get matches() { return query === '(max-width: 900px)' && mobile },
      media: query,
      addEventListener: (_: string, callback: (event: MediaQueryListEvent) => void) => { if (query === '(max-width: 900px)') mediaListeners.add(callback) },
      removeEventListener: (_: string, callback: (event: MediaQueryListEvent) => void) => mediaListeners.delete(callback),
    })) as unknown as typeof window.matchMedia
  })
  afterEach(() => { vi.unstubAllGlobals(); window.matchMedia = originalMatchMedia; document.body.innerHTML = '' })
  async function resize(narrow: boolean) {
    mobile = narrow
    mediaListeners.forEach(callback => callback({ matches: narrow } as MediaQueryListEvent))
    await flushPromises()
  }
  async function setup() {
    const data = seedDataStore()
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path:'/knowledge', component:Workspace }] })
    await router.push('/knowledge')
    const wrapper = mount(Workspace, { attachTo: document.body, global:{ plugins:[router], stubs:{ KnowledgeIconPicker:true, teleport:true } } })
    return { data, router, wrapper }
  }
  it('首页汇总目录，选择文档后同一编辑器在清单折叠时保留', async () => {
    const { wrapper, router } = await setup()
    expect(wrapper.find('.home-directory').exists()).toBe(true)
    expect(wrapper.find('.knowledge-home').exists()).toBe(true)
    await wrapper.find('.index-row button').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.query.doc).toBeTruthy()
    const editor = wrapper.get('.editor').element
    await wrapper.get('[aria-label="收起知识库清单"]').trigger('click')
    expect(wrapper.get('.editor').element).toBe(editor)
    await wrapper.get('[aria-label="展开知识库清单"]').trigger('click')
    expect(wrapper.get('.editor').element).toBe(editor)
    wrapper.unmount()
  })
  it('创建失败保留输入，关闭不产生知识库', async () => {
    const { wrapper, data } = await setup()
    const add = vi.spyOn(data, 'addKb').mockRejectedValue(new Error('offline'))
    await wrapper.get('[aria-label="新增知识库"]').trigger('click')
    await wrapper.get('input[required]').setValue('测试知识库')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('输入已保留')
    expect((wrapper.get('input[required]').element as HTMLInputElement).value).toBe('测试知识库')
    expect(add).toHaveBeenCalledTimes(1)
    await wrapper.get('[aria-label="关闭新增知识库"]').trigger('click')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(add).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
  it('读取中、失败、真实空库与搜索无结果互不混淆', async () => {
    const { wrapper, data } = await setup()
    data.kbs = []; data.loadStates.knowledge = 'loading'; await flushPromises()
    expect(wrapper.text()).toContain('正在读取知识库')
    expect(wrapper.text()).not.toContain('还没有知识库')
    data.loadStates.knowledge = 'error'; await flushPromises()
    expect(wrapper.text()).toContain('知识库读取失败')
    expect(wrapper.text()).not.toContain('还没有知识库')
    const retry = vi.spyOn(data, 'retryDomain').mockImplementation(async () => { data.loadStates.knowledge = 'loaded'; return true })
    await wrapper.get('[role="alert"] button').trigger('click'); await flushPromises()
    expect(retry).toHaveBeenCalledWith('knowledge')
    expect(wrapper.text()).toContain('还没有知识库')
    await wrapper.get('input[type="search"]').setValue('不存在')
    expect(wrapper.text()).toContain('没有匹配的内容')
    await wrapper.findAll('button').find(button => button.text() === '清除搜索')!.trigger('click')
    expect(wrapper.text()).toContain('还没有知识库')
    wrapper.unmount()
  })
  it('新用户主菜单默认收起，保留已存展开偏好', () => {
    expect(useNavStore().collapsed).toBe(true)
    setActivePinia(createPinia())
    localStorage.setItem('wao-nav-v1', JSON.stringify({ collapsed:false, foldedGroups:{} }))
    expect(useNavStore().collapsed).toBe(false)
  })
  it('名称搜索不区分大小写，标题草稿参与检索且不解析正文', async () => {
    const { wrapper, data } = await setup()
    const library = data.kbs.find(kb => !kb.notes)!
    library.name = 'TEAM Handbook'
    const docId = library.folders.flatMap(folder => folder.docs)[0]!
    data.docEdits[docId] = { title: 'Draft Guide', text: '草稿正文' }
    const resolveBody = vi.spyOn(data, 'docOf')
    await wrapper.get('input[type="search"]').setValue('team')
    expect(wrapper.get('.index-library .library-title').text()).toContain('TEAM Handbook')
    expect(wrapper.get('.index-library').text()).toContain('Draft Guide')
    await wrapper.get('input[type="search"]').setValue('draft guide')
    expect(wrapper.findAll('.index-row button')).toHaveLength(1)
    expect(wrapper.get('.index-row button').text()).toContain('Draft Guide')
    expect(resolveBody).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('最近更新跨库按时间展示，切换展示不改写目录顺序', async () => {
    const { wrapper, data } = await setup()
    const library = data.kbs.find(kb => !kb.notes)!
    const ids = library.folders.flatMap(folder => folder.docs)
    const first = ids[0]!, last = ids[ids.length - 1]!
    data.docs[first]!.updated = '2020-01-01T10:00:00+08:00'
    data.docs[last]!.updated = '2099-01-01T10:00:00+08:00'
    const before = JSON.stringify(library.folders)
    await wrapper.findAll('.home-views button')[1]!.trigger('click')
    expect(wrapper.get('.recent-row').text()).toContain(data.docs[last]!.title)
    expect(JSON.stringify(library.folders)).toBe(before)
    await wrapper.findAll('.home-views button')[0]!.trigger('click')
    expect(wrapper.find('.index-library').exists()).toBe(true)
    wrapper.unmount()
  })
  it('大型首页分批渲染，但搜索仍覆盖尚未显示的文档', async () => {
    const { wrapper, data } = await setup()
    const ids = Array.from({ length: 10000 }, (_, i) => `large-${i}`)
    const library: KnowledgeBase = { id: 'large', name: '大型知识库', desc: '', icon: 'ri-book-line', color: '', project: null, folders: [{ id: 'large-folder', name: '文档', docs: ids }] }
    data.kbs = [library]
    for (const [index, id] of ids.entries()) data.docs[id] = { id, title: `记录 ${index}`, author: '', created: '', updated: '', links: [], blocks: [], backlinks: [], bodyLoaded: false } satisfies Doc
    await flushPromises()
    expect(wrapper.findAll('.index-row')).toHaveLength(80)
    await wrapper.get('.show-more').trigger('click')
    expect(wrapper.findAll('.index-row')).toHaveLength(180)
    await wrapper.get('input[type="search"]').setValue('记录 9999')
    expect(wrapper.get('.index-row button').text()).toContain('记录 9999')
    expect(wrapper.find('.show-more').exists()).toBe(false)
    wrapper.unmount()
  })
  it('专注状态隐藏知识库清单，退出恢复偏好且不重建编辑器', async () => {
    const { wrapper } = await setup()
    await wrapper.find('.index-row button').trigger('click'); await flushPromises()
    const editor = wrapper.get('.editor').element
    wrapper.findComponent(KbDetailView).vm.$emit('focus-change', true); await flushPromises()
    expect(wrapper.get('.libraries').attributes('style')).toContain('display: none')
    expect(wrapper.get('.editor').element).toBe(editor)
    wrapper.findComponent(KbDetailView).vm.$emit('focus-change', false); await flushPromises()
    expect(wrapper.get('.libraries').attributes('style') || '').not.toContain('display: none')
    expect(wrapper.get('.editor').element).toBe(editor)
    wrapper.unmount()
  })
  it('手机导航默认收起，两个浮层互斥，打开文档后关闭', async () => {
    mobile = true
    const { wrapper } = await setup()
    await flushPromises()
    expect(wrapper.get('.libraries').classes()).toContain('collapsed')
    expect(wrapper.get('.home-directory').classes()).toContain('collapsed')
    await wrapper.get('.mobile-workspace-bar [aria-label="选择知识库"]').trigger('click')
    expect(wrapper.get('.libraries').classes()).not.toContain('collapsed')
    await wrapper.get('[aria-label="查看全部目录"]').trigger('click')
    expect(wrapper.get('.libraries').classes()).toContain('collapsed')
    expect(wrapper.get('.home-directory').classes()).not.toContain('collapsed')
    await wrapper.get('.mobile-backdrop').trigger('click')
    expect(wrapper.find('.mobile-backdrop').exists()).toBe(false)
    await wrapper.find('.index-row button').trigger('click'); await flushPromises()
    expect(wrapper.find('.editor').exists()).toBe(true)
    expect(wrapper.find('.mobile-backdrop').exists()).toBe(false)
    wrapper.unmount()
  })
  it('Ctrl J聚焦首页检索，刷新已有数据时保留可用索引', async () => {
    const { wrapper, data } = await setup()
    const focus = vi.spyOn(wrapper.get('input[type="search"]').element as HTMLInputElement, 'focus')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'j', ctrlKey: true, cancelable: true }))
    await flushPromises()
    expect(focus).toHaveBeenCalledOnce()
    data.loadStates.knowledge = 'loading'; await flushPromises()
    expect(wrapper.find('.index-library').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('正在读取知识库…')
    wrapper.unmount()
  })
  it('正文草稿更新不重建目录元数据，标题变化及时同步', async () => {
    const { wrapper, data } = await setup()
    const library = data.kbs.find(kb => !kb.notes)!
    const docId = library.folders.flatMap(folder => folder.docs)[0]!
    const title = data.docs[docId]!.title
    const firstTree = wrapper.findComponent(KnowledgeDirectoryTree)
    const originalRows = firstTree.props('nodes')
    data.docEdits = { ...data.docEdits, [docId]: { title, text: '正文正在输入' } }
    await flushPromises()
    expect(firstTree.props('nodes')).toBe(originalRows)
    data.docEdits = { ...data.docEdits, [docId]: { title: '新标题', text: '正文继续输入' } }
    await flushPromises()
    expect(firstTree.props('nodes')).not.toBe(originalRows)
    expect(wrapper.get('.index-library').text()).toContain('新标题')
    wrapper.unmount()
  })
  it('创建期间切换账号后关闭原表单，迟到回执不导航到原账号知识库', async () => {
    const { wrapper, data, router } = await setup()
    const oldLibrary = { ...data.kbs[0]!, id: 'old-account-library' }
    let finish!: (value: KnowledgeBase) => void
    vi.spyOn(data, 'addKb').mockImplementation(() => new Promise(resolve => { finish = resolve }))
    await wrapper.get('[aria-label="新增知识库"]').trigger('click')
    await wrapper.get('input[required]').setValue('原账号的名称')
    await wrapper.get('form').trigger('submit')
    useSessionStore().$patch({ account: { accountId: 'B', member: { id: 'B' } } } as never)
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    finish(oldLibrary); await flushPromises()
    expect(router.currentRoute.value.query.kb).toBeUndefined()
    await wrapper.get('[aria-label="新增知识库"]').trigger('click')
    expect((wrapper.get('input[required]').element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })
  it('撤销知识库可见权限后清除其索引、最近更新与侧栏条目', async () => {
    const { wrapper, data } = await setup()
    const library = data.kbs.find(kb => !kb.notes)!
    library.name = '撤权前可见库'
    await flushPromises()
    expect(wrapper.get('.library-list').text()).toContain('撤权前可见库')
    data.kbs = data.kbs.filter(kb => kb.id !== library.id)
    await flushPromises()
    expect(wrapper.text()).not.toContain('撤权前可见库')
    await wrapper.findAll('.home-views button')[1]!.trigger('click')
    expect(wrapper.text()).not.toContain('撤权前可见库')
    wrapper.unmount()
  })
  it('快速切库保留编辑器实例，撤权后退出专注恢复导航入口', async () => {
    const { wrapper, data, router } = await setup()
    const ids = data.kbs.filter(kb => !kb.notes).map(kb => kb.id)
    await router.push({ path: '/knowledge', query: { kb: ids[0], doc: 'first-doc' } }); await flushPromises()
    const editor = wrapper.get('.editor').element
    wrapper.findComponent(KbDetailView).vm.$emit('focus-change', true); await flushPromises()
    await router.push({ path: '/knowledge', query: { kb: ids[1] || ids[0], doc: 'second-doc' } }); await flushPromises()
    expect(wrapper.get('.editor').element).toBe(editor)
    wrapper.findComponent(KbDetailView).vm.$emit('focus-change', true); await flushPromises()
    data.kbs = []
    await flushPromises()
    expect(wrapper.get('.libraries').attributes('style') || '').not.toContain('display: none')
    expect(wrapper.get('.editor').element).toBe(editor)
    wrapper.unmount()
  })
  it('创建期间离开工作区后，成功回执不把用户拉回新知识库', async () => {
    const { wrapper, data, router } = await setup()
    let finish!: (value: KnowledgeBase) => void
    vi.spyOn(data, 'addKb').mockImplementation(() => new Promise(resolve => { finish = resolve }))
    await wrapper.get('[aria-label="新增知识库"]').trigger('click')
    await wrapper.get('input[required]').setValue('后台完成的创建')
    await wrapper.get('form').trigger('submit')
    wrapper.unmount()
    finish({ ...data.kbs[0]!, id: 'created-after-leaving' }); await flushPromises()
    expect(router.currentRoute.value.query.kb).toBeUndefined()
  })
  it.each([false, true])('切换账号后旧创建回执不导航、不关闭当前新表单（再切回：%s）', async returnToOriginal => {
    const session = useSessionStore()
    const login = (accountId: string) => { session.account = { accountId, member: { id: 'same-member' } } as never }
    login('first')
    const { wrapper, data, router } = await setup()
    const created = { ...data.kbs[0]!, id: 'old-created', name: '旧账号新建知识库' }
    let finish!: (kb: typeof created) => void
    vi.spyOn(data, 'addKb').mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    await wrapper.get('[aria-label="新增知识库"]').trigger('click')
    await wrapper.get('input[required]').setValue('旧账号新建知识库')
    await wrapper.get('form').trigger('submit')
    login('second'); if (returnToOriginal) login('first')
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    await wrapper.get('[aria-label="新增知识库"]').trigger('click')
    await wrapper.get('input[required]').setValue('当前表单输入')
    finish(created); await flushPromises()
    expect(router.currentRoute.value.query.kb).toBeUndefined()
    expect(wrapper.get<HTMLInputElement>('input[required]').element.value).toBe('当前表单输入')
    expect(wrapper.get('form button[type="submit"]').attributes('disabled')).toBeUndefined()
  })
  it('清除无结果的搜索后回到搜索框，继续输入无需重新定位', async () => {
    const { wrapper, data } = await setup()
    data.loadStates.knowledge = 'loaded'
    const input = wrapper.get<HTMLInputElement>('input[type="search"]')
    await input.setValue('查不到的知识库内容')
    const clear = wrapper.findAll('button').find(button => button.text() === '清除搜索')!
    ;(clear.element as HTMLButtonElement).focus()
    await clear.trigger('click'); await flushPromises()
    expect(input.element.value).toBe('')
    expect(wrapper.text()).not.toContain('没有匹配的内容')
    expect(document.activeElement).toBe(input.element)
  })
  it('库名与文档搜索都忽略大小写，空库仍能按名称找到', async () => {
    const { wrapper, data } = await setup()
    data.kbs = [{ ...data.kbs[0]!, name: 'WAO Docs', notes: false, folders: [] }]
    await wrapper.get('input[type="search"]').setValue('  wao  ')
    expect(wrapper.findAll('.index-library')).toHaveLength(1)
    expect(wrapper.get('.index-library').text()).toContain('WAO Docs')
    expect(wrapper.text()).not.toContain('没有匹配的内容')
  })
  it('窄屏默认让出正文，两种目录互斥，Escape 与遮罩关闭后恢复入口焦点', async () => {
    mobile = true
    const { wrapper } = await setup()
    expect(wrapper.get('.libraries').isVisible()).toBe(false)
    expect(wrapper.get('.home-directory').isVisible()).toBe(false)
    expect(wrapper.find('.navigation-mask').exists()).toBe(false)
    const libraries = wrapper.get('.mobile-toolbar button:first-child')
    const directory = wrapper.get('.mobile-toolbar button:nth-child(2)')
    await libraries.trigger('click'); await flushPromises()
    expect(wrapper.get('.libraries').isVisible()).toBe(true)
    expect(wrapper.get('.home-directory').isVisible()).toBe(false)
    expect(wrapper.get('.workspace-content').attributes('inert')).toBeDefined()
    expect(document.activeElement).toBe(wrapper.get('.libraries .pane-toggle').element)
    await wrapper.get('.libraries .pane-toggle').trigger('keydown', { key: 'Tab', shiftKey: true })
    const libraryButtons = wrapper.findAll('.library-list button')
    expect(document.activeElement).toBe(libraryButtons[libraryButtons.length - 1]!.element)
    await directory.trigger('click'); await flushPromises()
    expect(wrapper.get('.libraries').isVisible()).toBe(false)
    expect(wrapper.get('.home-directory').isVisible()).toBe(true)
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(wrapper.get('.home-directory').isVisible()).toBe(false)
    expect(document.activeElement).toBe(directory.element)
    await libraries.trigger('click'); await wrapper.get('.navigation-mask').trigger('click'); await flushPromises()
    expect(wrapper.get('.libraries').isVisible()).toBe(false)
    expect(document.activeElement).toBe(libraries.element)
  })
  it('窄屏选择后收起抽屉，折叠和调整屏幕不重挂编辑器也不覆盖桌面偏好', async () => {
    const { wrapper, router } = await setup()
    await wrapper.get('[aria-label="收起知识库目录"]').trigger('click')
    const saved = Object.fromEntries(Object.keys(sessionStorage).filter(key => key.startsWith('wao-task-view-v1:')).map(key => [key, sessionStorage.getItem(key)]))
    await resize(true)
    expect(wrapper.get('.libraries').isVisible()).toBe(false)
    expect(wrapper.get('.home-directory').isVisible()).toBe(false)
    await wrapper.get('.mobile-toolbar button:first-child').trigger('click')
    await wrapper.findAll('.library-list button')[1]!.trigger('click'); await flushPromises()
    expect(router.currentRoute.value.query.kb).toBeTruthy()
    expect(wrapper.get('.libraries').isVisible()).toBe(false)
    const editor = wrapper.get('.editor').element
    await wrapper.get('.mobile-toolbar button:first-child').trigger('click')
    await wrapper.get('[aria-label="收起知识库清单"]').trigger('click')
    expect(wrapper.get('.editor').element).toBe(editor)
    await resize(false)
    expect(wrapper.get('.editor').element).toBe(editor)
    expect(wrapper.get('.libraries').classes()).not.toContain('collapsed')
    for (const [key, value] of Object.entries(saved)) expect(sessionStorage.getItem(key)).toBe(value)
    await router.push('/knowledge'); await flushPromises()
    expect(wrapper.get('.home-directory').classes()).toContain('collapsed')
    await resize(true)
    await wrapper.get('.mobile-toolbar button:nth-child(2)').trigger('click')
    await wrapper.get('.tree-node').trigger('click')
    const doc = wrapper.findAll('.tree-node').find(node => node.find('.ri-file-text-line').exists())!
    await doc.trigger('click'); await flushPromises()
    expect(router.currentRoute.value.query.doc).toBeTruthy()
    expect(wrapper.find('.navigation-mask').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('.mobile-toolbar button:first-child').element)
  })
})

