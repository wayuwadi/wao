import type { OrderedExcalidrawElement } from '@excalidraw/excalidraw/element/types'
import { boundsOf, contains, overlaps, unionBounds, type Bounds } from './drawingTidyGeometry'

type Element = OrderedExcalidrawElement
type Image = Extract<Element, { type: 'image' }>
type Text = Extract<Element, { type: 'text' }>
type Card = { images: Image[]; bounds: Bounds; heading: Text[]; notes: Text[] }

const gutter = 28
const gap = 24
const centerX = (box: Bounds) => box.x + box.width / 2
const bottom = (box: Bounds) => box.y + box.height
const isImage = (element: Element): element is Image => element.type === 'image'
const isText = (element: Element): element is Text => element.type === 'text' && !element.containerId
const isPanel = (element: Element) => !element.angle && (element.type === 'frame' ||
  (element.type === 'rectangle' && !element.frameId && !element.groupIds.length &&
    !element.boundElements?.length && element.backgroundColor === 'transparent'))

function belongsTo(element: Element, panel: Bounds) {
  const box = boundsOf(element)
  // A wide screenshot can overhang the border. Its center still identifies the panel.
  return overlaps(box, panel) && box.x + box.width > panel.x && centerX(box) >= panel.x &&
    centerX(box) < panel.x + panel.width && box.y + box.height / 2 >= panel.y &&
    box.y + box.height / 2 < bottom(panel)
}

function readingOrder(images: Image[]) {
  const rows: Image[][] = []
  for (const image of [...images].sort((a, b) => a.y - b.y)) {
    const row = rows[rows.length - 1]
    if (row && image.y - row[0]!.y <= 30) row.push(image)
    else rows.push([image])
  }
  if (rows.filter((row) => row.length > 1).length > 1) {
    return rows.flatMap((row) => row.sort((a, b) => a.x - b.x))
  }
  // Near-vertical stacks read from top to bottom within their original column.
  const sorted = [...images].sort((a, b) => a.x - b.x)
  const columns: Image[][] = []
  for (const image of sorted) {
    const column = columns[columns.length - 1]
    if (column && Math.abs(image.x - column[0]!.x) < 24) column.push(image)
    else columns.push([image])
  }
  return columns.flatMap((column) => column.sort((a, b) => a.y - b.y))
}

function makeCards(panel: Bounds, images: Image[], captions: Text[]) {
  const top = Math.min(...images.map(({ y }) => y))
  const markers = captions.filter((item) => /^\s*\d{1,2}(?:\s+[^。]{1,20})?\s*$/.test(item.text) &&
    item.y < top && top - item.y < 140).sort((a, b) => centerX(a) - centerX(b))
  if (markers.length >= 3) {
    const steps = markers.map((heading) => ({ heading: [heading], images: [] as Image[] }))
    const stride = (panel.width - gutter * 2 + gap) / markers.length
    const startsAtGrid = markers.every((item, index) => Math.abs(item.x - panel.x - gutter - index * stride) <= 1)
    for (const image of images) {
      let closest = 0
      for (let i = 1; i < markers.length; i++) {
        const split = startsAtGrid ? markers[i]!.x - gap / 2 : (centerX(markers[i]!) + centerX(markers[i - 1]!)) / 2
        if (centerX(image) >= split) closest = i
      }
      steps[closest]!.images.push(image)
    }
    if (steps.every(({ images }) => images.length)) {
      return steps.map(({ images, heading }): Card =>
        ({ images: readingOrder(images), heading, notes: [], bounds: unionBounds(images.map(boundsOf)) }))
    }
  }
  return readingOrder(images).map((image): Card =>
    ({ images: [image], heading: [], notes: [], bounds: boundsOf(image) }))
}

// Estimate from the width the editor already measured, then keep word and CJK breaks usable.
function lineUnits(value: string) {
  return [...value].reduce((sum, character) => sum + (/[^\u0000-\u00ff]/.test(character) ? 1 : 0.62), 0)
}

function fitText(element: Text, width: number) {
  if (element.width <= width) return element
  const plain = element.originalText || element.text
  const lineHeight = element.fontSize * (element.lineHeight || 1.25)
  const longest = Math.max(1, ...element.text.split('\n').map(lineUnits))
  const unit = Math.max(element.fontSize, element.width / longest)
  const limit = width / unit
  const lines = plain.split('\n').flatMap((paragraph) => {
    const lines: string[] = []
    let current = ''
    let used = 0
    for (const token of paragraph.match(/[\x20-\x7e]+|[^\x20-\x7e]/gu) ?? []) {
      const parts = lineUnits(token) > limit ? [...token] : [token]
      for (const part of parts) {
        const size = lineUnits(part)
        if (used && used + size > limit) {
          lines.push(current.trimEnd())
          current = ''
          used = 0
        }
        current += part
        used += size
      }
    }
    return [...lines, current.trimEnd()]
  })
  return { ...element, originalText: plain, text: lines.join('\n'), width, height: lines.length * lineHeight,
    autoResize: false }
}

