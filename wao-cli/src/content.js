import { readFile, writeFile } from 'node:fs/promises'

export async function readUtf8(path, stdin) {
  let bytes
  if (path === '-') {
    const chunks = []
    for await (const chunk of stdin) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))
    bytes = Buffer.concat(chunks)
  } else bytes = await readFile(path)
  try { return new TextDecoder('utf-8', { fatal: true }).decode(bytes) }
  catch { throw new Error('输入文件必须使用有效的 UTF-8 编码') }
}

export function documentMarkdown(doc) {
  let body = doc.content?.markdown
  if (typeof body !== 'string') {
    body = (doc.blocks || []).map(block => {
      if (block.h !== undefined && block.h !== null) return '#'.repeat(Math.min(6, Math.max(1, block.hLevel || 2))) + ' ' + block.h
      if (block.p !== undefined && block.p !== null) return block.p
      if (block.ul) return block.ul.map(item => '- ' + item).join('\n')
      if (block.q) return block.q.map(item => String(item).split('\n').map(line => '> ' + line).join('\n')).join('\n>\n')
      if (block.call !== undefined && block.call !== null) return String(block.call).split('\n').map(line => '> ' + line).join('\n')
      return ''
    }).join('\n\n')
  }
  return (doc.title ? '# ' + String(doc.title).replace(/[\r\n]+/g, ' ') + '\n\n' : '') + body + (body.endsWith('\n') ? '' : '\n')
}

export async function exportDocument(doc, { format, file, force }, stdout) {
  const data = format === 'json' ? JSON.stringify(doc, null, 2) + '\n' : documentMarkdown(doc)
  if (file === '-') { stdout.write(data); return }
  try { await writeFile(file, data, { encoding: 'utf8', flag: force ? 'w' : 'wx' }) }
  catch (error) {
    if (error.code === 'EEXIST') throw new Error('导出文件已存在；确认覆盖时显式添加 --force：' + file)
    throw error
  }
  stdout.write('已导出：' + file + '\n')
}
