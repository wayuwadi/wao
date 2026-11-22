import { execFile } from 'node:child_process'
import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto'
import { realpath } from 'node:fs/promises'
import { homedir } from 'node:os'
import { dirname, isAbsolute, join } from 'node:path'
import { promisify } from 'node:util'
import { readMacCredential, writeMacCredential } from './auth.js'
import { endpoint } from './client.js'

const execute = promisify(execFile)
const PROVIDER_ORIGIN = 'https://devops.xingshiapp.cn'
const PREFIX = 'wao-devops-v1'
const KEY_SERVICE = 'cn.wayuwa.wao.cli.devops'
const KEY_ACCOUNT = 'device-key-v1'
const AUTH_REQUIRED = 'AUTH_REQUIRED：星狮 DevOps 授权缺失或已失效，请通过团队 CLI 扫码后运行 wao connections devops sync。'

function grantAccount(url) {
  const workspace = endpoint(url).replace(/\/+$/, '')
  return 'workspace-grant-v1-' + createHash('sha256').update(workspace).digest('hex').slice(0, 32)
}

export async function devopsWorkspaceToken(url, fallback) {
  if (process.env.WAO_TOKEN?.trim()) return fallback()
  if (process.platform === 'darwin') {
    const stored = await readMacCredential(KEY_SERVICE, grantAccount(url))
    if (stored) return stored
  }
  return fallback()
}

export async function authorizeDevops(url, token) {
  await devopsConnection('authorize', { url, tokenProvider: async () => token })
  await writeMacCredential(KEY_SERVICE, grantAccount(url), token, { replace: true })
}

async function deviceKey(create) {
  if (process.platform !== 'darwin') throw new Error('星狮 DevOps 密码本连接当前使用 macOS 钥匙串。')
  let raw = await readMacCredential(KEY_SERVICE, KEY_ACCOUNT)
  if (!raw && create) {
    const candidate = randomBytes(32).toString('base64url')
    try {
      await writeMacCredential(KEY_SERVICE, KEY_ACCOUNT, candidate)
      raw = candidate
    } catch (error) {
      raw = await readMacCredential(KEY_SERVICE, KEY_ACCOUNT)
      if (!raw) throw error
    }
  }
  if (!raw) return null
  if (!/^[A-Za-z0-9_-]{43}$/.test(raw)) throw new Error('星狮 DevOps 设备密钥格式无效，请检查本机钥匙串。')
  return Buffer.from(raw, 'base64url')
}

function seal(secret, key, audience) {
  const nonce = randomBytes(12)
  const cipher = createCipheriv('aes-256-gcm', key, nonce)
  cipher.setAAD(Buffer.from(audience))
  const encrypted = Buffer.concat([cipher.update(secret, 'utf8'), cipher.final()])
  return [PREFIX, nonce.toString('base64url'), cipher.getAuthTag().toString('base64url'), encrypted.toString('base64url')].join('.')
}

function unseal(envelope, key, audience) {
  try {
    const parts = envelope.split('.')
    const [version, nonce, tag, ciphertext] = parts
    if (parts.length !== 4 || version !== PREFIX || !/^[A-Za-z0-9_-]{16}$/.test(nonce) || !/^[A-Za-z0-9_-]{22}$/.test(tag)
      || !/^[A-Za-z0-9_-]{16,8000}$/.test(ciphertext)) throw new Error()
    const cipher = createDecipheriv('aes-256-gcm', key, Buffer.from(nonce, 'base64url'))
    cipher.setAAD(Buffer.from(audience))
    cipher.setAuthTag(Buffer.from(tag, 'base64url'))
    return Buffer.concat([cipher.update(Buffer.from(ciphertext, 'base64url')), cipher.final()]).toString('utf8')
  } catch {
    throw new Error('无法用本机密钥验证 WAO 中的设备授权，请检查设备及条目。')
  }
}

function envForProvider() {
  const allowed = ['PATH', 'HOME', 'USER', 'TMPDIR', 'LANG', 'LC_ALL', 'SSL_CERT_FILE', 'REQUESTS_CA_BUNDLE',
    'HTTPS_PROXY', 'HTTP_PROXY', 'NO_PROXY', 'https_proxy', 'http_proxy', 'no_proxy']
  return Object.fromEntries(allowed.filter(name => process.env[name] !== undefined).map(name => [name, process.env[name]]))
}

async function nativeExecutable() {
  const path = process.env.WAO_DEVOPS_CLI || join(homedir(), '.local', 'bin', 'cli-anything-devops')
  if (!isAbsolute(path)) throw new Error('WAO_DEVOPS_CLI 必须是已安装星狮 DevOps CLI 的绝对路径。')
  try { return await realpath(path) } catch { throw new Error('找不到星狮 DevOps CLI，请检查安装或 WAO_DEVOPS_CLI。') }
}

