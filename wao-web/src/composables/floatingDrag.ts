import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

interface Point { left: number; top: number }

/** 让全局悬浮入口可拖拽，并把最终位置保存在当前浏览器。 */
export function useFloatingDrag(storageKey: string) {
  const position = ref<Point | null>(load(storageKey))
  const dragging = ref(false)
  let element: HTMLElement | null = null
  let pointerId = -1
  let offsetX = 0
  let offsetY = 0
  let startX = 0
  let startY = 0
  let moved = false
  let suppressNextClick = false

  const style = computed(() => position.value ? {
    left: `${position.value.left}px`, top: `${position.value.top}px`, right: 'auto', bottom: 'auto',
  } : undefined)

  function clamp(point: Point) {
    const width = element?.offsetWidth || 48
    const height = element?.offsetHeight || 48
    return {
      left: Math.max(8, Math.min(point.left, window.innerWidth - width - 8)),
      top: Math.max(8, Math.min(point.top, window.innerHeight - height - 8)),
    }
  }

  function onPointerDown(event: PointerEvent) {
    if (event.button !== 0) return
    element = event.currentTarget as HTMLElement
    const rect = element.getBoundingClientRect()
    pointerId = event.pointerId
    offsetX = event.clientX - rect.left
    offsetY = event.clientY - rect.top
    startX = event.clientX
    startY = event.clientY
    moved = false
    position.value = { left: rect.left, top: rect.top }
    dragging.value = true
    element.setPointerCapture(pointerId)
    window.addEventListener('pointermove', onPointerMove, { passive: false })
    window.addEventListener('pointerup', onPointerUp)
    window.addEventListener('pointercancel', onPointerUp)
  }

  function onPointerMove(event: PointerEvent) {
    if (!dragging.value || event.pointerId !== pointerId) return
    if (Math.hypot(event.clientX - startX, event.clientY - startY) > 4) moved = true
    position.value = clamp({ left: event.clientX - offsetX, top: event.clientY - offsetY })
    if (moved) event.preventDefault()
  }

  function onPointerUp(event: PointerEvent) {
    if (event.pointerId !== pointerId) return
    if (element?.hasPointerCapture(pointerId)) element.releasePointerCapture(pointerId)
    dragging.value = false
    suppressNextClick = moved
    if (position.value && moved) localStorage.setItem(storageKey, JSON.stringify(position.value))
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerup', onPointerUp)
    window.removeEventListener('pointercancel', onPointerUp)
  }

  function consumeClick(event: MouseEvent) {
    if (!suppressNextClick) return false
    suppressNextClick = false
    event.preventDefault()
    event.stopPropagation()
    return true
  }

  function clampSaved() { if (position.value) position.value = clamp(position.value) }
  onMounted(() => {
    clampSaved()
    window.addEventListener('resize', clampSaved)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('resize', clampSaved)
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerup', onPointerUp)
    window.removeEventListener('pointercancel', onPointerUp)
  })
  return { style, dragging, onPointerDown, consumeClick }
}

function load(storageKey: string): Point | null {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey) || 'null') as Point | null
    return value && Number.isFinite(value.left) && Number.isFinite(value.top) ? value : null
  } catch { return null }
}
