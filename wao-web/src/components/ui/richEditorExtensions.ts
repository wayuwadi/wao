import { normalizedLinkUrl } from '@/utils/contentLinks'
import { marked } from 'marked'
import ColumnsNodeView from './ColumnsNodeView.vue'
import { Node, mergeAttributes, type JSONContent } from '@tiptap/core'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import RichCardNodeView from './RichCardNodeView.vue'
import MermaidNodeView from './MermaidNodeView.vue'
import { Table, renderTableToMarkdown } from '@tiptap/extension-table'

/** GFM 表格无法表示合并和列宽；仅这些情况回退到可往返的 HTML。 */
export function createDocumentTable(preserveLayout: boolean) { return Table.extend({
  renderMarkdown(node, helpers) {
    const rows = node.content ?? []
    const rich = rows.some(row => row.content?.some(cell =>
      Number(cell.attrs?.colspan ?? 1) > 1 || Number(cell.attrs?.rowspan ?? 1) > 1 || cell.attrs?.colwidth,
    ))
    if (!preserveLayout || !rich) return renderTableToMarkdown(node, helpers)
    return `<table>${rows.map(row => `<tr>${(row.content ?? []).map(cell => {
      const tag = cell.type === 'tableHeader' ? 'th' : 'td'
      const colspan = Math.max(1, Number(cell.attrs?.colspan) || 1)
      const rowspan = Math.max(1, Number(cell.attrs?.rowspan) || 1)
      const widths = Array.isArray(cell.attrs?.colwidth) ? cell.attrs.colwidth.map(Number).filter(Number.isFinite).join(',') : ''
      return `<${tag} colspan="${colspan}" rowspan="${rowspan}"${widths ? ` colwidth="${widths}"` : ''}>${marked.parse(renderChildren(cell, helpers), {async:false}).trim()}</${tag}>`
    }).join('')}</tr>`).join('')}</table>`
  },
}) }

function renderChildren(
  node: JSONContent,
  helpers: { renderChildren: (nodes: JSONContent | JSONContent[], separator?: string) => string },
) {
  return helpers.renderChildren(node.content ?? [])
}

export const Callout = Node.create({
  name: 'callout',
  group: 'block',
  content: 'block+',
  defining: true,
  draggable: true,
  addAttributes() {
    return {
      kind: { default: 'info' },
      uid: { default: null },
    }
  },
  parseHTML() { return [{ tag: 'aside[data-callout]' }] },
  renderHTML({ HTMLAttributes }) {
    return ['aside', mergeAttributes(HTMLAttributes, { 'data-callout': HTMLAttributes.kind }), 0]
  },
  renderMarkdown(node, helpers) {
    const label = String(node.attrs?.kind ?? 'info').toUpperCase()
    return `> [!${label}]\n${renderChildren(node, helpers).split('\n').map((line) => `> ${line}`).join('\n')}`
  },
})

export const Column = Node.create({
  name: 'column',
  content: 'block+',
  isolating: true,
  parseHTML() { return [{ tag: 'section[data-column]' }] },
  renderHTML({ HTMLAttributes }) { return ['section', mergeAttributes(HTMLAttributes, { 'data-column': '' }), 0] },
  renderMarkdown(node, helpers) { return renderChildren(node, helpers) },
})

export function createColumns(preserveLayout: boolean) { return Node.create({
  name: 'columns',
  group: 'block',
  content: 'column{2,5}',
  defining: true,
  draggable: true,
  addAttributes() { return { uid: { default: null } } },
  parseHTML() { return [{ tag: 'div[data-columns]' }] },
  renderHTML({ node, HTMLAttributes }) {
    return ['div', mergeAttributes(HTMLAttributes, { 'data-columns': String(node.childCount) }), 0]
  },
  addNodeView() { return VueNodeViewRenderer(ColumnsNodeView) },
  renderMarkdown(node, helpers) {
    const columns = node.content ?? []
    if (!preserveLayout) return columns.map(column => renderChildren(column, helpers)).join('\n\n---\n\n')
    return `<div data-columns="${columns.length}">${columns.map(column => `<section data-column="">${marked.parse(renderChildren(column, helpers), { async: false }).trim()}</section>`).join('')}</div>`
  },
}) }

function cardNode(name: 'fileCard' | 'mediaCard' | 'linkCard' | 'drawingCard', preserveLayout: boolean) {
  return Node.create({
    name,
    group: 'block',
    atom: true,
    draggable: true,
    selectable: true,
    addAttributes() {
      return {
        uid: { default: null }, id: { default: '' }, name: { default: '' }, size: { default: '' },
        mime: { default: '' }, url: { default: '' }, title: { default: '' }, snapshot: { default: '' },
      }
    },
    parseHTML() { return [{ tag: `div[data-rich-card="${name}"]` }] },
    renderHTML({ HTMLAttributes }) {
      return ['div', mergeAttributes(HTMLAttributes, { 'data-rich-card': name })]
    },
    renderMarkdown(node) {
      const attrs = node.attrs ?? {}
      const title = String(attrs.title || attrs.name || (name === 'drawingCard' ? '画板' : '链接'))
      const url = String(attrs.url || (attrs.id ? `/files/${attrs.id}` : '#'))
      if (!preserveLayout) return `[${title}](${url})`
      {
        const escape = (value: unknown) => String(value ?? '').replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        const attributes = ['id','name','size','mime','title','snapshot','url'].map(key => `${key}="${escape(attrs[key])}"`).join(' ')
        return `<div data-rich-card="${name}" ${attributes}><a href="${escape(normalizedLinkUrl(url) || '#')}">${escape(title)}</a></div>`
      }
    },
    addNodeView() { return VueNodeViewRenderer(RichCardNodeView) },
  })
}

export function createResourceCards(preserveLayout: boolean) {
  return (['fileCard', 'mediaCard', 'linkCard', 'drawingCard'] as const).map(name => cardNode(name, preserveLayout))
}

export const MermaidBlock = Node.create({
  name: 'mermaid',
  group: 'block',
  atom: true,
  draggable: true,
  selectable: true,
  addAttributes() {
    return { uid: { default: null }, source: { default: 'flowchart TD\n  A[开始] --> B[结束]' } }
  },
  parseHTML() { return [{ tag: 'div[data-mermaid-source]' }] },
  renderHTML({ HTMLAttributes }) { return ['div', mergeAttributes(HTMLAttributes, { 'data-mermaid-source': '' })] },
  renderMarkdown(node) { return `\`\`\`mermaid\n${String(node.attrs?.source ?? '')}\n\`\`\`` },
  addNodeView() { return VueNodeViewRenderer(MermaidNodeView) },
})
