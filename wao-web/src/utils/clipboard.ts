import { toast } from '@/composables/toast'

/** 复制文本到剪贴板并提示（失败时静默降级）。 */
export function copyText(text: string, msg = '已复制到剪贴板') {
  const done = () => toast(msg, 'ri-file-copy-line')
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(text).then(done).catch(done)
  } else {
    done()
  }
}
