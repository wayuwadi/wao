import { describe, expect, it } from 'vitest'
import ExcelJS from 'exceljs'
import type { IWorkbookData } from '@univerjs/core'
import { excelWorkbook } from '@/utils/workbookExcel'
import { readWorkbook, sheetContent } from '@/utils/knowledgeSheet'

function sample() {
  const book = readWorkbook(sheetContent([['0012', '=literal'], ['换行\n中文', '']]), 'test') as IWorkbookData
  const sheet = book.sheets.sheet1!
  sheet.name = '清单'
  sheet.cellData[2] = { 0: { v: 12, t: 2, s: 'number' }, 1: { f: '=A3*2', v: 24, t: 2 }, 2: { v: false, t: 3 } }
  sheet.cellData[3] = { 0: { v: '合并' } }
  sheet.mergeData = [{ startRow: 3, endRow: 3, startColumn: 0, endColumn: 1 }]
  sheet.columnData = { 0: { w: 145 } }; sheet.rowData = { 2: { h: 40 } }
  book.styles = { number: { bl: 1, bg: { rgb: '#FFFF00' }, n: { pattern: '0.00' }, bd: { t: { s: 1, cl: { rgb: '#000000' } } } } }
  book.sheetOrder.push('summary')
  book.sheets.summary = { ...sheet, id: 'summary', name: '汇总', mergeData: [], cellData: { 0: { 0: { f: "='清单'!A3", v: 12 } } } }
  return book
}

describe('Excel 导出', () => {
  it('生成可回读 XLSX，保留多表、值类型、公式、合并与常用格式', async () => {
    const source = sample(), before = JSON.stringify(source)
    const bytes = await excelWorkbook(source).xlsx.writeBuffer()
    const result = new ExcelJS.Workbook()
    await result.xlsx.load(bytes)
    expect(result.worksheets.map(s => s.name)).toEqual(['清单', '汇总'])
    const sheet = result.worksheets[0]!
    expect(sheet.getCell('A1').value).toBe('0012')
    expect(sheet.getCell('B1').value).toBe('=literal')
    expect(sheet.getCell('A2').value).toBe('换行\n中文')
    expect(sheet.getCell('B3').value).toMatchObject({ formula: 'A3*2', result: 24 })
    expect(sheet.getCell('C3').value).toBe(false)
    expect(sheet.getCell('B4').master.address).toBe('A4')
    expect(sheet.getCell('A3').numFmt).toBe('0.00')
    expect(sheet.getCell('A3').font.bold).toBe(true)
    expect(sheet.getCell('A3').border.top?.style).toBe('thin')
    expect(sheet.getColumn(1).width).toBe(20)
    expect(sheet.getRow(3).height).toBe(30)
    expect(result.worksheets[1]!.getCell('A1').formula).toBe("'清单'!A3")
    expect(JSON.stringify(source)).toBe(before)
  })
  it('拒绝会破坏 Excel 跨表引用的名称，不偷偷重命名', () => {
    const source = sample(); source.sheets.sheet1!.name = 'invalid/name'
    expect(() => excelWorkbook(source)).toThrow('工作表名称')
  })
})
