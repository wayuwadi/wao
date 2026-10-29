import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent } from 'vue'
import KbDetailView from '@/views/KbDetailView.vue'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import { useSessionStore } from '@/stores/session'
import { useTabsStore } from '@/stores/tabs'
import { useUiStore } from '@/stores/ui'
import { apiMoveKnowledgeNode, apiPatchDocTemplate, fetchDocTemplates, fetchDoc } from '@/api/board'

vi.mock('@/api/board', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/board')>()
  return {
    ...actual,
    apiMoveKnowledgeNode: vi.fn<typeof actual.apiMoveKnowledgeNode>(),
    fetchDoc: vi.fn<typeof actual.fetchDoc>(),
    fetchDocTemplates: vi.fn<typeof actual.fetchDocTemplates>(),
    apiPatchDocTemplate: vi.fn<typeof actual.apiPatchDocTemplate>(),
    apiDocPresence: vi.fn<typeof actual.apiDocPresence>().mockResolvedValue({
      rev: 0,
      peers: [],
      doc: null,
    }),
    apiLeaveDoc: vi.fn<typeof actual.apiLeaveDoc>().mockResolvedValue(undefined),
  }
})

const MarkdownEditorStub = defineComponent({
  name: 'MarkdownEditor',
  props: { modelValue: { type: String, default: '' }, readonly: Boolean },
  emits: ['update:modelValue'],
  setup(_props, { emit }) {
    return {
      input: (event: Event) =>
        emit('update:modelValue', (event.target as HTMLTextAreaElement).value),
    }
  },
  template:
    '<textarea class="markdown-editor-stub" :value="modelValue" :readonly="readonly" @input="input" />',
})

enableAutoUnmount(afterEach)
let pinia: ReturnType<typeof createPinia>

