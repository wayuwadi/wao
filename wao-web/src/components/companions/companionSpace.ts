const SIZE = 56
type Box = { left: number; top: number; right: number; bottom: number }
const overlaps = (a: Box, b: Box) => a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

/** 在操作周围寻找空白；不挤布局，也不遮住文字或控件。拥挤时直接略过。 */
export function companionSpace(anchor: Box): { left: string; top: string } | undefined {
  const candidates = [
    { left: anchor.left - SIZE - 8, top: anchor.bottom - SIZE },
    { left: anchor.right + 8, top: anchor.bottom - SIZE },
    { left: anchor.right - SIZE, top: anchor.top - SIZE - 8 },
    { left: anchor.left, top: anchor.bottom + 8 },
    { left: anchor.left - SIZE * 2 - 16, top: anchor.bottom - SIZE },
    { left: anchor.right + SIZE + 16, top: anchor.bottom - SIZE },
    { left: anchor.left - SIZE - 8, top: anchor.top - SIZE - 8 },
    { left: anchor.left - SIZE - 8, top: anchor.bottom + 8 },
  ].map(p => ({ ...p, right: p.left + SIZE, bottom: p.top + SIZE }))
    .filter(p => p.left >= 12 && p.top >= 64 && p.right <= innerWidth - 12 && p.bottom <= innerHeight - 12)
  if (!candidates.length) return
  const obstacles: { rect: Box; element: Element }[] = []
  document.querySelectorAll('button,a,input,textarea,[contenteditable="true"],img,canvas,video,svg').forEach(el => {
    if (el.getClientRects().length) obstacles.push({ rect: el.getBoundingClientRect(), element: el })
  })
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT)
  const range = document.createRange()
  let node: Node | null
  let count = 0
  while ((node = walker.nextNode())) {
    // 超大页面不花费无界时间查找装饰位置。
    if (++count > 3000) return
    if (!node.textContent?.trim() || !node.parentElement?.getClientRects().length
      || node.parentElement.closest('script,style,[aria-hidden="true"]')) continue
    range.selectNodeContents(node)
    for (const rect of range.getClientRects()) obstacles.push({ rect, element: node.parentElement! })
  }
  const space = candidates.find(p => !obstacles.some(({ rect, element }) => {
    if (!overlaps(p, rect)) return false
    const x = (Math.max(p.left, rect.left) + Math.min(p.right, rect.right)) / 2
    const y = (Math.max(p.top, rect.top) + Math.min(p.bottom, rect.bottom)) / 2
    const top = document.elementFromPoint(x, y)
    // 抽屉下面的页面内容不是当前层的障碍。
    return !!top && element.contains(top)
  }))
  if (space) return { left: `${space.left}px`, top: `${space.top}px` }
}
