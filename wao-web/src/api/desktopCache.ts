import { isDesktopWorkspace } from '@/utils/desktopWorkspace'
const PREFIX = 'wao-desktop-cache-v1:'
const REVISION = 'wao-desktop-cache-revision'
function revision() {
  try {
    return localStorage.getItem(REVISION)
  } catch {
    return null
  }
}
const TTL: Record<string, number> = {
  '/api/projects': 30_000,
  '/api/tasks': 10_000,
  '/api/areas': 60_000,
  '/api/members': 60_000,
  '/api/org': 60_000,
  '/api/kbs': 30_000,
  '/api/kb-groups': 30_000,
}
let memberId = ''
let generation = 0
const pending = new Map<string, Promise<unknown>>()
export function setDesktopCacheMember(id: string) {
  if (id !== memberId) {
    if (memberId || !id) invalidateDesktopCache()
    else {
      generation++
      pending.clear()
    }
    memberId = id
  }
}
export function invalidateDesktopCache() {
  generation++
  pending.clear()
  try {
    localStorage.setItem(REVISION, `${Date.now()}:${Math.random()}`)
    for (const key of Object.keys(localStorage))
      if (key.startsWith(PREFIX)) localStorage.removeItem(key)
  } catch {
    /* Caching is optional. */
  }
}
/** Short-lived, member-scoped reads only; writes invalidate every cached domain. */
export async function desktopCachedRead<T>(url: string, read: () => Promise<T>): Promise<T> {
  const ttl = TTL[url]
  if (!isDesktopWorkspace() || !memberId || !ttl) return read()
  const key = `${PREFIX}${memberId}:${url}`
  try {
    const value = JSON.parse(localStorage.getItem(key) || 'null')
    if (value && value.expiresAt > Date.now() && value.expiresAt <= Date.now() + ttl)
      return value.data as T
  } catch {
    /* Invalid cache falls back to the server. */
  }
  let request = pending.get(key) as Promise<T> | undefined
  if (!request) {
    const started = generation
    const startedRevision = revision()
    request = read()
      .then((data) => {
        if (generation === started && revision() === startedRevision) {
          try {
            localStorage.setItem(key, JSON.stringify({ expiresAt: Date.now() + ttl, data }))
          } catch {
            /* Quota or private mode. */
          }
        }
        return data
      })
      .finally(() => {
        if (pending.get(key) === request) pending.delete(key)
      })
    pending.set(key, request)
  }
  // Consumers own their copy; optimistic edits must never mutate shared cached results.
  return JSON.parse(JSON.stringify(await request)) as T
}