async function provider(action, token) {
  const binary = await nativeExecutable()
  const args = ['--json', '--read-only', '--base-url', PROVIDER_ORIGIN, ...(action === 'status' ? ['auth', 'status'] : ['environments'])]
  const env = envForProvider()
  if (token) env.DEVOPS_TOKEN = token
  let data
  try {
    data = JSON.parse((await execute(binary, args, { cwd: dirname(binary), env, timeout: 40000, maxBuffer: 1024 * 1024 })).stdout)
  } catch (error) {
    if (error.code === 3) {
      try { if (JSON.parse(error.stdout).error === 'authentication_required') return null } catch { /* Check other failures below. */ }
    }
    throw new Error('星狮 DevOps 请求失败，请检查网络及平台权限。')
  }
  if (action === 'status' && data?.authenticated === true) return true
  if (action === 'environments' && Array.isArray(data)) {
    return data.filter(row => row && typeof row.code === 'string' && /^[\w-]{1,64}$/.test(row.code))
      .map(row => ({ code: row.code, active: row.is_active === true }))
  }
  throw new Error('星狮 DevOps 返回了无法验证的响应。')
}

// Use the installed CLI's credential storage interface. Child output and stdin stay in process memory.
async function nativeCredential(action, input) {
  const binary = await nativeExecutable()
  const code = action === 'read'
    ? 'from cli_anything.devops.utils.auth import load_token; import sys; sys.stdout.write(load_token() or "")'
    : 'from cli_anything.devops.utils.auth import save_token; import sys; save_token(sys.stdin.read())'
  return new Promise((resolve, reject) => {
    const child = execFile(join(dirname(binary), 'python3'), ['-I', '-c', code], {
      cwd: dirname(binary), env: envForProvider(), timeout: 50000, maxBuffer: 8192,
    }, (error, stdout) => {
      if (error) reject(new Error('无法使用星狮 DevOps CLI 的本机凭据存储。'))
      else resolve(stdout)
    })
    child.stdin.on('error', () => {})
    child.stdin.end(input)
  })
}

export async function devopsConnection(action, { url, tokenProvider, fetchImpl = fetch,
  getDeviceKey = deviceKey, queryProvider = provider, credential = nativeCredential }) {
  if (!['authorize', 'status', 'environments', 'sync', 'restore'].includes(action)) {
    throw new Error('用法：wao connections devops authorize|status|environments|sync|restore')
  }
  const target = new URL(endpoint(url))
  const workspacePath = target.pathname.replace(/\/+$/, '')
  const suffix = '/api/mcp/workspace'
  if (!workspacePath.endsWith(suffix)) throw new Error('WAO 工作区地址路径须以 /api/mcp/workspace 结尾。')
  const token = await tokenProvider()
  if (!token) throw new Error('需要先将具备「使用星狮 DevOps 设备授权」的 WAO CLI 令牌存入本机。')

  let key = await getDeviceKey(false)
  const audience = PREFIX + '|' + target.origin + workspacePath
  async function request(body) {
    const device = key ? createHash('sha256').update(key).digest('hex').slice(0, 32) : '0'.repeat(32)
    target.pathname = workspacePath.slice(0, -suffix.length) + '/api/vault/cli/devops/' + device
    let response
    try {
      response = await fetchImpl(target, {
        method: body === undefined ? 'GET' : 'PUT', redirect: 'error', signal: AbortSignal.timeout(15000),
        headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
      })
    } catch { throw new Error('无法访问 WAO 密码本，请检查网络后重试。') }
    if (response.status === 401 || response.status === 403) throw new Error('当前 WAO CLI 令牌需要「使用星狮 DevOps 设备授权」权限。')
    if (response.status === 409) throw new Error('WAO 设备授权已变更，请重新检查状态。')
    if (!response.ok) throw new Error(`WAO 设备授权暂不可用（HTTP ${response.status}）。`)
    const result = await response.json().catch(() => null)
    if (result?.code !== 0 || !Object.hasOwn(result, 'data')) throw new Error('WAO 设备授权响应格式无效。')
    return result.data
  }

  let stored = await request()
  if (action === 'authorize') return { authorized: true }
  if (action === 'sync') {
    const native = await credential('read')
    if (!native || !(await queryProvider('status', native))) throw new Error(AUTH_REQUIRED)
    if (!key) {
      key = await getDeviceKey(true)
      stored = await request()
    }
    if (stored && unseal(stored.envelope, key, audience) === native) return { synced: false, source: 'vault' }
    if (Buffer.byteLength(native) > 6000) throw new Error('星狮授权超过 WAO 设备条目容量。')
    const revision = await request({ envelope: seal(native, key, audience), expectedRevision: stored?.revision ?? null })
    if (typeof revision !== 'string' || !/^[a-f0-9]{24}$/.test(revision)) throw new Error('WAO 未确认设备授权已保存。')
    return { synced: true, source: 'vault' }
  }

  const recovered = stored && key ? unseal(stored.envelope, key, audience) : null
  const operation = action === 'environments' ? 'environments' : 'status'
  const remote = recovered ? await queryProvider(operation, recovered) : null
  if (remote !== null) {
    if (action === 'restore') {
      if (await queryProvider('status') !== null) return { authenticated: true, restored: false, source: 'local' }
      await credential('write', recovered)
      if (await queryProvider('status') === null) throw new Error('WAO 已验证授权，但星狮本机存储复验失败。')
      return { authenticated: true, restored: true, source: 'vault' }
    }
    return { authenticated: true, source: 'vault', ...(operation === 'environments' ? { environments: remote } : {}) }
  }
  const local = await queryProvider(operation)
  if (local === null) throw new Error(AUTH_REQUIRED)
  return { authenticated: true, source: 'local', vault: stored ? 'expired' : 'missing',
    ...(operation === 'environments' ? { environments: local } : {}) }
}
