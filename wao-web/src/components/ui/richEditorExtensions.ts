import { Node, mergeAttributes, type JSONContent } from '@tiptap/core'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import RichCardNodeView from './RichCardNodeView.vue'
import MermaidNodeView from './MermaidNodeView.vue'

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

export const Columns = Node.create({
  name: 'columns',
  group: 'block',
  content: 'column{2,3}',
  defining: true,
  draggable: true,
  addAttributes() { return { uid: { default: null } } },
  parseHTML() { return [{ tag: 'div[data-columns]' }] },
  renderHTML({ node, HTMLAttributes }) {
    return ['div', mergeAttributes(HTMLAttributes, { 'data-columns': String(node.childCount) }), 0]
  },
  renderMarkdown(node, helpers) {
    return (node.content ?? []).map((child) => renderChildren(child, helpers)).join('\n\n---\n\n')
  },
})

function cardNode(name: 'fileCard' | 'mediaCard' | 'linkCard' | 'drawingCard') {
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
      if (name === 'drawingCard' && attrs.snapshot) return `![${title}](${attrs.snapshot})\n\n[打开画板](${url})`
      return `[${title}](${url})`
    },
    addNodeView() { return VueNodeViewRenderer(RichCardNodeView) },
  })
}

export const FileCard = cardNode('fileCard')
export const MediaCard = cardNode('mediaCard')
export const LinkCard = cardNode('linkCard')
export const DrawingCard = cardNode('drawingCard')

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
