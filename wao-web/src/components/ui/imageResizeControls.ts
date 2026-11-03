/** A local preview: document transactions happen only when the gesture is committed. */
export function imageResizeControls(options: {
  dom: HTMLElement
  image: HTMLImageElement
  enabled: () => boolean
  commit: (width: number, height: number) => void
  restore: () => void
}) {
  const { dom, image } = options
  const controls = document.createElement('span')
  controls.className = 'image-resize-controls'
  controls.contentEditable = 'false'
  const size = document.createElement('span')
  size.dataset.imageSize = ''
  size.className = 'image-resize-size'
  size.setAttribute('role', 'status')
  let active: { x: number; y: number; width: number; height: number; ratio: number; max: number; direction: string; pointer: number; handle: HTMLButtonElement } | null = null
  let preview: { width: number; height: number } | null = null
  const measure = () => {
    const rect = image.getBoundingClientRect()
    const parent = dom.parentElement
    const parentStyle = parent ? getComputedStyle(parent) : null
    const available = (parent?.getBoundingClientRect().width || rect.width)
      - (parseFloat(parentStyle?.paddingLeft || '') || 0) - (parseFloat(parentStyle?.paddingRight || '') || 0)
      - (parseFloat(getComputedStyle(dom).marginLeft) || 0) - (parseFloat(getComputedStyle(dom).marginRight) || 0)
    return { width: rect.width, height: rect.height, ratio: rect.width / rect.height, max: Math.max(1, available) }
  }
  const dimensions = (width: number, ratio: number, max: number) => {
    const w = Math.max(1, Math.min(Math.floor(max), 4000, Math.floor(4000 * ratio), Math.round(Math.max(1, width))))
    return { width: w, height: Math.max(1, Math.round(w / ratio)) }
  }
  const matches = (event: PointerEvent) => !active || event.pointerId == null || event.pointerId === active.pointer
  const move = (event: PointerEvent) => {
    if (!active || !matches(event)) return
    if (!options.enabled()) { finish(false); return }
    event.preventDefault()
    const { direction, width, ratio, max, x, y } = active
    const dx = (event.clientX - x) * (direction.includes('w') ? -1 : 1)
    const dy = (event.clientY - y) * (direction.includes('n') ? -1 : 1) * ratio
    const delta = direction.length === 2 ? (dx + dy) / 2 : /[ew]/.test(direction) ? dx : dy
    preview = dimensions(width + delta, ratio, max)
    dom.style.width = `${preview.width}px`
    image.style.width = '100%'
    image.style.height = 'auto'
    image.style.aspectRatio = `${preview.width}/${preview.height}`
    size.textContent = `${preview.width} × ${preview.height} px`
  }
  const finish = (save: boolean) => {
    if (!active) return
    const gesture = active, value = preview
    active = null; preview = null
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
    window.removeEventListener('pointercancel', cancelPointer)
    window.removeEventListener('keydown', escape, true)
    window.removeEventListener('blur', cancel)
    window.removeEventListener('resize', cancel)
    if (gesture.handle.hasPointerCapture?.(gesture.pointer)) gesture.handle.releasePointerCapture(gesture.pointer)
    dom.classList.remove('image-resizing')
    options.restore()
    if (save && value && options.enabled() && (value.width !== gesture.width || value.height !== gesture.height)) options.commit(value.width, value.height)
  }
  const up = (event: PointerEvent) => { if (matches(event)) finish(true) }
  const cancel = () => finish(false)
  const cancelPointer = (event: PointerEvent) => { if (matches(event)) cancel() }
  const escape = (event: KeyboardEvent) => {
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); cancel() }
  }
  const names = { n: '上边', ne: '右上角', e: '右边', se: '右下角', s: '下边', sw: '左下角', w: '左边', nw: '左上角' }
  for (const [direction, name] of Object.entries(names)) {
    const handle = document.createElement('button')
    handle.type = 'button'; handle.dataset.imageResize = direction
    handle.className = `image-resize-handle image-resize-${direction}`
    handle.setAttribute('aria-label', `从${name}缩放图片`)
    handle.title = '等比例缩放；方向键调整，Esc 取消拖动'
    handle.addEventListener('pointerdown', event => {
      if (event.button !== 0 || !options.enabled() || active) return
      event.preventDefault(); event.stopPropagation()
      const bounds = measure()
      if (!bounds.width || !bounds.height) return
      active = { ...bounds, x: event.clientX, y: event.clientY, direction, pointer: event.pointerId, handle }
      preview = null
      dom.draggable = false
      dom.classList.add('image-resizing')
      size.textContent = `${Math.round(bounds.width)} × ${Math.round(bounds.height)} px`
      handle.setPointerCapture?.(event.pointerId)
      window.addEventListener('pointermove', move, { passive: false })
      window.addEventListener('pointerup', up)
      window.addEventListener('pointercancel', cancelPointer)
      window.addEventListener('keydown', escape, true)
      window.addEventListener('blur', cancel)
      window.addEventListener('resize', cancel)
    })
    handle.addEventListener('keydown', event => {
      if (!options.enabled() || !['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return
      event.preventDefault(); event.stopPropagation()
      const bounds = measure()
      if (!bounds.width || !bounds.height) return
      const delta = (event.key === 'ArrowLeft' || event.key === 'ArrowDown' ? -1 : 1) * (event.shiftKey ? 10 : 1)
      const value = dimensions(bounds.width + delta, bounds.ratio, bounds.max)
      options.commit(value.width, value.height)
      size.textContent = `${value.width} × ${value.height} px`
    })
    handle.addEventListener('click', event => { event.preventDefault(); event.stopPropagation() })
    controls.append(handle)
  }
  controls.append(size)
  return { dom: controls, cancel, destroy: cancel }
}
