import { toast } from '@/composables/toast'

/**
 * 复制文本到剪贴板。
 *
 * <p>非 HTTPS / 旧浏览器下 `navigator.clipboard` 不可用，回落到临时 textarea +
 * `execCommand`，否则内网 http 访问时复制会静默失败。
 *
 * @param text 要复制的文本
 * @return 是否复制成功
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    /* 继续走回落方案 */
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}

/** 复制并提示；失败时给出可操作的提示而不是假装成功。 */
export async function copyText(text: string, msg = '已复制到剪贴板') {
  const ok = await copyToClipboard(text)
  toast(ok ? msg : '复制失败，请手动选中复制', ok ? 'ri-file-copy-line' : 'ri-error-warning-line')
}
