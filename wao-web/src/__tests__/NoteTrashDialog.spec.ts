import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import KnowledgeTrashDialog from '@/components/knowledge/KnowledgeTrashDialog.vue'
import { apiRestoreTrash, fetchTrash } from '@/api/board'
vi.mock('@/api/board', () => ({ fetchTrash: vi.fn(), apiRestoreTrash: vi.fn() }))
afterEach(() => { document.body.innerHTML = ''; vi.clearAllMocks() })
const item = { type: 'doc' as const, id: 'deleted', name: '待恢复', parent: '文档 · 小记 / wage', deletedAt: '2026-10-06T01:00:00+08:00', deletedBy: null, expiresAt: '2026-10-13T01:00:00+08:00' }
it('只展示小记，恢复失败可重试，成功后通知刷新且不永久删除', async () => {
  vi.mocked(fetchTrash).mockResolvedValue([item, { ...item, id: 'other', parent: '文档 · 工作' }])
  vi.mocked(apiRestoreTrash).mockRejectedValueOnce(new Error('暂时不可用')).mockResolvedValueOnce(undefined)
  const w = mount(KnowledgeTrashDialog, { props: { kbName: '小记' }, attachTo: document.body })
  await flushPromises()
  expect(document.querySelectorAll('li')).toHaveLength(1)
  document.querySelector<HTMLButtonElement>('[aria-label="恢复小记：待恢复"]')!.click(); await flushPromises()
  expect(document.querySelector('[role="alert"]')?.textContent).toContain('暂时不可用')
  expect(w.emitted('restored')).toBeUndefined()
  document.querySelector<HTMLButtonElement>('[aria-label="恢复小记：待恢复"]')!.click(); await flushPromises()
  expect(apiRestoreTrash).toHaveBeenLastCalledWith('doc', 'deleted')
  expect(w.emitted('restored')).toEqual([['deleted']])
  expect(document.querySelector('li')).toBeNull()
  w.unmount()
})
it('读取失败显示重试，关闭后的晚到响应不会恢复或发出事件', async () => {
  vi.mocked(fetchTrash).mockRejectedValueOnce(new Error('读取失败')).mockResolvedValueOnce([item])
  let finish!: () => void
  vi.mocked(apiRestoreTrash).mockReturnValue(new Promise<void>(resolve => { finish = resolve }))
  const w = mount(KnowledgeTrashDialog, { props: { kbName: '小记' }, attachTo: document.body })
  await flushPromises()
  document.querySelector<HTMLButtonElement>('[role="alert"] button')!.click(); await flushPromises()
  document.querySelector<HTMLButtonElement>('[aria-label="恢复小记：待恢复"]')!.click()
  w.unmount(); finish(); await flushPromises()
  expect(w.emitted('restored')).toBeUndefined()
})

it('知识库回收站按条目类型原位恢复关联画板', async () => {
  vi.mocked(fetchTrash).mockResolvedValue([
    { ...item, id: 'drawing-doc', name: '知识库画板', parent: '文档 · 工作 / 画板' },
    { ...item, id: 'deleted-folder', type: 'folder', name: '待恢复文件夹', parent: '文件夹 · 工作' },
  ])
  vi.mocked(apiRestoreTrash).mockResolvedValue(undefined)
  const wrapper = mount(KnowledgeTrashDialog, { attachTo: document.body })
  await flushPromises()
  const dialog = document.querySelector('[role="dialog"][aria-label="知识库回收站"]')!
  expect(dialog.querySelectorAll('li')).toHaveLength(2)
  dialog.querySelector<HTMLButtonElement>('[aria-label="恢复：知识库画板"]')!.click()
  await flushPromises()
  expect(apiRestoreTrash).toHaveBeenCalledExactlyOnceWith('doc', 'drawing-doc')
  expect(wrapper.emitted('restored')).toEqual([['drawing-doc']])
  expect(dialog.textContent).toContain('已恢复「知识库画板」')
  expect(dialog.querySelectorAll('li')).toHaveLength(1)
  wrapper.unmount()
})

it('草图回收站查询个人草图并按草图类型恢复', async () => {
  vi.mocked(fetchTrash).mockResolvedValue([{ ...item, id: 'dr1', type: 'drawing', name: '临时草图', parent: '临时分组' }])
  vi.mocked(apiRestoreTrash).mockResolvedValue(undefined)
  const wrapper = mount(KnowledgeTrashDialog, { props: { drawings: true }, attachTo: document.body })
  await flushPromises()
  const dialog = document.querySelector('[role="dialog"][aria-label="草图回收站"]')!
  expect(fetchTrash).toHaveBeenCalledExactlyOnceWith('drawing')
  expect(dialog.textContent).toContain('临时分组')
  dialog.querySelector<HTMLButtonElement>('[aria-label="恢复：临时草图"]')!.click()
  await flushPromises()
  expect(apiRestoreTrash).toHaveBeenCalledExactlyOnceWith('drawing', 'dr1')
  expect(wrapper.emitted('restored')).toEqual([['dr1']])
  wrapper.unmount()
})
