import type { ICellData, IWorkbookData } from '@univerjs/core'
import type { RichDocContent, RichDocNode } from '@/types'

export const MAX_SHEET_CELLS = 1000
export const MAX_SHEET_ROWS = 100
export const MAX_SHEET_COLUMNS = 26
export function columnName(index: number) { return String.fromCharCode(65 + index) }
export function emptySheet(): string[][] { return Array.from({ length: 20 }, () => Array<string>(8).fill('')) }
function cellText(node: RichDocNode): string {
  return node.text ?? (node.type === 'hardBreak' ? '\n' : (node.content ?? []).map(cellText).join(''))
}
export function readSheet(content?: RichDocContent | null): string[][] {
  if (!content?.document) return emptySheet()
  const table = content.document.content?.find(node => node.type === 'table')
  if (!table) throw new Error('表格内容无法读取，请保留原文并重试加载')
  const rows = (table.content ?? []).map(row => (row.content ?? []).map(cellText))
  const columns = Math.max(1, ...rows.map(row => row.length))
  if (!rows.length || rows.length > MAX_SHEET_ROWS || columns > MAX_SHEET_COLUMNS || rows.length * columns > MAX_SHEET_CELLS) throw new Error('表格尺寸超出当前支持范围')
  return rows.map(row => Array.from({ length: columns }, (_, col) => row[col] ?? ''))
}
export function sheetContent(rows: string[][]): RichDocContent {
  const escape = (value: string) => value.replace(/\|/g, '\\|').replace(/\n/g, '<br>')
  return {
    version: 1,
    document: { type: 'doc', content: [{ type: 'table', content: rows.map(row => ({
      type: 'tableRow', content: row.map(value => ({ type: 'tableCell', content: [{ type: 'paragraph', content: value ? [{ type: 'text', text: value }] : [] }] })),
    })) }] },
    markdown: [rows[0]!.map((_, col) => columnName(col)), rows[0]!.map(() => '---'), ...rows].map(row => `| ${row.map(escape).join(' | ')} |`).join('\n'),
  }
}
/** Excel/Sheets clipboard TSV, including quoted multiline cells and escaped quotes. */
export function parseSheetClipboard(text: string): string[][] {
  const rows: string[][] = [[]]
  let cell = '', quoted = false
  const input = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  for (let i = 0; i < input.length; i++) {
    const ch = input[i]!
    if (ch === '"' && (quoted || cell === '')) {
      if (quoted && input[i + 1] === '"') { cell += '"'; i++ } else quoted = !quoted
    } else if (!quoted && (ch === '\t' || ch === '\n')) {
      rows[rows.length - 1]!.push(cell); cell = ''
      if (ch === '\n') rows.push([])
    } else cell += ch
  }
  rows[rows.length - 1]!.push(cell)
  if (input.endsWith('\n') && rows[rows.length - 1]?.length === 1 && rows[rows.length - 1]?.[0] === '') rows.pop()
  return rows
}
export function sheetClipboard(rows: string[][]): string {
  return rows.map(row => row.map(value => /[\t\n"]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value).join('\t')).join('\n')
}


export const WORKBOOK_NODE = 'waoWorkbook'
/** Preserve the whole engine snapshot: formulas, styles, merges and all sheets. */
export function readWorkbook(content: RichDocContent | null | undefined, id: string): Partial<IWorkbookData> {
  if (content?.document?.type === WORKBOOK_NODE) {
    const snapshot = content.document.attrs?.snapshot as Partial<IWorkbookData> | undefined
    if (!snapshot?.id || !snapshot.sheets || !snapshot.sheetOrder?.length || snapshot.sheetOrder.some(key => !snapshot.sheets?.[key])) throw new Error('工作簿内容无法读取，原内容已保留')
    return JSON.parse(JSON.stringify(snapshot)) as Partial<IWorkbookData>
  }
  const rows = readSheet(content)
  const cellData: Record<number, Record<number, { v: string; t: number }>> = {}
  rows.forEach((row, r) => row.forEach((value, c) => {
    if (value) (cellData[r] ??= {})[c] = { v: value, t: 1 }
  }))
  return { id, name: '工作簿', appVersion: '1.1.0', locale: 'zhCN' as IWorkbookData['locale'], sheetOrder: ['sheet1'], styles: {},
    sheets: { sheet1: { id: 'sheet1', name: 'Sheet1', rowCount: Math.max(1000, rows.length), columnCount: Math.max(26, rows[0]?.length ?? 0), cellData } as IWorkbookData['sheets'][string] } }
}
export function workbookContent(snapshot: IWorkbookData): RichDocContent {
  const copy = JSON.parse(JSON.stringify(snapshot)) as IWorkbookData
  // A bounded, escaped text projection for search and read-only sharing. Snapshot is the source of truth.
  const lines: string[] = []
  let remaining = 100000
  const escape = (value: unknown) => String(value ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\|/g, '\\|').replace(/\r?\n/g, ' ')
  for (const key of copy.sheetOrder) {
    const sheet = copy.sheets[key]
    if (!sheet) continue
    lines.push(`## ${escape(sheet.name)}`)
    for (const [r, row] of Object.entries(sheet.cellData ?? {})) {
      if (!row || remaining <= 0) break
      const text = Object.entries(row as Record<string, ICellData | null>).filter(([, cell]) => cell != null).map(([c, cell]) => `${Number(c) + 1}: ${escape(cell?.v ?? cell?.f ?? cell?.p?.body?.dataStream)}`).join(' · ')
      const line = `${Number(r) + 1} | ${text}`.slice(0, remaining)
      lines.push(line); remaining -= line.length
    }
  }
  return { version: 1, document: { type: WORKBOOK_NODE, attrs: { snapshot: copy } }, markdown: lines.join('\n\n') }
}
