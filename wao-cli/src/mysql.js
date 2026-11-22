import { spawn } from 'node:child_process'
import { readFile, realpath } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { homedir } from 'node:os'
import { isAbsolute, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { readMacCredential } from './auth.js'
import { deviceWorkspaceToken, openDeviceVault, storeDeviceGrant } from './device-vault.js'

const READ_ONLY_CONFIG = fileURLToPath(new URL('./mysql-readonly.dbhub.toml', import.meta.url))
const AUTH_ERRORS = new Set(['ER_ACCESS_DENIED_ERROR', 'ER_MUST_CHANGE_PASSWORD_LOGIN', 'ER_ACCOUNT_HAS_BEEN_LOCKED'])

const openVault = (options) => openDeviceVault('mysql', ' MySQL ', options)
export const mysqlWorkspaceToken = (url, fallback) => deviceWorkspaceToken('mysql', url, fallback)

export async function authorizeMysql(url, token) {
  await openVault({ url, tokenProvider: async () => token })
  await storeDeviceGrant('mysql', url, token)
}

function readProfile(alias) {
  if (!/^mysql-[a-z0-9][a-z0-9-]{0,49}$/.test(alias)) throw new Error('MySQL 实例名称须以 mysql- 开头。')
  const directory = process.env.WAO_MYSQL_INSTANCE_DIR || join(homedir(), '.codex', 'config', 'mysql-instances')
  if (!isAbsolute(directory)) throw new Error('WAO_MYSQL_INSTANCE_DIR 须为绝对路径。')
  return loadProfile(join(directory, alias + '.json'))
}

async function loadProfile(path) {
  let config
  try { config = JSON.parse(await readFile(path, 'utf8')) }
  catch { throw new Error('无法读取 MySQL 本机实例配置，请检查 Codex 已配置的实例。') }
  if (!config) throw new Error('MySQL 本机实例配置无效。')
  const { host, port, database, user, sslmode } = config
  if (typeof host !== 'string' || !/^[a-zA-Z0-9.-]+$/.test(host) || host.length > 253
    || !Number.isInteger(port) || port < 1 || port > 65535
    || ![database, user].every(value => typeof value === 'string' && /^[^\x00-\x1f\x7f]{1,128}$/.test(value))
    || !['disable', 'require', 'verify-full'].includes(sslmode)) {
    throw new Error('MySQL 本机实例配置无效。')
  }
  return { host, port, database, user, sslmode }
}

async function dbhubEntry() {
  const path = process.env.WAO_MYSQL_DBHUB_ENTRY
  if (!path || !isAbsolute(path)) throw new Error('请将 WAO_MYSQL_DBHUB_ENTRY 配置为本机 DBHub 入口的绝对路径。')
  try { return await realpath(path) } catch { throw new Error('找不到本机 DBHub 入口。') }
}

async function localPassword(profile) {
  const account = `${profile.user}@${profile.host}:${profile.port}/${profile.database}`
  return readMacCredential('codex.mysql-instance', account, { trim: false })
}

async function probe(entry, profile, password) {
  let mysql
  try { mysql = createRequire(entry)('mysql2/promise') }
  catch { throw new Error('本机 DBHub 缺少 MySQL 驱动。') }
  const { sslmode, ...target } = profile
  const ssl = sslmode === 'disable' ? undefined : { rejectUnauthorized: sslmode !== 'require' }
  let connection
  try {
    connection = await mysql.createConnection({ ...target, password, ssl, connectTimeout: 10000 })
    const [rows] = await connection.query({ sql: 'SELECT 1 AS connection_ok', timeout: 10000 })
    if (rows?.[0]?.connection_ok !== 1) throw new Error()
    return true
  } catch (error) {
    if (AUTH_ERRORS.has(error.code)) return false
    throw new Error('MySQL 只读探针未通过，请检查网络、TLS 及默认库权限后重试。')
  } finally {
    connection?.destroy()
  }
}

function unpackPassword(record) {
  if (record === null) return null
  try {
    const { password } = JSON.parse(record)
    if (typeof password === 'string' && password) return password
  } catch { /* Return only a fixed diagnostic. */ }
  throw new Error('WAO 中的 MySQL 设备授权格式无效。')
}

function startReadOnlyMcp(entry, profile, password) {
  const { user, host, port, database, sslmode } = profile
  const q = encodeURIComponent
  const dsn = `mysql://${q(user)}:${q(password)}@${host}:${port}/${q(database)}?sslmode=${sslmode}`
  const allowed = ['PATH', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'NODE_EXTRA_CA_CERTS']
  const env = Object.fromEntries(allowed.filter(key => process.env[key] !== undefined).map(key => [key, process.env[key]]))
  env.CODEX_MYSQL_INSTANCE_DSN = dsn
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [entry, '--transport', 'stdio', '--config', READ_ONLY_CONFIG], {
      env, stdio: ['inherit', 'inherit', 'ignore'],
    })
    const terminate = () => child.kill('SIGTERM')
    process.once('SIGTERM', terminate)
    process.once('SIGINT', terminate)
    child.once('error', () => reject(new Error('无法启动只读 MySQL MCP。')))
    child.once('close', (code, signal) => {
      process.off('SIGTERM', terminate)
      process.off('SIGINT', terminate)
      if (code === 0 || signal === 'SIGTERM') resolve()
      else reject(new Error('只读 MySQL MCP 已退出。'))
    })
  })
}

export async function mysqlConnection(action, alias, { url, tokenProvider, fetchImpl, getDeviceKey,
  getProfile = readProfile, getEntry = dbhubEntry, getLocalPassword = localPassword,
  queryProvider = probe, serve = startReadOnlyMcp }) {
  if (!['status', 'sync', 'serve'].includes(action)) throw new Error('用法：wao connections mysql status|sync|serve 实例名')
  const profile = await getProfile(alias)
  const { host, port, database, user, sslmode } = profile
  const slot = JSON.stringify([host.toLowerCase(), port, database, user, sslmode])
  const vault = await openVault({ url, tokenProvider, fetchImpl, getDeviceKey, slot })
  const entry = await getEntry()
  const check = (password) => queryProvider(entry, profile, password)
  const required = `AUTH_REQUIRED：MySQL 本机凭据缺失或被拒绝，请通过 Codex 内置终端更新 ${alias}，再运行 wao connections mysql sync ${alias}。`
  if (action === 'sync') {
    const local = await getLocalPassword(profile)
    if (!local || !await check(local)) throw new Error(required)
    return { synced: await vault.save(JSON.stringify({ password: local })), source: 'vault' }
  }

  const recovered = unpackPassword(vault.read())
  if (recovered && await check(recovered)) {
    if (action === 'serve') return serve(entry, profile, recovered)
    return { authenticated: true, source: 'vault' }
  }
  const local = await getLocalPassword(profile)
  if (!local || local === recovered || !await check(local)) throw new Error(required)
  if (action === 'serve') {
    await vault.save(JSON.stringify({ password: local }))
    return serve(entry, profile, local)
  }
  return { authenticated: true, source: 'local', vault: recovered ? 'rejected' : 'missing' }
}
