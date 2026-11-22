import { execFile } from 'node:child_process'
import { realpath } from 'node:fs/promises'
import { homedir } from 'node:os'
import { dirname, isAbsolute, join } from 'node:path'
import { promisify } from 'node:util'
import { deviceWorkspaceToken, openDeviceVault, storeDeviceGrant } from './device-vault.js'

const execute = promisify(execFile)
const PROVIDER_ORIGIN = 'https://devops.xingshiapp.cn'
const AUTH_REQUIRED = 'AUTH_REQUIRED：星狮 DevOps 授权缺失或已失效，请通过团队 CLI 扫码后运行 wao connections devops sync。'

export const devopsWorkspaceToken = (url, fallback) => deviceWorkspaceToken('devops', url, fallback)

export async function authorizeDevops(url, token) {
  await devopsConnection('authorize', { url, tokenProvider: async () => token })
  await storeDeviceGrant('devops', url, token)
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
  getDeviceKey, queryProvider = provider, credential = nativeCredential }) {
  if (!['authorize', 'status', 'environments', 'sync', 'restore'].includes(action)) {
    throw new Error('用法：wao connections devops authorize|status|environments|sync|restore')
  }
  const vault = await openDeviceVault('devops', '星狮 DevOps ', { url, tokenProvider, fetchImpl, getDeviceKey })
  if (action === 'authorize') return { authorized: true }
  if (action === 'sync') {
    const native = await credential('read')
    if (!native || !(await queryProvider('status', native))) throw new Error(AUTH_REQUIRED)
    return { synced: await vault.save(native), source: 'vault' }
  }

  const recovered = vault.read()
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
  return { authenticated: true, source: 'local', vault: recovered ? 'expired' : 'missing',
    ...(operation === 'environments' ? { environments: local } : {}) }
}
