/** 从正文中寻找第一行可读文字，跳过放在文章开头的 Markdown 图片。 */
export function firstMarkdownTextLine(markdown: string): string {
  for (const source of markdown.split('\n')) {
    const line = source
      .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
      .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
      .replace(/^[\s#>+*\-]+/, '')
      .trim()
    if (line) return line
  }
  return ''
}
