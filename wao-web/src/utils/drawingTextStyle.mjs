// Shared by the WAO controls and the Excalidraw canvas/SVG renderers.
const hexColor = /^#[\da-f]{6}$/i
const isColor = (value) => typeof value === 'string' && hexColor.test(value)
const maxRuns = 256
export const textStyleKey = 'waoTextStyle'

export const textSource = (element) => (element.originalText ?? element.text).replace(/\r\n?/g, '\n')

export function contrastingText(color) {
  const channels = color.slice(1).match(/../g).map(hex => parseInt(hex, 16) / 255)
  const [r, g, b] = channels.map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > Math.sqrt(0.0525) - 0.05 ? '#000000' : '#ffffff'
}

export function setTextRange(runs, start, end, color) {
  if (start >= end) return runs
  const next = []
  for (const run of runs) {
    if (run.end <= start || run.start >= end) next.push(run)
    else {
      if (run.start < start) next.push({ ...run, end: start })
      if (run.end > end) next.push({ ...run, start: end })
    }
  }
  if (color) next.push({ start, end, color })
  next.sort((a, b) => a.start - b.start)
  const merged = []
  for (const run of next) {
    const last = merged[merged.length - 1]
    if (last?.color === run.color && last.end === run.start) last.end = run.end
    else merged.push({ ...run })
  }
  return merged
}

export function readTextStyle(element) {
  const source = textSource(element)
  const stored = element.customData?.[textStyleKey]
  const background = isColor(stored?.background) ? stored.background : undefined
  const previous = typeof stored?.source === 'string' ? stored.source : ''
  const runs = Array.isArray(stored?.runs) ? stored.runs : []
  if (runs.length === 0 || runs.length > maxRuns || !previous) return { source, background, runs: [] }
  // Native edits leave unchanged prefix/suffix highlights in place. A replaced span
  // takes the element's default color. Native undo also restores the original mapping.
  let prefix = 0
  let suffix = 0
  if (previous === source) prefix = source.length
  else {
    while (prefix < previous.length && prefix < source.length && previous[prefix] === source[prefix]) prefix++
    while (suffix < previous.length - prefix && suffix < source.length - prefix &&
      previous[previous.length - 1 - suffix] === source[source.length - 1 - suffix]) suffix++
    // The shared boundary can occur between the two code units of an emoji.
    if (prefix && /[\uDC00-\uDFFF]/.test(previous[prefix] ?? '')) prefix--
    if (suffix && /[\uDC00-\uDFFF]/.test(previous[previous.length - suffix] ?? '')) suffix--
  }
  const normalized = []
  const previousTail = previous.length - suffix
  const shift = source.length - previous.length
  let lastEnd = 0
  for (const run of runs) {
    if (!run || !Number.isInteger(run.start) || !Number.isInteger(run.end) || run.start < lastEnd ||
        run.end <= run.start || run.end > previous.length || !isColor(run.color)) continue
    lastEnd = run.end
    if (run.start < prefix) normalized.push({ ...run, end: Math.min(run.end, prefix) })
    if (run.end > previousTail) normalized.push({ ...run, start: Math.max(run.start, previousTail) + shift, end: run.end + shift })
  }
  return { source, background, runs: normalized }
}

export function textSegments(text, runs, offset = 0) {
  const segments = []
  let cursor = 0
  for (const run of runs) {
    const start = Math.max(0, run.start - offset)
    const end = Math.min(text.length, run.end - offset)
    if (end <= start) continue
    if (start > cursor) segments.push({ start: cursor, end: start, text: text.slice(cursor, start) })
    segments.push({ start, end, text: text.slice(start, end), color: run.color })
    cursor = end
  }
  if (cursor < text.length) segments.push({ start: cursor, end: text.length, text: text.slice(cursor) })
  return segments
}

function styledLines(style, lines) {
  let cursor = 0
  return lines.map(line => {
    if (style.source[cursor] === '\n') cursor++
    const offset = style.source.indexOf(line, cursor)
    if (offset < 0) return []
    cursor = offset + line.length
    return textSegments(line, style.runs, offset)
  })
}

function backgroundBox(element) {
  const pad = Math.min(6, element.fontSize / 5)
  return { x: -pad, y: -pad / 2, width: element.width + 2 * pad, height: element.height + pad }
}

export function paintStyledText(ctx, element, lines, x, lineHeight, baseline, rtl) {
  if (!element.customData?.[textStyleKey]) return false
  const style = readTextStyle(element)
  if (!style.background && !style.runs.length) return false
  const base = style.background ? contrastingText(style.background) : element.strokeColor
  if (style.background) {
    const box = backgroundBox(element)
    ctx.fillStyle = style.background
    ctx.fillRect(box.x, box.y, box.width, box.height)
  }
  const segments = styledLines(style, lines)
  lines.forEach((line, index) => {
    const y = index * lineHeight + baseline
    const runs = segments[index]
    if (!runs.some(run => run.color && run.color !== base)) {
      ctx.fillStyle = base
      ctx.fillText(line, x, y)
      return
    }
    const width = ctx.measureText(line).width
    const left = x - (element.textAlign === 'center' ? width / 2 : element.textAlign === 'right' ? width : 0)
    for (const run of runs) {
      const start = ctx.measureText(line.slice(0, run.start)).width
      const end = ctx.measureText(line.slice(0, run.end)).width
      const from = rtl ? width - end : start
      const to = rtl ? width - start : end
      const margin = element.fontSize / 2
      const clipStart = from === 0 ? -margin : from
      const clipEnd = to === width ? width + margin : to
      // Repaint the complete shaped line in each range to keep ligatures and kerning.
      ctx.save()
      ctx.beginPath()
      ctx.rect(left + clipStart, y - lineHeight, Math.max(0, clipEnd - clipStart), lineHeight * 2)
      ctx.clip()
      ctx.fillStyle = run.color ?? base
      ctx.fillText(line, x, y)
      ctx.restore()
    }
  })
  return true
}

export function styleSvgText(node, element, lines) {
  if (!element.customData?.[textStyleKey]) return
  const style = readTextStyle(element)
  if (!style.background && !style.runs.length) return
  const make = (name) => node.ownerDocument.createElementNS(node.namespaceURI, name)
  if (style.background) {
    const rectangle = make('rect')
    for (const [key, value] of Object.entries(backgroundBox(element))) rectangle.setAttribute(key, String(value))
    rectangle.setAttribute('fill', style.background)
    node.prepend(rectangle)
  }
  const segments = styledLines(style, lines)
  ;[...node.querySelectorAll('text')].forEach((line, index) => {
    if (style.background) line.setAttribute('fill', contrastingText(style.background))
    const runs = segments[index]
    if (!runs.some(run => run.color)) return
    line.replaceChildren(...runs.map(run => {
      if (!run.color) return node.ownerDocument.createTextNode(run.text)
      const span = make('tspan')
      span.setAttribute('fill', run.color)
      span.textContent = run.text
      return span
    }))
  })
}