function assignNotes(cards: Card[], captions: Text[]) {
  const used = new Set(cards.flatMap(({ heading }) => heading.map(({ id }) => id)))
  const top = Math.min(...cards.map(({ bounds }) => bounds.y))
  const headings: Text[] = [], footnotes: Text[] = []
  for (const caption of captions.sort((a, b) => a.y - b.y)) {
    if (used.has(caption.id)) continue
    if (bottom(caption) <= top + 6 && cards.every(({ heading }) => !heading.length)) {
      headings.push(caption)
      continue
    }
    const eligible = cards.filter(({ bounds }) => caption.y >= bottom(bounds) - 18)
    const score = ({ bounds }: Card) => {
      const anchor = caption.x + Math.min(caption.width / 2, bounds.width / 3)
      const xGap = Math.max(bounds.x - anchor, anchor - bounds.x - bounds.width, 0)
      return 3 * xGap + Math.abs(anchor - centerX(bounds)) * 0.2 + Math.abs(caption.y - bottom(bounds)) * 0.24
    }
    const closest = eligible.sort((a, b) => score(a) - score(b))[0]
    if (closest && score(closest) < 450) closest.notes.push(caption)
    else footnotes.push(caption)
  }
  return { headings, footnotes }
}

function layoutPanel(panel: Bounds, members: Element[]) {
  const images = members.filter(isImage)
  if (images.length < 2) return
  const captions = members.filter(isText)
  const cards = makeCards(panel, images, captions)
  const { headings, footnotes } = assignNotes(cards, captions)
  const stepped = cards.some(({ heading }) => heading.length)
  const width = panel.width - gutter * 2
  const preferredColumns = cards.length <= 4 ? cards.length : Math.ceil(cards.length / 2)
  const columns = stepped ? cards.length : Math.min(preferredColumns, Math.max(2, Math.floor((width + gap) / 210)))
  const rows = Array.from({ length: Math.ceil(cards.length / columns) },
    (_, index) => cards.slice(index * columns, (index + 1) * columns))
  const columnWidth = (width - gap * (columns - 1)) / columns
  if (columnWidth < 80) return
  const leftAt = (column: number) => panel.x + gutter + column * (columnWidth + gap)
  const placedOnGrid = rows.every((row) => row.every((card, index) =>
    Math.abs(centerX(card.bounds) - leftAt(index) - columnWidth / 2) <= 1 &&
    card.bounds.width <= columnWidth && card.images.every(({ y }) => Math.abs(y - row[0]!.bounds.y) <= 1)))
  if (placedOnGrid && captions.every((item) => contains(panel, item) &&
      rows[0]!.some((_, index) => Math.abs(item.x - leftAt(index)) <= 1)) &&
      captions.every((item) => !images.some((image) => overlaps(item, image)))) return new Map<string, Element>()

  const rowGaps = gap * (rows.length - 1)
  const stackHeight = (items: Text[]) => items.reduce((sum, item) => sum + item.height + 10, 0)

  const top = headings.map((item) => fitText(item, width))
  const bottomNotes = footnotes.map((item) => fitText(item, width))
  for (const card of cards) {
    card.heading = card.heading.map((item) => fitText(item, columnWidth))
    card.notes = card.notes.map((item) => fitText(item, columnWidth))
  }
  const sizes = rows.map((row) => ({
    headings: Math.max(...row.map(({ heading }) => stackHeight(heading))),
    notes: Math.max(...row.map(({ notes }) => stackHeight(notes))),
    // Keep all screenshots in a row at a shared visual height. Popups keep their own aspect ratio.
    maxHeight: Math.max(...row.flatMap(({ images }) => images.map(({ height }) => height))),
  }))
  const nonImageHeight = stackHeight(top) + stackHeight(bottomNotes) + sizes.reduce((sum, item) =>
    sum + item.headings + item.notes + 20, 0) + rowGaps + gutter * 2
  const imageBudget = panel.height - nonImageHeight
  if (imageBudget < rows.length * 95) return
  const imageScale = Math.min(1, imageBudget / sizes.reduce((sum, item) => sum + item.maxHeight, 0))
  const changed = new Map<string, Element>()
  const putText = (items: Text[], x: number, start: number) => {
    let y = start
    for (const element of items) {
      changed.set(element.id, { ...element, x, y })
      y += element.height + 10
    }
  }

  putText(top, panel.x + gutter, panel.y + gutter)
  let rowTop = panel.y + gutter + stackHeight(top)
  rows.forEach((row, rowIndex) => {
    const size = sizes[rowIndex]!
    const imageTop = rowTop + size.headings
    const targetHeight = size.maxHeight * imageScale
    let imageBottom = imageTop
    row.forEach((card, colIndex) => {
      const left = leftAt(colIndex)
      const gaps = 10 * (card.images.length - 1)
      const proposed = card.images.map((image) => ({ image,
        scale: Math.min(1, targetHeight / image.height),
      }))
      const groupWidth = proposed.reduce((sum, { image, scale }) => sum + image.width * scale, 0)
      const scaleToWidth = Math.min(1, (columnWidth - gaps) / groupWidth)
      const occupied = groupWidth * scaleToWidth + gaps
      let x = left + (columnWidth - occupied) / 2
      putText(card.heading, left, rowTop)
      for (const { image, scale } of proposed) {
        const width = image.width * scale * scaleToWidth
        const height = image.height * scale * scaleToWidth
        changed.set(image.id, { ...image, x, y: imageTop, width, height })
        x += width + 10
        imageBottom = Math.max(imageBottom, imageTop + height)
      }
    })
    row.forEach((card, index) => putText(card.notes, leftAt(index), imageBottom + 20))
    rowTop = imageBottom + 20 + size.notes + gap
  })
  putText(bottomNotes, panel.x + gutter, rowTop - gap)
  return changed
}

