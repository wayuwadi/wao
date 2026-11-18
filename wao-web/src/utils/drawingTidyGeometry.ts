import type { OrderedExcalidrawElement } from '@excalidraw/excalidraw/element/types'

export type Point = { x: number; y: number }
export type Bounds = Point & { width: number; height: number }
export type Segment = readonly [Point, Point]

export function boundsOf(element: OrderedExcalidrawElement): Bounds {
  const cos = Math.abs(Math.cos(element.angle))
  const sin = Math.abs(Math.sin(element.angle))
  const width = cos * element.width + sin * element.height
  const height = cos * element.height + sin * element.width
  return {
    x: element.x + (element.width - width) / 2,
    y: element.y + (element.height - height) / 2,
    width,
    height,
  }
}

export function unionBounds(items: readonly Bounds[]): Bounds {
  const x = Math.min(...items.map((item) => item.x))
  const y = Math.min(...items.map((item) => item.y))
  return {
    x,
    y,
    width: Math.max(...items.map((item) => item.x + item.width)) - x,
    height: Math.max(...items.map((item) => item.y + item.height)) - y,
  }
}

export function translate<T extends Point>(point: T, offset: Point): T {
  return { ...point, x: point.x + offset.x, y: point.y + offset.y }
}

export function overlaps(a: Bounds, b: Bounds) {
  return a.x < b.x + b.width && b.x < a.x + a.width &&
    a.y < b.y + b.height && b.y < a.y + a.height
}

export function contains(outer: Bounds, inner: Bounds) {
  return outer.x <= inner.x && outer.y <= inner.y &&
    outer.x + outer.width >= inner.x + inner.width &&
    outer.y + outer.height >= inner.y + inner.height
}

export const samePoint = (a: Point, b: Point) => Math.abs(a.x - b.x) < 0.001 && Math.abs(a.y - b.y) < 0.001

export function segments(points: readonly Point[]): Segment[] {
  return points.slice(1).map((point, index) => [points[index]!, point])
}

function side(a: Point, b: Point, c: Point) {
  return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
}

// Shared endpoints and parallel segments are not crossings.
export function crosses([a, b]: Segment, [c, d]: Segment) {
  return side(a, b, c) * side(a, b, d) < 0 && side(c, d, a) * side(c, d, b) < 0
}

export function sharedLength([a, b]: Segment, [c, d]: Segment) {
  if (Math.abs(side(a, b, c)) > 0.01 || Math.abs(side(a, b, d)) > 0.01) return 0
  const axis = Math.abs(b.x - a.x) > Math.abs(b.y - a.y) ? 'x' : 'y'
  const length = Math.abs(b[axis] - a[axis])
  if (length < 0.001) return 0
  const shared = Math.min(Math.max(a[axis], b[axis]), Math.max(c[axis], d[axis])) -
    Math.max(Math.min(a[axis], b[axis]), Math.min(c[axis], d[axis]))
  return Math.max(0, shared) * Math.hypot(b.x - a.x, b.y - a.y) / length
}

export function insideBounds([a, b]: Segment, rect: Bounds) {
  // Clip the segment against the interior of the obstacle (touching the outline is fine).
  let first = 0
  let last = 1
  for (const [axis, size] of [['x', 'width'], ['y', 'height']] as const) {
    const min = rect[axis] + 0.01
    const max = rect[axis] + rect[size] - 0.01
    if (min >= max) return false
    const delta = b[axis] - a[axis]
    if (Math.abs(delta) < 0.001) {
      if (a[axis] <= min || a[axis] >= max) return false
      continue
    }
    const u = (min - a[axis]) / delta
    const v = (max - a[axis]) / delta
    first = Math.max(first, Math.min(u, v))
    last = Math.min(last, Math.max(u, v))
    if (first >= last) return false
  }
  return true
}
