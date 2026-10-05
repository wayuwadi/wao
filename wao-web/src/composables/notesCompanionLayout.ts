import { computed, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'
import { toast } from '@/composables/toast'

const defaultRatio = .65
const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value))

/** 仅保存成员自己的布局偏好；调整空间不重新创建编辑器或伴读。 */
export function useNotesCompanionLayout(pane: Ref<HTMLElement | null>, member: Ref<string>) {
  const ratio = ref(defaultRatio), collapsed = ref(false), resizing = ref(false), height = ref(0)
  const bounds = computed(() => {
    const available = Math.max(1, height.value - 16)
    return height.value ? { min: Math.min(.45, 230 / available), max: 1 - Math.min(.45, 200 / available) } : { min: .3, max: .8 }
  })
  const visibleRatio = computed(() => clamp(ratio.value, bounds.value.min, bounds.value.max))
  const percentage = computed(() => Math.round(visibleRatio.value * 100))
  const style = computed(() => ({ '--notes-editor-share': `${visibleRatio.value}fr`, '--notes-companion-share': `${1 - visibleRatio.value}fr` }))
  let pointerId: number | undefined, dragBounds: DOMRect | undefined, observer: ResizeObserver | undefined
  const key = () => `wao-notes-companion-layout-v1:${encodeURIComponent(member.value)}`
  function save() {
    if (!member.value) return
    try { localStorage.setItem(key(), JSON.stringify({ ratio: ratio.value, collapsed: collapsed.value })) }
    catch { toast('布局已调整，但浏览器未能记住；再次调整可重试保存', 'ri-error-warning-line') }
  }
  function stop(persist = true) {
    if (resizing.value && persist) save()
    resizing.value = false
    pointerId = undefined
    dragBounds = undefined
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', end)
    window.removeEventListener('pointercancel', end)
    window.removeEventListener('blur', finish)
    window.removeEventListener('resize', finish)
  }
  function finish() { stop() }
  function move(event: PointerEvent) {
    if (event.pointerId !== pointerId || !dragBounds) return
    ratio.value = clamp((event.clientY - dragBounds.top - 8) / Math.max(1, dragBounds.height - 16), bounds.value.min, bounds.value.max)
  }
  function end(event: PointerEvent) { if (event.pointerId === pointerId) stop() }
  function start(event: PointerEvent) {
    if (event.button !== 0 || window.innerWidth <= 1100 || collapsed.value) return
    const rect = pane.value?.getBoundingClientRect()
    if (!rect?.height) return
    event.preventDefault()
    stop()
    height.value = rect.height
    dragBounds = rect
    pointerId = event.pointerId
    resizing.value = true
    ;(event.currentTarget as HTMLElement).focus()
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', end)
    window.addEventListener('pointercancel', end)
    window.addEventListener('blur', finish)
    window.addEventListener('resize', finish)
  }
  function setCollapsed(value: boolean) { stop(); collapsed.value = value; save() }
  function reset() { ratio.value = defaultRatio; save() }
  function keydown(event: KeyboardEvent) {
    if (window.innerWidth <= 1100 || collapsed.value || !['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    if (event.key === 'Home') ratio.value = defaultRatio
    else if (event.key === 'End') ratio.value = bounds.value.max
    else ratio.value = clamp(visibleRatio.value + (event.key === 'ArrowDown' ? 1 : -1) * (event.shiftKey ? .05 : .01), bounds.value.min, bounds.value.max)
    save()
  }
  watch(member, () => {
    stop(false)
    ratio.value = defaultRatio
    collapsed.value = false
    try {
      const value = JSON.parse(localStorage.getItem(key()) || 'null')
      if (typeof value?.ratio === 'number' && Number.isFinite(value.ratio) && value.ratio >= .1 && value.ratio <= .9) ratio.value = value.ratio
      if (typeof value?.collapsed === 'boolean') collapsed.value = value.collapsed
    } catch { /* 无法读取偏好时恢复默认，不影响正文草稿。 */ }
  }, { immediate: true })
  onMounted(() => {
    if (!pane.value) return
    height.value = pane.value.getBoundingClientRect().height
    if (typeof ResizeObserver === 'undefined') return
    observer = new ResizeObserver(() => { height.value = pane.value?.getBoundingClientRect().height || 0 })
    observer.observe(pane.value)
  })
  onBeforeUnmount(() => { stop(); observer?.disconnect() })
  return { collapsed, percentage, style, resizing, bounds, start, stop, reset, keydown, setCollapsed }
}
