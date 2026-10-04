import { Extension, type JSONContent, type MarkdownRendererHelpers, type RenderContext } from '@tiptap/core'
import { BulletList, OrderedList, TaskList } from '@tiptap/extension-list'
import { LIST_TYPES, listIndent, listIndentStyle, readIndentedList } from '@/utils/listIndent'

/** 只接受有界数字属性，由应用生成样式，不读取任意用户 style。 */
export const ListIndentAttributes = Extension.create({
  name: 'listIndentAttributes',
  addGlobalAttributes() {
    return [{ types: LIST_TYPES, attributes: { indent: {
      default: 0,
      parseHTML: element => listIndent(element.getAttribute('data-list-indent')),
      renderHTML: attrs => listIndent(attrs.indent)
        ? { 'data-list-indent': listIndent(attrs.indent), style: listIndentStyle(attrs.indent) } : {},
    } } }]
  },
  markdownTokenName: 'waoIndentedList',
  markdownTokenizer: {
    name: 'waoIndentedList', level: 'block',
    start: source => source.indexOf('[wao-list-indent]: # "'),
    tokenize: (source, _tokens, lexer) => readIndentedList(source, markdown => lexer.blockTokens(markdown)),
  },
  parseMarkdown: (token, helpers) => helpers.parseChildren(token.tokens ?? []).map(node =>
    LIST_TYPES.includes(node.type ?? '') ? { ...node, attrs: { ...node.attrs, indent: listIndent(token.indent) } } : node),
})

const listMarkdown = (node: JSONContent, helpers: MarkdownRendererHelpers, context: RenderContext) => {
  if (!node.content) return ''
  const body = helpers.renderChildren(node.content, '\n')
  const level = listIndent(node.attrs?.indent)
  // 相邻列表需要独立的边界，避免 Markdown 把不同偏移的列表合为一组。
  const adjacent = LIST_TYPES.includes(context.previousNode?.type ?? '')
  return level || adjacent
    ? `${adjacent ? '\n' : ''}[wao-list-indent]: # "${level}"\n\n${body}` : body
}

export const IndentableBulletList = BulletList.extend({ renderMarkdown: listMarkdown })
export const IndentableOrderedList = OrderedList.extend({ renderMarkdown: listMarkdown })
export const IndentableTaskList = TaskList.extend({ renderMarkdown: listMarkdown })
