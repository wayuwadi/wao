import { readNoteDraft } from '@/utils/noteDraftStorage'
import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import QuickNoteModal from '@/components/overlays/QuickNoteModal.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'

const MarkdownEditorStub = defineComponent({
  props: { modelValue: { type: String, default: '' }, readonly: Boolean },
  emits: ['update:modelValue'],
  template: '<textarea class="editor-stub" :value="modelValue" :readonly="readonly" @input="$emit(\'update:modelValue\', $event.target.value)" />',
})

function testRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/kb/:id', component: { template: '<div />' } },
    ],
  })
}

async function mountModal() {
  const router = testRouter()
  router.push('/')
  await router.isReady()
  return mount(QuickNoteModal, {
    global: { plugins: [router], stubs: { MarkdownEditor: MarkdownEditorStub } },
  })
}

function login(id: string, name: string) {
  useSessionStore().accept({
    member: { id, name, initial: name.slice(-1), color: '#4358D6', role: '成员' },
  } as never)
}

describe('新建小记弹窗', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    setActivePinia(createPinia())
    login('wg', '娃哥')
  })

  it('关闭后恢复当前成员草稿，并明确显示按创建人归档的位置', async () => {
    const first = await mountModal()
    await first.find('.editor-stub').setValue('刚想到的发布检查清单')

    const stored = JSON.parse(readNoteDraft('wao-quick-note-draft-v1:wg') || '{}')
    expect(stored.text).toBe('刚想到的发布检查清单')
    expect(stored.id).toMatch(/^[a-f0-9]{32}$/)
    expect(first.text()).toContain('小记 / 娃哥')
    first.unmount()

    const reopened = await mountModal()
    expect((reopened.find('.editor-stub').element as HTMLTextAreaElement).value)
      .toBe('刚想到的发布检查清单')
    expect(reopened.text()).toContain('草稿已保存')
    reopened.unmount()
  })

  it('页签草稿持久化失败时明确提示，保留内存输入供服务器保存', async () => {
    const wrapper = await mountModal()
    const fail = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    await wrapper.find('.editor-stub').setValue('存储失败也不能丢的输入')
    expect(wrapper.get('[role="alert"]').text()).toContain('浏览器草稿保存失败')
    expect(wrapper.get<HTMLTextAreaElement>('.editor-stub').element.value).toBe('存储失败也不能丢的输入')
    fail.mockRestore()
    wrapper.unmount()
  })

  it('不同成员使用各自的草稿，不会互相看到正文', async () => {
    const first = await mountModal()
    await first.find('.editor-stub').setValue('娃哥的草稿')
    first.unmount()

    setActivePinia(createPinia())
    login('wd', '娃弟')
    const second = await mountModal()

    expect((second.find('.editor-stub').element as HTMLTextAreaElement).value).toBe('')
    expect(second.text()).toContain('小记 / 娃弟')
    expect(readNoteDraft('wao-quick-note-draft-v1:wg')).toContain('娃哥的草稿')
    second.unmount()
  })

  it('服务端失败时保留弹窗和草稿，沿用同一 id 重试成功后才清理', async () => {
    const data = useDataStore()
    const ui = useUiStore()
    ui.openModal('note')
    const addNote = vi.spyOn(data, 'addNote')
      .mockRejectedValueOnce(new Error('网关暂时不可用'))
      .mockResolvedValueOnce('saved-note')
    const wrapper = await mountModal()
    await wrapper.find('.editor-stub').setValue('绝不能丢的正文')
    const stableId = JSON.parse(readNoteDraft('wao-quick-note-draft-v1:wg') || '{}').id

    await wrapper.find('.btn-primary').trigger('click')
    await flushPromises()

    expect(ui.modal).toBe('note')
    expect(wrapper.find('[role="alert"]').text()).toContain('未保存到服务器，草稿仍在')
    expect(wrapper.find('.btn-primary').text()).toContain('重试保存')
    expect(readNoteDraft('wao-quick-note-draft-v1:wg')).toContain('绝不能丢的正文')

    await wrapper.find('.btn-primary').trigger('click')
    await flushPromises()

    expect(addNote).toHaveBeenNthCalledWith(1, '绝不能丢的正文', '', stableId)
    expect(addNote).toHaveBeenNthCalledWith(2, '绝不能丢的正文', '', stableId)
    expect(ui.modal).toBeNull()
    expect(readNoteDraft('wao-quick-note-draft-v1:wg')).toBeNull()
    wrapper.unmount()
  })

  it('提交飞行期间禁用编辑和关闭，并拦住全局 Escape', async () => {
    const data = useDataStore()
    const ui = useUiStore()
    ui.openModal('note')
    let finish!: (id: string) => void
    vi.spyOn(data, 'addNote').mockReturnValue(new Promise((resolve) => { finish = resolve }))
    const wrapper = await mountModal()
    await wrapper.find('.editor-stub').setValue('正在写透服务器')

    await wrapper.find('.btn-primary').trigger('click')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))

    expect(ui.modal).toBe('note')
    expect(wrapper.find('.editor-stub').attributes('readonly')).toBeDefined()
    expect(wrapper.find('.close').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.btn-primary').text()).toContain('正在保存')

    finish('saved-note')
    await flushPromises()
    wrapper.unmount()
  })

  it('真实高级编辑器保存并打开后完整卸载弹窗，不留下遮罩或失效菜单', async () => {
    const ui = useUiStore()
    ui.openModal('note')
    localStorage.setItem('wao-quick-note-draft-v1:wg', JSON.stringify({
      id: 'editor-lifecycle-note', text: '高级编辑器保存与跳转验收',
    }))
    let finish!: (id: string) => void
    vi.spyOn(useDataStore(), 'addNote').mockReturnValue(new Promise((resolve) => { finish = resolve }))
    const router = testRouter()
    await router.push('/')
    const Host = defineComponent({
      components: { QuickNoteModal },
      setup: () => ({ ui }),
      template: '<div><QuickNoteModal v-if="ui.modal === \'note\'" /><p v-else>弹窗已关闭</p></div>',
    })
    const wrapper = mount(Host, { attachTo: document.body, global: { plugins: [router] } })
    // 保留真实异步编辑器及浮层插件；原来的 textarea 替身无法发现卸载错误。
    await vi.dynamicImportSettled()
    await flushPromises()
    await wrapper.find('.btn-ghost').trigger('click')
    expect(wrapper.find('.tiptap').attributes('contenteditable')).toBe('false')
    finish('editor-lifecycle-note')
    await flushPromises()
    expect(wrapper.find('.modal').exists()).toBe(false)
    expect(wrapper.find('.mask').exists()).toBe(false)
    expect(wrapper.text()).toBe('弹窗已关闭')
    expect(router.currentRoute.value.query.doc).toBe('editor-lifecycle-note')
    expect(readNoteDraft('wao-quick-note-draft-v1:wg')).toBeNull()
    wrapper.unmount()
  })
})
