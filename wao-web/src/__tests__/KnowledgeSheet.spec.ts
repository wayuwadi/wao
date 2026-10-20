import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import KnowledgeSheet from '@/components/knowledge/KnowledgeSheet.vue'
import { emptySheet, readSheet, sheetContent, parseSheetClipboard, sheetClipboard } from '@/utils/knowledgeSheet'

describe('独立表格', () => {
  it('保留中文、换行、引号和公式原文，导出后能重新粘贴', () => {
    const cells = [['中文', 'a\nb', 'a"b'], ['=SUM(A1:A3)', '0012', 'a\tb']]
    expect(readSheet(sheetContent(cells))).toEqual(cells)
    expect(parseSheetClipboard(sheetClipboard(cells))).toEqual(cells)
    expect(parseSheetClipboard('A\tB\r\nC\tD\r\n')).toEqual([['A', 'B'], ['C', 'D']])
  })
  it('编辑与多格粘贴发出完整正文，刷新后重新读取', async () => {
    const wrapper = mount(KnowledgeSheet, { props: { content: sheetContent(emptySheet()) } })
    await wrapper.get('[aria-label="A1"]').setValue('持久化内容')
    let content = wrapper.emitted('change')![wrapper.emitted('change')!.length - 1]![0] as ReturnType<typeof sheetContent>
    expect(readSheet(content)[0]![0]).toBe('持久化内容')
    await wrapper.get('[aria-label="B2"]').trigger('paste', { clipboardData: { getData: () => '甲\t乙\n丙\t丁' } })
    content = wrapper.emitted('change')![wrapper.emitted('change')!.length - 1]![0] as ReturnType<typeof sheetContent>
    expect(readSheet(content)[2]![2]).toBe('丁')
    wrapper.unmount()
    const reopened = mount(KnowledgeSheet, { props: { content } })
    expect((reopened.get('[aria-label="C3"]').element as HTMLInputElement).value).toBe('丁')
    reopened.unmount()
  })
  it('过大粘贴不改写原内容；不将损坏数据显示为空表格', async () => {
    const wrapper = mount(KnowledgeSheet)
    await wrapper.get('[aria-label="A1"]').trigger('paste', { clipboardData: { getData: () => Array(101).fill('x').join('\n') } })
    expect(wrapper.emitted('change')).toBeUndefined()
    expect(wrapper.get('[role="alert"]').text()).toContain('100')
    await wrapper.setProps({ content: { version: 1, document: { type: 'doc', content: [{ type: 'paragraph' }] }, markdown: '' } })
    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('无法读取')
    wrapper.unmount()
  })
})
