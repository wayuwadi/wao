import { readFileSync, writeFileSync, renameSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import { randomUUID } from 'node:crypto'

const PREFIX = 'WAO 验收 · 草稿回归 · '
const NATIVE_CONFIRM = 'if (!window.confirm(`删除文档「${title}」？删除后会进入回收站，7 天内可恢复。`)) return'
const LOCAL_CONFIRM = 'if (!await (await import("virtual:wao-acceptance-confirm")).confirmOwnedDocument(id, title)) return'

export function transformCleanupConfirmation(source) {
  if (source.split(NATIVE_CONFIRM).length !== 2) throw new Error('文档删除确认代码已变化，必须人工更新验收适配器')
  return source.replace(NATIVE_CONFIRM, LOCAL_CONFIRM)
}

export function registerCleanupDocuments(path, documents, now = Date.now()) {
  if (!Array.isArray(documents) || documents.length !== 2 || new Set(documents.map(doc => doc.id)).size !== 2
    || documents.some(doc => !/^[A-Za-z0-9_-]{1,120}$/.test(doc.id ?? '') || !doc.title?.startsWith(PREFIX))) {
    throw new Error('清理必须登记本轮两篇不同的验收文档')
  }
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 })
  const temporary = `${path}.${randomUUID()}.tmp`
  writeFileSync(temporary, JSON.stringify({ expiresAt: now + 10 * 60_000,
    documents: documents.map(({ id, title }) => ({ id, title })) }), { mode: 0o600 })
  renameSync(temporary, path)
}

export function cleanupAllowed(path, id, title, now = Date.now()) {
  try {
    const registry = JSON.parse(readFileSync(path, 'utf8'))
    return Number.isFinite(registry.expiresAt) && registry.expiresAt > now
      && registry.expiresAt <= now + 10 * 60_000 && typeof title === 'string' && title.startsWith(PREFIX)
      && Array.isArray(registry.documents) && registry.documents.length === 2
      && registry.documents.some(doc => doc.id === id && doc.title === title)
  } catch { return false }
}

export function cleanupRegistryMiddleware(path) {
  return (request, response, next) => {
    const url = new URL(request.url, 'http://127.0.0.1')
    if (url.pathname !== '/__wao_acceptance/cleanup-owned') return next()
    response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify({ allowed: request.method === 'GET'
      && cleanupAllowed(path, url.searchParams.get('id'), url.searchParams.get('title')) }))
  }
}
