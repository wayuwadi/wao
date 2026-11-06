/** Export the current draft, including a plain Markdown heading, without changing saved content. */
export function documentMarkdown(title: string, body: string): string {
  const heading = (title.trim() || '未命名文档').replace(/[\r\n]+/g, ' ').replace(/([\\`*_{}\[\]<>#])/g, '\\$1')
  return `# ${heading}\n\n${body}\n`
}

export function downloadDocumentMarkdown(title: string, body: string) {
  const url = URL.createObjectURL(new Blob([documentMarkdown(title, body)], { type: 'text/markdown;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `${title.replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').trim().replace(/[. ]+$/g, '').slice(0, 100) || '未命名文档'}.md`
  document.body.appendChild(link)
  try { link.click() }
  finally { link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 1000) }
}
