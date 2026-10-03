// 在浏览器控制台执行此文件；只读 DOM，不记录正文、账号或网络数据。
(() => {
  const visible = (element) => {
    const rect = element.getBoundingClientRect()
    const style = getComputedStyle(element)
    return rect.width > 0 && rect.height > 0 && style.visibility === 'visible'
  }
  const textRects = (element) => {
    const rects = []
    const visit = (parent) => {
      if (!visible(parent)) return
      for (const node of parent.childNodes) {
        if (node.nodeType === 1) visit(node)
        else if (node.nodeType === 3 && node.textContent.trim()) {
          const range = document.createRange()
          range.selectNodeContents(node)
          rects.push(...range.getClientRects())
        }
      }
    }
    visit(element)
    return rects.filter((rect) => rect.width && rect.height)
  }
  const controls = [...document.querySelectorAll('button, a, [role="button"]')].filter(visible)
  const controlCandidates = controls.flatMap((element) => {
    const bounds = element.getBoundingClientRect()
    const outside = textRects(element).some((rect) => rect.left < bounds.left - 2 ||
      rect.right > bounds.right + 2 || rect.top < bounds.top - 2 || rect.bottom > bounds.bottom + 2)
    return outside ? [{ tag: element.tagName, className: element.className }] : []
  })
  const circles = [...document.querySelectorAll('.focus-scene')].filter(visible).map((scene) => {
    const ring = scene.querySelector('.orb-ring').getBoundingClientRect()
    const cx = (ring.left + ring.right) / 2
    const cy = (ring.top + ring.bottom) / 2
    const radius = Math.min(ring.width, ring.height) / 2
    const rects = textRects(scene.querySelector('.center-copy'))
    const distance = Math.max(0, ...rects.flatMap((rect) =>
      [rect.left, rect.right].flatMap((x) => [rect.top, rect.bottom].map((y) => Math.hypot(x - cx, y - cy)))))
    return { diameter: radius * 2, textRectCount: rects.length,
      minimumInset: Math.round((radius - distance) * 100) / 100, passed: rects.length > 0 && distance <= radius }
  })
  return { viewport: [innerWidth, innerHeight], controlsChecked: controls.length, controlCandidates,
    circles, horizontalOverflow: document.documentElement.scrollWidth > innerWidth }
})()
