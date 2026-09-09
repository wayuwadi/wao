import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

interface Point { left: number; top: number }

/** 让全局悬浮入口可拖拽，并把最终位置保存在当前浏览器。 */
export function useFloatingDrag(storageKey: string) {
  const position = ref<Point | null>(load(storageKey))
  const dragging = ref(false)
  const moving = ref(false)
  const horizontalDirection = ref<'left' | 'right'>('right')
  const elementRef = ref<HTMLElement | null>(null)
  let pointerId = -1
  let offsetX = 0
  let offsetY = 0
  let startX = 0
  let startY = 0
  let moved = false
  let suppressNextClick = false
  let captureTarget: Element | null = null
  let lastX = 0
  let lastY = 0
  let movementTimer: ReturnType<typeof setTimeout> | undefined

  function stopMovement() {
    moving.value = false
    if (movementTimer !== undefined) clearTimeout(movementTimer)
    movementTimer = undefined
  }

  const style = computed(() => position.value ? {
    left: `${position.value.left}px`, top: `${position.value.top}px`, right: 'auto', bottom: 'auto',
  } : undefined)

  function clamp(point: Point) {
    const width = elementRef.value?.offsetWidth || 48
    const height = elementRef.value?.offsetHeight || 48
    return {
      left: Math.max(8, Math.min(point.left, window.innerWidth - width - 8)),
      top: Math.max(8, Math.min(point.top, window.innerHeight - height - 8)),
    }
  }

  function onPointerDown(event: PointerEvent) {
    if (event.button !== 0 || dragging.value) return
    elementRef.value = event.currentTarget as HTMLElement
    captureTarget = event.target instanceof Element ? event.target : elementRef.value
    const rect = elementRef.value.getBoundingClientRect()
    pointerId = event.pointerId
    offsetX = event.clientX - rect.left
    offsetY = event.clientY - rect.top
    startX = event.clientX
    startY = event.clientY
    lastX = event.clientX
    lastY = event.clientY
    stopMovement()
    moved = false
    position.value = { left: rect.left, top: rect.top }
    dragging.value = true
    // 捕获到最初按下的按钮或其子元素，确保未发生拖动时浏览器仍把 click 派发给按钮。
    // 如果改由外层拖拽容器捕获，pointerup 会被重定向到外层，按钮的 click 将不会生成。
    captureTarget.setPointerCapture(pointerId)
    window.addEventListener('pointermove', onPointerMove, { passive: false })
    window.addEventListener('pointerup', onPointerUp)
    window.addEventListener('pointercancel', onPointerUp)
  }

  function onPointerMove(event: PointerEvent) {
    if (!dragging.value || event.pointerId !== pointerId) return
    if (Math.hypot(event.clientX - startX, event.clientY - startY) > 4) moved = true
    const deltaX = event.clientX - lastX
    const deltaY = event.clientY - lastY
    if (moved && Math.hypot(deltaX, deltaY) >= 1) {
      if (Math.abs(deltaX) >= 1) horizontalDirection.value = deltaX < 0 ? 'left' : 'right'
      moving.value = true
      if (movementTimer !== undefined) clearTimeout(movementTimer)
      movementTimer = setTimeout(stopMovement, 180)
    }
    lastX = event.clientX
    lastY = event.clientY
    position.value = clamp({ left: event.clientX - offsetX, top: event.clientY - offsetY })
    if (moved) event.preventDefault()
  }

  function onPointerUp(event: PointerEvent) {
    if (event.pointerId !== pointerId) return
    finishDrag()
  }

  function finishDrag() {
    stopMovement()
    if (captureTarget?.hasPointerCapture(pointerId)) captureTarget.releasePointerCapture(pointerId)
    captureTarget = null
    dragging.value = false
    suppressNextClick = moved
    if (position.value && moved) {
      try { localStorage.setItem(storageKey, JSON.stringify(position.value)) } catch { /* 存储受限时保留当前页面位置。 */ }
    }
    pointerId = -1
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
    window.addEventListener('blur', finishDrag)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('resize', clampSaved)
    window.removeEventListener('blur', finishDrag)
    stopMovement()
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerup', onPointerUp)
    window.removeEventListener('pointercancel', onPointerUp)
  })
  return { elementRef, style, dragging, moving, horizontalDirection, onPointerDown, consumeClick }
}

function load(storageKey: string): Point | null {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey) || 'null') as Point | null
    return value && Number.isFinite(value.left) && Number.isFinite(value.top) ? value : null
  } catch { return null }
}
