import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { useSessionStore } from '@/stores/session'
import { toast } from '@/composables/toast'

type Pane = 'directory' | 'images'
const defaults = { directory: 180, images: 238 }
const minimum = { directory: 140, images: 180 }
const maximum = { directory: 600, images: 700 }

/** 阅读器两侧使用独立的成员偏好；只调整布局，不改正文宽屏/窄屏。 */
export function useNoteReaderWidths(layout: Ref<HTMLElement | null>) {
  const session = useSessionStore()
  const widths = ref({ ...defaults })
  const resizing = ref<Pane | null>(null)
  const key = () => `wao-note-reader-widths-v1:${session.currentUser.id || 'anonymous'}`
  let drag: { pane: Pane; pointerId: number; x: number; width: number; storageKey: string } | null = null
  function save(storageKey = key()) {
    try { localStorage.setItem(storageKey, JSON.stringify(widths.value)) }
    catch { toast('宽度已调整，但浏览器未能记住，请再次调整以重试保存', 'ri-error-warning-line') }
  }
  function stop() {
    if (resizing.value) save(drag?.storageKey)
    resizing.value = null
    drag = null
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', end)
    window.removeEventListener('pointercancel', end)
    window.removeEventListener('blur', stop)
    window.removeEventListener('resize', stop)
  }
  watch(() => session.currentUser.id, () => {
    stop()
    widths.value = { ...defaults }
    try {
      const stored = JSON.parse(localStorage.getItem(key()) || '{}')
      for (const pane of ['directory', 'images'] as const) {
        const value = stored?.[pane]
        if (typeof value === 'number' && Number.isFinite(value) && value >= minimum[pane] && value <= maximum[pane]) widths.value[pane] = value
      }
    } catch { /* 无效偏好或浏览器存储不可用时使用默认值。 */ }
  }, { immediate: true })
  function limit(pane: Pane) {
    const total = layout.value?.getBoundingClientRect().width || window.innerWidth
    const other = pane === 'directory' ? 'images' : 'directory'
    return Math.max(minimum[pane], Math.min(maximum[pane], total - widths.value[other] - 332))
  }
  function set(pane: Pane, value: number) { widths.value[pane] = Math.round(Math.max(minimum[pane], Math.min(limit(pane), value))) }
  function move(event: PointerEvent) {
    if (!drag || event.pointerId !== drag.pointerId) return
    set(drag.pane, drag.width + (event.clientX - drag.x) * (drag.pane === 'directory' ? 1 : -1))
  }
  function end(event: PointerEvent) { if (event.pointerId === drag?.pointerId) stop() }
  function start(pane: Pane, event: PointerEvent) {
    if (event.button !== 0 || window.innerWidth <= 850 || !layout.value) return
    event.preventDefault()
    stop()
    drag = { pane, pointerId: event.pointerId, x: event.clientX, width: widths.value[pane], storageKey: key() }
    resizing.value = pane
    ;(event.currentTarget as HTMLElement).focus({ preventScroll: true })
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', end)
    window.addEventListener('pointercancel', end)
    window.addEventListener('blur', stop)
    window.addEventListener('resize', stop)
  }
  function reset(pane: Pane) { stop(); set(pane, defaults[pane]); save() }
  function keydown(pane: Pane, event: KeyboardEvent) {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key) || window.innerWidth <= 850) return
    event.preventDefault()
    event.stopPropagation()
    if (event.key === 'Home') set(pane, defaults[pane])
    else if (event.key === 'End') set(pane, limit(pane))
    else set(pane, widths.value[pane] + (event.key === 'ArrowRight' ? 1 : -1) * (pane === 'directory' ? 1 : -1) * (event.shiftKey ? 50 : 10))
    save()
  }
  onBeforeUnmount(stop)
  const style = computed(() => ({ '--reader-directory-width': `${widths.value.directory}px`, '--reader-images-width': `${widths.value.images}px` }))
  return { widths, style, resizing, start, stop, reset, keydown, limit, minimum }
}
