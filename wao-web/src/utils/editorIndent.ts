import type { Editor } from '@tiptap/core'
import { Fragment, Slice, type Node, type ResolvedPos } from '@tiptap/pm/model'
import { EditorState, NodeSelection, TextSelection, type Transaction } from '@tiptap/pm/state'
import { liftListItem } from '@tiptap/pm/schema-list'
import { ReplaceAroundStep } from '@tiptap/pm/transform'
import { CellSelection } from '@tiptap/pm/tables'
import { closeHistory } from '@tiptap/pm/history'
import { LIST_TYPES, listIndent } from './listIndent'

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

interface ListScope { position: number; start: number; end: number }

/** 同时选中父项和子列表时按父项整体处理，避免重复缩进子项。 */
function selectedLists(state: EditorState): ListScope[] {
  if (state.selection instanceof NodeSelection && LIST_TYPES.includes(state.selection.node.type.name)) {
    return [{ position: state.selection.from, start: 0, end: state.selection.node.childCount }]
  }
  const { from, to, empty, $from, $to } = state.selection
  const nearest = ancestorPosition($from, LIST_TYPES)
  const common = nearest !== null && nearest === ancestorPosition($to, LIST_TYPES) ? nearest : null
  const scopes: ListScope[] = []
  state.doc.nodesBetween(from, to, (node, position) => {
    if (!LIST_TYPES.includes(node.type.name)) return
    if (common !== null && common > position && common < position + node.nodeSize) return
    const indices: number[] = []
    node.forEach((item, offset, index) => {
      const start = position + 1 + offset
      if (empty ? from >= start + 1 && from <= start + item.nodeSize - 1
        : to > start + 2 && from < start + item.nodeSize - 1) indices.push(index)
    })
    if (indices.length) scopes.push({ position, start: indices[0]!, end: indices[indices.length - 1]! + 1 })
    return false
  })
  return scopes
}

function itemPosition(list: Node, position: number, index: number) {
  let result = position + 1
  for (let item = 0; item < index; item++) result += list.child(item).nodeSize
  return result
}

/** 与 ProseMirror sinkListItem 相同的变换，但不合并独立缩进不同的子列表。 */
function sinkSelectedItems(tr: Transaction, list: Node, position: number, start: number, end: number) {
  const itemType = list.firstChild!.type
  const beforeItem = list.child(start - 1)
  const nested = beforeItem.lastChild
  const nestedBefore = nested?.type === list.type && listIndent(nested.attrs.indent) === 0
    && nested.attrs.type === list.attrs.type
  const inner = Fragment.from(nestedBefore ? itemType.create() : null)
  const attrs = { ...list.attrs, indent: 0, ...(list.type.name === 'orderedList' ? { start: 1 } : {}) }
  const slice = new Slice(Fragment.from(itemType.create(null,
    Fragment.from(list.type.create(attrs, inner)))), nestedBefore ? 3 : 1, 0)
  const from = itemPosition(list, position, start), to = itemPosition(list, position, end)
  tr.step(new ReplaceAroundStep(from - (nestedBefore ? 3 : 1), to, from, to, slice, 1, true))
}

/** 无前项时只拆分所选片段并整体平移，编号继续原序列，不创建空白父条目。 */
function offsetSelectedItems(tr: Transaction, list: Node, position: number, start: number, end: number, direction: number) {
  const indent = Math.max(0, Math.min(8, listIndent(list.attrs.indent) + direction))
  if (indent === listIndent(list.attrs.indent)) return
  const attrsAfter = (index: number) => ({ ...list.attrs,
    ...(list.type.name === 'orderedList' ? { start: (Number(list.attrs.start) || 1) + index } : {}),
  })
  if (end < list.childCount) tr.split(itemPosition(list, position, end), 1, [{ type: list.type, attrs: attrsAfter(end) }])
  let selectedPosition = position
  if (start > 0) {
    const boundary = itemPosition(list, position, start)
    tr.split(boundary, 1, [{ type: list.type, attrs: attrsAfter(start) }])
    selectedPosition = boundary + 1
  }
  const selected = tr.doc.nodeAt(selectedPosition)!
  tr.setNodeMarkup(selectedPosition, undefined, { ...selected.attrs, indent })
}

function indentListScopes(tr: Transaction, state: EditorState, direction: number) {
  for (const scope of selectedLists(state)) {
    const position = tr.mapping.map(scope.position, -1)
    const list = tr.doc.nodeAt(position)!
    if (direction > 0 && scope.start > 0) {
      sinkSelectedItems(tr, list, position, scope.start, scope.end)
    } else if (direction < 0 && listIndent(list.attrs.indent) === 0) {
      const parent = tr.doc.resolve(position).parent
      // 根列表不退成普通段落；只有真实子列表提升一级。
      if (!['listItem', 'taskItem'].includes(parent.type.name)) continue
      const from = itemPosition(list, position, scope.start) + 1
      const to = itemPosition(list, position, scope.end) - 1
      const temporary = EditorState.create({ schema: state.schema, doc: tr.doc,
        selection: TextSelection.between(tr.doc.resolve(from), tr.doc.resolve(to)) })
      liftListItem(list.firstChild!.type)(temporary, transaction => transaction.steps.forEach(step => tr.step(step)))
    } else {
      offsetSelectedItems(tr, list, position, scope.start, scope.end, direction)
    }
  }
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

/** 安全处理 Tab：保留正文，列表标记和整项内容随真实层级或容器一起移动。 */
export function indentEditorSelection(editor: Editor, direction: 1 | -1): boolean {
  if (!editor.isEditable) return true
  const { state } = editor
  const { selection } = state
  const { from, to, $from, $to, empty } = selection
  if (selection instanceof NodeSelection && selection.node.type.name !== 'image'
    && !LIST_TYPES.includes(selection.node.type.name)) return true
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
  const tr = closeHistory(state.tr)
  const codeSelection = sameAncestor($from, $to, ['codeBlock'])
  if (!codeSelection && (!(selection instanceof NodeSelection) || LIST_TYPES.includes(selection.node.type.name))) indentListScopes(tr, state, direction)
  state.doc.nodesBetween(from, to, (node, position) => {
    // 子正文、子图和子列表随列表整体移动，不重复加空白或位移。
    if (LIST_TYPES.includes(node.type.name) && !codeSelection
      && !(selection instanceof NodeSelection && selection.node.type.name === 'image')) return false
    if (node.type.name === 'image') {
      const level = Number(node.attrs.indent) || 0
      const indent = Math.max(0, Math.min(8, level + direction))
      if (indent !== level) tr.setNodeMarkup(tr.mapping.map(position), undefined, { ...node.attrs, indent })
      return false
    }
    if (!node.isTextblock) return
    // 全选常包含编辑器自动补的尾部空段落；不制造无内容的额外缩进。
    if (!empty && node.content.size === 0) return false
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
  tr.setSelection(selection instanceof NodeSelection
    ? NodeSelection.create(tr.doc, tr.mapping.map(selection.from, -1))
    : selection.map(tr.doc, tr.mapping))
  editor.view.dispatch(tr)
  editor.view.dispatch(closeHistory(editor.state.tr))
  return true
}
