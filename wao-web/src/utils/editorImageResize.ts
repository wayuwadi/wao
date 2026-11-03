/** Preview pointer movement locally; commit a single undoable document change on release. */
export function imageResizeControls(options: {
  dom: HTMLElement
  image: HTMLImageElement
  editable: () => boolean
  commit: (width: number) => void
  restore: () => void
}) {
  const { dom, image } = options
  let cancel: (() => void) | undefined
  const limit = (width: number) => {
    const parent = dom.parentElement
    const style = parent && getComputedStyle(parent)
    const max = parent ? parent.clientWidth - parseFloat(style!.paddingLeft || '0') - parseFloat(style!.paddingRight || '0') : width
    return Math.round(Math.max(Math.min(48, max || width), Math.min(width, max || width)))
  }
  const controls = (['nw', 'ne', 'sw', 'se'] as const).map(corner => {
    const handle = document.createElement('button')
    handle.type = 'button'
    handle.className = `image-resize-handle resize-${corner}`
    handle.setAttribute('aria-label', `调整图片大小（${({ nw: '左上', ne: '右上', sw: '左下', se: '右下' })[corner]}）`)
    handle.title = '拖动调整图片大小；方向键微调'
    handle.addEventListener('click', event => { event.preventDefault(); event.stopPropagation() })
    handle.addEventListener('keydown', event => {
      if (!options.editable() || !['ArrowLeft', 'ArrowRight'].includes(event.key)) return
      event.preventDefault(); event.stopPropagation()
      options.commit(limit(image.getBoundingClientRect().width + (event.key === 'ArrowRight' ? 10 : -10)))
    })
    handle.addEventListener('pointerdown', event => {
      if (!options.editable() || event.button !== 0) return
      event.preventDefault(); event.stopPropagation(); cancel?.()
      const width = image.getBoundingClientRect().width
      if (!width) return
      const start = event.clientX, direction = corner.endsWith('w') ? -1 : 1
      let next = width
      const move = (e: PointerEvent) => {
        if (e.pointerId !== event.pointerId) return
        next = limit(width + direction * (e.clientX - start))
        dom.style.width = `${next}px`; dom.style.marginLeft = '0'; dom.style.marginRight = 'auto'
        image.style.width = '100%'; image.style.height = 'auto'
      }
      const cleanup = () => {
        window.removeEventListener('pointermove', move)
        window.removeEventListener('pointerup', finish)
        window.removeEventListener('pointercancel', abort)
        window.removeEventListener('keydown', escape)
        window.removeEventListener('blur', abort)
        dom.classList.remove('image-resizing'); dom.draggable = options.editable(); cancel = undefined
      }
      const abort = () => { cleanup(); options.restore() }
      const escape = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.preventDefault(); abort() } }
      const finish = (e: PointerEvent) => {
        if (e.pointerId !== event.pointerId) return
        move(e); cleanup()
        if (options.editable() && Math.abs(next - width) >= 1) options.commit(next)
        else options.restore()
      }
      cancel = abort; dom.draggable = false; dom.classList.add('image-resizing')
      window.addEventListener('pointermove', move)
      window.addEventListener('pointerup', finish)
      window.addEventListener('pointercancel', abort)
      window.addEventListener('keydown', escape)
      window.addEventListener('blur', abort)
    })
    return handle
  })
  return { controls, destroy() { cancel?.() } }
}
