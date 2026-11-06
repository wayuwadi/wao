import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive, ref } from 'vue'
import { routeLocationKey } from 'vue-router'
import { pageVisibleKey } from '@/composables/pageVisibility'
import DrawingsView from '@/views/DrawingsView.vue'
import DrawingCanvas from '@/components/drawings/DrawingCanvas.vue'
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ userId: 'member-a' }) }))
import {
  fetchDrawing,
  fetchDrawingGroups, createDrawingGroup, moveDrawing, copyDrawing, createDrawing, saveDrawingOrder,
  fetchDrawings,
  fetchDrawingVersions,
  renameDrawing,
  restoreDrawingVersion,
  saveDrawing,
  type DrawingFile,
} from '@/api/drawings'

vi.mock('@/api/drawings', () => ({
  createDrawing: vi.fn(),
  fetchDrawingGroups: vi.fn().mockResolvedValue({ groups: [], assignments: [] }),
  createDrawingGroup: vi.fn(), renameDrawingGroup: vi.fn(), moveDrawing: vi.fn(), copyDrawing: vi.fn(), saveDrawingOrder: vi.fn(),
  deleteDrawing: vi.fn(),
  fetchDrawing: vi.fn(),
  fetchDrawings: vi.fn(),
  fetchDrawingVersions: vi.fn(),
  renameDrawing: vi.fn(),
  restoreDrawingVersion: vi.fn(),
  saveDrawing: vi.fn(),
}))

vi.mock('@/utils/drawingDrafts', () => ({
  readDrawingDraft: vi.fn().mockResolvedValue(null),
  removeDrawingDraft: vi.fn().mockResolvedValue(undefined),
  writeDrawingDraft: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/components/drawings/DrawingCanvas.vue', () => ({
  default: { template: '<div class="drawing-canvas-stub"></div>' },
}))

function drawing(patch: Partial<DrawingFile> = {}): DrawingFile {
  return {
    id: 'dr1',
    title: '测试草图',
    sceneJson: '{"type":"excalidraw","elements":[],"appState":{}}',
    revision: 5,
    createdAt: '2026-08-12T09:00:00',
    updatedAt: '2026-08-12T10:00:00',
    ...patch,
  }
}

