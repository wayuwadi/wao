import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import NoteTrashDialog from '@/components/notes/NoteTrashDialog.vue'
import { apiRestoreTrash, fetchTrash } from '@/api/board'
vi.mock('@/api/board', () => ({ fetchTrash: vi.fn(), apiRestoreTrash: vi.fn() }))
afterEach(() => { document.body.innerHTML = ''; vi.clearAllMocks() })
const item = { type: 'doc' as const, id: 'deleted', name: '待恢复', parent: '文档 · 小记 / wage', deletedAt: '2026-10-06T01:00:00+08:00', deletedBy: null, expiresAt: '2026-10-13T01:00:00+08:00' }
it('只展示小记，恢复失败可重试，成功后通知刷新且不永久删除', async () => {
  vi.mocked(fetchTrash).mockResolvedValue([item, { ...item, id: 'other', parent: '文档 · 工作' }])
  vi.mocked(apiRestoreTrash).mockRejectedValueOnce(new Error('暂时不可用')).mockResolvedValueOnce(undefined)
  const w = mount(NoteTrashDialog, { props: { kbName: '小记' }, attachTo: document.body })
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
  const w = mount(NoteTrashDialog, { props: { kbName: '小记' }, attachTo: document.body })
  await flushPromises()
  document.querySelector<HTMLButtonElement>('[role="alert"] button')!.click(); await flushPromises()
  document.querySelector<HTMLButtonElement>('[aria-label="恢复小记：待恢复"]')!.click()
  w.unmount(); finish(); await flushPromises()
  expect(w.emitted('restored')).toBeUndefined()
})
