import type { Editor } from '@tiptap/core'
import type { Node, ResolvedPos } from '@tiptap/pm/model'
import { NodeSelection } from '@tiptap/pm/state'
import { CellSelection } from '@tiptap/pm/tables'
import { closeHistory } from '@tiptap/pm/history'

// 普通空格 / Tab 在 Markdown 行首可能变成代码块。NBSP 可直接随已有正文往返。
const TEXT_INDENT = '\u00a0\u00a0'
const MAX_TEXT_INDENT = 8

function ancestorPosition(position: ResolvedPos, types: string[]): number | null {
  for (let depth = position.depth; depth > 0; depth--) {
    if (types.includes(position.node(depth).type.name)) return position.before(depth)
  }
  return null
}

function sameAncestor(from: ResolvedPos, to: ResolvedPos, types: string[]): boolean {
  const position = ancestorPosition(from, types)
  return position !== null && position === ancestorPosition(to, types)
}

/** 返回逻辑行的起止位置；硬换行和代码块换行均不会丢掉已有节点。 */
function textLines(node: Node, position: number): { from: number; to: number }[] {
  const lines: { from: number; to: number }[] = []
  let start = position + 1
  node.forEach((child, offset) => {
    const childPosition = position + 1 + offset
    if (child.type.name === 'hardBreak') {
      lines.push({ from: start, to: childPosition })
      start = childPosition + child.nodeSize
    } else if (child.isText) {
      for (let index = 0; index < (child.text?.length ?? 0); index++) {
        if (child.text![index] !== '\n') continue
        lines.push({ from: start, to: childPosition + index })
        start = childPosition + index + 1
      }
    }
  })
  lines.push({ from: start, to: position + node.nodeSize - 1 })
  return lines
}

/** 安全处理 Tab：仅改动行首空白和图片属性，绝不以缩进替换正文选区。 */
export function indentEditorSelection(editor: Editor, direction: 1 | -1): boolean {
  if (!editor.isEditable) return true
  const { state } = editor
  const { selection } = state
  const { from, to, $from, $to, empty } = selection
  if (selection instanceof NodeSelection && selection.node.type.name !== 'image') return true
  // 表格内继续导航单元格；跨表格的混合选区交给下面的无损逐行处理。
  if (selection instanceof CellSelection
    || (!(selection instanceof NodeSelection) && sameAncestor($from, $to, ['table']))) return false
  // 代码光标沿用插入两个空格的手感，同时将撤销与前后文字输入分开。
  if (empty && $from.parent.type.name === 'codeBlock' && direction > 0) {
    const tr = closeHistory(state.tr).insertText('  ', from)
    tr.setSelection(selection.map(tr.doc, tr.mapping))
    editor.view.dispatch(tr)
    editor.view.dispatch(closeHistory(editor.state.tr))
    return true
  }
  if (!(selection instanceof NodeSelection) && $from.parent.type.name !== 'codeBlock'
    && sameAncestor($from, $to, ['bulletList', 'orderedList', 'taskList'])) {
    const type = editor.isActive('taskItem') ? 'taskItem' : 'listItem'
    editor.view.dispatch(closeHistory(state.tr))
    if (direction > 0) editor.commands.sinkListItem(type)
    else editor.commands.liftListItem(type)
    editor.view.dispatch(closeHistory(editor.state.tr))
    return true
  }

  const tr = closeHistory(state.tr)
  state.doc.nodesBetween(from, to, (node, position) => {
    if (node.type.name === 'image') {
      const level = Number(node.attrs.indent) || 0
      const indent = Math.max(0, Math.min(8, level + direction))
      if (indent !== level) tr.setNodeMarkup(tr.mapping.map(position), undefined, { ...node.attrs, indent })
      return false
    }
    if (!node.isTextblock) return
    for (const line of textLines(node, position)) {
      // 选区末端恰好落在下一行开头时，不连带缩进那一行。
      const touched = empty ? from >= line.from && from <= line.to
        : from <= line.to && to > line.from
      if (!touched) continue
      const text = state.doc.textBetween(line.from, line.to, '', '\ufffc')
      const code = node.type.name === 'codeBlock'
      const prefix = code ? '  ' : TEXT_INDENT
      const levels = text.match(code ? /^(?: {2})*/ : /^(?:\u00a0{2})*/)?.[0].length ?? 0
      const mapped = tr.mapping.map(line.from)
      if (direction > 0) {
        if (code || levels < MAX_TEXT_INDENT * 2) tr.insert(mapped, state.schema.text(prefix))
      } else {
        // 兼容此前光标 Tab 输入的制表符和粘贴的普通空格，每次最多减少一级。
        const count = text.startsWith('\t') ? 1 : (text.match(/^[ \u00a0]{1,2}/)?.[0].length ?? 0)
        if (count) tr.delete(mapped, mapped + count)
      }
    }
    return false
  })
  if (!tr.docChanged) return true
  // Mapping 保留双向选区，原子节点选区也仍然指向原节点。
  tr.setSelection(selection.map(tr.doc, tr.mapping))
  editor.view.dispatch(tr)
  editor.view.dispatch(closeHistory(editor.state.tr))
  return true
}
