import type { PracticeSnapshot } from './breathingSession'
import { readPracticeSnapshot } from './breathingSession'

const KEY = 'wao-breathing-auth:v1'
const TTL = 10 * 60_000
let pending: { target: string; practice: PracticeSnapshot | null; expires: number } | null = null

/** 只允许本应用内部目标，避免外部跳转及认证循环。 */
export function safeAuthTarget(value: unknown, fallback = '/breathe'): string {
  if (
    typeof value !== 'string' ||
    !value.startsWith('/') ||
    /[\\\s]/.test(value) ||
    Array.from(value).some((character) => character.charCodeAt(0) < 32) ||
    value.startsWith('//')
  )
    return fallback
  try {
    const url = new URL(value, 'https://wao.invalid')
    if (
      url.origin !== 'https://wao.invalid' ||
      url.pathname.startsWith('/auth/') ||
      ['/login', '/connection-recovery'].includes(url.pathname)
    )
      return fallback
    return url.pathname + url.search + url.hash
  } catch {
    return fallback
  }
}

/** 仅在真实 OAuth 离站前调用；没有密码、账号资料或令牌。 */
export function saveAuthHandoff(
  target: string,
  practice: PracticeSnapshot | null,
  authorizationUrl: string,
): boolean {
  try {
    const state = new URL(authorizationUrl).searchParams.get('state')
    if (!state) return false
    sessionStorage.setItem(
      KEY,
      JSON.stringify({
        target: safeAuthTarget(target),
        practice,
        state,
        expires: Date.now() + TTL,
      }),
    )
    return true
  } catch {
    return false
  }
}

/** 回调校验并一次性取走；刷新回调不能重复恢复。 */
export function receiveAuthHandoff(state: string): void {
  pending = null
  try {
    const raw = sessionStorage.getItem(KEY)
    sessionStorage.removeItem(KEY)
    if (!raw) return
    const data = JSON.parse(raw)
    if (
      !state ||
      data.state !== state ||
      !Number.isFinite(data.expires) ||
      data.expires < Date.now() ||
      data.expires > Date.now() + TTL
    )
      return
    pending = {
      target: safeAuthTarget(data.target),
      practice: readPracticeSnapshot(data.practice),
      expires: data.expires,
    }
  } catch {
    /* 禁用或损坏的存储不阻断登录。 */
  }
}

export function setInAppAuthTarget(target: string, practice: PracticeSnapshot | null = null) {
  pending = {
    target: safeAuthTarget(target),
    practice: readPracticeSnapshot(practice),
    expires: Date.now() + TTL,
  }
}
export function authReturnTarget() {
  return pending && pending.expires >= Date.now() ? pending.target : '/'
}
export function consumeAuthPractice(): PracticeSnapshot | null {
  const value =
    pending && pending.expires >= Date.now() && pending.target.split('?')[0] === '/breathe'
      ? pending.practice
      : null
  pending = null
  return value
}
