export function imageToolbar(options: {
  anchor: HTMLElement
  enabled: () => boolean
  change: (attrs: Record<string, unknown>) => void
  dimensions: (anchor: HTMLElement) => void
  close: () => void
}) {
  const dom = document.createElement('span')
  dom.className = 'image-inline-toolbar'
  dom.setAttribute('role', 'group')
  dom.setAttribute('aria-label', '图片设置')
  dom.contentEditable = 'false'
  const position = () => {
    const anchor = options.anchor.getBoundingClientRect()
    const parent = options.anchor.parentElement?.getBoundingClientRect()
    if (!parent || !dom.getBoundingClientRect().width) return
    const left = Math.max(8, parent.left), right = Math.min(window.innerWidth - 8, parent.right)
    dom.style.maxWidth = `${Math.max(1, Math.min(360, right - left))}px`
    const width = dom.getBoundingClientRect().width
    dom.style.left = `${Math.max(left, Math.min(anchor.left, right - width)) - anchor.left}px`
  }
  let frame = 0
  const schedulePosition = () => { cancelAnimationFrame(frame); frame = requestAnimationFrame(position) }
  const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(schedulePosition) : null
  observer?.observe(dom)
  observer?.observe(options.anchor)
  window.addEventListener('resize', schedulePosition)
  const button = (text: string, label: string, action: (button: HTMLButtonElement) => void) => {
    const el = document.createElement('button')
    el.type = 'button'; el.textContent = text; el.setAttribute('aria-label', label)
    el.addEventListener('mousedown', event => event.preventDefault())
    el.addEventListener('click', event => { event.preventDefault(); event.stopPropagation(); if (options.enabled()) action(el) })
    dom.append(el)
  }
  button('尺寸', '设置图片尺寸', options.dimensions)
  const border = document.createElement('details')
  const summary = document.createElement('summary')
  summary.textContent = '边框'
  border.append(summary); dom.append(border)
  const fields = document.createElement('span'); fields.className = 'image-border-fields'; border.append(fields)
  const select = (parent: HTMLElement, label: string, values: [string, string][], change: (value: string) => void) => {
    const field = document.createElement('label'); field.textContent = label
    const input = document.createElement('select'); input.setAttribute('aria-label', label)
    for (const [value, text] of values) { const option = document.createElement('option'); option.value = value; option.textContent = text; input.append(option) }
    input.addEventListener('change', () => { if (options.enabled()) change(input.value) })
    field.append(input); parent.append(field); return input
  }
  const width = select(fields, '图片边框粗细', [['0', '无边框'], ...Array.from({ length: 8 }, (_, i): [string, string] => [String(i + 1), `${i + 1} px`])], value => options.change({ borderWidth: Number(value) }))
  const style = select(fields, '图片边框样式', [['solid', '实线'], ['dashed', '虚线']], value => options.change({ borderStyle: value }))
  const color = select(fields, '图片边框颜色', [['', '跟随正文'], ['#1f2329', '黑色'], ['#646a73', '灰色'], ['#d83931', '红色'], ['#de7802', '橙色'], ['#2ea121', '绿色'], ['#245bdb', '蓝色'], ['#6425d0', '紫色']], value => options.change({ borderColor: value || null }))
  const align = select(dom, '图片对齐', [['', '默认'], ['left', '左对齐'], ['center', '居中'], ['right', '右对齐']], value => options.change({ align: value || null }))
  button('原尺寸', '恢复图片原尺寸', () => options.change({ width: null, height: null }))
  dom.addEventListener('keydown', event => {
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); border.open = false; options.close() }
  })
  return { dom, position: schedulePosition,
    destroy() { cancelAnimationFrame(frame); observer?.disconnect(); window.removeEventListener('resize', schedulePosition) },
    update(attrs: Record<string, unknown>) {
    dom.hidden = !options.enabled()
    width.value = String(attrs.borderWidth || 0); style.value = String(attrs.borderStyle || 'solid')
    color.value = String(attrs.borderColor || ''); align.value = String(attrs.align || '')
    schedulePosition()
  } }
}
