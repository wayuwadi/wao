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
import { apiPatchDocTemplate, fetchDocTemplates } from '@/api/board'

vi.mock('@/api/board', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/board')>()
  return {
    ...actual,
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
    seedDataStore()
    vi.mocked(fetchDocTemplates).mockReset()
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

  it('首次进入只展开当前文档所在文件夹，其余文件夹默认收起', async () => {
    const wrapper = await mountView('/kb/k3?doc=d7')
    const folders = wrapper.findAll('.folder-row')

    expect(folders).toHaveLength(2)
    expect(folders[0]!.get('.chev').classes()).toContain('ri-arrow-down-s-line')
    expect(folders[1]!.get('.chev').classes()).toContain('ri-arrow-right-s-line')
    expect(wrapper.findAll('.doc-tree')).toHaveLength(1)

    await folders[1]!.get('.folder-btn').trigger('click')
    expect(wrapper.findAll('.doc-tree')).toHaveLength(2)
    wrapper.unmount()
  })

  it('从链接直达另一篇文档时展开它所在的文件夹', async () => {
    const wrapper = await mountView('/kb/k3?doc=d8')
    const folders = wrapper.findAll('.folder-row')

    expect(folders[0]!.get('.chev').classes()).toContain('ri-arrow-right-s-line')
    expect(folders[1]!.get('.chev').classes()).toContain('ri-arrow-down-s-line')
    expect(wrapper.get('.doc-node.on').text()).toContain('阅读笔记：卡片盒笔记法')
    wrapper.unmount()
  })

  it('删除文档必须二次确认，取消时不修改持久数据', async () => {
    const wrapper = await mountView('/kb/k3?doc=d7')
    const data = useDataStore(pinia)
    const remove = vi.spyOn(data, 'deleteDoc').mockResolvedValue(undefined)
    const confirm = vi.spyOn(window, 'confirm')
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true)
    const button = wrapper.get('.doc-row .row-act.danger')

    await button.trigger('click')
    expect(remove).not.toHaveBeenCalled()
    await button.trigger('click')
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

    expect(wrapper.findAll('.toc-item').map((item) => item.text())).toEqual([
      '一级标题',
      '二级标题',
      '三级标题',
    ])
    expect(wrapper.findAll('.toc-item')[2]!.classes()).toContain('deep')
    wrapper.unmount()
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

    await wrapper.findAll('.side-actions button')[1]!.trigger('click')
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

    await wrapper.findAll('.side-actions button')[1]!.trigger('click')
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

    await wrapper.findAll('.side-actions button')[1]!.trigger('click')
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
    await wrapper.findAll('.side-actions button')[1]!.trigger('click')
    await wrapper.get('.template-row-actions button').trigger('click')
    expect(wrapper.get('textarea.template-content-editor').attributes('value')).toBe(
      '只属于成员一的未保存正文',
    )
    expect(wrapper.get('.template-edit-status').text()).toContain('已恢复本机草稿')
    wrapper.unmount()

    wrapper = await mountView('/kb/k3?doc=d7', true, true, 'm2')
    await wrapper.findAll('.side-actions button')[1]!.trigger('click')
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

    await wrapper.findAll('.side-actions button')[1]!.trigger('click')
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
})
