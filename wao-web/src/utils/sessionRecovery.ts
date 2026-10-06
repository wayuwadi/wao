export const SESSION_EXPIRED_EVENT = 'wao:session-expired'
let paused = false

export function isSessionPaused() {
  return paused
}
export function resumeSessionRequests() {
  paused = false
}
export function requireSessionRecovery() {
  if (paused) return
  paused = true
  window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT))
}
export function isProtectedRequest(url: string) {
  return /^\/api\//.test(url) && !/^\/api\/(?:auth|share|public|edge\/ready)(?:\/|$)/.test(url)
}
export function assertSessionRequest(url: string) {
  if (paused && isProtectedRequest(url)) {
    throw Object.assign(new Error('登录已过期，请先重新登录；当前内容已保留。'), { status: 401 })
  }
}
