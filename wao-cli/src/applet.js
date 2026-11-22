import { readFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { isAbsolute, join } from 'node:path'
import { deviceWorkspaceToken, openDeviceVault, storeDeviceGrant } from './device-vault.js'

export const APPLET_PROFILE = /^[a-z][a-z0-9-]{0,63}$/
const SESSION = /^(?:Bearer )?[A-Za-z0-9._~-]{16,5200}$/
const WECHAT_CODE = /^[A-Za-z0-9_-]{8,128}$/

const openVault = (options) => openDeviceVault('applet', '小程序 Auth ', options)
export const appletWorkspaceToken = (url, fallback) => deviceWorkspaceToken('applet', url, fallback)

export async function authorizeApplet(url, token) {
  await openVault({ url, tokenProvider: async () => token })
  await storeDeviceGrant('applet', url, token)
}

async function loadProfile(alias) {
  const directory = process.env.WAO_APPLET_PROFILE_DIR || join(homedir(), '.config', 'wao', 'applets')
  if (!isAbsolute(directory)) throw new Error('WAO_APPLET_PROFILE_DIR 须为绝对路径。')
  try { return JSON.parse(await readFile(join(directory, alias + '.json'), 'utf8')) }
  catch { throw new Error('找不到有效的小程序本机配置，请核对环境和 App ID。') }
}

function validateProfile(profile) {
  let origin
  try {
    const url = new URL(profile?.origin)
    if (url.protocol === 'https:' && url.pathname === '/' && !url.search && !url.hash && !url.username && !url.password) {
      origin = url.origin
    }
  } catch { /* Report invalid configuration without echoing it. */ }
  const { clientType, appId, group, name } = profile ?? {}
  if (!origin || typeof clientType !== 'string' || !/^[a-z][a-z0-9-]{0,48}-applet$/.test(clientType)
    || typeof appId !== 'string' || !/^wx[0-9a-f]{16}$/.test(appId)) {
    throw new Error('小程序配置须包含准确的 HTTPS 网关、微信 App ID 和小程序客户端标识。')
  }
  for (const [value, max] of [[group, 64], [name, 128]]) {
    if (value !== undefined && (typeof value !== 'string' || !value.trim() || value.trim().length > max || /\p{Cc}/u.test(value))) {
      throw new Error('小程序配置中的分组名或条目名无效。')
    }
  }
  return { origin, clientType, appId, group: group?.trim(), name: name?.trim() }
}

function userId(value) {
  if (typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value)) return value
  return Number.isSafeInteger(value) && value > 0 ? String(value) : null
}

async function passport(fetchImpl, profile, path, { session, code } = {}) {
  const headers = { Accept: 'application/json', 'plat-form': profile.clientType, 'Xs-Client-Type': profile.clientType, appId: profile.appId }
  if (session) headers.Authorization = session
  if (code) headers['Content-Type'] = 'application/json'
  try {
    const response = await fetchImpl(new URL(path, profile.origin), {
      method: code ? 'POST' : 'GET', headers, redirect: 'error', signal: AbortSignal.timeout(15000),
      body: code ? JSON.stringify({ code, appId: profile.appId }) : undefined,
    })
    if (response.status === 401) return { unauthorized: true }
    if (!response.ok) throw new Error()
    const payload = await response.json()
    if (payload?.code === 401) return { unauthorized: true }
    if (Number.isInteger(payload?.code)) return { result: payload }
  } catch { /* Keep diagnostics independent of the provider response and credentials. */ }
  throw new Error('小程序 Passport 请求失败，请检查网络、网关、客户端和 App ID。')
}

async function verifyPassport(fetchImpl, profile, session) {
  const { unauthorized, result } = await passport(fetchImpl, profile, '/passport/user', { session })
  if (unauthorized) return null
  const id = userId(result?.data?.userId)
  if (result?.code !== 200 || !id) throw new Error('小程序 Passport 未确认当前账号身份，请核对 Test 接口及客户端配置。')
  return id
}

function unpack(record) {
  if (record === null) return null
  try {
    const value = JSON.parse(record)
    if (typeof value?.session === 'string' && SESSION.test(value.session) && typeof value.userId === 'string' && userId(value.userId)) {
      return value
    }
  } catch { /* Use the same diagnostic for corrupted records. */ }
  throw new Error('WAO 中的小程序授权格式无效。')
}

export async function appletConnection(action, alias, { url, tokenProvider, fetchImpl, getDeviceKey,
  getProfile = loadProfile, providerFetch = fetch, readInput }) {
  if (!['status', 'import', 'login'].includes(action) || !APPLET_PROFILE.test(alias ?? '')) throw new Error('请指定小程序本机配置名。')
  const profile = validateProfile(await getProfile(alias))
  const slot = JSON.stringify([alias, profile.origin, profile.appId, profile.clientType])
  const vault = await openVault({ url, tokenProvider, fetchImpl, getDeviceKey, slot, group: profile.group, name: profile.name })
  const prior = unpack(vault.read())
  if (prior) {
    const id = await verifyPassport(providerFetch, profile, prior.session)
    if (id && prior.userId !== id) throw new Error('小程序会话账号与本机绑定不一致。')
    if (id) return { authenticated: true, source: 'vault', profile: alias }
  }
  if (action === 'status') return { authenticated: false, reason: prior ? 'expired' : 'missing', profile: alias }

  const input = (await readInput(profile)).trim()
  let session = input
  let loginUserId = null
  if (action === 'login') {
    if (!WECHAT_CODE.test(input)) throw new Error('请提供原小程序本次微信登录流程生成的临时代码。')
    const { result } = await passport(providerFetch, profile, '/passport/applet/login', { code: input })
    session = result?.data?.token
    loginUserId = userId(result?.data?.userId)
    if (result?.code !== 200 || !loginUserId) throw new Error('Passport 未签发已认证的小程序会话，请在原小程序确认微信登录结果。')
  }
  if (typeof session !== 'string' || !SESSION.test(session)) throw new Error('请提供该环境和 App ID 的已授权小程序会话。')
  const id = await verifyPassport(providerFetch, profile, session)
  if (!id) throw new Error('小程序 Passport 已拒绝该会话。')
  if ((prior && id !== prior.userId) || (loginUserId && id !== loginUserId)) throw new Error('小程序会话账号与本机绑定不一致。')
  const saved = await vault.save(JSON.stringify({ userId: id, session }))
  return { authenticated: true, saved, source: 'vault', profile: alias }
}
