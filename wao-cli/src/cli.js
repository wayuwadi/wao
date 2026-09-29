import { readFile, stat } from 'node:fs/promises'
import { basename, extname, resolve } from 'node:path'
import { createInterface } from 'node:readline/promises'
import { createClient, endpoint, hostedPageUrl } from './client.js'
import { getToken, normalizeWorkspaceToken, readToken, removeToken, storeToken } from './auth.js'

// 子命令是人类友好的别名；参数定义和权限始终来自服务端 tools/list。
export const COMMANDS = new Map([
  ['projects list', ['list_projects']],
  ['projects get', ['get_project', 'project_id']],
  ['projects create', ['create_project']],
  ['projects update', ['update_project', 'project_id']],
  ['tasks open', ['list_open_tasks']],
  ['tasks get', ['get_task', 'task_id']],
  ['tasks create', ['create_task']],
  ['tasks update', ['update_task', 'task_id']],
  ['subtasks status', ['update_subtask_status', 'subtask_id']],
  ['knowledge list', ['list_knowledge_bases']],
  ['knowledge get', ['get_knowledge_base', 'kb_id']],
  ['knowledge create', ['create_knowledge_base']],
  ['knowledge update', ['update_knowledge_base', 'kb_id']],
  ['documents list', ['list_documents', 'kb_id']],
  ['documents get', ['get_document', 'document_id']],
  ['documents create', ['create_document']],
  ['documents update', ['update_document', 'document_id']],
  ['folders create', ['create_folder']],
  ['folders rename', ['rename_folder']],
  ['knowledge move', ['move_knowledge_node']],
])

const HELP = `WAO 工作区 CLI

用法：wao <资源> <操作> [ID] [--字段 值] [--output json|table]

认证：
  wao auth login                 安全输入令牌，存入 Windows DPAPI 或 macOS 钥匙串
  wao auth login --token-stdin   从标准输入读取令牌（不会出现在命令参数中）
  wao auth status                检查令牌及可用工具数
  wao auth logout                删除本机安全凭据

常用命令：
  wao projects list | get ID | create | update ID
  wao tasks open | get ID | create | update ID
  wao subtasks status ID --task-id TASK_ID --status 待验证 --expected-subtask-version VERSION
  wao knowledge list | get ID | create | update ID | move
  wao documents list KB_ID | get ID | create | update ID
  wao folders create | rename
  wao pages publish --file index.html --title 标题 --slug public-name [--project-id ID] [--yes]
  wao tools list                 查看当前令牌实际可用的工具
  wao tools call NAME --input '{"field":"value"}'
  wao tools call NAME --input-file payload.json

例子：
  wao tasks open --limit 10
  wao tasks open --mine-only false --project-id PROJECT_ID
  wao tasks create --project-id PROJECT_ID --title "修复问题"
  wao documents update DOC_ID --input-file update.json
  wao pages publish --file dist/index.html --title "分享稿" --slug share-draft

托管页发布：只接受 UTF-8 单文件 HTML，最大 10 MB；会公开生成公网链接。
每次交互发布前均需确认；自动化须显式添加 --yes。请在 WAO 首页“连接 AI 客户端”中单独开启发布权限。

配置：WAO_TOKEN（优先于本机安全凭据）；WAO_URL（默认 https://wao.wayuwa.cn/api/mcp/workspace）。
      --url 可指定本次地址，仅 HTTPS 和本机 HTTP；--output json 提供脚本可读结果。
字段名与 WAO MCP tools/list 一致，命令行使用连字符（如 --project-id）。
--input 和 --input-file 接受 JSON 对象，适用于复杂字段或标准输入（--input-file -）。`

const MAX_HTML_BYTES = 10 * 1024 * 1024
const HOSTED_PAGE_SLUG = /^[a-z0-9](?:[a-z0-9-]{0,78}[a-z0-9])?$/

