import { beforeEach, describe, it, expect, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import KnowledgeSheet from '@/components/knowledge/KnowledgeSheet.vue'
import { readWorkbook, sheetContent, workbookContent } from '@/utils/knowledgeSheet'
import type { IWorkbookData } from '@univerjs/core'

const engine = vi.hoisted(() => ({ snapshot: {} as Record<string, unknown>, change: (_command: { type: number }) => {}, dispose: vi.fn(), disposeUnit: vi.fn(), init: vi.fn(), editable: vi.fn(), create: vi.fn(), endEdit: vi.fn() }))
vi.mock('@univerjs/presets', () => ({
  LocaleType: { ZH_CN: 'zhCN' }, CommandType: { MUTATION: 2 }, mergeLocales: () => ({}),
  createUniver: () => (engine.init(), { univer: { dispose: engine.dispose }, univerAPI: { disposeUnit: engine.disposeUnit, toggleDarkMode: vi.fn(), createWorkbook: (snapshot: Record<string, unknown>) => {
    engine.create(snapshot); engine.snapshot = snapshot
    return { getId: () => snapshot.id as string, save: () => engine.snapshot, getWorkbookPermission: () => ({ setMode: engine.editable }), onCommandExecuted: (callback: typeof engine.change) => { engine.change = callback; return { dispose: vi.fn() } }, isCellEditing: () => true, endEditingAsync: engine.endEdit }
  } } }),
}))
vi.mock('@univerjs/preset-sheets-core', () => ({ UniverSheetsCorePreset: () => ({}) }))
vi.mock('@univerjs/preset-sheets-filter', () => ({ UniverSheetsFilterPreset: () => ({}) }))
vi.mock('@univerjs/preset-sheets-sort', () => ({ UniverSheetsSortPreset: () => ({}) }))
vi.mock('@univerjs/preset-sheets-core/locales/zh-CN', () => ({ default: {} }))
vi.mock('@univerjs/preset-sheets-filter/locales/zh-CN', () => ({ default: {} }))
vi.mock('@univerjs/preset-sheets-sort/locales/zh-CN', () => ({ default: {} }))

function book() {
  const snapshot = readWorkbook(sheetContent([['原值']]), 'book') as IWorkbookData
  snapshot.sheets.sheet1!.cellData = { 0: { 0: { v: 7, t: 2 }, 1: { v: 14, t: 2, f: '=A1*2', s: 'bold' } } }
  snapshot.styles = { bold: { bl: 1 } }
  snapshot.sheetOrder.push('second')
  snapshot.sheets.second = { ...snapshot.sheets.sheet1!, id: 'second', name: '预算', cellData: {} }
  return snapshot
}
describe('电子表格工作簿', () => {
  beforeEach(() => { vi.clearAllMocks(); engine.endEdit.mockResolvedValue(true) })
  it('迁移旧表格为网格工作簿，保留文本而不把旧公式原文偷偷执行', () => {
    const result = readWorkbook(sheetContent([['0012', '=SUM(A1:A3)', '多行\n文本']]), 'legacy')
    expect(result.sheets?.sheet1?.cellData?.[0]?.[1]).toEqual({ v: '=SUM(A1:A3)', t: 1 })
    expect(result.sheets?.sheet1?.rowCount).toBe(1000)
  })
  it('保存与重开保留全部 Sheet、公式及格式，转义可读投影', () => {
    const snapshot = book()
    snapshot.sheets.second!.cellData = { 0: { 0: { v: '<img src=x>' } } }
    const content = workbookContent(snapshot)
    const restored = readWorkbook(JSON.parse(JSON.stringify(content)), 'ignored')
    expect(restored).toEqual(snapshot)
    expect(content.markdown).toContain('&lt;img src=x&gt;')
    expect(restored).not.toBe(snapshot)
  })
  it('编辑保存整本工作簿，父组件回传保存值不重建正在编辑的画布', async () => {
    const wrapper = mount(KnowledgeSheet, { props: { content: workbookContent(book()), documentId: 'd1' } })
    await vi.dynamicImportSettled(); await flushPromises()
    engine.snapshot = book() as unknown as Record<string, unknown>
    ;(engine.snapshot as unknown as IWorkbookData).sheets.second!.name = '已改名'
    engine.change({ type: 2 })
    const updates = wrapper.emitted('change')!
    expect(updates).toHaveLength(1)
    await wrapper.setProps({ content: updates[0]![0] as ReturnType<typeof workbookContent> })
    await vi.dynamicImportSettled(); await flushPromises()
    expect(engine.create).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ disabled: true })
    await vi.dynamicImportSettled(); await flushPromises()
    expect(engine.editable).toHaveBeenLastCalledWith('viewer')
    engine.change({ type: 2 })
    expect(wrapper.emitted('change')).toHaveLength(1)
    wrapper.unmount()
    expect(engine.dispose).toHaveBeenCalledOnce()
  })
  it('切换工作簿复用引擎并释放旧工作簿', async () => {
    const wrapper = mount(KnowledgeSheet, { props: { content: workbookContent(book()), documentId: 'd1' } })
    await vi.dynamicImportSettled(); await flushPromises()
    const next = book(); next.id = 'next'
    await wrapper.setProps({ documentId: 'd2', content: workbookContent(next) })
    await vi.dynamicImportSettled(); await flushPromises()
    expect(engine.init).toHaveBeenCalledTimes(1)
    expect(engine.disposeUnit).toHaveBeenCalledWith('book')
    expect(engine.create).toHaveBeenCalledTimes(2)
    expect(engine.snapshot).toEqual(next)
    wrapper.unmount()
  })
  it('离开前提交仍在编辑的单元格；损坏快照显示错误而不伪装空工作簿', async () => {
    const wrapper = mount(KnowledgeSheet, { props: { content: workbookContent(book()) } })
    await vi.dynamicImportSettled(); await flushPromises()
    await wrapper.vm.flush()
    expect(engine.endEdit).toHaveBeenCalledWith(true)
    wrapper.unmount()
    const broken = mount(KnowledgeSheet, { props: { content: { version: 1, document: { type: 'waoWorkbook', attrs: { snapshot: {} } }, markdown: '' } } })
    await vi.dynamicImportSettled(); await flushPromises()
    expect(broken.get('[role="alert"]').text()).toContain('原内容已保留')
    broken.unmount()
  })
})
