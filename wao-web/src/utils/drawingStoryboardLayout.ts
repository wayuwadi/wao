import type { OrderedExcalidrawElement } from '@excalidraw/excalidraw/element/types'
import { boundsOf, contains, insideBounds, overlaps, segments, translate, unionBounds, type Bounds } from './drawingTidyGeometry'

type Element = OrderedExcalidrawElement
type Image = Extract<Element, { type: 'image' }>
type Text = Extract<Element, { type: 'text' }>
type Arrow = Extract<Element, { type: 'arrow' }>
type Card = { images: Image[]; bounds: Bounds; above: Text[]; below: Text[] }

function imageCards(images: Image[]) {
  const cards: Card[] = []
  for (const image of images.sort((a, b) => a.x - b.x)) {
    const box = boundsOf(image)
    const previous = cards[cards.length - 1]
    if (previous && box.x < previous.bounds.x + previous.bounds.width + 32 &&
        box.y < previous.bounds.y + previous.bounds.height && previous.bounds.y < box.y + box.height) {
      previous.images.push(image)
      previous.bounds = unionBounds([previous.bounds, box])
    } else {
      cards.push({ images: [image], bounds: box, above: [], below: [] })
    }
  }
  return cards
}

function attachCaptions(panel: Bounds, cards: Card[], captions: Text[]) {
  for (const caption of captions) {
    // Keep a note with its clear column. A spanning title or a floating note stays fixed.
    const matches = cards.filter(({ bounds }, i) => {
      const left = i ? (cards[i - 1]!.bounds.x + cards[i - 1]!.bounds.width + bounds.x) / 2 : bounds.x - 90
      const right = i < cards.length - 1 ? (bounds.x + bounds.width + cards[i + 1]!.bounds.x) / 2 : panel.x + panel.width
      const center = caption.x + caption.width / 2
      return center >= left && center < right && caption.x >= left - 65 &&
        caption.x + caption.width <= right + 200
    })
    if (matches.length !== 1) continue
    const card = matches[0]!
    if (caption.y + caption.height <= card.bounds.y + 4 && card.bounds.y - caption.y < 160) card.above.push(caption)
    else if (caption.y >= card.bounds.y + card.bounds.height - 8) card.below.push(caption)
  }
}

function captionBands(captions: Text[]) {
  const bands: Text[][] = []
  for (const caption of captions.sort((a, b) => a.y - b.y)) {
    const band = bands[bands.length - 1]
    if (band && caption.y - band[0]!.y <= 32 && !band.some((other) => overlaps(boundsOf(other), boundsOf(caption)))) {
      band.push(caption)
    } else {
      bands.push([caption])
    }
  }
  return bands
}