function parseHostedPageFlags(words) {
  const flags = new Map()
  let confirm = false
  for (let index = 0; index < words.length; index++) {
    const word = words[index]
    if (word === '--yes') {
      if (confirm) throw new Error('重复选项 --yes')
      confirm = true
      continue
    }
    if (!word.startsWith('--') || word === '--') throw new Error('wao pages publish 不接受位置参数')
    const name = word.slice(2)
    if (!['file', 'title', 'slug', 'project-id'].includes(name)) throw new Error('wao pages publish 不支持 --' + name)
    if (flags.has(name)) throw new Error('重复选项 --' + name)
    const value = words[++index]
    if (!value || value.startsWith('--')) throw new Error('--' + name + ' 缺少值')
    flags.set(name, value)
  }
  for (const name of ['file', 'title', 'slug']) {
    if (!flags.has(name)) throw new Error('缺少必填选项 --' + name)
  }
  return { flags, confirm }
}

async function prepareHostedHtml(flags) {
  const file = resolve(flags.get('file'))
  if (!['.html', '.htm'].includes(extname(file).toLowerCase())) throw new Error('--file 必须是 .html 或 .htm 文件')
  const info = await stat(file).catch(() => null)
  if (!info?.isFile()) throw new Error('找不到 HTML 文件：' + file)
  if (info.size === 0 || info.size > MAX_HTML_BYTES) throw new Error('HTML 文件大小必须大于 0 且不超过 10 MB')
  const html = await readFile(file)
  if (html.length === 0 || html.length > MAX_HTML_BYTES) throw new Error('HTML 文件大小必须大于 0 且不超过 10 MB')
  try { new TextDecoder('utf-8', { fatal: true }).decode(html) }
  catch { throw new Error('HTML 文件必须使用有效的 UTF-8 编码') }
  const title = flags.get('title').trim()
  if (!title || title.length > 200) throw new Error('--title 长度必须在 1 到 200 个字符之间')
  const slug = flags.get('slug')
  if (!HOSTED_PAGE_SLUG.test(slug)) throw new Error('--slug 只能使用小写字母、数字和中划线，长度 1～80，首尾不能是中划线')
  const projectId = flags.get('project-id')
  if (projectId !== undefined && !projectId.trim()) throw new Error('--project-id 不能为空')
  return { file, fileName: basename(file), html, title, slug, projectId }
}

function safeTerminalText(value) {
  return String(value).replace(/[\u0000-\u001f\u007f\u001b]/g, ' ')
}

async function confirmHostedHtml(details, publicUrl, stdin, stdout) {
  if (!stdin.isTTY) throw new Error('公开发布需要交互确认；在确认文件、标题和链接后，可在自动化场景使用 --yes')
  stdout.write([
    '即将公开发布一个 WAO 托管 HTML 页面：',
    '  标题：' + safeTerminalText(details.title),
    '  链接后缀：' + details.slug,
    '  文件：' + safeTerminalText(details.fileName) + '（' + details.html.length + ' 字节）',
    '  项目：' + safeTerminalText(details.projectId || '未指定项目'),
    '  公开地址：' + publicUrl,
    '确认这些内容可公开后，输入 yes 继续：',
  ].join('\n') + '\n')
  const prompt = createInterface({ input: stdin, output: stdout })
  try {
    const answer = (await prompt.question('> ')).trim().toLowerCase()
    if (answer !== 'yes' && answer !== 'y') throw new Error('已取消发布')
  } finally { prompt.close() }
}

function removeGlobalOptions(argv) {
  const rest = []
  const globals = { output: 'auto', url: undefined }
  for (let index = 0; index < argv.length; index++) {
    const word = argv[index]
    if (word === '--output' || word === '--url') {
      const value = argv[++index]
      if (!value || value.startsWith('--')) throw new Error(`${word} 缺少值`)
      globals[word.slice(2)] = value
    } else rest.push(word)
  }
  if (!['auto', 'json', 'table'].includes(globals.output)) throw new Error('--output 只接受 auto、json 或 table')
  return { rest, globals }
}

