/* This small loader stays on the main origin; application cookies stay host-only. */
const boot = document.getElementById('wao-edge-bootstrap')
const entry = boot?.dataset.entry
const styles = JSON.parse(boot?.dataset.styles || '[]')
async function prepareStaticRouting() {
  if (!('serviceWorker' in navigator) || location.hostname !== 'wao.wayuwa.cn') return
  try {
    await navigator.serviceWorker.register('/edge-static-worker.js', { scope: '/', updateViaCache: 'none' })
    if (!navigator.serviceWorker.controller) await new Promise((resolve) => {
      const ready = () => { navigator.serviceWorker.removeEventListener('controllerchange', ready); clearTimeout(timer); resolve() }
      const timer = setTimeout(ready, 1800)
      navigator.serviceWorker.addEventListener('controllerchange', ready)
    })
  } catch { /* Browser policy may disable workers; same-version cloud assets work. */ }
}
async function start() {
  // Registration itself may stall. Never make it a prerequisite for using WAO.
  await Promise.race([prepareStaticRouting(), new Promise((resolve) => setTimeout(resolve, 2000))])
  await Promise.all(styles.map((href) => new Promise((resolve, reject) => {
    const link = document.createElement('link')
    link.rel = 'stylesheet'
    link.href = href
    const timer = setTimeout(() => reject(new Error('Stylesheet timed out')), 30_000)
    link.onload = () => { clearTimeout(timer); resolve() }
    link.onerror = () => { clearTimeout(timer); reject(new Error('Stylesheet unavailable')) }
    document.head.append(link)
  })))
  if (!entry) throw new Error('Missing application entry')
  await import(entry)
}
start().catch(() => {
  // Do not reload automatically: a late chunk failure must not discard a draft.
  if (document.getElementById('app')?.childElementCount) return
  const box = document.createElement('p')
  box.setAttribute('role', 'alert')
  box.textContent = '页面资源暂时无法加载，请稍后刷新重试。'
  document.body.append(box)
})
