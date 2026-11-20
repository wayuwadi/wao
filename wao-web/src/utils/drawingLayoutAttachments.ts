import type { OrderedExcalidrawElement } from '@excalidraw/excalidraw/element/types'
import { boundsOf, contains, insideBounds, segments, type Point } from './drawingTidyGeometry'

type Element = OrderedExcalidrawElement
type Text = Extract<Element, { type: 'text' }>
type Arrow = Extract<Element, { type: 'arrow' }>
export type FloatingLabel = { text: Text; fraction: number; side: number; distance: number }

const freeText = (element: Element): element is Text => element.type === 'text' && !element.containerId
const center = (element: Element) => {
  const { x, y, width, height } = boundsOf(element)
  return { x: x + width / 2, y: y + height / 2 }
}

export function findDrawingAttachments(live: readonly Element[], paths: ReadonlyMap<string, Point[]>) {
  const rectangles = live.filter((element) => element.type === 'rectangle' && !element.angle && element.width && element.height)
    .sort((a, b) => a.width * a.height - b.width * b.height)
  const parents = new Map<string, string>()
  const ambiguous = new Set<string>()
  for (const element of live) {
    if (element.type === 'arrow' || element.type === 'frame' || element.type === 'magicframe' ||
      (element.type === 'text' && element.containerId)) continue
    const box = boundsOf(element)
    const matches = rectangles.filter((item) => item !== element && item.frameId === element.frameId &&
      item.width * item.height > box.width * box.height && contains(boundsOf(item), box))
    const [nearest, wider] = matches
    if (!nearest) continue
    if (wider && !contains(boundsOf(wider), boundsOf(nearest))) {
      for (const { id } of matches) ambiguous.add(id)
    } else parents.set(element.id, nearest.id)
  }
  const occupied = new Set(parents.values())
  const loose = live.filter(freeText).filter((element) => !parents.has(element.id) && !element.angle)
  const arrows = live.filter((element): element is Arrow => element.type === 'arrow' &&
    !element.boundElements?.some(({ type }) => type === 'text'))
  const labels = new Map<string, FloatingLabel[]>()
  const attached = new Set<string>()
  const ambiguousArrows = new Set<string>()
  for (const text of loose) {
    const pos = center(text)
    const matches = arrows.flatMap((arrow) => {
      if (arrow.frameId !== text.frameId ||
          text.strokeColor && arrow.strokeColor && text.strokeColor !== arrow.strokeColor) return []
      const legs = pathLegs(paths.get(arrow.id)!)
      const total = legs.reduce((sum, { length }) => sum + length, 0)
      const candidates = []
      let previous = 0
      for (const { a, b, length } of legs) {
        const dx = b.x - a.x, dy = b.y - a.y
        const along = ((pos.x - a.x) * dx + (pos.y - a.y) * dy) / length
        const fraction = (previous + along) / total
        previous += length
        if (along < 0 || along > length || fraction < 0.16 || fraction > 0.84) continue
        const distance = ((pos.y - a.y) * dx - (pos.x - a.x) * dy) / length
        const radius = (text.width * Math.abs(dy) + text.height * Math.abs(dx)) / (2 * length)
        const gap = Math.abs(distance) - radius
        if (gap <= Math.min(30, Math.max(14, text.fontSize * 1.3 || 14)) &&
            !insideBounds([a, b], boundsOf(text))) {
          candidates.push({ arrow, gap: Math.abs(gap), text, fraction,
            side: Math.sign(distance) || -1, distance: Math.abs(distance) })
        }
      }
      const closest = candidates.sort((a, b) => a.gap - b.gap)[0]
      return closest ? [closest] : []
    }).sort((a, b) => a.gap - b.gap)
    const [first, second] = matches
    if (!first) continue
    if (second && second.gap <= first.gap + 6) {
      for (const item of matches) if (item.gap <= first.gap + 6) ambiguousArrows.add(item.arrow.id)
      attached.add(text.id)
      continue
    }
    const siblings = labels.get(first.arrow.id) ?? []
    siblings.push(first)
    labels.set(first.arrow.id, siblings)
    attached.add(text.id)
  }
  for (const text of loose) {
    if (attached.has(text.id)) continue
    const candidates = rectangles.filter((rect) => {
      if (rect.frameId !== text.frameId || !occupied.has(rect.id) || text.width > rect.width) return false
      const gap = Math.max(rect.y - text.y - text.height, text.y - rect.y - rect.height)
      const left = Math.max(rect.x, text.x)
      const right = Math.min(rect.x + rect.width, text.x + text.width)
      return gap >= 0 && gap <= Math.min(80, Math.max(30, text.fontSize * 2.6 || 45)) &&
        right - left >= text.width * 0.88
    })
    if (candidates.length === 1) parents.set(text.id, candidates[0]!.id)
    if (candidates.length > 1) for (const { id } of candidates) ambiguous.add(id)
  }
  return { parents, labels, occupied, ambiguous, ambiguousArrows }
}

function pathLegs(path: Point[]) {
  return segments(path).map(([a, b]) => ({ a, b, length: Math.hypot(b.x - a.x, b.y - a.y) }))
    .filter(({ length }) => length > 0)
}

export function floatingLabelPositions(label: FloatingLabel, path: Point[]) {
  const legs = pathLegs(path)
  const distance = legs.reduce((sum, { length }) => sum + length, 0) * label.fraction
  const candidates: Text[] = []
  const { text } = label
  let previous = 0
  for (const { a, b, length } of legs) {
    if (previous + length < distance) {
      previous += length
      continue
    }
    const ratio = (distance - previous) / length
    const nx = (a.y - b.y) / length, ny = (b.x - a.x) / length
    const radius = (text.width * Math.abs(nx) + text.height * Math.abs(ny)) / 2
    const offset = Math.max(label.distance, radius + 6) * label.side
    candidates.push({ ...text,
      x: a.x + (b.x - a.x) * ratio + nx * offset - text.width / 2,
      y: a.y + (b.y - a.y) * ratio + ny * offset - text.height / 2 })
    break
  }
  const first = path[0], last = path[path.length - 1]
  if (!first || !last || !legs.length) return candidates
  const dx = last.x - first.x, dy = last.y - first.y
  if (Math.abs(dx) >= Math.abs(dy)) {
    const below = label.side * Math.sign(dx) > 0
    const y = below ? Math.max(...path.map(({ y }) => y)) + 6 : Math.min(...path.map(({ y }) => y)) - text.height - 6
    candidates.push({ ...text, x: (first.x + last.x - text.width) / 2, y })
  } else {
    const right = label.side * Math.sign(dy) < 0
    const x = right ? Math.max(...path.map(({ x }) => x)) + 6 : Math.min(...path.map(({ x }) => x)) - text.width - 6
    candidates.push({ ...text, x, y: (first.y + last.y - text.height) / 2 })
  }
  return candidates
}