function parseFlags(words) {
  const positional = []
  const flags = new Map()
  for (let i = 0; i < words.length; i++) {
    const word = words[i]
    if (!word.startsWith('--')) { positional.push(word); continue }
    const name = word.slice(2)
    if (!name) throw new Error('无效的选项 --')
    if (flags.has(name)) throw new Error(`重复选项 --${name}`)
    const value = words[i + 1]
    if (value === undefined || value.startsWith('--')) throw new Error(`--${name} 缺少值`)
    flags.set(name, value)
    i++
  }
  return { positional, flags }
}

async function readInput(flags, stdin) {
  if (flags.has('input') && flags.has('input-file')) throw new Error('--input 与 --input-file 只能选一个')
  let raw = flags.get('input')
  if (flags.has('input-file')) {
    const path = flags.get('input-file')
    if (path === '-') {
      raw = ''
      for await (const chunk of stdin) raw += chunk
    } else raw = await readFile(path, 'utf8')
  }
  flags.delete('input')
  flags.delete('input-file')
  if (raw === undefined) return {}
  let value
  try { value = JSON.parse(raw) } catch { throw new Error('输入文件或 --input 必须是有效 JSON') }
  if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error('工具参数必须是 JSON 对象')
  return value
}

function coerce(value, property, flag) {
  if (property.type === 'string') return value
  if (property.type === 'boolean') {
    if (value !== 'true' && value !== 'false') throw new Error(`--${flag} 必须是 true 或 false`)
    return value === 'true'
  }
  if (property.type === 'integer') {
    const number = Number(value)
    if (!Number.isSafeInteger(number)) throw new Error(`--${flag} 必须是整数`)
    return number
  }
  if (property.type === 'array' || property.type === 'object') {
    try {
      const parsed = JSON.parse(value)
      if (property.type === 'array' ? Array.isArray(parsed) : parsed && !Array.isArray(parsed) && typeof parsed === 'object') return parsed
    } catch { /* 统一报参数错误 */ }
    throw new Error(`--${flag} 必须是 JSON ${property.type === 'array' ? '数组' : '对象'}`)
  }
  throw new Error(`不支持的参数类型：${property.type}`)
}

export async function argumentsForTool(words, positionalName, schema, stdin = process.stdin) {
  const { positional, flags } = parseFlags(words)
  if (positional.length > (positionalName ? 1 : 0)) throw new Error('位置参数过多')
  if (positionalName && positional.length !== 1) throw new Error(`缺少 ${positionalName}`)
  const args = await readInput(flags, stdin)
  const properties = schema?.properties || {}
  if (positionalName) args[positionalName] = positional[0]
  for (const [flag, value] of flags) {
    const field = flag.replaceAll('-', '_')
    if (!Object.hasOwn(properties, field)) throw new Error(`工具不支持 --${flag}；运行 wao tools list 查看可用工具`)
    args[field] = coerce(value, properties[field], flag)
  }
  for (const field of Object.keys(args)) {
    if (!Object.hasOwn(properties, field)) throw new Error(`工具不支持字段 ${field}`)
  }
  const missing = (schema?.required || []).filter(field => args[field] === undefined || args[field] === null)
  if (missing.length) throw new Error(`缺少必填字段：${missing.join('、')}`)
  return args
}

function formatCell(value) {
  const text = value === null || value === undefined ? '' : typeof value === 'object' ? JSON.stringify(value) : String(value)
  return text.replaceAll(/[\r\n\t]+/g, ' ').slice(0, 100)
}

export function formatOutput(value, mode = 'auto') {
  if (mode === 'json' || (mode === 'auto' && !Array.isArray(value))) return JSON.stringify(value, null, 2)
  if (!Array.isArray(value)) return JSON.stringify(value, null, 2)
  if (!value.length) return '（无结果）'
  if (value.some(row => !row || typeof row !== 'object' || Array.isArray(row))) return value.map(formatCell).join('\n')
  const columns = [...new Set(value.flatMap(row => Object.keys(row)))].filter(column =>
    value.some(row => row[column] === null || typeof row[column] !== 'object'))
  if (!columns.length) return JSON.stringify(value, null, 2)
  const widths = columns.map(column => Math.max(column.length, ...value.map(row => formatCell(row[column]).length)))
  const line = row => columns.map((column, index) => formatCell(row[column]).padEnd(widths[index])).join('  ').trimEnd()
  return [line(Object.fromEntries(columns.map(column => [column, column]))), ...value.map(line)].join('\n')
}

