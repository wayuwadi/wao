/** 列表容器的独立缩进；实际父子关系仍由嵌套列表表达。 */
export const LIST_TYPES = ['bulletList', 'orderedList', 'taskList']
export const LIST_INDENT_METADATA = /^ {0,3}\[wao-list-indent\]: # "([0-8])"\r?\n(?: *\r?\n)*/

export function listIndent(value: unknown): number {
  const number = Number(value)
  return Number.isInteger(number) && number >= 0 && number <= 8 ? number : 0
}

export function listIndentStyle(value: unknown): string {
  const level = listIndent(value)
  return level ? `margin-left:min(${level * 24}px,50%)` : ''
}

/** 引用定义在普通 Markdown 中不可见；每条元数据只消费紧随其后的一组列表。 */
export function readIndentedList<T extends { type?: string; raw?: string }>(
  source: string,
  tokenize: (markdown: string) => T[],
) {
  const metadata = LIST_INDENT_METADATA.exec(source)
  if (!metadata) return undefined
  const rest = source.slice(metadata[0].length)
  // 不对普通段落或代码块应用元数据，也不调用 lexer 解析无关正文。
  if (!/^ {0,3}(?:[-+*] |\d+[.)] |[a-zA-Z]+[.)] )/.test(rest)) return undefined
  // 只解析本组列表；递归解析整份剩余正文会让多组元数据反复重解析。
  const ownIndent = /^ */.exec(metadata[0])![0].length
  const boundary = /(?:^|\n)( {0,3})\[wao-list-indent\]: # "[0-8]"\r?\n/g
  let limit = rest.length
  for (const next of rest.matchAll(boundary)) {
    if (next[1]!.length > ownIndent) continue
    limit = next.index!
    break
  }
  const token = tokenize(rest.slice(0, limit))[0]
  if (!token?.raw || !['list', 'taskList'].includes(token.type ?? '')) return undefined
  return { type: 'waoIndentedList', raw: metadata[0] + token.raw, indent: Number(metadata[1]), tokens: [token] }
}
