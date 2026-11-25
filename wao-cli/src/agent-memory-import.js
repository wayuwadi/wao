import { createHash } from 'node:crypto'
import { lstat, readdir, readFile, realpath } from 'node:fs/promises'
import { join, resolve } from 'node:path'

export const IMPORT_HELP = '用法：wao memories import --directory SKILL_DIR [--entry ID ...] [--kind experience|preference] [--project 仓库名] [--apply 预览编号]。不选条目时只列出索引章节和手记；选中后默认只预览。'
const INDEX = 'memory-index.md'
const NOTES = 'ad-hoc-notes'

async function checkFile(file, root) {
  const info = await lstat(file)
  if (!info.isFile() || info.isSymbolicLink() || info.size > 512 * 1024 || await realpath(file) !== file ||
    !file.startsWith(root + '/')) throw new Error('只能读取所选 Skill 下的普通 Markdown 文件')
  return info
}

async function markdown(path, root) {
  await checkFile(path, root)
  const bytes = await readFile(path)
  if (bytes.length > 512 * 1024) throw new Error('Markdown 文件过大')
  return new TextDecoder('utf-8', { fatal: true }).decode(bytes).replace(/\r\n/g, '\n')
}

function importOptions(words) {
  const options = { entries: [], kind: 'experience', project: '' }
  const flags = new Map([['--directory', 'directory'], ['--kind', 'kind'], ['--project', 'project'], ['--apply', 'apply']])
  const seen = new Set()
  for (let index = 0; index < words.length; index += 2) {
    const flag = words[index]
    const value = words[index + 1]
    if (!value || value.startsWith('--') || (!flags.has(flag) && flag !== '--entry') || seen.has(flag)) throw new Error(IMPORT_HELP)
    if (flag === '--entry') options.entries.push(value)
    else { options[flags.get(flag)] = value; seen.add(flag) }
  }
  if (!options.directory || !['experience', 'preference'].includes(options.kind) ||
    new Set(options.entries).size !== options.entries.length || options.entries.length > 20 ||
    (options.apply && !options.entries.length)) throw new Error(IMPORT_HELP)
  return options
}

export async function importAgentMemories(words, clientFactory) {
  const options = importOptions(words)
  const root = await realpath(resolve(options.directory))
  const references = join(root, 'references')
  const indexPath = join(references, INDEX)
  const noteDir = join(references, NOTES)
  const catalog = new Map()
  const lines = (await markdown(indexPath, root)).split('\n')
  const headings = lines.flatMap((line, index) => {
    const match = line.match(/^#{2,6}\s+(.+)$/)
    return match ? [{ index, title: match[1] }] : []
  })
  for (const [position, { index, title }] of headings.entries()) {
    catalog.set('index:' + (index + 1), {
      title, source: INDEX, body: () => lines.slice(index + 1, headings[position + 1]?.index).join('\n').trim(),
    })
  }
  if (await realpath(noteDir) !== noteDir) throw new Error('手记目录不能是符号链接')
  for (const entry of (await readdir(noteDir, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
    if (!entry.isFile() || !/^[a-z0-9][a-z0-9_.+\-]*\.md$/i.test(entry.name)) continue
    catalog.set(entry.name, {
      source: entry.name,
      body: async () => (await markdown(join(noteDir, entry.name), root)).trim(),
    })
  }
  if (!options.entries.length) return {
    status: 'catalog',
    entries: [...catalog].map(([id, item]) => ({ id, title: item.title ?? item.source })),
  }
  const entries = await Promise.all(options.entries.map(async (id) => {
    const item = catalog.get(id)
    if (!item) throw new Error('找不到导入条目：' + id)
    const raw = await item.body()
    const first = raw.match(/^#\s+(.+)\n?/)
    const title = item.title ?? first?.[1] ?? item.source.slice(0, -3)
    // Old archives are machine-local pointers. Preserve the reviewed summary and standalone notes.
    const content = (first ? raw.slice(first[0].length) : raw).split('\n')
      .filter(line => !/\.codex\/(?:memories(?:_v2)?|sessions|archived_sessions)(?:\/|\b)/.test(line)).join('\n').trim()
    if (!content || content.length > 4000 || title.length > 200) {
      throw new Error('条目为空或太长，请选较短章节，或在 WAO 整理正文后手动添加：' + id)
    }
    return { kind: options.kind, title, content, project: options.project, source: 'import',
      source_summary: '来自 Codex → Agy 迁移 Skill：' + item.source + (id.startsWith('index:') ? '，第 ' + id.slice(6) + ' 行' : '') }
  }))
  const reviewId = createHash('sha256').update(JSON.stringify(entries)).digest('hex')
  if (!options.apply) return { status: 'preview', reviewId, entries }
  if (options.apply !== reviewId) throw new Error('所选内容或范围与预览不同，请重新预览后提交')
  const client = await clientFactory()
  if (!(await client.listTools()).some(({ name }) => name === 'propose_agent_memory')) {
    throw new Error('请在 WAO 为设备逐项授权「提交 Agent 记忆候选」')
  }
  const submissions = []
  for (const entry of entries) {
    try { submissions.push(await client.call('propose_agent_memory', entry)) }
    catch (error) { throw new Error(`已收到 ${submissions.length}/${entries.length} 条回执，可用同一预览编号重试。${error.message}`) }
  }
  return { status: 'submitted', submissions }
}
