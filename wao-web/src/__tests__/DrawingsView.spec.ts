import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DrawingsView from '@/views/DrawingsView.vue'
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
  beforeEach(() => {
    vi.clearAllMocks()
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
  it('知识库内嵌打开同一画板，不读取个人列表或显示重命名删除入口', async () => {
    vi.mocked(fetchDrawing).mockResolvedValue(drawing({ docId: 'doc1', kbId: 'kb1' }))
    const wrapper = mount(DrawingsView, { props: { drawingId: 'dr1', embedded: true } })
    await flushPromises()
    expect(fetchDrawing).toHaveBeenCalledWith('dr1')
    expect(fetchDrawings).not.toHaveBeenCalled()
    expect(wrapper.find('.file-sidebar').exists()).toBe(false)
    expect(wrapper.find('.title-trigger').exists()).toBe(false)
    expect(wrapper.find('.drawing-canvas-stub').exists()).toBe(true)
    wrapper.unmount()
  })
})
