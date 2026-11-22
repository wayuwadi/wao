import { execFile } from 'node:child_process'
import { createInterface } from 'node:readline'
import { promisify } from 'node:util'
import { endpoint } from './client.js'

const runExecutable = promisify(execFile)
const MAX_SECRET_BYTES = 8192
const HELP = `用法：
  wao vault find 条目名 [--account 账号]
  wao vault save 条目名 [--account 账号] [--secret-stdin | --from-codeup-keychain]
默认由本人在终端隐藏输入。--secret-stdin 从可信程序接收原样 UTF-8。`

async function readLine(stdin, output) {
  const lines = createInterface({ input: stdin, output, terminal: true, historySize: 0 })
  try {
    return await new Promise((resolve, reject) => {
      lines.once('line', resolve)
      lines.once('close', () => reject(new Error('未完成输入。')))
      lines.once('SIGINT', () => reject(new Error('已取消。')))
    })
  } finally {
    lines.close()
  }
}

export async function readSecret(stdin, stderr, source) {
  if (source === '--from-codeup-keychain') {
    if (process.platform !== 'darwin') throw new Error('Codeup 钥匙串导入适用于 macOS。')
    try {
      const { stdout } = await runExecutable('/usr/bin/security', ['find-generic-password', '-s', 'codeup-cli', '-a', 'codeup', '-w'], {
        timeout: 25_000, maxBuffer: MAX_SECRET_BYTES + 1,
      })
      return stdout.replace(/\r?\n$/, '')
    } catch { throw new Error('无法读取本机 Codeup CLI 的钥匙串条目，请检查已有授权。') }
  }
  if (source === '--secret-stdin') {
    const chunks = []
    let size = 0
    for await (const chunk of stdin) {
      const bytes = Buffer.from(chunk)
      size += bytes.length
      if (size > MAX_SECRET_BYTES) throw new Error('凭据最多 8192 个 UTF-8 字节。')
      chunks.push(bytes)
    }
    try { return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(Buffer.concat(chunks)) }
    catch { throw new Error('凭据须为有效 UTF-8。') }
  }
  if (!stdin.isTTY) throw new Error('请由本人在交互终端输入凭据。')
  stderr.write('请输入凭据（输入时隐藏）：')
  try { return await readLine(stdin) } finally { stderr.write('\n') }
}

function vaultUrl(workspaceUrl, action) {
  const url = new URL(endpoint(workspaceUrl))
  const suffix = '/api/mcp/workspace'
  const path = url.pathname.replace(/\/+$/, '')
  if (!path.endsWith(suffix)) throw new Error('WAO 工作区地址路径须以 /api/mcp/workspace 结尾。')
  url.pathname = path.slice(0, -suffix.length) + '/api/vault/cli/' + action
  return url
}

export async function runVault(args, { url, stdin, stdout, stderr, tokenProvider, output, formatOutput, fetchImpl = fetch }) {
  if (!args.length || args[0] === '--help' || args[1] === '--help') { stdout.write(HELP + '\n'); return }
  const [action, title, ...flags] = args
  const name = title?.trim()
  if (!['find', 'save'].includes(action) || !name || name.startsWith('--') || name.length > 128 || /\p{Cc}/u.test(name)) throw new Error(HELP)
  let account
  let source
  for (let index = 0; index < flags.length; index++) {
    const flag = flags[index]
    if (flag === '--account' && account === undefined) {
      account = flags[++index]?.trim()
      if (!account || account.startsWith('--') || account.length > 128 || /\p{Cc}/u.test(account)) throw new Error('请输入有效账号。')
    } else if (action === 'save' && !source && ['--secret-stdin', '--from-codeup-keychain'].includes(flag)) {
      source = flag
    } else {
      throw new Error(HELP)
    }
  }
  const token = await tokenProvider()
  if (!token) throw new Error('未找到工作区令牌。请为 CLI 签发密码本权限并运行 wao auth login。')

  async function request(route, body) {
    const target = vaultUrl(url, route)
    let response
    try {
      response = await fetchImpl(target, {
        method: body === undefined ? 'GET' : 'POST',
        headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json', 'Accept': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body), redirect: 'error', signal: AbortSignal.timeout(20_000),
      })
    } catch { throw new Error('无法连接 WAO 密码本。若保存结果未知，请先查询对应条目。') }
    if (response.status === 401) throw new Error('工作区令牌无效或已吊销，请重新登录。')
    if (response.status === 403) throw new Error('当前令牌未获此密码本操作授权，请在能力中心新建 CLI 授权。')
    if (response.status === 409) throw new Error('密码本条目存在冲突，请查询或在密码本中选择正确条目。')
    if (!response.ok) throw new Error(`WAO 密码本请求失败（HTTP ${response.status}）。`)
    try {
      const payload = await response.json()
      if (payload.code === 0 && Object.hasOwn(payload, 'data')) return payload.data
    } catch { /* Use the same safe error for an invalid response. */ }
    throw new Error('WAO 密码本返回了无效响应。')
  }

  function itemMetadata(item) {
    if (item?.name !== name || typeof item.id !== 'string' || !/^[\w.:-]{1,32}$/.test(item.id) || typeof item.hasSecret !== 'boolean') {
      throw new Error('WAO 密码本条目信息格式无效。')
    }
    return { id: item.id, name, hasSecret: item.hasSecret }
  }

  if (action === 'find') {
    const rows = await request('find', { name, account })
    if (!Array.isArray(rows)) throw new Error('WAO 密码本查询结果格式无效。')
    stdout.write(formatOutput(rows.map(itemMetadata), output) + '\n')
    return
  }
  if (stdin.isTTY) stderr.write(`保存到 ${vaultUrl(url, 'save').origin} 的密码本条目「${name}」\n`)
  if (source === '--from-codeup-keychain') {
    if (!stdin.isTTY) throw new Error('导入 Codeup 钥匙串凭据需要本人在交互终端确认。')
    stderr.write('将本机 Codeup 授权复制到上述 WAO 密码本，输入 yes 确认：')
    if (await readLine(stdin, stderr) !== 'yes') throw new Error('已取消导入。')
  }
  if (await request('save-access') !== true) throw new Error('WAO 未确认当前令牌具有密码本保存权限。')
  const secret = await readSecret(stdin, stderr, source)
  if (!secret.trim() || Buffer.byteLength(secret) > MAX_SECRET_BYTES) throw new Error('请输入有效凭据，最多 8192 个 UTF-8 字节。')
  const receipt = await request('save', { name, account, secret })
  if (typeof receipt?.saved !== 'boolean') throw new Error('WAO 密码本保存结果格式无效。')
  stdout.write(formatOutput({ ...itemMetadata(receipt.item), saved: receipt.saved }, output) + '\n')
}
