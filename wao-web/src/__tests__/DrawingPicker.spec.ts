import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import type { Editor } from '@tiptap/core'
import MarkdownEditor from '@/components/ui/MarkdownEditor.vue'
import { createDrawing, fetchDrawing, fetchDrawings } from '@/api/drawings'

vi.mock('@/api/drawings', () => ({
  createDrawing: vi.fn(),
  fetchDrawing: vi.fn(),
  fetchDrawings: vi.fn(),
  renameDrawing: vi.fn(),
}))

const drawings = Array.from({ length: 8 }, (_, i) => ({
  id: `drawing-${i}`, title: `画板 ${i}`, revision: 1,
  createdAt: '2026-11-01', updatedAt: `2026-11-0${8 - i}`,
}))
let pinia = createPinia()

beforeEach(() => {
  vi.clearAllMocks()
  pinia = createPinia()
  setActivePinia(pinia)
  vi.mocked(fetchDrawings).mockResolvedValue(drawings)
  vi.mocked(fetchDrawing).mockImplementation(async id => ({
    ...drawings.find(item => item.id === id)!, sceneJson: '{"elements":[]}',
  }))
  if (!Range.prototype.getClientRects) Object.defineProperty(Range.prototype, 'getClientRects', { configurable: true, value: () => [] })
  if (!Range.prototype.getBoundingClientRect) Object.defineProperty(Range.prototype, 'getBoundingClientRect', { configurable: true, value: () => new DOMRect() })
})

async function openPicker() {
  const wrapper = mount(MarkdownEditor, { props: { modelValue: '', advanced: true }, attachTo: document.body, global: { plugins: [pinia] } })
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  const editor = (wrapper.vm as unknown as { editor: Editor }).editor
  vi.spyOn(editor.view, 'coordsAtPos').mockReturnValue({ top: 0, bottom: 20, left: 0, right: 0 })
  editor.commands.insertContent('/画板')
  await nextTick()
  document.querySelector<HTMLButtonElement>('.slash-menu button')!.click()
  await flushPromises()
  return { wrapper, editor }
}

it('斜线插入默认只显示最近五个，搜索仍可选较早的画板', async () => {
  const { wrapper, editor } = await openPicker()
  const dialog = document.querySelector<HTMLElement>('[role="dialog"][aria-label="插入画板"]')!
  expect(dialog.querySelectorAll('.drawing-list button')).toHaveLength(5)
  expect(dialog.textContent).toContain('画板 0')
  expect(dialog.textContent).not.toContain('画板 7')
  expect(dialog.querySelector('.dialog-footer')?.textContent).toContain('新建并插入')
  const search = dialog.querySelector<HTMLInputElement>('[aria-label="搜索画板"]')!
  search.value = '画板 7'
  search.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  expect(dialog.querySelectorAll('.drawing-list button')).toHaveLength(1)
  dialog.querySelector<HTMLButtonElement>('.drawing-list button')!.click()
  await flushPromises()
  expect(editor.getJSON().content).toContainEqual(expect.objectContaining({ type: 'drawingCard', attrs: expect.objectContaining({ id: 'drawing-7' }) }))
  expect(document.querySelector('[aria-label="插入画板"]')).toBeNull()
  wrapper.unmount()
})

it('列表读取失败时仍可新建；创建失败保留入口，重试成功后插入', async () => {
  vi.mocked(fetchDrawings).mockRejectedValueOnce(new Error('offline'))
  vi.mocked(createDrawing).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({
    ...drawings[0]!, id: 'new-drawing', title: '文档画板', sceneJson: '{"elements":[]}',
  })
  const { wrapper, editor } = await openPicker()
  const dialog = document.querySelector<HTMLElement>('[role="dialog"][aria-label="插入画板"]')!
  expect(dialog.textContent).toContain('画板列表加载失败')
  const create = [...dialog.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.includes('新建并插入'))!
  expect(create.disabled).toBe(false)
  create.click()
  await flushPromises()
  expect(dialog.textContent).toContain('新建画板失败')
  create.click()
  await flushPromises()
  expect(createDrawing).toHaveBeenCalledTimes(2)
  expect(editor.getJSON().content).toContainEqual(expect.objectContaining({ type: 'drawingCard', attrs: expect.objectContaining({ id: 'new-drawing' }) }))
  wrapper.unmount()
})