function arrangePanel(panel: Bounds, members: Element[]) {
  const cards = imageCards(members.filter((element): element is Image => element.type === 'image'))
  if (cards.length < 2) return
  attachCaptions(panel, cards, members.filter((element): element is Text => element.type === 'text' && !element.containerId))
  if (cards.filter(({ above, below }) => above.length || below.length).length < 2) return
  const owned = new Set(cards.flatMap(({ images, above, below }) => [...images, ...above, ...below].map(({ id }) => id)))
  if (members.some((element) => owned.has(element.id) && (element.locked || element.angle ||
    element.type === 'image' && element.boundElements?.length ||
    element.groupIds.some((id) => members.some((other) => !owned.has(other.id) && other.groupIds.includes(id)))))) return
  const arrows = members.filter((element): element is Arrow => element.type === 'arrow')
  const arrowCards = arrows.map((arrow) => {
    if (arrow.locked || arrow.groupIds.length || arrow.angle || arrow.points.length !== 2 || arrow.startBinding || arrow.endBinding ||
        arrow.boundElements?.length || arrow.elbowed) return
    const first = arrow.points[0]!, last = arrow.points[1]!
    if (Math.abs(last[0] - first[0]) <= 3 * Math.abs(last[1] - first[1])) return
    const a = { x: arrow.x + first[0], y: arrow.y + first[1] }
    const b = { x: arrow.x + last[0], y: arrow.y + last[1] }
    const right = a.x < b.x
    const side = (x: number, y: number, left: boolean) =>
      cards.filter(({ bounds }) => y > bounds.y && y < bounds.y + bounds.height &&
        Math.abs(x - (left ? bounds.x + bounds.width : bounds.x)) <= 80)
    const from = side(a.x, a.y, right), to = side(b.x, b.y, !right)
    if (from.length !== 1 || to.length !== 1 || from[0] === to[0]) return
    return { arrow, from: from[0]!, to: to[0]!, right }
  })
  if (arrowCards.some((link) => !link)) return

  const widths = cards.map(({ bounds, above, below }) => Math.max(bounds.width, ...[...above, ...below].map(({ width }) => width)))
  const room = panel.width - 64 - widths.reduce((total, width) => total + width, 0)
  const gap = Math.min(112, room / (cards.length - 1))
  if (gap < 40) return
  let x = panel.x + 32
  let y = panel.y + 32
  const changes = new Map<string, Element>()
  const columns = new Map<string, number>()
  const placedImages = new Map<Card, Bounds>()
  const put = (element: Element, left: number, top: number) => {
    changes.set(element.id, { ...element, x: left, y: top })
  }
  cards.forEach(({ above, below }, index) => {
    for (const caption of [...above, ...below]) columns.set(caption.id, x)
    x += widths[index]! + gap
  })
  const putCaptions = (captions: Text[]) => {
    for (const band of captionBands(captions)) {
      const perColumn = new Map<number, number>()
      for (const caption of band) {
        const left = columns.get(caption.id)!
        const top = perColumn.get(left) ?? y
        put(caption, left, top)
        perColumn.set(left, top + caption.height + 12)
      }
      y = Math.max(...perColumn.values()) + 10
    }
  }
  const headerTop = y
  for (const { above } of cards) {
    let top = headerTop
    for (const caption of above.sort((a, b) => a.y - b.y)) {
      put(caption, columns.get(caption.id)!, top)
      top += caption.height + 12
    }
    y = Math.max(y, top)
  }
  if (y > headerTop) y += 16
  x = panel.x + 32
  cards.forEach((card, index) => {
    const { bounds, images } = card
    const left = x + (widths[index]! - bounds.width) / 2
    placedImages.set(card, { ...bounds, x: left, y })
    for (const image of images) put(image, left + image.x - bounds.x, y + image.y - bounds.y)
    x += widths[index]! + gap
  })
  y += Math.max(...cards.map(({ bounds }) => bounds.height)) + 32
  putCaptions(cards.flatMap(({ below }) => below))
  const boxes = members.filter((element) => element.type !== 'line' && element.type !== 'arrow')
  for (const original of boxes) {
    const next = changes.get(original.id)
    if (!next) continue
    const box = boundsOf(next)
    if (!contains(panel, box)) return
    if (boxes.some((other) => other.id !== original.id && overlaps(box, boundsOf(changes.get(other.id) ?? other)) &&
      !overlaps(boundsOf(original), boundsOf(other)))) return
  }
  const groups = new Map<string, Element>()
  for (const member of members) {
    for (const id of member.groupIds) {
      const anchor = groups.get(id)
      if (!anchor) {
        groups.set(id, member)
        continue
      }
      const next = changes.get(member.id) ?? member
      const nextAnchor = changes.get(anchor.id) ?? anchor
      if (Math.abs(next.x - member.x - nextAnchor.x + anchor.x) > 0.01 ||
        Math.abs(next.y - member.y - nextAnchor.y + anchor.y) > 0.01) return
    }
  }
  for (const link of arrowCards) {
    const { arrow, from, to, right } = link!
    const a = placedImages.get(from)!, b = placedImages.get(to)!
    const height = Math.min(a.height, b.height)
    const start = right ? a.x + a.width + 12 : a.x - 12
    const end = right ? b.x - 12 : b.x + b.width + 12
    const y = a.y + height / 2
    if ((end - start) * (right ? 1 : -1) < 16 || boxes.some((item) =>
      segments([{ x: start, y }, { x: end, y }]).some((leg) => insideBounds(leg, boundsOf(changes.get(item.id) ?? item))))) return
    changes.set(arrow.id, { ...arrow, x: start, y, width: Math.abs(end - start), height: 0,
      points: [[0, 0], [end - start, 0]] as unknown as Arrow['points'] })
  }
  return [...changes.values()].filter((element) => {
    const original = members.find(({ id }) => id === element.id)!
    return Math.abs(original.x - element.x) > 0.01 || Math.abs(original.y - element.y) > 0.01 ||
      original.width !== element.width || original.height !== element.height
  })
}

/** Imported walkthroughs often use plain bordered rectangles with screenshots and free text. */
export function planDrawingStoryboard(elements: readonly Element[], selected?: ReadonlySet<string>) {
  const changed: Element[] = []
  let sections = 0
  const outlines = elements.filter((element) => element.type === 'rectangle' && !element.angle && !element.frameId &&
    !element.groupIds.length && !element.boundElements?.length && element.backgroundColor === 'transparent')
  for (const outline of outlines) {
    if (selected && !selected.has(outline.id)) continue
    const box = boundsOf(outline)
    const inside = elements.filter((element) => element !== outline && contains(box, boundsOf(element)))
    if (inside.filter(({ type }) => type === 'image').length < 2 ||
        outlines.some((other) => other !== outline && overlaps(box, boundsOf(other)))) continue
    const parts = elements.filter((element) => element.type === 'line' && !element.angle &&
      element.width < 4 && Math.abs(element.y - box.y) <= 16 &&
      Math.abs(element.y + element.height - box.y - box.height) <= 16)
      .map(({ x }) => x).filter((x) => x > box.x + 32 && x < box.x + box.width - 32).sort((a, b) => a - b)
    const boundaries = [box.x, ...parts, box.x + box.width]
    for (let i = 1; i < boundaries.length; i++) {
      const panel = { ...box, x: boundaries[i - 1]!, width: boundaries[i]! - boundaries[i - 1]! }
      const members = inside.filter((element) => contains(panel, boundsOf(element)))
      const memberIds = new Set(members.map(({ id }) => id))
      // Never leave a crossing arrow or a partially included group behind.
      const uncertain = elements.some((element) => {
        if (element.type === 'arrow' && !memberIds.has(element.id)) {
          const points = element.points.map(([x, y]) => translate({ x, y }, element))
          return segments(points).some((leg) => insideBounds(leg, panel))
        }
        return members.some((member) => !memberIds.has(element.id) &&
          member.groupIds.some((group) => element.groupIds.includes(group)))
      })
      if (uncertain) continue
      const result = arrangePanel(panel, members)
      if (!result?.length) continue
      changed.push(...result)
      sections++
    }
  }
  if (!changed.length) return
  return { changed, sections, skipped: 0, moved: changed.filter(({ type }) => type !== 'arrow').length,
    routed: changed.filter(({ type }) => type === 'arrow').length }
}
