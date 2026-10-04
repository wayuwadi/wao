export const PAGE_LAYOUT_STYLES = ['unified', 'fluid', 'centered'] as const
export type PageLayoutStyle = typeof PAGE_LAYOUT_STYLES[number]

export const PAGE_LAYOUT_OPTIONS: ReadonlyArray<{ value: PageLayoutStyle; name: string; description: string }> = [
  { value: 'unified', name: '统一外框', description: '默认推荐。页面同宽，长文和表单保持舒适行宽。' },
  { value: 'fluid', name: '全部铺满', description: '页面、正文和表单都使用可用空间。' },
  { value: 'centered', name: '统一居中', description: '页面最多 1600px 居中，超宽屏留出两侧空白。' },
]

export function isPageLayoutStyle(value: unknown): value is PageLayoutStyle {
  return PAGE_LAYOUT_STYLES.includes(value as PageLayoutStyle)
}

export function normalizePageLayoutStyle(value: unknown): PageLayoutStyle {
  return isPageLayoutStyle(value) ? value : 'unified'
}

const draftKey = (memberId: string) => `wao:page-layout-pending-v1:${memberId}`

/** A failed single-choice save is retained without applying or automatically resubmitting it. */
export function readPageLayoutAttempt(memberId: string): PageLayoutStyle | null {
  if (!memberId) return null
  try {
    const value = localStorage.getItem(draftKey(memberId))
    return isPageLayoutStyle(value) ? value : null
  } catch { return null }
}

export function rememberPageLayoutAttempt(memberId: string, value: PageLayoutStyle | null): boolean {
  if (!memberId) return true
  try {
    if (value) localStorage.setItem(draftKey(memberId), value)
    else localStorage.removeItem(draftKey(memberId))
    return true
  } catch { return false }
}
