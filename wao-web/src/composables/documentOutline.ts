import { nextTick, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import type { RichHeading } from '@/utils/richDoc'

/** Keep layout reads out of the scroll handler, including for very long documents. */
export function useDocumentOutline(scroll: Ref<HTMLElement | null>, source: () => unknown) {
  const activeIndex = ref(0)
  const progress = ref(0)
  const renderedHeadings = ref<RichHeading[] | null>(null)
  let headings: HTMLElement[] = []
  let offsets: number[] = []
  let visiblePositions: { offset: number; index: number }[] = []
  let navigationTarget: { heading: HTMLElement; scrollTop: number } | null = null
  let frame = 0
  let needsMeasure = true
  let resize: ResizeObserver | undefined
  let mutation: MutationObserver | undefined
  let disposed = false

  function update() {
    frame = 0
    const root = scroll.value
    if (!root) { renderedHeadings.value = null; return }
    if (needsMeasure) {
      headings = Array.from(root.querySelectorAll<HTMLElement>('.doc-body .tiptap h1, .doc-body .tiptap h2, .doc-body .tiptap h3'))
      const metadata = root.querySelector('.doc-body .tiptap') ? headings.map((heading, index) => ({
        id: heading.getAttribute('data-uid') || `rendered-heading-${index}`,
        text: heading.textContent?.trim() ?? '', level: Number(heading.tagName.slice(1)),
      })) : null
      if (JSON.stringify(metadata) !== JSON.stringify(renderedHeadings.value)) renderedHeadings.value = metadata
      const top = root.getBoundingClientRect().top - root.scrollTop
      offsets = headings.map(heading => heading.getBoundingClientRect().top - top)
      // Columns have a visual order that differs from their document order.
      visiblePositions = offsets.map((offset, index) => ({ offset, index }))
        .filter(item => !headings[item.index]!.closest('[data-type="detailsContent"][hidden]'))
        .sort((left, right) => left.offset - right.offset || left.index - right.index)
      needsMeasure = false
    }
    const position = root.scrollTop + 48
    let low = 0, high = visiblePositions.length
    while (low < high) {
      const middle = (low + high) >>> 1
      if (visiblePositions[middle]!.offset <= position) low = middle + 1
      else high = middle
    }
    const distance = root.scrollHeight - root.clientHeight
    const targetIndex = navigationTarget && Math.abs(root.scrollTop - navigationTarget.scrollTop) <= 1
      ? headings.indexOf(navigationTarget.heading) : -1
    if (targetIndex >= 0 && visiblePositions.some(item => item.index === targetIndex)) {
      // Near the end, the browser cannot align a heading with the viewport top.
      // Keep the explicitly chosen heading until the reader actually scrolls away.
      activeIndex.value = targetIndex
    } else {
      navigationTarget = null
      const atBottom = distance > 0 && root.scrollTop >= distance - 1
      activeIndex.value = visiblePositions[atBottom ? visiblePositions.length - 1 : Math.max(0, low - 1)]?.index ?? 0
    }
    progress.value = distance > 0 ? Math.min(100, Math.max(0, Math.round(root.scrollTop / distance * 100))) : 100
  }
  function schedule() {
    if (!frame && !disposed) frame = requestAnimationFrame(update)
  }
  function refresh() { needsMeasure = true; schedule() }
  function goTo(index: number) {
    const root = scroll.value
    if (!root) { renderedHeadings.value = null; return }
    const target = root.querySelectorAll<HTMLElement>('.doc-body .tiptap h1, .doc-body .tiptap h2, .doc-body .tiptap h3')[index]
    const collapsed: HTMLElement[] = []
    for (let parent = target?.parentElement; parent && parent !== root; parent = parent.parentElement) {
      if (parent.matches('[data-type="details"]:not(.is-open)')) collapsed.unshift(parent)
    }
    // Details is configured with persist:false: this changes presentation only.
    for (const details of collapsed) details.querySelector<HTMLButtonElement>(':scope > button')?.click()
    // A just-inserted heading may not have reached the next animation frame yet.
    needsMeasure = true
    if (frame) cancelAnimationFrame(frame)
    update()
    if (!headings[index]) return
    root.scrollTop = Math.max(0, offsets[index]! - 24)
    navigationTarget = { heading: headings[index]!, scrollTop: root.scrollTop }
    activeIndex.value = index
    schedule()
  }
  function toTop() { navigationTarget = null; if (scroll.value) scroll.value.scrollTop = 0; schedule() }

  watch(scroll, (root, previous) => {
    navigationTarget = null
    resize?.disconnect()
    mutation?.disconnect()
    previous?.removeEventListener('load', refresh, true)
    if (!root) { renderedHeadings.value = null; headings = []; offsets = []; visiblePositions = []; return }
    if (typeof ResizeObserver !== 'undefined') {
      resize = new ResizeObserver(refresh)
      resize.observe(root)
      const content = root.querySelector('.doc-col')
      if (content) resize.observe(content)
    }
    if (typeof MutationObserver !== 'undefined') {
      mutation = new MutationObserver(refresh)
      mutation.observe(root, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['hidden', 'open'] })
    }
    root.addEventListener('load', refresh, true)
    refresh()
  }, { flush: 'post' })
  watch(source, async () => { navigationTarget = null; await nextTick(); if (!disposed) refresh() }, { flush: 'post' })
  onBeforeUnmount(() => {
    disposed = true
    if (frame) cancelAnimationFrame(frame)
    resize?.disconnect()
    mutation?.disconnect()
    scroll.value?.removeEventListener('load', refresh, true)
  })
  return { activeIndex, progress, renderedHeadings, onScroll: schedule, refresh, goTo, toTop }
}