export function planDrawingStoryboard(
  elements: readonly Element[], selected?: ReadonlySet<string>, pinned: ReadonlySet<string> = new Set(),
) {
  const images = elements.filter(isImage)
  const panels = elements.filter(isPanel).map((element) => ({ element, box: boundsOf(element) }))
    .filter(({ element, box }) => images.filter((image) =>
      image.frameId ? image.frameId === element.id : belongsTo(image, box)).length >= 2)
    .sort((a, b) => a.box.width * a.box.height - b.box.width * b.box.height)
  const panelIds = new Set(panels.map(({ element }) => element.id))
  const owner = new Map(elements.filter((element) => isImage(element) || isText(element)).map((element) =>
    [element.id, element.frameId || panels.find(({ box }) => belongsTo(element, box))?.element.id]))
  const changes = new Map<string, Element>()
  let sections = 0
  let aligned = false
  for (const { element: outline, box } of panels) {
    if (selected && !selected.has(outline.id)) continue
    const parts = elements.filter((element) => element.type === 'line' && !element.angle &&
      element.width < 4 && Math.abs(element.y - box.y) <= 16 && Math.abs(bottom(element) - bottom(box)) <= 16)
      .map(({ x }) => x).filter((x) => x > box.x + gutter * 2 && x < box.x + box.width - gutter * 2).sort((a, b) => a - b)
    const boundaries = [box.x, ...parts, box.x + box.width]
    for (let i = 1; i < boundaries.length; i++) {
      const panel = { ...box, x: boundaries[i - 1]!, width: boundaries[i]! - boundaries[i - 1]! }
      const members = elements.filter((element) => owner.get(element.id) === outline.id && belongsTo(element, panel))
      const eligible = members.filter((element) => !element.locked && !element.angle && !pinned.has(element.id) &&
        !element.boundElements?.some(({ type }) => type === 'text') && element.width > 0 && element.height > 0)
      const eligibleIds = new Set(eligible.map(({ id }) => id))
      const candidates = eligible.filter((element) => element.groupIds.every((id) => elements.every((other) =>
        !other.groupIds.includes(id) || eligibleIds.has(other.id) && isImage(other) && isImage(element))))
      const updated = layoutPanel(panel, candidates)
      if (!updated) continue
      if (!updated.size) {
        aligned = true
        continue
      }
      const fixed = elements.filter((element) => !updated.has(element.id) && !panelIds.has(element.id) &&
        element.type !== 'line' && element.type !== 'arrow' && (element.type !== 'text' || !element.containerId))
      if ([...updated.values()].some((element) => !contains(panel, boundsOf(element)) ||
          fixed.some((other) => overlaps(boundsOf(element), boundsOf(changes.get(other.id) ?? other))))) continue
      const moved = [...updated.values()].filter((element) => {
        const original = candidates.find(({ id }) => id === element.id)!
        return (['x', 'y', 'width', 'height'] as const).some((axis) => Math.abs(element[axis] - original[axis]) > 0.01)
      })
      if (!moved.length) continue
      moved.forEach((element) => changes.set(element.id, element))
      sections++
    }
  }
  if (!panelIds.size || selected && !panels.some(({ element }) => selected.has(element.id))) return
  return { changes, panelIds, sections, aligned }
}
