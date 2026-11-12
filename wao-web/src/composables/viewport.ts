import { computed, onBeforeUnmount, readonly, ref, watch, type Ref } from 'vue'

/** 手机断点，与各组件 CSS 里的 `@media (max-width: 760px)` 保持一致。 */
export const MOBILE_QUERY = '(max-width: 760px)'

/**
 * 当前是不是手机宽度。
 *
 * <p>有些差异靠 CSS 改不动 —— 比如子任务详情在手机上要变成一个盖住整屏的新页面，
 * 而不是排在正文下面等人往下滑。这类结构性差异需要在脚本里知道现在是不是手机。
 *
 * @return 只读响应式布尔值，随窗口宽度变化
 */
export function useIsMobile() {
  return useViewportQuery(MOBILE_QUERY)
}

function useViewportQuery(query: string) {
  const mobile = ref(false)
  // SSR / 老浏览器没有 matchMedia 时按桌面处理，至少不会崩
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    const mql = window.matchMedia(query)
    mobile.value = mql.matches
    const onChange = (e: MediaQueryListEvent) => (mobile.value = e.matches)
    mql.addEventListener('change', onChange)
    onBeforeUnmount(() => mql.removeEventListener('change', onChange))
  }
  return readonly(mobile)
}

/** 当浮窗或侧栏改变可用宽度时，同步需要重排的交互结构。 */
export function useNarrowWorkspace(element: Ref<HTMLElement | null>, maxWidth = 760) {
  const viewport = useViewportQuery(`(max-width: ${maxWidth}px)`)
  const width = ref(0)
  watch(element, (workspace, _, onCleanup) => {
    if (!workspace || typeof ResizeObserver === 'undefined') {
      width.value = 0
      return
    }
    width.value = workspace.clientWidth
    const observer = new ResizeObserver(([entry]) => {
      if (entry?.contentRect.width) width.value = entry.contentRect.width
    })
    observer.observe(workspace)
    onCleanup(() => observer.disconnect())
  }, { flush: 'post' })
  return computed(() => width.value ? width.value <= maxWidth : viewport.value)
}
