import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto'
import { readMacCredential, writeMacCredential } from './auth.js'
import { endpoint } from './client.js'

const SUFFIX = '/api/mcp/workspace'
const accountFor = (url) => 'workspace-grant-v1-' + createHash('sha256').update(endpoint(url).replace(/\/+$/, '')).digest('hex').slice(0, 32)
const serviceFor = (kind) => 'cn.wayuwa.wao.cli.' + kind

export async function deviceWorkspaceToken(kind, url, fallback) {
  if (!process.env.WAO_TOKEN?.trim() && process.platform === 'darwin') {
    const grant = await readMacCredential(serviceFor(kind), accountFor(url))
    if (grant) return grant
  }
  return fallback()
}

export function storeDeviceGrant(kind, url, token) {
  return writeMacCredential(serviceFor(kind), accountFor(url), token, { replace: true })
}

async function deviceKey(kind, create) {
  if (process.platform !== 'darwin') throw new Error('WAO 设备授权当前使用 macOS 钥匙串。')
  const service = serviceFor(kind)
  const account = 'device-key-v1'
  let raw = await readMacCredential(service, account)
  if (!raw && create) {
    const candidate = randomBytes(32).toString('base64url')
    try {
      await writeMacCredential(service, account, candidate)
      raw = candidate
    } catch (error) {
      raw = await readMacCredential(service, account)
      if (!raw) throw error
    }
  }
  if (!raw) return null
  if (!/^[A-Za-z0-9_-]{43}$/.test(raw)) throw new Error('设备密钥格式无效，请检查本机钥匙串。')
  return Buffer.from(raw, 'base64url')
}

export function workspaceApiUrl(workspaceUrl, suffix) {
  const target = new URL(endpoint(workspaceUrl))
  const path = target.pathname.replace(/\/+$/, '')
  if (!path.endsWith(SUFFIX)) throw new Error('WAO 工作区地址路径须以 /api/mcp/workspace 结尾。')
  target.pathname = path.slice(0, -SUFFIX.length) + suffix
  return target
}

export async function openDeviceVault(kind, label, { url, tokenProvider, fetchImpl = fetch,
  slot = '', getDeviceKey = (create) => deviceKey(kind, create) }) {
  const workspace = endpoint(url).replace(/\/+$/, '')
  const token = await tokenProvider()
  if (!token) throw new Error(`需要先将具备「使用${label}设备授权」的 WAO CLI 令牌存入本机。`)
  const version = 'wao-' + kind + '-v1'
  const audience = Buffer.from(version + '|' + workspace + (slot ? '|' + slot : ''))
  let key = await getDeviceKey(false)

  async function request(body) {
    const device = key ? createHash('sha256').update(key).update(slot).digest('hex').slice(0, 32) : '0'.repeat(32)
    const target = workspaceApiUrl(workspace, `/api/vault/cli/${kind}/${device}`)
    let response
    try {
      response = await fetchImpl(target, {
        method: body === undefined ? 'GET' : 'PUT', redirect: 'error', signal: AbortSignal.timeout(15000),
        headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
      })
    } catch { throw new Error('无法访问 WAO 密码本，请检查网络后重试。') }
    if (response.status === 401 || response.status === 403) throw new Error(`当前 WAO CLI 令牌需要「使用${label}设备授权」权限。`)
    if (response.status === 409) throw new Error('WAO 设备授权已变更，请重新检查状态。')
    if (!response.ok) throw new Error(`WAO 设备授权暂不可用（HTTP ${response.status}）。`)
    const result = await response.json().catch(() => null)
    if (result?.code !== 0 || !Object.hasOwn(result, 'data')) throw new Error('WAO 设备授权响应格式无效。')
    return result.data
  }

  let record = await request()
  const read = () => {
    if (!record || !key) return null
    try {
      const parts = record.envelope.split('.')
      const [prefix, nonce, tag, ciphertext] = parts
      if (parts.length !== 4 || prefix !== version || !/^[A-Za-z0-9_-]{16}$/.test(nonce) || !/^[A-Za-z0-9_-]{22}$/.test(tag)
          || !/^[A-Za-z0-9_-]{16,8000}$/.test(ciphertext)) throw new Error()
      const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(nonce, 'base64url'))
      decipher.setAAD(audience)
      decipher.setAuthTag(Buffer.from(tag, 'base64url'))
      return Buffer.concat([decipher.update(Buffer.from(ciphertext, 'base64url')), decipher.final()]).toString('utf8')
    } catch { throw new Error('无法用本机密钥验证 WAO 中的设备授权，请检查设备及条目。') }
  }

  return {
    read,
    async save(secret) {
      if (typeof secret !== 'string' || Buffer.byteLength(secret) < 12 || Buffer.byteLength(secret) > 6000) {
        throw new Error('设备凭据长度须在 12 至 6000 字节之间。')
      }
      if (!key) {
        key = await getDeviceKey(true)
        record = await request()
      }
      if (read() === secret) return false
      const nonce = randomBytes(12)
      const cipher = createCipheriv('aes-256-gcm', key, nonce)
      cipher.setAAD(audience)
      const encrypted = Buffer.concat([cipher.update(secret, 'utf8'), cipher.final()])
      const envelope = [version, nonce.toString('base64url'), cipher.getAuthTag().toString('base64url'), encrypted.toString('base64url')].join('.')
      const revision = await request({ envelope, expectedRevision: record?.revision ?? null })
      if (typeof revision !== 'string' || !/^[a-f0-9]{24}$/.test(revision)) throw new Error('WAO 未确认设备授权已保存。')
      record = { envelope, revision }
      return true
    },
  }
}
