import { createHash } from 'node:crypto'
import { endpoint } from './client.js'

export const MAX_PET_BYTES = 10 * 1024 * 1024
const ID = /^[A-Za-z0-9_-]{1,128}$/
export function petId(value) {
  if (typeof value !== 'string' || !ID.test(value)) throw new Error('Pet ID 必须是 1–128 位字母、数字、下划线或中划线')
  return value
}
export function petWorkspaceUrl(url) {
  const target = new URL(endpoint(url))
  if (!['/api/mcp/workspace', '/api/mcp/workspace/'].includes(target.pathname)) throw new Error('Pet 命令的 WAO 工作区地址路径必须为 /api/mcp/workspace，不支持路径前缀')
  return target
}
export function petPageUrl(url, id) {
  return new URL('/pets/' + encodeURIComponent(petId(id)), petWorkspaceUrl(url).origin).toString()
}
function petsEndpoint(url, suffix = '') {
  const target = petWorkspaceUrl(url)
  target.pathname = '/api/pets' + suffix
  return target
}
function safeMessage(value, token) {
  return String(value).split(token).join('[已隐藏令牌]').replace(/wao_workspace_[A-Za-z0-9_-]+/g, '[已隐藏令牌]').replace(/[\u0000-\u001f\u007f]/g, ' ')
}
function validateCard(card, id) {
  if (!card || typeof card !== 'object' || Array.isArray(card) || (id && card.id !== id)) throw new Error('WAO Pet 卡片格式或 ID 无效')
  petId(card.id)
  if (typeof card.listed !== 'boolean' || !/^[a-f0-9]{64}$/i.test(card.sha256 || '') || !Number.isSafeInteger(card.packageSize) || card.packageSize < 1 || card.packageSize > MAX_PET_BYTES) throw new Error('WAO Pet 卡片缺少有效的状态、SHA-256 或包大小')
  return card
}
async function boundedBytes(response) {
  const length = response.headers.get('content-length')
  if (length !== null && (!/^\d+$/.test(length) || Number(length) > MAX_PET_BYTES)) {
    await response.body?.cancel().catch(() => {})
    throw new Error('公开 Pet 下载超过 10 MiB 或长度无效')
  }
  if (!response.body) throw new Error('公开 Pet 下载没有内容')
  const reader = response.body.getReader()
  const chunks = []
  let size = 0
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      size += value.byteLength
      if (size > MAX_PET_BYTES) throw new Error('公开 Pet 下载超过 10 MiB')
      chunks.push(Buffer.from(value))
    }
  } catch (error) { await reader.cancel().catch(() => {}); throw error }
  finally { reader.releaseLock() }
  if (!size) throw new Error('公开 Pet 下载没有内容')
  return Buffer.concat(chunks, size)
}

export function createPetClient({ url, token, fetchImpl }) {
  async function request(target, options = {}, credentialed = true) {
    let response
    try {
      response = await fetchImpl(target, {
        ...options, headers: { Accept: 'application/json', ...(credentialed ? { Authorization: 'Bearer ' + token } : {}), ...options.headers },
        redirect: 'error', signal: AbortSignal.timeout(30_000),
      })
    } catch { throw new Error('无法连接 WAO Pet 服务（不跟随重定向，未自动重试）') }
    if (response.status === 401) throw new Error('工作区令牌无效或已吊销；请重新登录')
    if (response.status === 403) throw new Error('当前令牌没有此 Pet 操作权限；请在能力中心 CLI/MCP 新授权中选择对应的 Pet 操作')
    if (!response.ok) throw new Error('WAO Pet 服务返回 HTTP ' + response.status)
    return response
  }
  async function json(target, options, credentialed = true) {
    const response = await request(target, options, credentialed)
    let body
    try { body = await response.json() } catch { throw new Error('WAO Pet 服务返回的不是 JSON') }
    if (!body || body.code !== 0 || body.data === undefined || body.data === null) throw new Error(safeMessage(body?.message || 'WAO Pet 业务请求失败', token))
    return body.data
  }
  return {
    async checkPetPermission(operation) {
      if (!['list_my_pets', 'upload_pet', 'share_pet'].includes(operation)) throw new Error('无效的 Pet 操作')
      const target = petsEndpoint(url, '/cli-access')
      target.searchParams.set('operation', operation)
      if (await json(target, { method: 'GET' }) !== true) throw new Error('WAO 未确认当前令牌具有 Pet 操作权限')
    },
    async listPets() {
      const cards = await json(petsEndpoint(url, '/mine'), { method: 'GET' })
      if (!Array.isArray(cards)) throw new Error('WAO Pet 列表格式无效')
      return cards.map(card => validateCard(card))
    },
    async uploadPet({ archive, fileName, source, license }) {
      if (!Buffer.isBuffer(archive) || !archive.length || archive.length > MAX_PET_BYTES) throw new Error('Pet ZIP 大小必须大于 0 且不超过 10 MiB')
      const form = new FormData()
      form.append('archive', new Blob([archive], { type: 'application/zip' }), fileName)
      form.append('source', source)
      form.append('license', license)
      return validateCard(await json(petsEndpoint(url), { method: 'POST', body: form }))
    },
    async setPetVisibility(id, listed) {
      return validateCard(await json(petsEndpoint(url, '/' + encodeURIComponent(petId(id)) + '/visibility'), {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ listed, ...(listed ? { rightsConfirmed: true } : {}) }),
      }), id)
    },
    async verifyPet(card) {
      try {
        validateCard(card)
        const target = new URL('/api/public/pets/' + encodeURIComponent(card.id), petWorkspaceUrl(url).origin)
        const publicCard = validateCard(await json(target, { method: 'GET' }, false), card.id)
        if (!publicCard.listed || publicCard.sha256.toLowerCase() !== card.sha256.toLowerCase() || publicCard.packageSize !== card.packageSize || ['slug', 'displayName', 'description', 'spriteVersion', 'source', 'license', 'handle', 'author'].some(field => publicCard[field] !== card[field])) throw new Error('公开 Pet 元数据与保存结果不一致')
        target.pathname += '/download'
        const bytes = await boundedBytes(await request(target, { method: 'GET', headers: { Accept: 'application/zip' } }, false))
        if (bytes.length !== card.packageSize || createHash('sha256').update(bytes).digest('hex') !== card.sha256.toLowerCase()) throw new Error('公开 Pet 下载包大小或 SHA-256 与保存结果不一致')
        return { ok: true, bytes: bytes.length, sha256: card.sha256 }
      } catch (error) { return { ok: false, message: safeMessage(error.message, token) } }
    },
  }
}
