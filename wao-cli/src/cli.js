import { readFile, stat, mkdir, writeFile } from 'node:fs/promises'
import { basename, extname, resolve } from 'node:path'
import { homedir } from 'node:os'
import { randomUUID } from 'node:crypto'
import { createInterface } from 'node:readline/promises'
import { createClient, endpoint, hostedPageUrl } from './client.js'
import { getToken, normalizeWorkspaceToken, readToken, removeToken, storeToken } from './auth.js'
import { COMMANDS } from './commands.js'
import { readUtf8, exportDocument } from './content.js'
export { COMMANDS } from './commands.js'

// 子命令是人类友好的别名；参数定义和权限始终来自服务端 tools/list。
export const VERSION = '0.2.0'

const HELP = `WAO 工作区 CLI

用法：wao <资源> <操作> [ID] [--字段 值] [--output json|table]

认证：
  wao auth login                 安全输入令牌，存入 Windows DPAPI 或 macOS 钥匙串
  wao auth login --token-stdin   从标准输入读取令牌（不会出现在命令参数中）
  wao auth status                检查令牌及可用工具数
  wao auth logout                删除本机安全凭据

常用命令：
  wao projects list | get ID | create | update ID
  wao workspace info | members list
  wao tasks open | list | get ID | create | update ID | move ID | schedule ID
  wao tasks comment TASK_ID --text 文字
  wao comments update COMMENT_ID --task-id TASK_ID --text 文字
  wao tasks document TASK_ID --document-id DOC_ID --mode add --expected-edit-version VERSION
  wao subtasks create TASK_ID | update ID | reorder TASK_ID | move ID
  wao subtasks status ID --task-id TASK_ID --status 待验证 --expected-subtask-version VERSION
  wao knowledge list | get ID | create | update ID | move
  wao documents list KB_ID | get ID | create | update ID
  wao documents archived KB_ID | archive ID | restore ID
  wao documents export ID --format md|json --file FILE|- [--force]
  wao notes list | get ID | create | update ID | export ID
  wao notes tags add|remove|set|clear DOC_ID --expected-rev REV [--tags '["标签"]']
  wao tags list | create --name 标签
  wao templates list | create | update ID
  wao search --keyword 关键词 [--kinds '["task","note","document"]']
  wao todo day yyyy-MM-dd | days --from yyyy-MM-dd --to yyyy-MM-dd
  wao daily record --business-date yyyy-MM-dd --file article.md [--document-id ID]
  wao daily operation OPERATION_ID
  wao folders create | rename
  wao pages publish --file index.html --title 标题 --slug public-name [--project-id ID] [--yes]
  wao tools list                 查看当前令牌实际可用的工具
  wao tools describe NAME        查看动态输入字段和必填条件
  wao tools call NAME --input '{"field":"value"}'
  wao tools call NAME --input-file payload.json

例子：
  wao projects create --name "新项目"           不指定截止日期即未排期
  wao projects update PROJECT_ID --due 2026-12-31
  wao projects update PROJECT_ID --input '{"due":""}'  清空截止日期
  wao tasks open --limit 10
  wao tasks open --mine-only false --project-id PROJECT_ID
  wao tasks create --project-id PROJECT_ID --title "修复问题"
  wao documents update DOC_ID --input-file update.json
  wao notes create --title "今日小记" --file article.md --tags '["工作"]'
  wao notes create --title "系统功能介绍" --file article.md --system-generated true --tags '["新功能介绍"]'
  wao notes list --include-system false
  wao pages publish --file dist/index.html --title "分享稿" --slug share-draft

托管页发布：只接受 UTF-8 单文件 HTML，最大 10 MB；会公开生成公网链接。
每次交互发布前均需确认；自动化须显式添加 --yes。请在能力中心“我的连接”CLI/MCP 新授权中选择发布 HTML。

配置：WAO_TOKEN（优先于本机安全凭据）；WAO_URL（默认 https://wao.wayuwa.cn/api/mcp/workspace）。
      --url 可指定本次地址，仅 HTTPS 和本机 HTTP；--output json 提供脚本可读结果。
字段名与 WAO MCP tools/list 一致，命令行使用连字符（如 --project-id）。
--input 和 --input-file 接受 JSON 对象，适用于复杂字段或标准输入（--input-file -）。
正文写入可使用 --file UTF-8_FILE|- 保留 Markdown 排版，与 JSON 正文不可同时输入。
每个业务命令可用 --help 查看实时 schema；wao doctor 检查连接；wao --version 查看版本。
小记创建及日操作自动生成稳定标识，并在写入前持久保存回执；--receipt-file 可指定新回执文件。
排程必须显式提供全部安排字段；可空字段使用 null。冲突后重读版本，CLI 不自动重试写入。`

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
    if (name === 'force') { flags.set(name, true); continue }
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
      raw = await readUtf8('-', stdin)
    } else raw = await readUtf8(path, stdin)
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
  if (Array.isArray(property.type)) {
    if (value === 'null' && property.type.includes('null')) return null
    return coerce(value, { ...property, type: property.type.find(type => type !== 'null') }, flag)
  }
  if (property.anyOf) {
    if (value === 'null' && property.anyOf.some(schema => schema.type === 'null')) return null
    for (const schema of property.anyOf) {
      try { return coerce(value, schema, flag) } catch { /* 尝试其他明确声明的类型 */ }
    }
    throw new Error(`--${flag} 不符合工具声明的参数类型`)
  }
  if (property.type === 'string') return value
  if (property.type === 'boolean') {
    if (value !== 'true' && value !== 'false') throw new Error(`--${flag} 必须是 true 或 false`)
    return value === 'true'
  }
  if (property.type === 'integer') {
    const number = Number(value)
    if (!Number.isSafeInteger(number) || !value.trim()) throw new Error(`--${flag} 必须是整数`)
    return number
  }
  if (property.type === 'number') {
    const number = Number(value)
    if (!Number.isFinite(number) || !value.trim()) throw new Error(`--${flag} 必须是数字`)
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

function validateValue(value, property, field) {
  if (property.anyOf) {
    for (const choice of property.anyOf) {
      try { validateValue(value, choice, field); return } catch { /* 检查候选类型 */ }
    }
    throw new Error(`${field} 不符合工具声明的类型`)
  }
  const types = Array.isArray(property.type) ? property.type : [property.type]
  const type = value === null ? 'null' : Array.isArray(value) ? 'array' : typeof value
  if (property.type && !types.includes(type) && !(type === 'number' && types.includes('integer') && Number.isSafeInteger(value))) {
    throw new Error(`${field} 不符合工具声明的类型 ${types.join('|')}`)
  }
  if (property.enum && !property.enum.includes(value)) throw new Error(`${field} 只接受：${property.enum.join('、')}`)
  if (typeof value === 'string') {
    if ((property.minLength !== undefined && value.length < property.minLength) || (property.maxLength !== undefined && value.length > property.maxLength)) throw new Error(`${field} 长度超出工具允许范围`)
    if (property.pattern && !new RegExp(property.pattern).test(value)) throw new Error(`${field} 不符合工具声明的格式`)
  }
  if (typeof value === 'number' && ((property.minimum !== undefined && value < property.minimum) || (property.maximum !== undefined && value > property.maximum))) throw new Error(`${field} 超出工具允许范围`)
  if (Array.isArray(value) && property.items) value.forEach((item, index) => validateValue(item, property.items, `${field}[${index}]`))
  if (Array.isArray(value) && ((property.minItems !== undefined && value.length < property.minItems) || (property.maxItems !== undefined && value.length > property.maxItems))) throw new Error(`${field} 数组长度超出工具允许范围`)
  if (value && !Array.isArray(value) && typeof value === 'object' && property.properties) {
    const missing = (property.required || []).filter(name => !Object.hasOwn(value, name))
    if (missing.length) throw new Error(`${field} 缺少必填字段：${missing.join('、')}`)
    for (const [key, nested] of Object.entries(value)) {
      if (property.properties[key]) validateValue(nested, property.properties[key], `${field}.${key}`)
      else if (property.additionalProperties === false) throw new Error(`${field} 不支持字段 ${key}`)
    }
  }
}

export async function argumentsForTool(words, positionalName, schema, stdin = process.stdin, defaults = {}) {
  const { positional, flags } = parseFlags(words)
  if (flags.has('force')) throw new Error('--force 仅适用于 notes/documents export')
  if (positional.length > (positionalName ? 1 : 0)) throw new Error('位置参数过多')
  if (positionalName && positional.length !== 1) throw new Error(`缺少 ${positionalName}`)
  if (flags.get('file') === '-' && flags.get('input-file') === '-') throw new Error('JSON 标准输入与正文标准输入不能同时使用')
  const input = await readInput(flags, stdin)
  const args = { ...defaults, ...input }
  const properties = schema?.properties || {}
  if (flags.has('file')) {
    if (!properties.content) throw new Error('当前工具不支持 Markdown 文件正文；请升级服务端或使用 --input-file')
    if (input.content !== undefined || input.blocks !== undefined || input.text !== undefined || flags.has('content') || flags.has('blocks') || flags.has('text')) throw new Error('--file 与 JSON / 参数中的正文 content、blocks、text 只能选一种')
    args.content = { version: 1, markdown: await readUtf8(flags.get('file'), stdin), document: null }
    flags.delete('file')
  }
  if (positionalName) args[positionalName] = positional[0]
  for (const [flag, value] of flags) {
    const field = flag.replaceAll('-', '_')
    if (!Object.hasOwn(properties, field)) throw new Error(`工具不支持 --${flag}；运行 wao tools list 查看可用工具`)
    args[field] = coerce(value, properties[field], flag)
  }
  for (const field of Object.keys(args)) {
    if (!Object.hasOwn(properties, field)) throw new Error(`工具不支持字段 ${field}`)
    validateValue(args[field], properties[field], field)
  }
  const missing = (schema?.required || []).filter(field => !Object.hasOwn(args, field) || args[field] === undefined)
  if (missing.length) throw new Error(`缺少必填字段：${missing.join('、')}`)
  return args
}

function formatCell(value) {
  const text = value === null || value === undefined ? '' : typeof value === 'object' ? JSON.stringify(value) : String(value)
  return text.replaceAll(/[\r\n\t]+/g, ' ').slice(0, 100)
}

export function formatOutput(value, mode = 'auto') {
  if (mode !== 'json' && value && !Array.isArray(value) && Array.isArray(value.items)) {
    return formatOutput(value.items, 'table') + `\n共 ${value.total ?? value.items.length} 条，偏移 ${value.offset ?? 0}，本页 ${value.items.length} 条${value.hasMore ? '，还有下一页' : ''}`
  }
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

function describeTool(tool) {
  const schema = tool.inputSchema || {}
  const rows = Object.entries(schema.properties || {}).map(([name, property]) => ({
    option: '--' + name.replaceAll('_', '-'),
    type: Array.isArray(property.type) ? property.type.join('|') : property.type || JSON.stringify(property.anyOf || {}),
    required: (schema.required || []).includes(name),
    description: property.description || '',
    ...(property.enum ? { choices: property.enum.join('|') } : {}),
  }))
  return `${tool.name}${tool.description ? '\n' + tool.description : ''}\n${formatOutput(rows, 'table')}\n\n完整输入 schema：\n${JSON.stringify(schema, null, 2)}`
}

function defaultReceiptDirectory() {
  return process.platform === 'win32'
    ? resolve(process.env.LOCALAPPDATA || process.env.APPDATA || homedir(), 'WAO', 'cli', 'data', 'operations')
    : resolve(homedir(), '.local', 'share', 'wao-cli', 'operations')
}

async function prepareReceipt(toolName, args, receiptFile, receiptDirectory, stderr) {
  const id = args.operation_id || args.document_id
  const file = resolve(receiptFile || resolve(receiptDirectory, `${toolName}-${encodeURIComponent(id)}.json`))
  if (!receiptFile) await mkdir(receiptDirectory, { recursive: true })
  const receipt = { tool: toolName, ...(args.operation_id ? { operation_id: args.operation_id } : {}), ...(args.document_id ? { document_id: args.document_id } : {}), state: 'prepared', created_at: new Date().toISOString() }
  try { await writeFile(file, JSON.stringify(receipt, null, 2) + '\n', { encoding: 'utf8', flag: 'wx', mode: 0o600 }) }
  catch (error) {
    if (error.code === 'EEXIST') throw new Error('回执已存在；先检查原操作结果，不会自动重新写入：' + file)
    throw error
  }
  stderr.write(`写入标识：${id}${args.operation_id && args.document_id ? '；文档：' + args.document_id : ''}；回执：${file}\n`)
  return { file, receipt }
}

export async function run(argv, { stdout = process.stdout, stderr = process.stderr, stdin = process.stdin,
  tokenProvider = getToken, clientFactory = createClient, tokenStore = storeToken,
  confirmHostedPage = confirmHostedHtml, receiptDirectory = defaultReceiptDirectory() } = {}) {
  const { rest, globals } = removeGlobalOptions(argv)
  const path = rest.slice(0, 2).join(' ')
  if (rest.length === 1 && (rest[0] === '--version' || rest[0] === '-v')) { stdout.write(VERSION + '\n'); return }
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
  if (path === 'auth status' || rest[0] === 'doctor') {
    if ((rest[0] === 'doctor' && rest.length !== 1) || (path === 'auth status' && rest.length !== 2)) throw new Error('用法：wao doctor 或 wao auth status')
    const tools = await client.listTools()
    const info = tools.some(tool => tool.name === 'get_workspace_info') ? await client.call('get_workspace_info', {}) : null
    const data = { connectionStatus: 'connected', cliVersion: VERSION, nodeVersion: process.versions.node, endpoint: url,
      toolCount: tools.length, availableTools: tools.map(tool => tool.name), receiptDirectory,
      authorizationUrl: new URL('/capabilities?area=connections', url).toString(),
      ...(info ? { workspace: info } : { workspace: null, compatibility: '连接成功；当前令牌未授权 get_workspace_info，或服务端尚未提供该工具，身份、权限及版本不可查询。新增能力请在能力中心 CLI/MCP 新授权中逐项选择，已有令牌不会自动扩权。' }) }
    stdout.write(`${formatOutput(data, globals.output)}\n`); return
  }
  if (path === 'tools list') {
    const tools = await client.listTools()
    stdout.write(`${formatOutput(tools.map(({ name, title, annotations }) => ({ name, title, readOnly: !!annotations?.readOnlyHint })), globals.output)}\n`)
    return
  }
  let toolName, words, positionalName, defaults = {}, commandPath = path
  if (path === 'tools call' || path === 'tools describe') {
    toolName = rest[2]
    if (!toolName) throw new Error('用法：wao tools call|describe NAME')
    words = rest.slice(3)
  } else {
    commandPath = [...COMMANDS.keys()].sort((a, b) => b.split(' ').length - a.split(' ').length)
      .find(key => rest.slice(0, key.split(' ').length).join(' ') === key)
    const command = COMMANDS.get(commandPath)
    if (!command) throw new Error(`未知命令：${path || rest[0]}。运行 wao help 查看用法`)
    ;[toolName, positionalName, defaults = {}] = command
    words = rest.slice(commandPath.split(' ').length)
  }
  const tools = await client.listTools()
  const tool = tools.find(item => item.name === toolName)
  if (!tool) throw new Error(`当前令牌没有工具 ${toolName}，或服务端尚未提供该工具`)
  if (path === 'tools describe' || words.includes('--help') || words.includes('-h')) {
    if (path === 'tools describe' && words.length) throw new Error('用法：wao tools describe NAME')
    stdout.write(`${globals.output === 'json' ? JSON.stringify(tool, null, 2) : describeTool(tool)}\n`); return
  }
  let exportOptions
  if (commandPath === 'notes export' || commandPath === 'documents export') {
    const { flags } = parseFlags(words)
    const format = flags.get('format') || 'md'
    if (!['md', 'json'].includes(format)) throw new Error('--format 只接受 md 或 json')
    if (!flags.has('file')) throw new Error('导出需要 --file FILE 或 --file -')
    exportOptions = { format, file: flags.get('file'), force: flags.has('force') }
    words = words.filter((word, index) => !['--format', '--file', '--force'].includes(word) && !['--format', '--file'].includes(words[index - 1]))
  }
  const special = parseFlags(words)
  const receiptFile = special.flags.get('receipt-file')
  if (receiptFile === '-') throw new Error('--receipt-file 必须是持久文件路径')
  if (receiptFile) words = words.filter((word, index) => word !== '--receipt-file' && words[index - 1] !== '--receipt-file')
  const receipted = ['schedule_task', 'record_daily_note', 'create_note'].includes(toolName)
  if (receiptFile && !receipted) throw new Error('此工具没有操作回执；--receipt-file 仅适用于小记创建和日操作')
  // 默认标识也经过实时 schema 验证；旧服务端未声明该字段时不注入。
  if (receipted && tool.inputSchema?.properties?.operation_id) defaults = { operation_id: randomUUID(), ...defaults }
  if (['create_note', 'record_daily_note'].includes(toolName) && tool.inputSchema?.properties?.document_id) defaults = { document_id: randomUUID().replaceAll('-', ''), ...defaults }
  // 新服务端显式分页，旧服务端继续原数组契约；显式参数 / JSON 可覆盖默认值。
  if (toolName === 'list_documents' && tool.inputSchema?.properties?.offset) defaults = { offset: 0, ...(tool.inputSchema.properties.limit ? { limit: 50 } : {}), ...defaults }
  const args = await argumentsForTool(words, positionalName, tool.inputSchema, stdin, defaults)
  // tags 别名的 mode 不能被参数覆盖，否则命令语义不明确。
  if (commandPath?.startsWith('notes tags ') && args.mode !== defaults.mode) throw new Error('标签命令的 mode 与子命令不一致')
  if (commandPath?.startsWith('notes tags ') && defaults.mode) {
    if (defaults.mode === 'clear' && Object.hasOwn(args, 'tags')) throw new Error('notes tags clear 不接受 --tags')
    if (defaults.mode !== 'clear' && !Object.hasOwn(args, 'tags')) throw new Error('此标签命令需要 --tags JSON数组')
  }
  const pending = receipted && (args.operation_id || (toolName === 'create_note' && args.document_id))
    ? await prepareReceipt(toolName, args, receiptFile, receiptDirectory, stderr) : null
  let data
  try { data = await client.call(toolName, args) }
  catch (error) {
    if (pending) {
      try { await writeFile(pending.file, JSON.stringify({ ...pending.receipt, state: 'unknown', finished_at: new Date().toISOString(), error: error.message }, null, 2) + '\n', { encoding: 'utf8', mode: 0o600 }) }
      catch { /* prepared 回执仍保留稳定标识；不掩盖原网络/业务错误 */ }
      throw new Error(`${error.message}；结果待核对，未自动重试。回执：${pending.file}`)
    }
    throw error
  }
  if (pending) {
    try { await writeFile(pending.file, JSON.stringify({ ...pending.receipt, state: 'succeeded', finished_at: new Date().toISOString(), result: data }, null, 2) + '\n', { encoding: 'utf8', mode: 0o600 }) }
    catch (error) { throw new Error(`WAO 写入已成功，但回执保存失败：${pending.file}；请检查操作 ${args.operation_id || args.document_id}，不要直接重试。${error.message}`) }
  }
  if (exportOptions) { await exportDocument(data, exportOptions, stdout); return }
  stdout.write(`${formatOutput(data, globals.output)}\n`)
}