export async function run(argv, { stdout = process.stdout, stdin = process.stdin,
  tokenProvider = getToken, clientFactory = createClient, tokenStore = storeToken,
  confirmHostedPage = confirmHostedHtml } = {}) {
  const { rest, globals } = removeGlobalOptions(argv)
  const path = rest.slice(0, 2).join(' ')
  if (!rest.length || rest[0] === 'help' || rest[0] === '--help' || rest[0] === '-h') {
    stdout.write(`${HELP}\n`); return
  }
  if (path === 'auth logout') { await removeToken(); stdout.write('已移除本机安全凭据库中的 WAO 令牌。\n'); return }
  const url = endpoint(globals.url)
  if (path === 'auth login') {
    if (rest.length > 3 || (rest[2] && rest[2] !== '--token-stdin')) throw new Error('用法：wao auth login [--token-stdin]')
    const token = normalizeWorkspaceToken(await readToken({ stdin, stdout, fromStdin: rest[2] === '--token-stdin' }))
    await clientFactory({ url, token }).listTools()
    await tokenStore(token)
    stdout.write('工作区令牌已验证并存入本机安全凭据库。\n')
    return
  }
  if (path === 'pages publish') {
    const { flags, confirm } = parseHostedPageFlags(rest.slice(2))
    const details = await prepareHostedHtml(flags)
    const token = await tokenProvider()
    const client = clientFactory({ url, token })
    await client.checkHostedPagePermission(details.projectId)
    const expectedUrl = new URL('/api/pages/' + encodeURIComponent(details.slug), new URL(url).origin).toString()
    if (!confirm) await confirmHostedPage(details, expectedUrl, stdin, stdout)
    const page = await client.publishHostedPage(details)
    let publicUrl
    try { publicUrl = hostedPageUrl(url, page).toString() }
    catch (error) {
      throw new Error('页面已提交，但 WAO 返回的链接无法核验；请先检查 WAO 页面列表，不要直接重试。' + error.message)
    }
    stdout.write('页面已创建：' + publicUrl + '\n')
    const verification = await client.verifyHostedPage(page, details.html)
    if (!verification.ok) {
      throw new Error('页面已创建，但匿名核验未通过（HTTP ' + verification.status + '）：' +
        verification.message + '。请先检查此链接，不要直接重试：' + publicUrl)
    }
    stdout.write('匿名访问核验通过（HTTP ' + verification.status + '），公网 HTML 与本地文件一致，共 ' +
      verification.bytes + ' 字节。\n')
    return
  }
  const token = await tokenProvider()
  const client = clientFactory({ url, token })
  if (path === 'auth status') {
    const tools = await client.listTools()
    stdout.write(`连接成功，可用工具 ${tools.length} 个。\n`); return
  }
  if (path === 'tools list') {
    const tools = await client.listTools()
    stdout.write(`${formatOutput(tools.map(({ name, title, annotations }) => ({ name, title, readOnly: !!annotations?.readOnlyHint })), globals.output)}\n`)
    return
  }
  let toolName, words, positionalName
  if (path === 'tools call') {
    toolName = rest[2]
    if (!toolName) throw new Error('用法：wao tools call NAME --input 或 --input-file')
    words = rest.slice(3)
  } else {
    const command = COMMANDS.get(path)
    if (!command) throw new Error(`未知命令：${path || rest[0]}。运行 wao help 查看用法`)
    ;[toolName, positionalName] = command
    words = rest.slice(2)
  }
  const tools = await client.listTools()
  const tool = tools.find(item => item.name === toolName)
  if (!tool) throw new Error(`当前令牌没有工具 ${toolName}，或服务端尚未提供该工具`)
  const args = await argumentsForTool(words, positionalName, tool.inputSchema, stdin)
  const data = await client.call(toolName, args)
  stdout.write(`${formatOutput(data, globals.output)}\n`)
}