describe('知识库详情目录体验', () => {
  beforeEach(() => {
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    // 目录属于登录成员；先登录再装载样本，不能沿用匿名阶段的目录。
    useSessionStore(pinia).accept({
      accountId: 'account-m1',
      member: { id: 'm1', name: 'm1', initial: 'm', color: '#64748b', role: '' },
      identities: [],
      createdAt: '2026-08-01T00:00:00',
    })
    seedDataStore()
    vi.mocked(fetchDocTemplates).mockReset()
    vi.mocked(fetchDoc).mockReset()
    vi.mocked(apiPatchDocTemplate).mockReset()
  })

  async function mountView(path = '/kb/k3', apiLive = false, realEditorStub = false, memberId = 'm1') {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/kb/:id', name: 'kb', component: KbDetailView }],
    })
    await router.push(path)
    await router.isReady()
    useSessionStore(pinia).accept({
      accountId: `account-${memberId}`,
      member: { id: memberId, name: memberId, initial: memberId[0]!, color: '#64748b', role: '' },
      identities: [],
      createdAt: '2026-08-01T00:00:00',
    })
    useDataStore(pinia).apiLive = apiLive
    const wrapper = mount(KbDetailView, {
      global: {
        plugins: [pinia, router],
        stubs: { MarkdownEditor: realEditorStub ? MarkdownEditorStub : true },
      },
    })
    await flushPromises()
    return wrapper
  }

  it('收起和展开目录保留编辑器及未保存输入', async () => {
    const wrapper = await mountView('/kb/k3?doc=d7', false, true)
    await wrapper.get('.markdown-editor-stub').setValue('目录切换期间的草稿')
    const editor = wrapper.get('.markdown-editor-stub').element
    const toggle = wrapper.get('.directory-toggle').element
    await wrapper.get('[aria-label="收起知识库目录"]').trigger('click')
    expect(wrapper.get('[aria-label="展开知识库目录"]').element).toBe(toggle)
    expect(wrapper.get('.directory-toggle').attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('#kb-directory').attributes('style')).toContain('display: none')
    expect(wrapper.get('.markdown-editor-stub').element).toBe(editor)
    await wrapper.get('[aria-label="展开知识库目录"]').trigger('click')
    expect(wrapper.get('#kb-directory').attributes('style')).not.toContain('display: none')
    expect((wrapper.get('.markdown-editor-stub').element as HTMLTextAreaElement).value).toBe('目录切换期间的草稿')
    expect(wrapper.get('.markdown-editor-stub').element).toBe(editor)
  })
  it('文档宽度切换保留未保存正文和同一个编辑器，重开恢复成员偏好', async () => {
    const wrapper = await mountView('/kb/k3?doc=d7', false, true)
    const data = useDataStore(pinia)
    await wrapper.get('.markdown-editor-stub').setValue('这份未保存输入需要保留')
    const editor = wrapper.get('.markdown-editor-stub').element
    const before = JSON.stringify(data.docOf('d7'))
    const edit = vi.spyOn(data, 'setDocEdit')
    await wrapper.get('[aria-label="文档宽度"] button:last-child').trigger('click')
    expect(wrapper.get('.document-pane').classes()).toContain('content-narrow')
    expect(wrapper.get('.markdown-editor-stub').element).toBe(editor)
    expect(JSON.stringify(data.docOf('d7'))).toBe(before)
    expect((wrapper.get('.markdown-editor-stub').element as HTMLTextAreaElement).value).toBe('这份未保存输入需要保留')
    expect(edit).not.toHaveBeenCalled()
    wrapper.unmount()
    const reopened = await mountView('/kb/k3?doc=d7', false, true)
    expect(reopened.get('.document-pane').classes()).toContain('content-narrow')
    await reopened.get('[aria-label="文档宽度"] button:first-child').trigger('click')
    expect(reopened.get('.document-pane').classes()).not.toContain('content-narrow')
  })

  it('重命名期间后台刷新不覆盖输入，失败保留草稿并可重试', async () => {
    const wrapper = await mountView('/kb/k3')
    const data = useDataStore(pinia)
    const folder = data.kbById('k3')!.folders[0]!
    const menu = wrapper.findAllComponents({ name: 'ResourceMenu' }).find(item => item.props('label') === `${folder.name}的更多操作`)!
    menu.vm.$emit('select', 'rename')
    await flushPromises()
    await wrapper.get('.folder-input').setValue('尚未输入完成的新名称')
    data.kbById('k3')!.folders = data.kbById('k3')!.folders.map(f => ({ ...f }))
    await flushPromises()
    expect((wrapper.get('.folder-input').element as HTMLInputElement).value).toBe('尚未输入完成的新名称')
    const rename = vi.spyOn(data, 'renameFolder').mockResolvedValueOnce(false).mockResolvedValueOnce(true)
    await wrapper.get('.folder-input').trigger('keydown', { key: 'Enter', isComposing: true })
    expect(rename).not.toHaveBeenCalled()
    await wrapper.get('.folder-input').trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(wrapper.text()).toContain('重命名失败')
    expect((wrapper.get('.folder-input').element as HTMLInputElement).value).toBe('尚未输入完成的新名称')
    await wrapper.get('.folder-input').trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(wrapper.find('.folder-input').exists()).toBe(false)
  })

  it('保存失败显示服务端原因，成功后撤下错误与重试入口', async () => {
    const wrapper = await mountView('/kb/k3?doc=d7')
    const data = useDataStore(pinia)
    data.markSaved('doc:d7', false, '有序列表编号样式不受支持')
    await flushPromises()
    expect(wrapper.get('.doc-draft-warning').text()).toContain('有序列表编号样式不受支持')
    expect(wrapper.get('.save-retry').text()).toBe('重试')
    data.markSaved('doc:d7', true)
    await flushPromises()
    expect(wrapper.find('.doc-draft-warning').exists()).toBe(false)
    expect(wrapper.find('.save-retry').exists()).toBe(false)
  })

  it('目录条目不渲染空白编辑器，正文失败后重试才开放编辑', async () => {
    const data = useDataStore(pinia)
    const original = { ...data.docs.d7! }
    data.docs.d7 = { ...original, bodyLoaded: false, content: null, blocks: [] }
    data.loadStates.docs = 'loaded'
    vi.mocked(fetchDoc).mockRejectedValueOnce(new Error('正文超时'))
      .mockResolvedValueOnce({ ...original, bodyLoaded: true })
    const wrapper = await mountView('/kb/k3?doc=d7', false, true)
    expect(wrapper.find('.markdown-editor-stub').exists()).toBe(false)
    expect(wrapper.get('.doc-empty').text()).toContain('正文超时')
    await wrapper.get('.doc-empty button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.markdown-editor-stub').exists()).toBe(true)
    expect(wrapper.find('.doc-empty').exists()).toBe(false)
  })

  it('普通知识库入口展示目录首页，点击文档后可返回首页', async () => {
    const data = useDataStore(pinia)
    data.loadStates.docs = 'loaded'
    const wrapper = await mountView('/kb/k3')
    expect(wrapper.find('.library-home').exists()).toBe(true)
    expect(wrapper.find('.doc-bar').exists()).toBe(false)
    await wrapper.get('.home-documents button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.doc-bar').exists()).toBe(true)
    await wrapper.get('.library-home-link').trigger('click')
    await flushPromises()
    expect(wrapper.vm.$router.currentRoute.value.query.doc).toBeUndefined()
    expect(wrapper.find('.library-home').exists()).toBe(true)
  })

  // Regression: knowledge QA — 首页搜索、读取失败与归档提示，2026-10-29。
  it('首页与目录共享搜索，清空后恢复列表，排序不修改存储顺序', async () => {
    const data = useDataStore(pinia)
    data.docs.d7!.title = 'Alpha 方案'
    data.docs.d8!.title = 'Beta 方案'
    data.docs.d7!.updated = '2026-10-01T10:00:00Z'
    data.docs.d8!.updated = '2026-10-28T10:00:00Z'
    const before = JSON.stringify(data.kbById('k3')!.folders)
    const wrapper = await mountView()
    await wrapper.get('.tree-search input').setValue('Beta')
    expect(wrapper.findAll('.home-document-name').map(node => node.text())).toEqual(['Beta 方案'])
    expect(wrapper.get('.home-result-count').text()).toContain('1')
    await wrapper.get('[aria-label="搜索首页文档"]').setValue('不存在的文档')
    expect(wrapper.get('.library-home').text()).toContain('没有匹配的文件夹或文档')
    await wrapper.get('.home-clear-search').trigger('click')
    expect(wrapper.findAll('.home-document-name')).toHaveLength(2)
    await wrapper.get('[aria-label="首页文档排序"]').setValue('updated')
    expect(wrapper.findAll('.home-document-name').map(node => node.text())).toEqual(['Beta 方案', 'Alpha 方案'])
    await wrapper.get('[aria-label="首页文档排序"]').setValue('title')
    expect(wrapper.findAll('.home-document-name').map(node => node.text())).toEqual(['Alpha 方案', 'Beta 方案'])
    expect(JSON.stringify(data.kbById('k3')!.folders)).toBe(before)
  })

  it('目录加载失败显示原因并可重试，保留已有列表', async () => {
    const data = useDataStore(pinia)
    data.loadStates.docs = 'error'
    data.loadErrors.docs = '网络暂时不可用'
    const retry = vi.spyOn(data, 'refreshDocs').mockImplementation(async () => {
      data.loadStates.docs = 'loaded'
      return true
    })
    const wrapper = await mountView()
    expect(wrapper.get('.home-load-error').text()).toContain('网络暂时不可用')
    expect(wrapper.get('.library-home').text()).not.toContain('正在加载目录')
    expect(wrapper.findAll('.home-document-name')).toHaveLength(2)
    await wrapper.get('.home-load-error button').trigger('click')
    await flushPromises()
    expect(retry).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.home-load-error').exists()).toBe(false)
  })

  it('历史群聊库不伪造最近同步时间和新增条数', async () => {
    const wrapper = await mountView('/kb/k4')
    expect(wrapper.get('.sync-bar').text()).not.toContain('今天 06:12')
    expect(wrapper.get('.sync-bar').text()).not.toContain('新增 12 条')
    expect(wrapper.get('.sync-bar').text()).toContain('历史归档')
  })

  it('切换知识库时加载新库归档，旧请求失败不污染新库状态', async () => {
    const data = useDataStore(pinia)
    let finishOld!: (value: boolean) => void
    const archived = vi.spyOn(data, 'refreshArchivedDocs')
      .mockReturnValueOnce(new Promise<boolean>(resolve => { finishOld = resolve }))
      .mockResolvedValueOnce(true)
    const wrapper = await mountView('/kb/k3', true)
    await wrapper.vm.$router.push('/kb/k4')
    await flushPromises()
    expect(archived.mock.calls.map(args => args[0])).toEqual(['k3', 'k4'])
    finishOld(false)
    await flushPromises()
    // 空归档区在请求完成后隐藏；旧请求不能重新带回错误或加载提示。
    expect(wrapper.find('.archive-tree').exists()).toBe(false)
  })

  it('小记知识库目录和首页默认按更新时间倒序，可切换正序', async () => {
    const data = useDataStore(pinia)
    data.kbById('kb-notes')!.folders[0]!.docs = ['d7', 'd8']
    data.docs.d7!.updated = '2026-09-01T10:00:00Z'
    data.docs.d8!.updated = '2026-09-20T10:00:00Z'
    const wrapper = await mountView('/kb/kb-notes')
    const titles = (selector: string) => wrapper.findAll(selector).map(node => node.text())
    expect((wrapper.get('[aria-label="小记目录时间排序"]').element as HTMLSelectElement).value).toBe('desc')
    expect(titles('.tree .doc-node')).toEqual([data.docs.d8!.title, data.docs.d7!.title])
    expect(titles('.home-document-name')).toEqual([data.docs.d8!.title, data.docs.d7!.title])
    await wrapper.get('[aria-label="小记目录时间排序"]').setValue('asc')
    expect(titles('.tree .doc-node')).toEqual([data.docs.d7!.title, data.docs.d8!.title])
    expect(titles('.home-document-name')).toEqual([data.docs.d7!.title, data.docs.d8!.title])
  })

  it('首次进入全部展开，一键收起展开不改变文档，重新进入恢复展开', async () => {
    const wrapper = await mountView('/kb/k3?doc=d7')
    expect(wrapper.findAll('.folder-row')).toHaveLength(2)
    expect(wrapper.findAll('.tree .doc-node')).toHaveLength(2)
    await wrapper.get('[aria-label="折叠全部目录"]').trigger('click')
    expect(wrapper.findAll('.tree .doc-node')).toHaveLength(0)
    expect(wrapper.vm.$router.currentRoute.value.query.doc).toBe('d7')
    await wrapper.get('[aria-label="展开全部目录"]').trigger('click')
    expect(wrapper.findAll('.tree .doc-node')).toHaveLength(2)
    await wrapper.get('[aria-label="折叠全部目录"]').trigger('click')
    useTabsStore(pinia).activeKey = 'notes'
    await wrapper.vm.$nextTick()
    useTabsStore(pinia).activeKey = 'kb:k3'
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('.tree .doc-node')).toHaveLength(2)
    wrapper.unmount()
  })

  it('搜索展开匹配文档，定位恢复当前文件夹，移动仍保留编辑内容', async () => {
    const wrapper = await mountView('/kb/k3?doc=d7', false, true)
    const data = useDataStore(pinia)
    const target = data.kbById('k3')!.folders.find(folder => !folder.docs.includes('d7'))!
    const scrolling = HTMLElement.prototype.scrollIntoView
    HTMLElement.prototype.scrollIntoView = vi.fn()
    const order = vi.mocked(apiMoveKnowledgeNode).mockImplementation(async (_kb, node, parent) => {
      const saved = JSON.parse(JSON.stringify(data.kbById('k3')!))
      for (const folder of saved.folders) folder.docs = folder.docs.filter((id: string) => id !== node.slice(2))
      saved.folders.find((f: { id: string }) => f.id === parent.slice(2)).docs.push(node.slice(2))
      saved.directory = { [node]: parent }
      return saved
    })
    try {
      await wrapper.get('[aria-label="搜索本知识库文件夹和文档"]').setValue('卡片盒')
      expect(wrapper.findAll('.folder-row')).toHaveLength(1)
      expect(wrapper.get('.tree .doc-node').text()).toContain('卡片盒笔记法')
      await wrapper.get('[title="定位当前文档"]').trigger('click')
      await flushPromises()
      expect(wrapper.find('.doc-node.on').exists()).toBe(true)
      const original = (wrapper.get('.markdown-editor-stub').element as HTMLTextAreaElement).value
      await wrapper.get('[aria-label="移动当前文档到文件夹"]').setValue(target.id)
      await flushPromises()
      expect(order).toHaveBeenCalledWith('k3', 'd:d7', `f:${target.id}`, expect.any(String), undefined)
      expect(data.kbById('k3')!.folders.find(folder => folder.id === target.id)?.docs).toContain('d7')
      expect((wrapper.get('.markdown-editor-stub').element as HTMLTextAreaElement).value).toBe(original)
      expect(wrapper.find('.doc-node.on').exists()).toBe(true)
    } finally { HTMLElement.prototype.scrollIntoView = scrolling; order.mockReset() }
  })

  it('从文档选区创建待办时不继承知识库项目并保留完整原文，并隔离不同选区草稿', async () => {
    const wrapper = await mountView('/kb/k3?doc=d7')
    useDataStore(pinia).kbById('k3')!.project = 'p2'
    const vm = wrapper.vm as unknown as { addSelectionToTask: (text: string) => Promise<void> }
    const text = '需要处理的事项\n' + '完整正文'.repeat(80)
    await vm.addSelectionToTask(text)
    const ui = useUiStore(pinia)
    expect(ui.modal).toBe('task')
    expect(ui.modalProject).toBe('')
    expect(ui.modalTaskSeed?.title).toHaveLength(200)
    expect(ui.modalTaskSeed?.desc).toContain(text)
    expect(ui.modalTaskSeed?.desc).toContain('[查看来源文档](/kb/k3?doc=d7)')
    expect(ui.modalTaskSeed?.sourceUrl).toBe('/kb/k3?doc=d7')
    const key = ui.modalTaskSeed?.draftKey
    await vm.addSelectionToTask('另一个待办')
    expect(ui.modalTaskSeed?.draftKey).not.toBe(key)
    await vm.addSelectionToTask(text)
    expect(ui.modalTaskSeed?.draftKey).toBe(key)
    ui.openModal('task')
    expect(ui.modalTaskSeed?.draftKey).toBe(key)
    ui.closeModal()
    ui.openModal('task')
    expect(ui.modalTaskSeed).toBeNull()
    wrapper.unmount()
  })

  it('从链接直达另一篇文档时展开它所在的文件夹', async () => {
    const wrapper = await mountView('/kb/k3?doc=d8')
    const folders = wrapper.findAll('.folder-row')

    expect(folders[0]!.get('.chev').classes()).toContain('ri-arrow-down-s-line')
    expect(folders[1]!.get('.chev').classes()).toContain('ri-arrow-down-s-line')
    expect(wrapper.get('.doc-node.on').text()).toContain('阅读笔记：卡片盒笔记法')
    wrapper.unmount()
  })

  it('拖到行上缘显示排序线并保存同层顺序', async () => {
    const wrapper = await mountView('/kb/k3')
    const data = useDataStore(pinia)
    const saved = JSON.parse(JSON.stringify(data.kbById('k3')!))
    saved.directory = { 'f:f5': '', 'd:d8': 'f:f5', 'f:f4': '', 'd:d7': 'f:f4' }
    saved.directoryOrder = ['f:f5', 'd:d8', 'f:f4', 'd:d7']
    const move = vi.mocked(apiMoveKnowledgeNode).mockResolvedValueOnce(saved)
    const source = wrapper.findAll('.folder-row')[1]!
    const target = wrapper.findAll('.folder-row')[0]!
    vi.spyOn(target.element, 'getBoundingClientRect').mockReturnValue({ top: 100, height: 38 } as DOMRect)
    await source.trigger('dragstart')
    await target.trigger('dragover', { clientY: 102 })
    expect(target.classes()).toContain('drop-before')
    await target.trigger('drop')
    await flushPromises()
    expect(move).toHaveBeenCalledWith('k3', 'f:f5', '', '', 'f:f4')
    expect(wrapper.findAll('.folder-row')[0]!.text()).toContain('学习笔记')
    move.mockReset()
  })

  it('展示所有关联当前文档的可见任务，并能直接打开任务详情', async () => {
    const data = useDataStore(pinia)
    data.tasks[0]!.docs = ['d7']
    data.tasks[1]!.docs = ['d7']
    const wrapper = await mountView('/kb/k3?doc=d7')

    const taskLinks = wrapper.findAll('.linked-tasks .dl-chip')
    expect(wrapper.get('.linked-tasks .dl-label').text()).toBe('关联任务')
    expect(taskLinks.map((link) => link.text())).toEqual([
      data.tasks[0]!.title,
      data.tasks[1]!.title,
    ])

    await taskLinks[1]!.trigger('click')
    expect(useUiStore(pinia).openTaskId).toBe(data.tasks[1]!.id)

    data.tasks[0]!.docs = []
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('.linked-tasks .dl-chip').map((link) => link.text())).toEqual([
      data.tasks[1]!.title,
    ])
    wrapper.unmount()
  })

  it('没有任务关联当前文档时不显示空入口', async () => {
    const data = useDataStore(pinia)
    data.tasks.forEach((task) => { task.docs = task.docs.filter((id) => id !== 'd7') })
    const wrapper = await mountView('/kb/k3?doc=d7')

    expect(wrapper.find('.linked-tasks').exists()).toBe(false)
    wrapper.unmount()
  })

  it('切换文档后同步地址，刷新仍打开所选文档', async () => {
    const wrapper = await mountView('/kb/k3?doc=d7')
    const target = wrapper.findAll('.doc-node').find((node) => node.text().includes('阅读笔记：卡片盒笔记法'))!
    await target.trigger('click')
    await flushPromises()
    expect(wrapper.vm.$router.currentRoute.value.query.doc).toBe('d8')
    const path = wrapper.vm.$router.currentRoute.value.fullPath
    wrapper.unmount()
    const reopened = await mountView(path)
    expect(reopened.get('.doc-node.on').text()).toContain('阅读笔记：卡片盒笔记法')
  })

  it('删除文档必须二次确认，取消时不修改持久数据', async () => {
    const wrapper = await mountView('/kb/k3?doc=d7')
    const data = useDataStore(pinia)
    const remove = vi.spyOn(data, 'deleteDoc').mockResolvedValue(undefined)
    const confirm = vi.spyOn(window, 'confirm')
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true)
    const removeAction = () => wrapper.find('.doc-row').findComponent({ name: 'ResourceMenu' }).vm.$emit('select', 'delete')

    removeAction()
    await flushPromises()
    expect(remove).not.toHaveBeenCalled()
    removeAction()
    await flushPromises()
    await flushPromises()

    expect(confirm.mock.calls[0]?.[0]).toContain('7 天内可恢复')
    expect(remove).toHaveBeenCalledWith('d7')
    confirm.mockRestore()
    wrapper.unmount()
  })

  it('右侧目录展示一至三级标题，并按三级缩进表达层级', async () => {
    const wrapper = await mountView('/kb/k3?doc=d7')
    useDataStore(pinia).docs.d7!.blocks = [
      { h: '一级标题', hLevel: 1 },
      { h: '二级标题', hLevel: 2 },
      { h: '三级标题', hLevel: 3 },
      { h: '四级标题', hLevel: 4 },
    ]
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[title="文档大纲"]').attributes('aria-expanded')).toBe('true')
    expect(wrapper.findAll('.toc-item').map((item) => item.text())).toEqual([
      '一级标题',
      '二级标题',
      '三级标题',
    ])
    expect(wrapper.findAll('.toc-item')[2]!.classes()).toContain('deep')
    expect(wrapper.get('.toc').element.parentElement).toBe(wrapper.get('.document-content').element)
    expect(wrapper.find('.doc-wrap .toc').exists()).toBe(false)
    await wrapper.get('[aria-label="关闭文档大纲"]').trigger('click')
    expect(wrapper.find('.toc').exists()).toBe(false)
    await wrapper.get('[title="文档大纲"]').trigger('click')
    await wrapper.get('.toc').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('.toc').exists()).toBe(false)
    // 同一篇文档手动关闭后保持关闭，切换文档和刷新重开则默认展示。
    const router = wrapper.vm.$router
    await router.replace('/kb/k3?doc=d8')
    await flushPromises()
    expect(wrapper.find('.toc').exists()).toBe(true)
    await wrapper.get('[aria-label="关闭文档大纲"]').trigger('click')
    await router.replace('/kb/k3?doc=d8&find=标题')
    await flushPromises()
    expect(wrapper.find('.toc').exists()).toBe(false)
    wrapper.unmount()
    const reopened = await mountView('/kb/k3?doc=d7')
    expect(reopened.find('.toc').exists()).toBe(true)
  })

  it('模板管理展示真实正文，并把编辑后的正文块与名称说明一起保存', async () => {
    const template = {
      id: 'dt1',
      name: '周复盘',
      description: '每周日使用',
      blocks: [{ h: '本周进展', hLevel: 1 }, { p: '填写真实进展' }],
      sourceDocId: 'd7',
      createdAt: '2026-08-19T10:00:00',
      updatedAt: '2026-08-19T10:00:00',
    }
    vi.mocked(fetchDocTemplates).mockResolvedValue([template])
    vi.mocked(apiPatchDocTemplate).mockImplementation(async (_id, payload) => ({
      ...template,
      name: payload.name ?? template.name,
      description: payload.description ?? template.description,
      blocks: payload.blocks ?? template.blocks,
      updatedAt: '2026-08-20T13:00:00',
    }))
    const wrapper = await mountView('/kb/k3?doc=d7', true, true)
    await flushPromises()

    wrapper.findComponent({ name: 'ResourceMenu' }).vm.$emit('select', 'templates')
    await flushPromises()
    expect(wrapper.get('.template-content-preview textarea').attributes('value')).toContain(
      '# 本周进展',
    )

    await wrapper.get('.template-row-actions button').trigger('click')
    const fields = wrapper.findAll('.template-modal.manager input, .template-modal.manager textarea')
    await fields[0]!.setValue('月度复盘')
    await fields[1]!.setValue('每月最后一天使用')
    await wrapper.get('textarea.template-content-editor').setValue('# 本月进展\n\n写入实际结果')
    await wrapper.findAll('.template-row-actions button')[1]!.trigger('click')
    await flushPromises()

    expect(apiPatchDocTemplate).toHaveBeenCalledWith('dt1', {
      name: '月度复盘',
      description: '每月最后一天使用',
      blocks: [{ h: '本月进展', hLevel: 1 }, { p: '写入实际结果' }],
      expectedUpdatedAt: '2026-08-19T10:00:00',
    })
    expect(wrapper.get('.template-edit-status').text()).toContain('已保存到服务器')
    expect(localStorage.getItem('wao-doc-template-draft-v1:m1:dt1')).toBeNull()
    await wrapper.get('.template-edit-footer .template-row-actions button').trigger('click')
    expect(wrapper.get('.template-copy').text()).toContain('月度复盘')
    expect(wrapper.get('.template-content-preview textarea').attributes('value')).toContain(
      '# 本月进展',
    )
    wrapper.unmount()
  })

  it('旧版本模板草稿进入明确冲突态，确认后才允许用最新版本号保存', async () => {
    const template = {
      id: 'dt1',
      name: '服务器新版',
      description: '',
      blocks: [{ p: '服务器刚保存的正文' }],
      sourceDocId: 'd7',
      createdAt: '2026-08-19T10:00:00',
      updatedAt: '2026-08-20T10:00:00',
    }
    localStorage.setItem('wao-doc-template-draft-v1:m1:dt1', JSON.stringify({
      name: '本机旧草稿',
      description: '',
      content: '不能静默覆盖的正文',
      templateUpdatedAt: '2026-08-19T10:00:00',
      savedAt: '2026-08-20T09:00:00',
    }))
    vi.mocked(fetchDocTemplates).mockResolvedValue([template])
    vi.mocked(apiPatchDocTemplate).mockResolvedValue({
      ...template,
      name: '本机旧草稿',
      blocks: [{ p: '不能静默覆盖的正文' }],
      updatedAt: '2026-08-20T10:00:01',
    })
    const wrapper = await mountView('/kb/k3?doc=d7', true, true)

    wrapper.findComponent({ name: 'ResourceMenu' }).vm.$emit('select', 'templates')
    await flushPromises()
    await wrapper.get('.template-row-actions button').trigger('click')
    expect(wrapper.get('.template-edit-status').text()).toContain('其他窗口保存的新版本')
    expect(wrapper.get('textarea.template-content-editor').attributes('value')).toBe(
      '不能静默覆盖的正文',
    )
    expect(apiPatchDocTemplate).not.toHaveBeenCalled()

    await wrapper.findAll('.conflict-actions button')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.get('.template-edit-status').text()).toContain('尚未同步')
    expect(localStorage.getItem('wao-doc-template-draft-v1:m1:dt1')).toContain(
      '2026-08-20T10:00:00',
    )
    await wrapper.findAll('.template-edit-footer .template-row-actions button')[1]!.trigger('click')
    await flushPromises()

    expect(apiPatchDocTemplate).toHaveBeenCalledWith('dt1', expect.objectContaining({
      expectedUpdatedAt: '2026-08-20T10:00:00',
      blocks: [{ p: '不能静默覆盖的正文' }],
    }))
    expect(localStorage.getItem('wao-doc-template-draft-v1:m1:dt1')).toBeNull()
    wrapper.unmount()
  })

  it('文档和模板粘贴图片都要求真实图床地址，失败时不插入 data URL', async () => {
    const wrapper = await mountView('/kb/k3?doc=d7')
    const data = useDataStore(pinia)
    const upload = vi.spyOn(data, 'uploadImage').mockResolvedValue(null)
    const done = vi.fn<(url: string | null, name: string) => void>()
    const file = new File(['png'], '离线截图.png', { type: 'image/png' })
    const instance = wrapper.vm as unknown as {
      onUploadImage: (file: File, done: (url: string | null, name: string) => void) => Promise<void>
    }

    await instance.onUploadImage(file, done)

    expect(upload).toHaveBeenCalledWith(
      file,
      '文档 · 健康重启：训练与恢复笔记',
      { requirePersisted: true },
    )
    expect(done).toHaveBeenCalledWith(null, '离线截图.png')
    expect(done).not.toHaveBeenCalledWith(expect.stringContaining('data:'), expect.any(String))
    wrapper.unmount()
  })

  it.each(['kb-notes', 'k3'])('知识库入口编辑 %s 时，保存前的图片和附件也使用私有上传', async kbId => {
    const data = useDataStore(pinia)
    data.docs.d7!.kbId = kbId
    const wrapper = await mountView('/kb/k3?doc=d7')
    const image = vi.spyOn(data, 'uploadImage').mockResolvedValue(null)
    const attachment = vi.spyOn(data, 'uploadFile').mockResolvedValue(null)
    const file = new File(['private draft'], '草稿图.png', { type: 'image/png' })
    const instance = wrapper.vm as unknown as {
      onUploadImage: (file: File, done: (url: string | null, name: string) => void) => Promise<void>
      onUploadFile: (file: File, done: (item: null) => void) => Promise<void>
    }
    await instance.onUploadImage(file, vi.fn())
    await instance.onUploadFile(file, vi.fn())
    expect(image).toHaveBeenCalledWith(file, expect.stringMatching(kbId === 'kb-notes' ? /^小记 · / : /^文档 · /), { requirePersisted: true })
    expect(attachment).toHaveBeenCalledWith(file, undefined, true)
    wrapper.unmount()
  })

  it('按成员和模板保留本机草稿，关闭后重新进入会恢复', async () => {
    const template = {
      id: 'dt1',
      name: '周复盘',
      description: '每周日使用',
      blocks: [{ p: '服务端正文' }],
      sourceDocId: 'd7',
      createdAt: '2026-08-19T10:00:00',
      updatedAt: '2026-08-19T10:00:00',
    }
    vi.mocked(fetchDocTemplates).mockResolvedValue([template])
    let wrapper = await mountView('/kb/k3?doc=d7', true, true, 'm1')

    wrapper.findComponent({ name: 'ResourceMenu' }).vm.$emit('select', 'templates')
    await flushPromises()
    await wrapper.get('.template-row-actions button').trigger('click')
    await wrapper.get('textarea.template-content-editor').setValue('只属于成员一的未保存正文')

    expect(localStorage.getItem('wao-doc-template-draft-v1:m1:dt1')).toContain(
      '只属于成员一的未保存正文',
    )
    expect(localStorage.getItem('wao-doc-template-draft-v1:m1:other-template')).toBeNull()
    expect(wrapper.get('.template-edit-status').text()).toContain('尚未同步')
    await wrapper.get('.template-modal.manager header > button').trigger('click')
    expect(wrapper.find('.template-modal.manager').exists()).toBe(false)
    wrapper.unmount()

    wrapper = await mountView('/kb/k3?doc=d7', true, true, 'm1')
    wrapper.findComponent({ name: 'ResourceMenu' }).vm.$emit('select', 'templates')
    await flushPromises()
    await wrapper.get('.template-row-actions button').trigger('click')
    expect(wrapper.get('textarea.template-content-editor').attributes('value')).toBe(
      '只属于成员一的未保存正文',
    )
    expect(wrapper.get('.template-edit-status').text()).toContain('已恢复本机草稿')
    wrapper.unmount()

    wrapper = await mountView('/kb/k3?doc=d7', true, true, 'm2')
    wrapper.findComponent({ name: 'ResourceMenu' }).vm.$emit('select', 'templates')
    await flushPromises()
    await wrapper.get('.template-row-actions button').trigger('click')
    expect(wrapper.get('textarea.template-content-editor').attributes('value')).toBe('服务端正文')
    expect(wrapper.get('.template-edit-status').text()).toContain('已保存到服务器')
    wrapper.unmount()
  })

  it('模板保存失败保留草稿并提供原地重试，成功后才清理草稿', async () => {
    const template = {
      id: 'dt1',
      name: '周复盘',
      description: '',
      blocks: [{ p: '服务端正文' }],
      sourceDocId: 'd7',
      createdAt: '2026-08-19T10:00:00',
      updatedAt: '2026-08-19T10:00:00',
    }
    vi.mocked(fetchDocTemplates).mockResolvedValue([template])
    vi.mocked(apiPatchDocTemplate)
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockImplementationOnce(async (_id, payload) => ({
        ...template,
        name: payload.name ?? template.name,
        description: payload.description ?? template.description,
        blocks: payload.blocks ?? template.blocks,
        updatedAt: '2026-08-20T13:00:00',
      }))
    const wrapper = await mountView('/kb/k3?doc=d7', true, true)

    wrapper.findComponent({ name: 'ResourceMenu' }).vm.$emit('select', 'templates')
    await flushPromises()
    await wrapper.get('.template-row-actions button').trigger('click')
    await wrapper.get('.template-modal.manager input').setValue('失败后仍可重试')
    await wrapper.findAll('.template-edit-footer .template-row-actions button')[1]!.trigger('click')
    await flushPromises()

    expect(wrapper.get('.template-edit-status').text()).toContain('保存失败')
    expect(wrapper.findAll('.template-edit-footer .template-row-actions button')[1]!.text()).toBe('重试保存')
    expect(localStorage.getItem('wao-doc-template-draft-v1:m1:dt1')).toContain('失败后仍可重试')

    await wrapper.findAll('.template-edit-footer .template-row-actions button')[1]!.trigger('click')
    await flushPromises()
    expect(apiPatchDocTemplate).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.template-edit-status').text()).toContain('已保存到服务器')
    expect(localStorage.getItem('wao-doc-template-draft-v1:m1:dt1')).toBeNull()
    wrapper.unmount()
  })

  it('把详情页限制在应用壳剩余视口，并为左右滚动容器阻止滚动串联', () => {
    const source = readFileSync(join(process.cwd(), 'src/views/KbDetailView.vue'), 'utf8')

    expect(source).toContain('height: calc(100dvh - var(--wao-header-h) - var(--wao-tabbar-h))')
    expect(source.match(/overscroll-behavior: contain/g)).toHaveLength(2)
  })
  it('加号和目录右键提供独立表格及画板，并传递页面类型', async () => {
    const wrapper = await mountView('/kb/k3')
    const add = vi.spyOn(useDataStore(pinia), 'addDoc').mockResolvedValue(null)
    await wrapper.get('[aria-label="新增内容"]').trigger('click')
    expect(document.body.textContent).toContain('新建表格')
    expect(document.body.textContent).toContain('新建画板')
    const button = [...document.querySelectorAll('button')].find(item => item.textContent?.trim() === '新建表格')
    button?.click()
    await flushPromises()
    expect(add).toHaveBeenCalledWith('k3', expect.any(String), undefined, 'spreadsheet')
    await wrapper.get('.tree').trigger('contextmenu', { clientX: 20, clientY: 30 })
    await flushPromises()
    const drawing = [...document.querySelectorAll('[role="menuitem"]')].find(item => item.textContent === '新建画板') as HTMLButtonElement
    expect(drawing).toBeTruthy()
    drawing.click()
    await flushPromises()
    expect(add).toHaveBeenLastCalledWith('k3', expect.any(String), undefined, 'drawing')
  })

})
