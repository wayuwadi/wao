import { computed, onBeforeUnmount, ref, type Ref } from 'vue'
import { toast } from '@/composables/toast'

const storageKey = 'wao-notes-writing-width-v1'
const defaultRatio = 1 / 3
const clamp = (ratio: number) => Math.max(.25, Math.min(.6, ratio))

/** 浏览器内记住记录区比例，拖拽只改变布局，不重新创建编辑器。 */
export function useNotesPaneWidth(workspace: Ref<HTMLElement | null>) {
  const ratio = ref(defaultRatio)
  try {
    const stored = localStorage.getItem(storageKey)
    const value = stored === null ? NaN : Number(stored)
    if (Number.isFinite(value) && value >= .25 && value <= .6) ratio.value = value
  } catch { /* 存储不可用时仍能调整当前页面。 */ }
  const resizing = ref(false)
  let pointerId: number | undefined
  let dragBounds: DOMRect | undefined
  const percentage = computed(() => Math.round(ratio.value * 100))
  const style = computed(() => ({ '--notes-writing-width': `${ratio.value * 100}%` }))

  function save() {
    try { localStorage.setItem(storageKey, String(ratio.value)) }
    catch { toast('宽度已调整，但浏览器未能记住，请再次调整以重试保存', 'ri-error-warning-line') }
  }
  function stop() {
    if (resizing.value) save()
    resizing.value = false
    pointerId = undefined
    dragBounds = undefined
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', end)
    window.removeEventListener('pointercancel', end)
    window.removeEventListener('blur', stop)
    window.removeEventListener('resize', stop)
  }
  function move(event: PointerEvent) {
    if (event.pointerId !== pointerId || !dragBounds) return
    ratio.value = clamp((event.clientX - dragBounds.left - 10) / dragBounds.width)
  }
  function end(event: PointerEvent) {
    if (event.pointerId === pointerId) stop()
  }
  function start(event: PointerEvent) {
    if (event.button !== 0 || window.innerWidth <= 1100) return
    const bounds = workspace.value?.getBoundingClientRect()
    if (!bounds?.width) return
    event.preventDefault()
    stop()
    dragBounds = bounds
    pointerId = event.pointerId
    resizing.value = true
    ;(event.currentTarget as HTMLElement).focus()
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', end)
    window.addEventListener('pointercancel', end)
    window.addEventListener('blur', stop)
    window.addEventListener('resize', stop)
  }
  function reset() { ratio.value = defaultRatio; save() }
  function keydown(event: KeyboardEvent) {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    if (event.key === 'Home') ratio.value = defaultRatio
    else if (event.key === 'End') ratio.value = .6
    else ratio.value = clamp(ratio.value + (event.key === 'ArrowRight' ? 1 : -1) * (event.shiftKey ? .05 : .01))
    save()
  }
  onBeforeUnmount(stop)
  return { percentage, style, resizing, start, stop, reset, keydown }
}
