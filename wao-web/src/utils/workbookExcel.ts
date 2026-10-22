import ExcelJS from 'exceljs'
import type { ICellData, IColorStyle, IStyleData, IWorkbookData } from '@univerjs/core'

function color(value?: IColorStyle | null | void): Partial<ExcelJS.Color> | undefined {
  const rgb = value?.rgb
  if (!rgb) return undefined
  if (/^#[\da-f]{6}$/i.test(rgb)) return { argb: `FF${rgb.slice(1).toUpperCase()}` }
  if (/^#[\da-f]{3}$/i.test(rgb)) return { argb: `FF${rgb.slice(1).split('').map(c => c + c).join('').toUpperCase()}` }
  const parts = rgb.match(/^rgba?\(\s*(\d+)[, ]+\s*(\d+)[, ]+\s*(\d+)/)
  return parts ? { argb: 'FF' + parts.slice(1, 4).map(c => Math.min(255, Number(c)).toString(16).padStart(2, '0')).join('').toUpperCase() } : undefined
}
function styleFor(book: IWorkbookData, style: ICellData['s']): IStyleData {
  return (typeof style === 'string' ? book.styles?.[style] : style) ?? {}
}
function applyStyle(cell: ExcelJS.Cell, style: IStyleData) {
  cell.font = { name: style.ff ?? undefined, size: style.fs, bold: style.bl === 1, italic: style.it === 1, underline: style.ul?.s === 1, strike: style.st?.s === 1, color: color(style.cl) }
  if (color(style.bg)) cell.fill = { type: 'pattern', pattern: 'solid', fgColor: color(style.bg)! }
  if (style.n?.pattern) cell.numFmt = style.n.pattern
  cell.alignment = {
    horizontal: ({ 0: undefined, 1: 'left', 2: 'center', 3: 'right', 4: 'justify', 5: 'justify', 6: 'distributed' } as const)[style.ht ?? 0],
    vertical: ({ 0: undefined, 1: 'top', 2: 'middle', 3: 'bottom' } as const)[style.vt ?? 0],
    wrapText: style.tb === 3, shrinkToFit: style.stf === 1,
  }
  const borderStyles: Record<number, ExcelJS.BorderStyle> = { 1: 'thin', 2: 'hair', 3: 'dotted', 4: 'dashed', 5: 'dashDot', 6: 'dashDotDot', 7: 'double', 8: 'medium', 9: 'mediumDashed', 10: 'mediumDashDot', 11: 'mediumDashDotDot', 12: 'slantDashDot', 13: 'thick' }
  for (const [source, target] of [['t', 'top'], ['b', 'bottom'], ['l', 'left'], ['r', 'right']] as const) {
    const border = style.bd?.[source]
    if (border && borderStyles[border.s]) cell.border = { ...cell.border, [target]: { style: borderStyles[border.s], color: color(border.cl) } }
  }
}

/** Build an actual XLSX workbook. Strings beginning with '=' remain strings. */
export function excelWorkbook(snapshot: IWorkbookData) {
  const book = new ExcelJS.Workbook()
  book.calcProperties.fullCalcOnLoad = true
  const names = new Set<string>()
  for (const id of snapshot.sheetOrder) {
    const source = snapshot.sheets[id]
    if (!source) throw new Error('工作表缺失，无法导出')
    // Renaming would break cross-sheet formulas, so reject invalid Excel names explicitly.
    const name = source.name || 'Sheet'
    if (name.length > 31 || /[\\/*?:\[\]]/.test(name) || /^'|'$/.test(name) || names.has(name.toLowerCase())) throw new Error('工作表名称不符合 Excel 规则，请使用不重复且不超过 31 字符的名称')
    names.add(name.toLowerCase())
    const sheet = book.addWorksheet(name, { state: source.hidden === 2 ? 'veryHidden' : source.hidden === 1 ? 'hidden' : 'visible' })
    sheet.properties.defaultRowHeight = (source.defaultRowHeight || 24) * 0.75
    sheet.properties.defaultColWidth = Math.max(1, ((source.defaultColumnWidth || 88) - 5) / 7)
    for (const [r, data] of Object.entries(source.rowData ?? {})) {
      if (!data) continue
      const row = sheet.getRow(Number(r) + 1)
      if (data.h) row.height = data.h * 0.75
      row.hidden = data.hd === 1
    }
    for (const [c, data] of Object.entries(source.columnData ?? {})) {
      if (!data) continue
      const column = sheet.getColumn(Number(c) + 1)
      if (data.w) column.width = Math.max(1, (data.w - 5) / 7)
      column.hidden = data.hd === 1
    }
    const anchors = new Map<string, string>()
    for (const [r, row] of Object.entries(source.cellData ?? {})) for (const [c, data] of Object.entries((row ?? {}) as Record<string, ICellData | null>)) {
      if (data?.si && data.f) anchors.set(data.si, sheet.getCell(Number(r) + 1, Number(c) + 1).address)
    }
    for (const [r, row] of Object.entries(source.cellData ?? {})) for (const [c, data] of Object.entries((row ?? {}) as Record<string, ICellData | null>)) {
      if (!data) continue
      const cell = sheet.getCell(Number(r) + 1, Number(c) + 1)
      const value = data.p?.body?.dataStream?.replace(/\r\n$/, '') ?? data.v ?? null
      if (data.f) cell.value = { formula: data.f.replace(/^=/, ''), result: value ?? undefined }
      else if (data.si && anchors.has(data.si)) cell.value = { sharedFormula: anchors.get(data.si)!, result: value ?? undefined }
      else cell.value = data.t === 3 ? Boolean(value) : value
      applyStyle(cell, { ...styleFor(snapshot, source.defaultStyle), ...styleFor(snapshot, source.columnData?.[Number(c)]?.s), ...styleFor(snapshot, source.rowData?.[Number(r)]?.s), ...styleFor(snapshot, data.s) })
    }
    for (const range of source.mergeData ?? []) sheet.mergeCells(range.startRow + 1, range.startColumn + 1, range.endRow + 1, range.endColumn + 1)
    sheet.views = [{ state: 'frozen', xSplit: source.freeze?.xSplit || 0, ySplit: source.freeze?.ySplit || 0, showGridLines: source.showGridlines !== 0 }]
  }
  return book
}

export async function downloadWorkbookExcel(snapshot: IWorkbookData, title: string) {
  const bytes = await excelWorkbook(snapshot).xlsx.writeBuffer()
  const blob = new Blob([new Uint8Array(bytes)], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = (title.replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').trim().slice(0, 120) || '工作簿') + '.xlsx'
  document.body.append(link); link.click(); link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}
