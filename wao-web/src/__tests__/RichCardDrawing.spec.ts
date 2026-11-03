import { beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import type { NodeViewProps } from '@tiptap/vue-3'
import RichCardNodeView from '@/components/ui/RichCardNodeView.vue'
import { fetchDrawing, renameDrawing } from '@/api/drawings'
import { apiUploadImage } from '@/api/board'

vi.mock('@tiptap/vue-3', () => ({ NodeViewWrapper: { template: '<div><slot /></div>' } }))
vi.mock('@/api/drawings', () => ({ fetchDrawing: vi.fn(), renameDrawing: vi.fn() }))
vi.mock('@/api/board', () => ({ apiUploadImage: vi.fn() }))
vi.mock('@excalidraw/excalidraw', () => ({ exportToBlob: vi.fn(async () => new Blob(['preview'])) }))
vi.mock('@/views/DrawingsView.vue', () => ({ default: { setup(_: unknown, { expose }: { expose: (value: unknown) => void }) { expose({ flush: async () => {} }) }, template: '<div>画板编辑区</div>' } }))
const drawing = { id: 'dr', title: '最新草图', sceneJson: '{"elements":[]}', revision: 3, createdAt: '', updatedAt: '' }
const update = vi.fn()
function card(editable = true) {
  return mount(RichCardNodeView, { props: { editor: { isEditable: editable }, node: { attrs: { id: 'dr', title: '旧名称', snapshot: '/old.png', url: '/drawings?drawing=dr' }, type: { name: 'drawingCard' } }, updateAttributes: update } as unknown as NodeViewProps,
    global: { stubs: { SettingsDialogShell: { template: '<div class="dialog"><slot /><slot name="footer" /></div>' } } } })
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(fetchDrawing).mockResolvedValue(drawing)
  vi.mocked(renameDrawing).mockResolvedValue({ ...drawing, title: '新名称' })
  vi.mocked(apiUploadImage).mockResolvedValue({ url: '/new.png' } as Awaited<ReturnType<typeof apiUploadImage>>)
  URL.createObjectURL = vi.fn(() => 'blob:latest'); URL.revokeObjectURL = vi.fn()
})
it('打开自动读取最新画板，预览不上传图片或修改正文，移除路径和操作栏', async () => {
  const w = card(); await flushPromises()
  expect(fetchDrawing).toHaveBeenCalledWith('dr')
  expect(w.get('img').attributes('src')).toBe('blob:latest')
  expect(w.text()).toContain('最新草图')
  expect(w.text()).not.toMatch(/刷新快照|编辑画板|打开画板|\/drawings/)
  expect(apiUploadImage).not.toHaveBeenCalled(); expect(update).not.toHaveBeenCalled()
  w.unmount(); expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:latest')
})
it('标题点击编辑并保存到草图及正文，失败时保留输入', async () => {
  const w = card(); await flushPromises()
  await w.get('.drawing-caption button').trigger('click')
  await w.get('input').setValue('新名称')
  vi.mocked(renameDrawing).mockRejectedValueOnce(new Error('offline'))
  await w.get('input').trigger('keydown', { key: 'Enter' }); await flushPromises()
  expect(w.get('input').element.value).toBe('新名称')
  expect(w.get('[role="alert"]').text()).toContain('失败')
  await w.get('input').trigger('keydown', { key: 'Enter' }); await flushPromises()
  expect(renameDrawing).toHaveBeenLastCalledWith('dr', '新名称')
  expect(update).toHaveBeenCalledWith({ title: '新名称' }); w.unmount()
})
it('单击不打开，双击编辑，完成后保存最新快照', async () => {
  const w = card(); await flushPromises()
  await w.get('.drawing-preview').trigger('click'); expect(w.find('.dialog').exists()).toBe(false)
  await w.get('.drawing-preview').trigger('dblclick'); await flushPromises()
  expect(w.find('.dialog').exists()).toBe(true)
  await w.get('.dialog button').trigger('click'); await flushPromises()
  expect(update).toHaveBeenCalledWith({ snapshot: '/new.png', title: '最新草图' })
  expect(w.find('.dialog').exists()).toBe(false); w.unmount()
})
it('读取失败保留已有快照；只读分享不发私有请求或允许编辑', async () => {
  vi.mocked(fetchDrawing).mockRejectedValueOnce(new Error('offline'))
  let w = card(); await flushPromises(); expect(w.get('img').attributes('src')).toBe('/old.png'); w.unmount()
  vi.mocked(fetchDrawing).mockClear()
  w = card(false); await flushPromises(); await w.get('.drawing-preview').trigger('dblclick')
  expect(fetchDrawing).not.toHaveBeenCalled(); expect(w.find('.dialog').exists()).toBe(false); w.unmount()
})
it('完成时快照上传失败保留画板编辑窗口，重试成功才关闭', async () => {
  const w = card(); await flushPromises()
  await w.get('.drawing-preview').trigger('dblclick'); await flushPromises()
  vi.mocked(apiUploadImage).mockRejectedValueOnce(new Error('offline'))
  await w.get('.dialog button').trigger('click'); await flushPromises()
  expect(w.find('.dialog').exists()).toBe(true)
  expect(w.get('[role="alert"]').text()).toContain('预览更新失败')
  await w.get('.dialog button').trigger('click'); await flushPromises()
  expect(w.find('.dialog').exists()).toBe(false); w.unmount()
})
