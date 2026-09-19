// 仅由独立 Vite 验收入口作为虚拟模块加载，不进入正常应用构建。
export async function confirmOwnedDocument(id, title) {
  const query = new URLSearchParams({ id, title })
  const response = await fetch(`/__wao_acceptance/cleanup-owned?${query}`, { cache: 'no-store' })
  if (!response.ok || !(await response.json()).allowed) return false
  return new Promise(resolve => {
    const dialog = document.createElement('dialog')
    dialog.setAttribute('aria-label', '本轮验收文档清理')
    dialog.style.cssText = 'max-width:480px;width:calc(100vw - 48px);padding:24px;border:1px solid #cbd5e1;border-radius:12px;color:#0f172a;background:white;z-index:2147483647'
    const heading = document.createElement('h2')
    heading.textContent = '本轮验收文档清理'
    const message = document.createElement('p')
    message.textContent = `将「${title}」移入回收站？7 天内可恢复。此确认框仅用于本地验收。`
    const finish = accepted => { dialog.close(); dialog.remove(); resolve(accepted) }
    const cancel = document.createElement('button')
    cancel.textContent = '取消清理'
    cancel.onclick = () => finish(false)
    const accept = document.createElement('button')
    accept.textContent = '确认移入回收站'
    accept.style.marginLeft = '16px'
    accept.onclick = () => finish(true)
    dialog.addEventListener('cancel', event => { event.preventDefault(); finish(false) })
    dialog.append(heading, message, cancel, accept)
    document.body.append(dialog)
    dialog.showModal()
    cancel.focus()
  })
}
