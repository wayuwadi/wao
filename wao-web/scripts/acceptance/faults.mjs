import { readFileSync, writeFileSync, mkdirSync, renameSync, rmSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { randomUUID } from 'node:crypto'

export const SAVE_FAILURE = '本地验收：文档保存暂时失败'

export function localOrigin(value, label) {
  let url
  try { url = new URL(value) } catch { throw new Error(`${label} 必须是本地 HTTP 地址`) }
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)
    || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error(`${label} 只允许无凭据的本地 HTTP 源地址`)
  }
  return url.origin
}

export function validateFault(input) {
  if (!input || !/^[A-Za-z0-9_-]{1,120}$/.test(input.docId ?? '')) throw new Error('必须指定单篇文档 ID')
  if (!['fail', 'delay'].includes(input.mode)) throw new Error('故障模式必须为 fail 或 delay')
  const delayMs = input.mode === 'delay' ? Number(input.delayMs ?? 2000) : 0
  if (!Number.isInteger(delayMs) || delayMs < 0 || delayMs > 5000) throw new Error('延迟范围为 0—5000ms')
  const expiresAt = Number(input.expiresAt)
  if (!Number.isFinite(expiresAt)) throw new Error('必须指定故障过期时间')
  return { docId: input.docId, mode: input.mode, delayMs, expiresAt }
}

export function writeFault(path, config) {
  const fault = validateFault(config)
  const file = resolve(path)
  mkdirSync(dirname(file), { recursive: true, mode: 0o700 })
  const temporary = `${file}.${randomUUID()}.tmp`
  writeFileSync(temporary, JSON.stringify(fault), { mode: 0o600 })
  renameSync(temporary, file)
}

export function clearFault(path) { rmSync(path, { force: true }) }

export function readFault(path, now = Date.now()) {
  try {
    const fault = validateFault(JSON.parse(readFileSync(path, 'utf8')))
    return fault.expiresAt > now ? fault : null
  } catch { return null }
}

export function matchesFault(request, fault) {
  if (!fault || request.method !== 'PATCH') return false
  // 不解码斜线，不接受前缀、正则或通配符。
  return request.url?.split('?')[0] === `/api/docs/${fault.docId}`
}

export function faultMiddleware(path) {
  return (request, response, next) => {
    const fault = readFault(path)
    if (!matchesFault(request, fault)) return next()
    if (fault.mode === 'delay') {
      const timer = setTimeout(() => { if (!response.destroyed) next() }, fault.delayMs)
      response.once('close', () => clearTimeout(timer))
      return
    }
    response.writeHead(503, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify({ code: 503, message: SAVE_FAILURE, data: null, timestamp: Date.now() }))
  }
}
