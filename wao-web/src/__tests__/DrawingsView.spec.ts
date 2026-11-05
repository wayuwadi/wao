import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DrawingsView from '@/views/DrawingsView.vue'
import DrawingCanvas from '@/components/drawings/DrawingCanvas.vue'
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ userId: 'member-a' }) }))
import {
  fetchDrawing,
  fetchDrawings,
  fetchDrawingVersions,
  renameDrawing,
  restoreDrawingVersion,
  saveDrawing,
  type DrawingFile,
} from '@/api/drawings'

vi.mock('@/api/drawings', () => ({
  createDrawing: vi.fn(),
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
