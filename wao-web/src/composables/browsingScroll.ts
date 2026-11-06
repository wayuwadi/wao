import { nextTick, onBeforeUnmount, onMounted, watch, type Ref } from 'vue'

/** Restore after data/layout is ready; user scrolling always wins over late image layout. */
export function useBrowsingScroll(
  root: Ref<HTMLElement | undefined>,
  position: Ref<number>,
  ready: () => boolean,
  scope: () => string,
) {
  let container: HTMLElement | null = null,
    observer: ResizeObserver | undefined
  let restoring = true,
    applying = false,
    disposed = false
  function save() {
    if (!restoring && !applying && container) position.value = container.scrollTop
  }
  function interact() {
    restoring = false
  }
  async function restore() {
    if (!restoring || !ready()) return
    await nextTick()
    if (disposed || !container || !restoring) return
    applying = true
    container.scrollTop = position.value
    applying = false
  }
  watch(
    scope,
    () => {
      restoring = true
      void restore()
    },
    { flush: 'post' },
  )
  watch(
    ready,
    () => {
      void restore()
    },
    { flush: 'post' },
  )
  onMounted(() => {
    container =
      root.value?.closest<HTMLElement>('.floating .window-content, .content') ?? root.value ?? null
    container?.addEventListener('scroll', save, { passive: true })
    for (const event of ['wheel', 'touchstart', 'pointerdown', 'keydown'])
      container?.addEventListener(event, interact, { passive: true })
    if (typeof ResizeObserver !== 'undefined' && root.value) {
      observer = new ResizeObserver(() => {
        void restore()
      })
      observer.observe(root.value)
    }
    void restore()
  })
  onBeforeUnmount(() => {
    disposed = true
    save()
    observer?.disconnect()
    container?.removeEventListener('scroll', save)
    for (const event of ['wheel', 'touchstart', 'pointerdown', 'keydown'])
      container?.removeEventListener(event, interact)
  })
  return { stopRestoring: interact }
}
