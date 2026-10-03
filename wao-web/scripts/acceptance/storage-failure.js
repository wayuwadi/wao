// 仅本地 dev:acceptance 注入；不改变已保存数据、登录或业务请求。
(() => {
  const original = Storage.prototype.setItem
  Storage.prototype.setItem = function (key, value) {
    if (this === localStorage && /^(wao-note-edit-v1:|wao-quick-note-draft-v1:)/.test(String(key))) {
      throw new DOMException('隔离验收：小记草稿写入失败', 'QuotaExceededError')
    }
    return original.call(this, key, value)
  }
  document.addEventListener('DOMContentLoaded', () => {
    const button = document.createElement('button')
    button.type = 'button'
    button.textContent = '验收模式：恢复草稿写入（仅本页）'
    button.style.cssText = 'position:fixed;bottom:8px;left:70px;z-index:2147483647;padding:8px;background:#fff3cd;color:#664d03;border:1px solid #997404;border-radius:6px'
    button.onclick = () => {
      Storage.prototype.setItem = original
      button.textContent = '草稿写入已恢复；继续输入以重新保护草稿'
      button.disabled = true
    }
    document.body.append(button)
  }, { once: true })
})()
