export type LoginMethod = 'feishu' | 'email'
const KEY = 'wao:last-successful-login:v1'

export function preferredLoginMethod(): LoginMethod {
  try {
    return localStorage.getItem(KEY) === 'email' ? 'email' : 'feishu'
  } catch {
    return 'feishu'
  }
}
export function rememberLoginMethod(method: LoginMethod) {
  try {
    localStorage.setItem(KEY, method)
  } catch {
    /* 存储受限不阻断认证。 */
  }
}
export function prefersFeishuAuthorization() {
  return (
    /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent) ||
    (navigator.maxTouchPoints > 1 && window.matchMedia?.('(pointer: coarse)').matches)
  )
}