describe('个人草图优化', () => {
  it('内嵌个人画板支持改名，关闭前保存名称，失败不丢输入', async () => {
    const wrapper = mount(DrawingsView, { props: { drawingId: 'dr1', embedded: true } })
    await flushPromises()
    await wrapper.get('.title-trigger').trigger('click')
    await wrapper.get('[aria-label="草图名称"]').setValue('内嵌新名称')
    vi.mocked(renameDrawing).mockRejectedValueOnce(new Error('名称保存失败'))
    const view = wrapper.vm as unknown as { flush: () => Promise<void> }
    await expect(view.flush()).rejects.toThrow('名称保存失败')
    expect(wrapper.get<HTMLInputElement>('[aria-label="草图名称"]').element.value).toBe('内嵌新名称')
    vi.mocked(renameDrawing).mockResolvedValueOnce(drawing({ title: '内嵌新名称' }))
    await expect(view.flush()).resolves.toBeUndefined()
    expect(wrapper.get('.title-trigger').text()).toContain('内嵌新名称')
    wrapper.unmount()
  })
  it('内嵌画板退出前等待保存，失败保留草稿供重试', async () => {
    vi.mocked(fetchDrawing).mockResolvedValue(drawing())
    const wrapper = mount(DrawingsView, { props: { drawingId:'dr1', embedded:true } })
    await flushPromises()
    const canvas = wrapper.findComponent(DrawingCanvas)
    canvas.vm.$emit('change', '{"type":"excalidraw","elements":[{"id":"new"}],"appState":{}}')
    vi.mocked(saveDrawing).mockRejectedValueOnce(new Error('网络不可用'))
    const view = wrapper.vm as unknown as {flush:()=>Promise<void>}
    await expect(view.flush()).rejects.toThrow()
    expect(wrapper.text()).toContain('重试保存')
    vi.mocked(saveDrawing).mockResolvedValueOnce(drawing({revision:6}))
    await expect(view.flush()).resolves.toBeUndefined()
    expect(saveDrawing).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchDrawingGroups).mockResolvedValue({ groups: [], assignments: [] })
    localStorage.clear()
    window.history.replaceState({}, '', '/drawings?drawing=dr1')
    vi.mocked(fetchDrawings).mockResolvedValue([drawing()])
    vi.mocked(fetchDrawing).mockResolvedValue(drawing())
    vi.mocked(fetchDrawingVersions).mockResolvedValue([
      { revision: 5, createdAt: '2026-08-12T10:00:00' },
      { revision: 4, createdAt: '2026-08-12T09:50:00' },
      { revision: 3, createdAt: '2026-08-12T09:40:00' },
    ])
  })

  it('普通入口只加载列表，不读取首个草图或历史版本', async () => {
    window.history.replaceState({}, '', '/drawings')
    const wrapper = mount(DrawingsView)
    await flushPromises()
    expect(fetchDrawing).not.toHaveBeenCalled()
    expect(fetchDrawingVersions).not.toHaveBeenCalled()
    expect(wrapper.find('.drawing-canvas-stub').exists()).toBe(false)
    await wrapper.get('.file-open').trigger('click')
    await flushPromises()
    expect(fetchDrawing).toHaveBeenCalledWith('dr1')
    wrapper.unmount()
  })

  it('再次进入恢复上次打开的草图，显式链接优先且无效记录安全回退', async () => {
    window.history.replaceState({}, '', '/drawings')
    const first = mount(DrawingsView)
    await flushPromises()
    await first.get('.file-open').trigger('click')
    await flushPromises()
    first.unmount()
    const second = mount(DrawingsView)
    await flushPromises()
    expect(second.find('.drawing-canvas-stub').exists()).toBe(true)
    expect(fetchDrawing).toHaveBeenLastCalledWith('dr1')
    second.unmount()
    localStorage.setItem('wao:last-drawing:member-a', 'missing')
    const third = mount(DrawingsView)
    await flushPromises()
    expect(third.find('.drawing-canvas-stub').exists()).toBe(false)
    expect(localStorage.getItem('wao:last-drawing:member-a')).toBeNull()
    third.unmount()
    localStorage.setItem('wao:last-drawing:member-a', 'other')
    window.history.replaceState({}, '', '/drawings?drawing=dr1')
    const linked = mount(DrawingsView)
    await flushPromises()
    expect(fetchDrawing).toHaveBeenLastCalledWith('dr1')
    linked.unmount()
  })

  it('恢复读取失败时保留上次记录，内嵌画板不更改个人入口', async () => {
    localStorage.setItem('wao:last-drawing:member-a', 'dr1')
    window.history.replaceState({}, '', '/drawings')
    vi.mocked(fetchDrawing).mockRejectedValueOnce(new Error('网络不可用'))
    const failed = mount(DrawingsView)
    await flushPromises()
    expect(failed.text()).toContain('网络不可用')
    expect(localStorage.getItem('wao:last-drawing:member-a')).toBe('dr1')
    failed.unmount()
    const embedded = mount(DrawingsView, { props: { drawingId: 'other', embedded: true } })
    await flushPromises()
    expect(localStorage.getItem('wao:last-drawing:member-a')).toBe('dr1')
    embedded.unmount()
  })

  it('拖入分组并在分组中新建，保存归属后才展示', async () => {
    vi.mocked(fetchDrawingGroups).mockResolvedValue({ groups: [{ id: 'g1', name: '项目' }], assignments: [], order: [], revision: 0 })
    vi.mocked(moveDrawing).mockResolvedValue(undefined)
    vi.mocked(createDrawing).mockResolvedValue(drawing({ id: 'new1' }))
    vi.mocked(fetchDrawing).mockImplementation(async id => drawing({ id }))
    const wrapper = mount(DrawingsView, { attachTo: document.body })
    await flushPromises()
    const transfer = { effectAllowed: '', dropEffect: '', setData: vi.fn() }
    await wrapper.get('.file-row').trigger('dragstart', { dataTransfer: transfer })
    await wrapper.findAll('.group-header')[0]!.trigger('dragover', { dataTransfer: transfer })
    await wrapper.findAll('.group-header')[0]!.trigger('drop', { dataTransfer: transfer })
    await flushPromises()
    expect(moveDrawing).toHaveBeenCalledWith('dr1', 'g1')
    expect(wrapper.findAll('.drawing-group')[0]!.text()).toContain('测试草图')
    await wrapper.get('[aria-label="在项目新建草图"]').trigger('click'); await flushPromises()
    expect(createDrawing).toHaveBeenCalledWith(undefined, 'g1')
    expect(wrapper.findAll('.drawing-group')[0]!.findAll('.file-row')).toHaveLength(2)
    wrapper.unmount()
  })

  it('手动排序提交版本并在更新文件后保持顺序', async () => {
    vi.mocked(fetchDrawings).mockResolvedValue([drawing({ id: 'a', title: 'A' }), drawing({ id: 'b', title: 'B' })])
    vi.mocked(fetchDrawingGroups).mockResolvedValue({ groups: [], assignments: [], order: [], revision: 2 })
    vi.mocked(fetchDrawing).mockImplementation(async id => drawing({ id }))
    vi.mocked(saveDrawingOrder).mockResolvedValue(3)
    window.history.replaceState({}, '', '/drawings')
    const wrapper = mount(DrawingsView, { attachTo: document.body })
    await flushPromises()
    const transfer = { effectAllowed: '', dropEffect: '', setData: vi.fn() }
    await wrapper.findAll('.file-row')[1]!.trigger('dragstart', { dataTransfer: transfer })
    await wrapper.findAll('.file-row')[0]!.trigger('drop', { dataTransfer: transfer, clientY: 0 })
    await flushPromises()
    expect(saveDrawingOrder).toHaveBeenCalledWith(['f:b', 'f:a'], 2)
    expect(wrapper.findAll('.file-main strong').map(x => x.text())).toEqual(['B', 'A'])
    wrapper.unmount()
  })

  it('分组可拖动排序，也可从菜单用键盘上下移动', async () => {
    vi.mocked(fetchDrawingGroups).mockResolvedValue({ groups: [{ id: 'g1', name: '甲' }, { id: 'g2', name: '乙' }], assignments: [], order: [], revision: 0 })
    vi.mocked(saveDrawingOrder).mockResolvedValueOnce(1).mockResolvedValueOnce(2).mockResolvedValueOnce(3)
    const wrapper = mount(DrawingsView, { attachTo: document.body }); await flushPromises()
    const transfer = { effectAllowed: '', dropEffect: '', setData: vi.fn() }
    await wrapper.findAll('.group-header')[1]!.trigger('dragstart', { dataTransfer: transfer })
    await wrapper.findAll('.group-header')[0]!.trigger('drop', { dataTransfer: transfer }); await flushPromises()
    expect(saveDrawingOrder).toHaveBeenCalledWith(['g:g2', 'g:g1', 'f:dr1'], 0)
    expect(wrapper.findAll('.group-toggle').slice(0, 2).map(x => x.text())).toEqual(['乙 0', '甲 0'])
    await wrapper.get('[aria-label="甲的操作"]').trigger('click'); await flushPromises()
    ;(document.querySelector('[role=menuitem][aria-label="上移分组"]') as HTMLButtonElement).click(); await flushPromises()
    expect(saveDrawingOrder).toHaveBeenLastCalledWith(['g:g1', 'g:g2', 'f:dr1'], 1)
    await wrapper.findAll('.group-header')[0]!.trigger('dragstart', { dataTransfer: transfer })
    await wrapper.findAll('.group-header')[1]!.trigger('drop', { dataTransfer: transfer, clientY: 1 }); await flushPromises()
    expect(saveDrawingOrder).toHaveBeenLastCalledWith(['g:g2', 'g:g1', 'f:dr1'], 2)
    wrapper.unmount()
  })

  it('其他设备修改排序时不覆盖，重新读取后可重试', async () => {
    vi.mocked(fetchDrawings).mockResolvedValue([drawing({ id: 'a', title: 'A' }), drawing({ id: 'b', title: 'B' })])
    vi.mocked(fetchDrawingGroups).mockResolvedValueOnce({ groups: [], assignments: [], order: [], revision: 1 }).mockResolvedValueOnce({ groups: [], assignments: [], order: ['f:b', 'f:a'], revision: 2 })
    vi.mocked(saveDrawingOrder).mockRejectedValueOnce(new Error('排序已在其他页面修改'))
    window.history.replaceState({}, '', '/drawings')
    const wrapper = mount(DrawingsView, { attachTo: document.body }); await flushPromises()
    const transfer = { effectAllowed: '', dropEffect: '', setData: vi.fn() }
    await wrapper.findAll('.file-row')[1]!.trigger('dragstart', { dataTransfer: transfer })
    await wrapper.findAll('.file-row')[0]!.trigger('drop', { dataTransfer: transfer, clientY: 0 }); await flushPromises()
    expect(wrapper.findAll('.file-main strong').map(x => x.text())).toEqual(['A', 'B'])
    expect(wrapper.get('.group-error').text()).toContain('其他页面')
    await wrapper.get('.group-error button').trigger('click'); await flushPromises()
    expect(wrapper.findAll('.file-main strong').map(x => x.text())).toEqual(['B', 'A'])
    wrapper.unmount()
  })

  it('目录宽度支持键盘调整并按成员保存', async () => {
    const wrapper = mount(DrawingsView)
    await flushPromises()
    const separator = wrapper.get('[aria-label="草图目录宽度"]')
    await separator.trigger('keydown', { key: 'ArrowRight' })
    expect(separator.attributes('aria-valuenow')).toBe('296')
    expect(localStorage.getItem('wao:drawing-sidebar-width:member-a')).toBe('296')
    wrapper.unmount()
  })

  it('返回页签无感核对版本，云端变更不会覆盖并发输入', async () => {
    const visible = ref(true)
    const wrapper = mount(DrawingsView, { global: { provide: { [pageVisibleKey as symbol]: visible } } })
    await flushPromises()
    const original = wrapper.findComponent(DrawingCanvas).element
    visible.value = false; await wrapper.vm.$nextTick()
    vi.mocked(fetchDrawing).mockResolvedValueOnce(drawing())
    visible.value = true; await flushPromises()
    expect(wrapper.findComponent(DrawingCanvas).element).toBe(original)
    visible.value = false; await wrapper.vm.$nextTick()
    const canvas = wrapper.findComponent(DrawingCanvas)
    canvas.vm.$emit('change', '{"elements":[{"id":"local"}],"appState":{}}')
    vi.mocked(fetchDrawing).mockResolvedValueOnce(drawing({ revision: 6, sceneJson: '{"elements":[{"id":"remote"}],"appState":{}}' }))
    visible.value = true; await flushPromises()
    expect(wrapper.text()).toContain('本机修改已保留')
    expect(wrapper.findComponent(DrawingCanvas).element).toBe(original)
    wrapper.unmount()
  })

  it('保留实例时显式草图链接仍可切换到目标文件', async () => {
    const route = reactive({ query: { drawing: 'dr1' } })
    vi.mocked(fetchDrawings).mockResolvedValue([drawing(), drawing({ id: 'dr2' })])
    vi.mocked(fetchDrawing).mockImplementation(async id => drawing({ id, title: id }))
    const wrapper = mount(DrawingsView, { global: { provide: { [routeLocationKey as symbol]: route } } })
    await flushPromises()
    expect(wrapper.get('.title-trigger').text()).toContain('dr1')
    route.query.drawing = 'dr2'; await flushPromises()
    expect(wrapper.get('.title-trigger').text()).toContain('dr2')
    wrapper.unmount()
  })

  it('后台版本变化时刷新画布，权限失效时不继续展示缓存', async () => {
    const visible = ref(true)
    const wrapper = mount(DrawingsView, { global: { provide: { [pageVisibleKey as symbol]: visible } } })
    await flushPromises()
    const firstCanvas = wrapper.findComponent(DrawingCanvas).element
    visible.value = false; await wrapper.vm.$nextTick()
    vi.mocked(fetchDrawing).mockResolvedValueOnce(drawing({ revision: 6, sceneJson: '{"elements":[{"id":"remote"}],"appState":{}}' }))
    visible.value = true; await flushPromises()
    expect(wrapper.findComponent(DrawingCanvas).element).not.toBe(firstCanvas)
    expect(wrapper.text()).toContain('版本 6')
    visible.value = false; await wrapper.vm.$nextTick()
    vi.mocked(fetchDrawing).mockRejectedValueOnce(Object.assign(new Error('无权访问'), { status: 404 }))
    visible.value = true; await flushPromises()
    expect(wrapper.findComponent(DrawingCanvas).exists()).toBe(false)
    expect(wrapper.text()).toContain('当前账号无权访问')
    wrapper.unmount()
  })

  it('分组读取失败可重试，不阻止打开已有草图', async () => {
    vi.mocked(fetchDrawingGroups).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ groups: [{ id: 'g1', name: '项目' }], assignments: [] })
    const wrapper = mount(DrawingsView)
    await flushPromises()
    expect(wrapper.get('.group-error').text()).toContain('读取失败')
    expect(wrapper.find('.drawing-canvas-stub').exists()).toBe(true)
    await wrapper.get('.group-error button').trigger('click'); await flushPromises()
    expect(wrapper.find('.group-error').exists()).toBe(false)
    expect(wrapper.get('.drawing-group').text()).toContain('项目')
    wrapper.unmount()
  })

  it('右键菜单支持创建分组并把文件移入分组', async () => {
    vi.mocked(createDrawingGroup).mockResolvedValue({ id: 'g1', name: '项目' })
    vi.mocked(moveDrawing).mockResolvedValue(undefined)
    const wrapper = mount(DrawingsView, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('.file-row').trigger('contextmenu')
    await flushPromises()
    ;(document.querySelector('[role=menuitem][aria-label="新建分组"]') as HTMLButtonElement).click()
    await flushPromises()
    const input = document.querySelector('.name-dialog input') as HTMLInputElement
    input.value = '项目'; input.dispatchEvent(new Event('input', { bubbles: true }))
    ;(document.querySelector('.name-dialog') as HTMLFormElement).dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()
    expect(createDrawingGroup).toHaveBeenCalledWith('项目')
    await wrapper.get('.file-row').trigger('contextmenu'); await flushPromises()
    ;(document.querySelector('[role=menuitem][aria-label="移至 项目"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(moveDrawing).toHaveBeenCalledWith('dr1', 'g1')
    expect(wrapper.findAll('.drawing-group')[0]!.text()).toContain('测试草图')
    wrapper.unmount()
  })

  it('复制已保存草图并保留分组', async () => {
    vi.mocked(fetchDrawingGroups).mockResolvedValueOnce({ groups: [{ id: 'g1', name: '项目' }], assignments: [{ fileId: 'dr1', groupId: 'g1' }] }).mockResolvedValueOnce({ groups: [{ id: 'g1', name: '项目' }], assignments: [{ fileId: 'dr1', groupId: 'g1' }, { fileId: 'copy1', groupId: 'g1' }] })
    vi.mocked(copyDrawing).mockResolvedValue(drawing({ id: 'copy1', title: '测试草图 副本' }))
    vi.mocked(fetchDrawing).mockImplementation(async id => drawing({ id, title: id === 'copy1' ? '测试草图 副本' : '测试草图' }))
    const wrapper = mount(DrawingsView, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('.file-row').trigger('contextmenu'); await flushPromises()
    ;(document.querySelector('[role=menuitem][aria-label="复制当前文件"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(copyDrawing).toHaveBeenCalledWith('dr1')
    expect(wrapper.get('.title-trigger').text()).toContain('副本')
    expect(wrapper.findAll('.drawing-group')[0]!.text()).toContain('测试草图 副本')
    wrapper.unmount()
  })

  it('导出当前画布的源文件包含尚未保存的内容', async () => {
    const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const wrapper = mount(DrawingsView, { attachTo: document.body })
    await flushPromises()
    const scene = '{"type":"excalidraw","elements":[{"id":"local"}],"appState":{}}'
    wrapper.findComponent(DrawingCanvas).vm.$emit('change', scene)
    await wrapper.get('.file-row').trigger('contextmenu'); await flushPromises()
    ;(document.querySelector('[role=menuitem][aria-label="导出源文件"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(create).toHaveBeenCalledOnce()
    const exported = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.onerror = () => reject(reader.error)
      reader.readAsText(create.mock.calls[0]![0] as Blob)
    })
    expect(exported).toBe(scene)
    expect(click).toHaveBeenCalledOnce()
    expect(saveDrawing).not.toHaveBeenCalled()
    wrapper.unmount(); create.mockRestore(); revoke.mockRestore(); click.mockRestore()
  })

  it('保存失败时不复制草图并保留当前内容', async () => {
    const wrapper = mount(DrawingsView, { attachTo: document.body })
    await flushPromises()
    const canvas = wrapper.findComponent(DrawingCanvas)
    canvas.vm.$emit('change', '{"type":"excalidraw","elements":[{"id":"x"}],"appState":{}}')
    vi.mocked(saveDrawing).mockRejectedValueOnce(new Error('保存失败'))
    await wrapper.get('.file-row').trigger('contextmenu'); await flushPromises()
    ;(document.querySelector('[role=menuitem][aria-label="复制当前文件"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(copyDrawing).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('保存失败')
    wrapper.unmount()
  })

  it('打开未修改草图不触发保存并展示最近三个版本', async () => {
    const wrapper = mount(DrawingsView)
    await flushPromises()

    expect(saveDrawing).not.toHaveBeenCalled()
    expect(fetchDrawingVersions).toHaveBeenCalledWith('dr1')
    await wrapper.get('.version-menu summary').trigger('click')
    expect(wrapper.findAll('.version-popover button')).toHaveLength(3)
    expect(wrapper.text()).toContain('最近三个有效版本')
    wrapper.unmount()
  })

  it('点击标题后直接改名并同步文件列表', async () => {
    vi.mocked(renameDrawing).mockResolvedValue(drawing({ title: '演示草图' }))
    const wrapper = mount(DrawingsView)
    await flushPromises()

    await wrapper.get('.title-trigger').trigger('click')
    const input = wrapper.get('input[aria-label="草图名称"]')
    await input.setValue('演示草图')
    await input.trigger('keydown', { key: 'Enter' })
    await flushPromises()

    expect(renameDrawing).toHaveBeenCalledWith('dr1', '演示草图')
    expect(wrapper.get('.title-trigger').text()).toContain('演示草图')
    expect(wrapper.get('.file-main strong').text()).toBe('演示草图')
    expect(wrapper.get('.file-row').text()).toBe('演示草图')
    expect(wrapper.find('.file-actions').exists()).toBe(false)
    expect(wrapper.get('.drawing-meta').text()).toContain('编辑于')
    expect(wrapper.find('.drawing-meta [title=删除]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('全屏按钮只把草图工作区送入浏览器全屏', async () => {
    const requestFullscreen = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(HTMLElement.prototype, 'requestFullscreen', {
      configurable: true,
      value: requestFullscreen,
    })
    const wrapper = mount(DrawingsView)
    await flushPromises()

    await wrapper.get('.fullscreen-button').trigger('click')
    await flushPromises()

    expect(requestFullscreen).toHaveBeenCalledOnce()
    expect(requestFullscreen.mock.instances[0]).toBe(wrapper.get('.drawings-page').element)
    wrapper.unmount()
  })

  it('恢复历史场景时携带当前乐观锁版本并刷新为新版本', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(restoreDrawingVersion).mockResolvedValue(
      drawing({ revision: 6, sceneJson: '{"elements":[{"id":"old"}],"appState":{}}' }),
    )
    const wrapper = mount(DrawingsView)
    await flushPromises()

    await wrapper.get('.version-menu summary').trigger('click')
    await wrapper.findAll('.version-popover button')[1]!.trigger('click')
    await flushPromises()

    expect(restoreDrawingVersion).toHaveBeenCalledWith('dr1', 4, 5)
    expect(wrapper.text()).toContain('版本 6')
    expect(fetchDrawingVersions).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('知识库内嵌打开同一画板并可重命名和编辑，不读取个人列表', async () => {
    vi.mocked(fetchDrawing).mockResolvedValue(drawing({ docId: 'doc1', kbId: 'kb1' }))
    const wrapper = mount(DrawingsView, { props: { drawingId: 'dr1', embedded: true } })
    await flushPromises()
    expect(fetchDrawing).toHaveBeenCalledWith('dr1')
    expect(fetchDrawings).not.toHaveBeenCalled()
    expect(wrapper.find('.file-sidebar').exists()).toBe(false)
    expect(wrapper.find('.title-trigger').exists()).toBe(true)
    expect(wrapper.find('.drawing-canvas-stub').exists()).toBe(true)
    vi.mocked(renameDrawing).mockResolvedValueOnce(drawing({docId:'doc1',kbId:'kb1',title:'知识库新名称'}))
    await wrapper.get('[title="重命名"]').trigger('click')
    await wrapper.get('[aria-label="草图名称"]').setValue('知识库新名称')
    await wrapper.get('[aria-label="草图名称"]').trigger('keydown',{key:'Enter'})
    await flushPromises()
    expect(renameDrawing).toHaveBeenCalledWith('dr1','知识库新名称')
    wrapper.findComponent(DrawingCanvas).vm.$emit('change','{"elements":[{"id":"linked-edit"}],"appState":{}}')
    vi.mocked(saveDrawing).mockResolvedValueOnce(drawing({docId:'doc1',kbId:'kb1',title:'知识库新名称',revision:6}))
    await (wrapper.vm as unknown as {flush:()=>Promise<void>}).flush()
    expect(saveDrawing).toHaveBeenCalledWith('dr1',expect.stringContaining('linked-edit'),5)
    wrapper.unmount()
  })
})
