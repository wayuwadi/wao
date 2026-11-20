import type { OrderedExcalidrawElement } from '@excalidraw/excalidraw/element/types'
import { orthogonalRoute, routeDrawingConnection } from './drawingOrthogonalRouter'
import { findDrawingAttachments, floatingLabelPositions } from './drawingLayoutAttachments'
import { flowLane, type FlowLane } from './drawingFlowLayout'
import { planDrawingStoryboard } from './drawingStoryboardLayout'
import type { DrawingAiGuidance } from './drawingAiLayoutModel'
import { planDrawingLayout, type DrawingLayout } from './drawingTidyLayout'
import {
  boundsOf, contains, crosses, insideBounds, overlaps, samePoint, segments, sharedLength, translate, unionBounds,
  type Bounds, type Point,
} from './drawingTidyGeometry'

type Element = OrderedExcalidrawElement
type Arrow = Extract<Element, { type: 'arrow' }>
type Shape = Extract<Element, { type: 'rectangle' | 'diamond' | 'ellipse' | 'image' }>
export type DrawingTidyMode = 'light' | 'route' | 'layout' | 'ai'
export type DrawingTidyRequest = {
  mode: DrawingTidyMode
  scope: 'selection' | 'all'
  layout?: DrawingLayout
  expandFrames?: boolean
  guidance?: DrawingAiGuidance
}

export type DrawingTidyInput = {
  elements: readonly Element[]
  selectedIds: readonly string[]
  request: DrawingTidyRequest
}

export type DrawingTidyResult = {
  changed: Element[]
  moved: number
  routed: number
  skipped: number
  lanes?: FlowLane[]
  mainCount?: number
  sections?: number
  images?: number
  captions?: number
  canExpandFrames?: boolean
  crossings?: { before: number; after: number }
  limited?: 'lanes' | 'space' | 'wiring'
}

type Unit = { id: string; elements: Element[]; bounds: Bounds; frameId: string | null; inScope: boolean; movable: boolean }
type Endpoints = readonly [string | undefined, string | undefined]
const origin = { x: 0, y: 0 }
const isFrame = (element: Element) => element.type === 'frame' || element.type === 'magicframe'
const isShape = (element: Element): element is Shape =>
  element.type === 'rectangle' || element.type === 'diamond' || element.type === 'ellipse' || element.type === 'image'
const isArrow = (element: Element): element is Arrow => element.type === 'arrow'
const isObstacle = (element: Element) => !isFrame(element) && element.type !== 'arrow' &&
  element.type !== 'line' && element.type !== 'freedraw' && !(element.type === 'text' && element.containerId)
const idOfGroup = (element: Element) => element.groupIds[element.groupIds.length - 1]
const endpointIds = (arrow: Arrow) => [arrow.startBinding?.elementId, arrow.endBinding?.elementId] as const

function nearbyShape(point: Point, shapes: Shape[], frameId: string | null) {
  const candidates = shapes.flatMap((shape) => {
    if (shape.angle || (frameId && shape.frameId !== frameId)) return []
    const x = point.x - shape.x, y = point.y - shape.y
    const dx = Math.max(0, -x, x - shape.width), dy = Math.max(0, -y, y - shape.height)
    const distance = dx || dy ? Math.hypot(dx, dy) : Math.min(x, y, shape.width - x, shape.height - y)
    return distance <= 12 ? [{ id: shape.id, distance }] : []
  }).sort((a, b) => a.distance - b.distance)
  const best = candidates[0]
  return best && (!candidates[1] || candidates[1].distance > best.distance + 4) ? best.id : undefined
}

function unitsFor(
  elements: readonly Element[], selected: ReadonlySet<string>, all: boolean, endpoints: Map<string, Endpoints>,
  attachments: ReturnType<typeof findDrawingAttachments> | undefined,
  includeLoose = false,
) {
  const groups = new Map<string, Element[]>()
  const groupByElement = new Map(elements.map((element) =>
    [element.id, idOfGroup(element) ? `group:${idOfGroup(element)}` : `element:${element.id}`]))
  const merged = new Map<string, string>()
  const root = (id: string): string => {
    let current = id
    while (merged.has(current)) current = merged.get(current)!
    return current
  }
  for (const [child, parent] of attachments?.parents ?? []) {
    const a = root(groupByElement.get(child)!), b = root(groupByElement.get(parent)!)
    if (a !== b) merged.set(a, b)
  }
  for (const [id, group] of groupByElement) groupByElement.set(id, root(group))
  const locked = new Set(elements.filter((element) => element.locked).map((element) => element.id))
  for (const element of elements) {
    const group = groupByElement.get(element.id)!
    const members = groups.get(group) ?? []
    members.push(element)
    groups.set(group, members)
  }
  const pinned = new Set(attachments?.ambiguous)
  if (includeLoose) {
    const anchors = elements.flatMap((element) => {
      if (element.type !== 'line') return []
      const points = arrowPoints(element)
      return points.length > 1 ? [points[0]!, points[points.length - 1]!] : []
    })
    for (const element of elements.filter(isObstacle)) {
      const { x, y, width, height } = boundsOf(element)
      if (anchors.some((point) => point.x >= x - 10 && point.x <= x + width + 10 &&
        point.y >= y - 10 && point.y <= y + height + 10)) pinned.add(element.id)
    }
  }
  for (const arrow of elements.filter(isArrow)) {
    if (!arrow.locked && !hasFixedSegments(arrow) && !arrow.angle &&
      !arrow.boundElements?.some(({ id }) => locked.has(id)) &&
      !attachments?.labels.get(arrow.id)?.some(({ text }) => text.locked) &&
      !attachments?.ambiguousArrows.has(arrow.id)) continue
    for (const id of endpoints.get(arrow.id)!) if (id) pinned.add(id)
  }
  const units = new Map<string, Unit>()
  const captions = new Set([...attachments?.labels.values() ?? []].flat().map(({ text }) => text.id))
  const candidates = elements.filter((element) => isShape(element) ||
    (includeLoose && element.type === 'text' && !element.containerId && !captions.has(element.id)))
  for (const element of candidates) {
    const id = groupByElement.get(element.id)!
    if (units.has(id)) continue
    const members = groups.get(id)!
    const shapes = members.filter(isShape)
    const boxes = members.filter((member) => isShape(member) || (member.type === 'text' && !member.containerId))
    const memberIds = new Set(boxes.map(({ id }) => id))
    const inScope = all || members.some((member) =>
      selected.has(member.id) || (member.frameId !== null && selected.has(member.frameId)),
    ) || shapes.some((shape) => shape.boundElements?.some((item) => item.type === 'text' && selected.has(item.id)))
    const movable = inScope && !members.some((member) => member.locked || pinned.has(member.id) ||
      member.boundElements?.some(({ type, id }) => type === 'text' && locked.has(id)) ||
      (isArrow(member) && endpoints.get(member.id)!.some((id) => !id || !memberIds.has(id))) ||
      member.frameId !== element.frameId || (!isShape(member) && !isArrow(member) && member.type !== 'text'))
    units.set(id, { id, elements: boxes, bounds: unionBounds(boxes.map(boundsOf)), frameId: element.frameId, inScope, movable })
  }
  return [...units.values()]
}

function groupRows(units: readonly Unit[], axis: 'x' | 'y') {
  const other = axis === 'x' ? 'y' : 'x'
  const size = axis === 'x' ? 'width' : 'height'
  const otherSize = axis === 'x' ? 'height' : 'width'
  const center = (unit: Unit) => unit.bounds[other] + unit.bounds[otherSize] / 2
  const remaining = [...units].sort((a, b) => center(a) - center(b))
  const result = new Map<string, Point>()
  while (remaining.length) {
    const first = remaining.shift()!
    const row = [first]
    while (remaining[0] && Math.abs(center(remaining[0]) - center(first)) <= 24) row.push(remaining.shift()!)
    if (row.length < 2) continue
    row.sort((a, b) => a.bounds[axis] - b.bounds[axis])
    const anchors = [0, 0.5, 1].map((factor) => {
      const values = row.map((unit) => unit.bounds[other] + unit.bounds[otherSize] * factor).sort((a, b) => a - b)
      const median = values[Math.floor(values.length / 2)]!
      return { factor, median, error: values.reduce((sum, value) => sum + Math.abs(value - median), 0) }
    })
    const { factor, median } = anchors.sort((a, b) => a.error - b.error)[0]!
    const start = row[0]!.bounds[axis]
    const last = row[row.length - 1]!.bounds
    const gap = (last[axis] + last[size] - start - row.reduce((sum, unit) => sum + unit.bounds[size], 0)) / (row.length - 1)
    let next = start
    const spaced = row.map((unit) => {
      const position = next
      next += unit.bounds[size] + gap
      return position - unit.bounds[axis]
    })
    const distribute = row.length >= 3 && gap >= 8 && spaced.every((delta) => Math.abs(delta) <= 24)
    row.forEach((unit, index) => {
      const delta = median - (unit.bounds[other] + unit.bounds[otherSize] * factor)
      if (Math.abs(delta) > 24) return
      result.set(unit.id, { x: 0, y: 0, [other]: delta, [axis]: distribute ? spaced[index]! : 0 })
    })
  }
  return result
}

function lightOffsets(units: Unit[]) {
  const changed = new Map<string, Point>()
  for (const axis of ['x', 'y'] as const) {
    const movedUnits = units.map((unit) => ({ ...unit, bounds: translate(unit.bounds, changed.get(unit.id) ?? origin) }))
    for (const [id, offset] of groupRows(movedUnits, axis)) {
      const move = translate(changed.get(id) ?? origin, offset)
      if (Math.hypot(move.x, move.y) <= 36) changed.set(id, move)
    }
  }
  return changed
}

function layoutLinks(
  units: Unit[], endpoints: Map<string, Endpoints>, labels: Map<string, string>, arrows: Map<string, Arrow>,
  floating: ReturnType<typeof findDrawingAttachments>['labels'] | undefined,
) {
  const unitIds = new Map(units.flatMap((unit) => unit.elements.map((element) => [element.id, unit.id] as const)))
  return [...endpoints].flatMap(([id, [from, to]]) => {
    const arrow = arrows.get(id)!
    if (arrow.startArrowhead && arrow.endArrowhead) return []
    const reversed = arrow.startArrowhead && !arrow.endArrowhead
    const sourceId = reversed ? to : from
    const targetId = reversed ? from : to
    const source = sourceId && unitIds.get(sourceId)
    const target = targetId && unitIds.get(targetId)
    if (!source || !target || source === target) return []
    const text = floating?.get(id)
    const captionSize = text?.length ? {
      width: Math.max(...text.map(({ text }) => text.width)),
      height: Math.max(...text.map(({ text }) => text.height)),
    } : undefined
    return [{ id, source, target, label: labels.get(id), captionSize }]
  })
}

function hasFixedSegments(arrow: Arrow) {
  const elbow = arrow as Arrow & {
    fixedSegments?: readonly unknown[] | null
    startIsSpecial?: boolean | null
    endIsSpecial?: boolean | null
  }
  return !!(elbow.fixedSegments?.length || elbow.startIsSpecial || elbow.endIsSpecial)
}

function arrowPoints(arrow: Pick<Arrow, 'angle' | 'points' | 'x' | 'y'>) {
  const points = arrow.points.map(([x, y]) => ({ x: arrow.x + x, y: arrow.y + y }))
  if (!arrow.angle || !points.length) return points
  const box = unionBounds(points.map((point) => ({ ...point, width: 0, height: 0 })))
  const center = { x: box.x + box.width / 2, y: box.y + box.height / 2 }
  const cos = Math.cos(arrow.angle), sin = Math.sin(arrow.angle)
  return points.map(({ x, y }) => ({
    x: center.x + (x - center.x) * cos - (y - center.y) * sin,
    y: center.y + (x - center.x) * sin + (y - center.y) * cos,
  }))
}

function relativePointsKey(points: Arrow['points']) {
  return points.map(([x, y]) => `${x},${y}`).join(';')
}

function arrowWithPath(arrow: Arrow, path: Point[], automatic: boolean): Arrow {
  const first = path[0]!
  const box = unionBounds(path.map((point) => ({ ...point, width: 0, height: 0 })))
  const points = path.map(({ x, y }) => [x - first.x, y - first.y]) as unknown as Arrow['points']
  const customData = automatic
    ? { ...arrow.customData, waoTidyRoute: relativePointsKey(points) }
    : arrow.customData
  return { ...arrow, ...first, width: box.width, height: box.height, points, customData, lastCommittedPoint: null }
}

function moveEndpoints(arrow: Arrow, offsets: ReadonlyMap<string, Point>, [from, to]: Endpoints) {
  const path = arrowPoints(arrow)
  const start = (from && offsets.get(from)) || origin
  const end = (to && offsets.get(to)) || origin
  if (path.length < 2 || (samePoint(start, origin) && samePoint(end, origin))) return arrow
  if (samePoint(start, end)) return translate(arrow, start)
  const moved = [...path]
  moved[0] = translate(path[0]!, start)
  moved[moved.length - 1] = translate(path[path.length - 1]!, end)
  if (arrow.elbowed) {
    // Leave the first and last legs orthogonal when their bound shapes move independently.
    if (path.length > 2) {
      const firstAxis = path[0]!.x === path[1]!.x ? 'x' : 'y'
      const lastAxis = path[path.length - 1]!.x === path[path.length - 2]!.x ? 'x' : 'y'
      moved[1] = { ...moved[1]!, [firstAxis]: moved[0]![firstAxis] }
      moved[moved.length - 2] = { ...moved[moved.length - 2]!, [lastAxis]: moved[moved.length - 1]![lastAxis] }
    } else if (moved[0]!.x !== moved[1]!.x && moved[0]!.y !== moved[1]!.y) {
      moved.splice(1, 0, { x: moved[1]!.x, y: moved[0]!.y })
    }
  }
  return arrowWithPath(arrow, moved, arrow.customData?.waoTidyRoute === relativePointsKey(arrow.points))
}

function collisionsOf(legs: ReturnType<typeof segments>, obstacles: ReadonlyMap<string, Bounds>, [start, end]: Endpoints) {
  const collisions = new Set<string>()
  for (const [id, box] of obstacles) {
    if (legs.some((leg, index) => (id !== start || index > 0) &&
      (id !== end || index < legs.length - 1) && insideBounds(leg, box))) collisions.add(id)
  }
  return collisions
}

function score(arrow: Arrow, paths: ReadonlyMap<string, Point[]>, obstacles: ReadonlyMap<string, Bounds>, endpoints: Endpoints) {
  const legs = segments(paths.get(arrow.id) ?? [])
  const collisions = collisionsOf(legs, obstacles, endpoints)
  let crossings = 0
  let shared = 0
  for (const [id, other] of paths) {
    if (id === arrow.id) continue
    for (const leg of segments(other)) {
      for (const item of legs) {
        if (crosses(item, leg)) crossings++
        shared += sharedLength(item, leg)
      }
    }
  }
  return { collisions, crossings, shared, length: legs.reduce((sum, [a, b]) => sum + Math.hypot(a.x - b.x, a.y - b.y), 0) }
}

function perimeterPaths(path: Point[], obstacles: ReadonlyMap<string, Bounds>) {
  const first = path[0]!, last = path[path.length - 1]!
  const envelope = unionBounds(path.map((point) => ({ ...point, width: 0, height: 0 })))
  const nearby = { x: envelope.x - 32, y: envelope.y - 32, width: envelope.width + 64, height: envelope.height + 64 }
  const bounds = unionBounds([envelope, ...[...obstacles.values()].filter((box) => overlaps(nearby, box))])
  const xs = [bounds.x - 18, bounds.x + bounds.width + 18]
  const ys = [bounds.y - 18, bounds.y + bounds.height + 18]
  const candidates: Point[][] = []
  for (const x of xs) {
    for (const y of ys) {
      const oppositeY = ys.find((value) => value !== y)!
      const oppositeX = xs.find((value) => value !== x)!
      candidates.push([first, { x: first.x, y }, { x, y }, { x, y: oppositeY }, { x: last.x, y: oppositeY }, last])
      candidates.push([first, { x, y: first.y }, { x, y }, { x: oppositeX, y }, { x: oppositeX, y: last.y }, last])
    }
  }
  return candidates
}

function outsideFeedback(
  arrow: Arrow, [from, to]: Endpoints, paths: Map<string, Point[]>,
  obstacles: ReadonlyMap<string, Bounds>, frame?: Bounds,
) {
  const source = from && obstacles.get(from), target = to && obstacles.get(to)
  if (!source || !target || arrow.elbowed) return
  const original = paths.get(arrow.id)!
  const before = score(arrow, paths, obstacles, [from, to])
  const bounds = unionBounds([source, target])
  const near = { x: bounds.x - 48, y: bounds.y - 48, width: bounds.width + 96, height: bounds.height + 96 }
  const nearby = [...obstacles.values()].filter((box) => overlaps(near, box))
  const outer = unionBounds(nearby)
  const boxes = [...obstacles.values()]
  let chosen: Point[] | undefined
  let quality = Infinity
  for (const [axis, size] of [['x', 'width'], ['y', 'height']] as const) {
    for (const sign of [-1, 1]) {
      const edge = (box: Bounds) => box[axis] + (sign > 0 ? box[size] : 0)
      const port = (box: Bounds, gap: number) => ({
        x: box.x + box.width / 2, y: box.y + box.height / 2, [axis]: edge(box) + sign * gap,
      })
      const start = port(source, arrow.startBinding?.gap || 5)
      const end = port(target, arrow.endBinding?.gap || 5)
      const perimeter = edge(outer) + sign * 36
      const candidate = [start, { ...start, [axis]: perimeter }, { ...end, [axis]: perimeter }, end]
      const legs = segments(candidate)
      if (samePoint(candidate[1]!, candidate[2]!) ||
          frame && candidate.some((point) => !contains(frame, { ...point, width: 0, height: 0 })) ||
          legs.some((leg) => boxes.some((box) => insideBounds(leg, box)))) continue
      paths.set(arrow.id, candidate)
      const after = score(arrow, paths, obstacles, [from, to])
      const value = after.crossings * 300 + after.shared * 2 + after.length
      if (after.crossings > before.crossings || value >= quality) continue
      chosen = candidate
      quality = value
    }
  }
  paths.set(arrow.id, chosen ?? original)
  if (!chosen) return
  return arrowWithPath({
    ...arrow,
    startBinding: arrow.startBinding && { ...arrow.startBinding, focus: 0 },
    endBinding: arrow.endBinding && { ...arrow.endBinding, focus: 0 },
  }, chosen, true)
}

function chooseRoute(
  arrow: Arrow, paths: Map<string, Point[]>, obstacles: ReadonlyMap<string, Bounds>,
  endpoints: Endpoints, conservative: boolean, frame?: Bounds, feedback = false,
) {
  const original = paths.get(arrow.id)!
  const before = score(arrow, paths, obstacles, endpoints)
  if (!feedback && original.length === 2 && !before.collisions.size && !before.crossings && !before.shared) {
    return { needsImprovement: false }
  }
  const otherLines = [...paths.entries()].flatMap(([id, points]) => id === arrow.id ? [] : segments(points))
  const proposal = orthogonalRoute(original[0]!, original[original.length - 1]!, [...obstacles.values()], otherLines)
  let best = original
  let bestScore = before
  const candidates = proposal ? [proposal] : []
  if (feedback) candidates.unshift(...perimeterPaths(original, obstacles))
  else if (before.crossings && !conservative) candidates.push(...perimeterPaths(original, obstacles))
  const perimeter = feedback ? new Set(candidates.slice(0, proposal ? -1 : undefined)) : new Set<Point[]>()
  for (const candidate of candidates) {
    if (candidate.length < 2 || !samePoint(candidate[0]!, original[0]!) ||
      !samePoint(candidate[candidate.length - 1]!, original[original.length - 1]!)) continue
    if (frame && candidate.some((point) => !contains(frame, { ...point, width: 0, height: 0 }))) continue
    if (feedback && endpoints.some((id) => {
      const box = id && obstacles.get(id)
      return box && segments(candidate).some((leg) => insideBounds(leg, box))
    })) continue
    if (candidate.length === original.length && candidate.every((point, index) => samePoint(point, original[index]!))) continue
    paths.set(arrow.id, candidate)
    const next = score(arrow, paths, obstacles, endpoints)
    if (conservative && next.length > before.length * 1.5 + 32) continue
    if ([...next.collisions].some((id) => !before.collisions.has(id)) || next.crossings > before.crossings) continue
    const usePerimeter = perimeter.has(candidate) && !perimeter.has(best) && !next.collisions.size
    if (perimeter.has(best) && !perimeter.has(candidate) && next.crossings >= bestScore.crossings) continue
    const improvement = usePerimeter ? 1 : bestScore.collisions.size - next.collisions.size ||
      bestScore.crossings - next.crossings || (Math.abs(bestScore.shared - next.shared) > 1 ? bestScore.shared - next.shared : 0) ||
      ((bestScore.length + best.length * 8) - (next.length + candidate.length * 8) > 1 ? 1 : 0)
    if (improvement <= 0) continue
    best = candidate
    bestScore = next
  }
  paths.set(arrow.id, best)
  return { path: best === original ? undefined : best, needsImprovement: !!(before.collisions.size || before.crossings || before.shared) }
}

function alignArrowPorts(
  arrow: Arrow, ids: Endpoints, paths: Map<string, Point[]>, obstacles: ReadonlyMap<string, Bounds>,
  frame?: Bounds, smart = false,
) {
  const [from, to] = ids
  const a = from && obstacles.get(from), b = to && obstacles.get(to)
  if (!a || !b || arrow.elbowed) return
  const startGap = arrow.startBinding?.gap ?? 5, endGap = arrow.endBinding?.gap ?? 5
  const xGap = Math.max(b.x - a.x - a.width, a.x - b.x - b.width)
  const yGap = Math.max(b.y - a.y - a.height, a.y - b.y - b.height)
  if (Math.max(xGap, yGap) <= startGap + endGap) return
  const horizontal = xGap >= yGap
  const axis = horizontal ? 'x' : 'y', size = horizontal ? 'width' : 'height'
  const forward = a[axis] < b[axis]
  const start = { x: a.x + a.width / 2, y: a.y + a.height / 2 }
  const end = { x: b.x + b.width / 2, y: b.y + b.height / 2 }
  start[axis] = forward ? a[axis] + a[size] + startGap : a[axis] - startGap
  end[axis] = forward ? b[axis] - endGap : b[axis] + b[size] + endGap

  const original = paths.get(arrow.id)!
  if (samePoint(start, original[0]!) && samePoint(end, original[original.length - 1]!)) return
  const before = score(arrow, paths, obstacles, ids)
  const lines = [...paths.entries()].flatMap(([id, points]) => id === arrow.id ? [] : segments(points))
  const clearance = Math.min(18, (Math.abs(end[axis] - start[axis])) / 4)
  const outward = forward ? clearance : -clearance
  const startStub = { ...start, [axis]: start[axis] + outward }
  const endStub = { ...end, [axis]: end[axis] - outward }
  const boxes = [...obstacles.values()]
  const route = smart ? orthogonalRoute(startStub, endStub, boxes, lines) : orthogonalRoute(start, end, boxes, lines)
  const path = smart && route ? [start, ...route, end].filter((point, i, points) => {
    if (i === 0 || i === points.length - 1) return true
    const a = points[i - 1]!, b = points[i + 1]!
    return !(a.x === point.x && point.x === b.x || a.y === point.y && point.y === b.y)
  }) : route
  if (!path || path.length < 2 || (frame && path.some((point) => !contains(frame, { ...point, width: 0, height: 0 })))) return
  paths.set(arrow.id, path)
  const after = score(arrow, paths, obstacles, ids)
  if ([...after.collisions].some((id) => !before.collisions.has(id)) || after.crossings > before.crossings ||
    after.length > before.length * (smart ? 1.8 : 1.25) + 24) {
    paths.set(arrow.id, original)
    return
  }
  return arrowWithPath({
    ...arrow,
    startBinding: arrow.startBinding && { ...arrow.startBinding, focus: 0 },
    endBinding: arrow.endBinding && { ...arrow.endBinding, focus: 0 },
  }, path, true)
}

function arrowCrossings(paths: ReadonlyMap<string, Point[]>) {
  const lines = [...paths.values()].map(segments)
  let intersections = 0
  lines.forEach((line, index) => {
    for (let other = 0; other < index; other++) {
      for (const leg of line) {
        for (const segment of lines[other]!) if (crosses(leg, segment)) intersections++
      }
    }
  })
  return intersections
}

function permitsMove(
  unit: Unit, moved: Bounds, bounds: ReadonlyMap<string, Bounds>,
  before: ReadonlyMap<string, Bounds>, frame: Element | undefined,
) {
  if (frame && !contains(boundsOf(frame), moved)) return false
  const own = new Set(unit.elements.map((element) => element.id))
  for (const [id, obstacle] of bounds) {
    if (!own.has(id) && overlaps(moved, obstacle) && !overlaps(unit.bounds, before.get(id) ?? obstacle)) return false
  }
  return true
}

function storyboardEndpoint(point: Point, candidates: readonly Element[]) {
  const nearby = candidates.flatMap((element) => {
    const box = boundsOf(element)
    const dx = Math.max(box.x - point.x, point.x - box.x - box.width, 0)
    const dy = Math.max(box.y - point.y, point.y - box.y - box.height, 0)
    const distance = dx || dy ? Math.hypot(dx, dy) :
      Math.min(point.x - box.x, box.x + box.width - point.x, point.y - box.y, box.y + box.height - point.y)
    return distance <= 48 ? [{ element, distance }] : []
  }).sort((a, b) => a.distance - b.distance)
  const first = nearby[0], second = nearby[1]
  return first && (!second || second.distance > first.distance + 6) ? first.element : undefined
}

function storyboardPort(point: Point, original: Element | undefined, changed: ReadonlyMap<string, Element>) {
  const next = original && changed.get(original.id)
  if (!original || !next) return point
  const old = boundsOf(original), box = boundsOf(next)
  const ratio = (offset: number, size: number) => Math.max(0.08, Math.min(0.92, offset / size))
  const projected = {
    x: box.x + ratio(point.x - old.x, old.width) * box.width,
    y: box.y + ratio(point.y - old.y, old.height) * box.height,
  }
  const edges = [
    { distance: Math.abs(point.x - old.x), point: { ...projected, x: box.x - 6 } },
    { distance: Math.abs(point.x - old.x - old.width), point: { ...projected, x: box.x + box.width + 6 } },
    { distance: Math.abs(point.y - old.y), point: { ...projected, y: box.y - 6 } },
    { distance: Math.abs(point.y - old.y - old.height), point: { ...projected, y: box.y + box.height + 6 } },
  ]
  return edges.sort((a, b) => a.distance - b.distance)[0]!.point
}

function arrangeStoryboard(
  live: readonly Element[], selected: ReadonlySet<string> | undefined,
): DrawingTidyResult | undefined {
  const arrows = live.filter(isArrow)
  const candidates = live.filter((element) => !element.angle && isShape(element))
  const byId = new Map(live.map((element) => [element.id, element]))
  const pinned = new Set<string>()
  const connectors = arrows.map((arrow) => {
    const path = arrowPoints(arrow)
    const [from, to] = endpointIds(arrow)
    const start = from ? byId.get(from) : (path[0] && storyboardEndpoint(path[0], candidates))
    const last = path[path.length - 1]
    const end = to ? byId.get(to) : (last && storyboardEndpoint(last, candidates))
    const fixed = arrow.elbowed || arrow.locked || arrow.angle || hasFixedSegments(arrow) ||
      (path.length > 2 && arrow.customData?.waoTidyRoute !== relativePointsKey(arrow.points)) ||
      arrow.boundElements?.some(({ id }) => byId.get(id)?.locked)
    if (fixed) for (const endpoint of [start, end]) if (endpoint) pinned.add(endpoint.id)
    return { arrow, path, start, end, fixed }
  })

  for (let attempt = 0; attempt < 3; attempt++) {
    const layout = planDrawingStoryboard(live, selected, pinned)
    if (!layout) return
    const { changes, panelIds, sections } = layout
    if (!changes.size) {
      return { changed: [], moved: 0, routed: 0, skipped: pinned.size, limited: layout.aligned ? undefined : 'space' }
    }
    const obstacles = live.filter((element) => isObstacle(element) && !panelIds.has(element.id))
    const before = new Map(obstacles.map((element) => [element.id, boundsOf(element)]))
    const after = new Map(obstacles.map((element) => [element.id, boundsOf(changes.get(element.id) ?? element)]))
    const paths = new Map(connectors.map(({ arrow, path }) => [arrow.id, path]))
    let routed = 0, retry = false
    for (const { arrow, path, start, end, fixed } of connectors) {
      if (path.length < 2) continue
      const originalStart = path[0]!, originalEnd = path[path.length - 1]!
      const ids = [start?.id, end?.id] as const
      const a = storyboardPort(originalStart, start, changes)
      const b = storyboardPort(originalEnd, end, changes)
      const movedPort = !samePoint(a, originalStart) || !samePoint(b, originalEnd)
      const knownHits = collisionsOf(segments(path), before, ids)
      const introduced = (route: Point[]) => [...collisionsOf(segments(route), after, ids)]
        .filter((id) => !knownHits.has(id))
      const newHits = introduced(path)
      if (!movedPort && !newHits.length) continue
      if (!fixed && !samePoint(a, b)) {
        const otherLines = [...paths].flatMap(([id, points]) => id === arrow.id ? [] : segments(points))
        const route = orthogonalRoute(a, b, [...after.values()], otherLines)
        if (route && route.length > 1 && !introduced(route).length) {
          changes.set(arrow.id, arrowWithPath(arrow, route, true))
          paths.set(arrow.id, route)
          routed++
          continue
        }
      }
      for (const id of [...ids, ...newHits]) {
        if (id && changes.has(id)) {
          pinned.add(id)
          retry = true
        }
      }
    }
    if (retry) continue
    const changed = [...changes.values()]
    const images = changed.filter(({ type }) => type === 'image').length
    const captions = changed.filter(({ type }) => type === 'text').length
    return { changed, images, captions, sections, moved: images + captions, routed, skipped: pinned.size }
  }
  return { changed: [], moved: 0, routed: 0, skipped: pinned.size, limited: 'wiring' }
}

export function tidyDrawing(input: DrawingTidyInput): Promise<DrawingTidyResult> {
  return runTidy(input, input.request.mode === 'light', new Set())
}

function inspectDrawing({ elements, selectedIds, request }: DrawingTidyInput) {
  const live = elements.filter((element) => !element.isDeleted)
  const selected = new Set(selectedIds)
  const elementById = new Map(live.map((element) => [element.id, element]))
  const arrows = live.filter(isArrow)
  const arrowsById = new Map(arrows.map((arrow) => [arrow.id, arrow]))
  const labels = new Map(live.flatMap((element) =>
    element.type === 'text' && element.containerId ? [[element.containerId, element.originalText || element.text] as const] : []))
  const all = request.scope === 'all'
  const originalPaths = new Map(arrows.map((arrow) => [arrow.id, arrowPoints(arrow)]))
  const shapes = request.mode !== 'route' ? live.filter(isShape) : []
  const looseTexts = live.filter((element) =>
    element.type === 'text' && !element.containerId && !element.angle)
  const attachments = request.mode === 'route' ? undefined : findDrawingAttachments(live, originalPaths)
  const endpointAt = (point: Point, frameId: string | null) => {
    const close = nearbyShape(point, shapes, frameId)
    if (close) return close
    const enclosing = shapes.filter((shape) => attachments?.occupied.has(shape.id) &&
      shape.frameId === frameId && contains(boundsOf(shape), { ...point, width: 0, height: 0 }))
      .sort((a, b) => a.width * a.height - b.width * b.height)[0]
    if (enclosing) return enclosing.id
    const text = looseTexts.filter((element) => {
      const box = boundsOf(element)
      return element.frameId === frameId && point.y > box.y - 8 && point.y < box.y + box.height + 8 &&
        Math.min(Math.abs(point.x - box.x), Math.abs(point.x - box.x - box.width)) <= 24
    })
    return text.length === 1 ? text[0]!.id : undefined
  }
  const endpoints = new Map(arrows.map((arrow): [string, Endpoints] => {
    const points = originalPaths.get(arrow.id)!
    const first = points[0], last = points.length >= 2 ? points[points.length - 1] : undefined
    const [from, to] = endpointIds(arrow)
    if (!first || !last || request.mode === 'route') return [arrow.id, [from, to]]
    return [arrow.id, [from ?? endpointAt(first, arrow.frameId), to ?? endpointAt(last, arrow.frameId)]]
  }))
  const units = unitsFor(live, selected, all, endpoints, attachments, request.mode === 'ai' || !!request.guidance)
  return { live, selected, elementById, arrows, arrowsById, labels, all, originalPaths, attachments, endpoints, units }
}

export function describeDrawingForAi(input: DrawingTidyInput) {
  const { units, labels, endpoints, arrowsById, attachments } = inspectDrawing(input)
  const movable = units.filter((unit) => unit.movable)
  if (movable.length < 2) throw new Error('AI 整理需要至少两个可移动的图形、图片或文字。')
  if (movable.length > 140) throw new Error('画板内容较多，请分批框选后整理。')
  const nodes = movable.map(({ id, bounds, elements, frameId }) => ({
    id, frame: frameId, bounds,
    items: elements.slice(0, 12).map((element) => ({
      type: element.type,
      text: (element.type === 'text' ? element.originalText || element.text : labels.get(element.id))?.slice(0, 160),
    })),
  }))
  const links = layoutLinks(movable, endpoints, labels, arrowsById, attachments?.labels).flatMap((link) => {
    const arrow = arrowsById.get(link.id)!
    if (!arrow.startArrowhead && !arrow.endArrowhead) return []
    return [{ id: link.id, from: link.source, to: link.target, label: link.label?.slice(0, 160) }]
  })
  return { nodes, links }
}

async function runTidy(
  input: DrawingTidyInput,
  conservative: boolean,
  frozen: ReadonlySet<string>,
  retries = 0,
): Promise<DrawingTidyResult> {
  const { elements, selectedIds, request } = input
  const smart = request.mode === 'layout' && (!!request.guidance ||
    !!request.layout && ['main', 'smart', 'lanes'].includes(request.layout))
  const storyboard = smart && !request.guidance && !frozen.size && request.layout !== 'main'
    ? arrangeStoryboard(elements.filter((element) => !element.isDeleted),
      request.scope === 'all' ? undefined : new Set(selectedIds))
    : undefined
  if (storyboard) return storyboard
  const { live, selected, elementById, arrows, arrowsById, labels, all, originalPaths, attachments, endpoints, units } =
    inspectDrawing(input)
  const unitByElement = new Map(units.flatMap((unit) => unit.elements.map(({ id }) => [id, unit.id])))
  const inScope = new Set(units.filter((unit) => unit.inScope).flatMap((unit) => unit.elements.map(({ id }) => id)))
  const changes = new Map<string, Element>()
  const offsets = new Map<string, Point>()
  const originalBounds = new Map(live.filter(isObstacle).map((element) => [element.id, boundsOf(element)]))
  const obstacles = new Map(originalBounds)
  const lanes: FlowLane[] = []
  const spine = new Set<string>()
  const feedback = new Set<string>()
  let mainCount = 0
  let canExpandFrames = false
  let skipped = frozen.size

  if (request.mode !== 'route') {
    const partitions = new Map<string | null, Unit[]>()
    for (const unit of units) {
      if (!unit.movable || frozen.has(unit.id)) continue
      if (!partitions.has(unit.frameId)) partitions.set(unit.frameId, [])
      partitions.get(unit.frameId)!.push(unit)
    }
    for (const [frameId, candidates] of partitions) {
      const frame = frameId ? elementById.get(frameId) : undefined
      let planned: Map<string, Point>
      if (request.mode === 'layout') {
        const moving = new Set(candidates.flatMap((unit) => unit.elements.map(({ id }) => id)))
        const fixed = [...obstacles].flatMap(([id, box]) => moving.has(id) ? [] : [box])
        if (!frame) fixed.push(...live.filter(isFrame).map(boundsOf))
        const nodes = candidates.map((unit) => {
          const names = new Set(unit.elements.flatMap((element) => {
            const role = flowLane(labels.get(element.id))
            return role ? [role] : []
          }))
          const containers = unit.elements.filter((element) => attachments?.occupied.has(element.id) &&
            !attachments.parents.has(element.id))
          const box = containers.length === 1 ? boundsOf(containers[0]!) : undefined
          const anchor = box && { x: box.x + box.width / 2, y: box.y + box.height / 2 }
          return { ...unit, lane: names.size === 1 ? [...names][0] : undefined, anchor }
        })
        const layoutEdges = layoutLinks(candidates, endpoints, labels, arrowsById, attachments?.labels)
        const links = request.guidance ? layoutEdges.filter(({ id }) => {
          const arrow = arrowsById.get(id)!
          return arrow.startArrowhead || arrow.endArrowhead
        }) : layoutEdges
        const preset = request.layout ?? 'auto'
        const frameBox = frame && boundsOf(frame)
        let layout = await planDrawingLayout(nodes, links, preset, fixed, frameBox, request.guidance)
        if (smart && frame && frameBox && !frame.locked && !frame.angle && layout.skipped) {
          canExpandFrames = true
          if (request.expandFrames) {
            const space = { ...frameBox, width: frameBox.width * 2, height: frameBox.height * 2 }
            const expanded = await planDrawingLayout(nodes, links, preset, fixed, space, request.guidance)
            const relocated = nodes.filter(({ id }) => expanded.offsets.has(id))
              .map(({ id, bounds }) => translate(bounds, expanded.offsets.get(id)!))
            if (expanded.skipped < layout.skipped && relocated.length) {
              const padded = relocated.map((box) => ({ ...box, width: box.width + 20, height: box.height + 20 }))
              const next = unionBounds([frameBox, ...padded])
              const blockers = live.filter((item) => item.id !== frame.id && item.frameId !== frame.id &&
                (isFrame(item) || isObstacle(item))).map((item) => boundsOf(changes.get(item.id) ?? item))
              if (contains(space, next) && !blockers.some((box) => overlaps(next, box) && !overlaps(frameBox, box))) {
                layout = expanded
                changes.set(frame.id, { ...frame, width: next.width, height: next.height })
              }
            }
          }
        }
        planned = layout.offsets
        skipped += layout.skipped
        mainCount += layout.mainCount ?? 0
        layout.spine?.forEach((id) => spine.add(id))
        layout.feedback?.forEach((id) => feedback.add(id))
        for (const lane of layout.guides ?? []) {
          const ids = new Set(lane.units)
          const members = candidates.filter(({ id }) => ids.has(id)).flatMap(({ elements }) =>
            elements.flatMap((element) => [element.id, ...(element.boundElements ?? [])
              .filter(({ type }) => type === 'text').map(({ id }) => id)]))
          lanes.push({ ...lane, units: members })
        }
      } else {
        planned = lightOffsets(candidates)
        const proposed = new Map(obstacles)
        for (const unit of candidates) {
          const offset = planned.get(unit.id)
          if (!offset) continue
          for (const element of unit.elements) proposed.set(element.id, translate(boundsOf(element), offset))
        }
        if (candidates.some((unit) => {
          const offset = planned.get(unit.id)
          return offset && !permitsMove(unit, translate(unit.bounds, offset), proposed, originalBounds, frame)
        })) {
          skipped += planned.size
          continue
        }
      }
      for (const unit of candidates) {
        const offset = planned.get(unit.id)
        if (!offset || samePoint(offset, origin)) continue
        for (const element of unit.elements) {
          offsets.set(element.id, offset)
          changes.set(element.id, translate(element, offset))
          obstacles.set(element.id, translate(boundsOf(element), offset))
          for (const binding of element.boundElements ?? []) {
            const label = elementById.get(binding.id)
            if (binding.type === 'text' && label?.type === 'text' && label.containerId === element.id) {
              changes.set(label.id, translate(label, offset))
            }
          }
        }
      }
    }
  }
  const paths = new Map<string, Point[]>()
  const route: Arrow[] = []
  for (const arrow of arrows) {
    const ids = endpoints.get(arrow.id)!
    const [from, to] = ids
    const moved = moveEndpoints(arrow, offsets, ids)
    const after = arrowPoints(moved)
    paths.set(arrow.id, after)
    if (moved !== arrow) changes.set(arrow.id, moved)
    const sameUnit = !!from && !!to && unitByElement.has(from) && unitByElement.get(from) === unitByElement.get(to)
    const internal = !!from && !!to && (all || (inScope.has(from) && inScope.has(to)))
    const eligible = internal || (request.mode === 'layout' && moved !== arrow) ||
      (request.mode !== 'light' && selected.has(arrow.id)) ||
      (request.mode === 'route' && all)
    if (!eligible || sameUnit && request.mode !== 'route') continue
    const manual = !arrow.elbowed && arrow.points.length > 2 &&
      arrow.customData?.waoTidyRoute !== relativePointsKey(arrow.points)
    if (arrow.locked || arrow.boundElements?.some(({ id }) => elementById.get(id)?.locked) ||
      attachments?.labels.get(arrow.id)?.some(({ text }) => text.locked) ||
      attachments?.ambiguousArrows.has(arrow.id) || arrow.angle || (manual && !request.guidance) || hasFixedSegments(arrow) || after.length < 2 ||
      (from && from === to) || (from && !elementById.has(from)) || (to && !elementById.has(to))) {
      skipped++
      continue
    }
    route.push(moved)
  }
  let routed = 0
  route.sort((a, b) => Number(spine.has(b.id)) - Number(spine.has(a.id)) ||
    Number(feedback.has(a.id)) - Number(feedback.has(b.id)))
  const stillToRoute = new Set(request.guidance ? route.map(({ id }) => id) : [])
  for (const arrow of route) {
    stillToRoute.delete(arrow.id)
    const ids = endpoints.get(arrow.id)!
    const [from, to] = ids
    const frameId = from ? elementById.get(from)?.frameId : null
    const frame = frameId && to && elementById.get(to)?.frameId === frameId ? elementById.get(frameId) : undefined
    const frameBounds = frame && boundsOf(changes.get(frame.id) ?? frame)
    const insetEndpoint = ids.some((id, index) => {
      const target = id && elementById.get(id)
      const points = originalPaths.get(arrow.id)!
      const anchor = points[index === 0 ? 0 : points.length - 1]!
      if (!target || !attachments?.occupied.has(target.id)) return false
      const box = boundsOf(target)
      return Math.min(anchor.x - box.x, box.x + box.width - anchor.x,
        anchor.y - box.y, box.y + box.height - anchor.y) > 12
    })
    const source = from && obstacles.get(from), target = to && obstacles.get(to)
    if (request.guidance && !arrow.elbowed && !insetEndpoint && source && target &&
        (offsets.has(from!) || offsets.has(to!))) {
      const earlier = [...paths.entries()].flatMap(([id, points]) =>
        id === arrow.id || stillToRoute.has(id) ? [] : segments(points))
      const candidate = routeDrawingConnection(source, target, [...obstacles.values()], earlier,
        [arrow.startBinding?.gap || 6, arrow.endBinding?.gap || 6])
      if (candidate && (!frameBounds || candidate.every((point) =>
        contains(frameBounds, { ...point, width: 0, height: 0 })))) {
        paths.set(arrow.id, candidate)
        changes.set(arrow.id, arrowWithPath({
          ...arrow,
          startBinding: arrow.startBinding && { ...arrow.startBinding, focus: 0 },
          endBinding: arrow.endBinding && { ...arrow.endBinding, focus: 0 },
        }, candidate, true))
        routed++
        continue
      }
    }
    if (feedback.has(arrow.id) && !insetEndpoint) {
      const outside = outsideFeedback(arrow, ids, paths, obstacles, frameBounds)
      if (outside) {
        changes.set(arrow.id, outside)
        routed++
        continue
      }
    }
    if (request.mode === 'layout' && !insetEndpoint && !feedback.has(arrow.id) && from && to && inScope.has(from) && inScope.has(to) &&
      (offsets.has(from) || offsets.has(to)) && [from, to].every((id) => {
        const shape = elementById.get(id)!
        return isShape(shape) && !shape.angle
      })) {
      const aligned = alignArrowPorts(arrow, ids, paths, obstacles, frameBounds, smart)
      if (aligned) {
        changes.set(arrow.id, aligned)
        routed++
        continue
      }
    }
    const choice = chooseRoute(arrow, paths, obstacles, ids, conservative, frameBounds, feedback.has(arrow.id))
    if (!choice.path) {
      if (choice.needsImprovement) skipped++
      continue
    }
    changes.set(arrow.id, arrowWithPath(arrow, choice.path, true))
    routed++
  }
  const unsafe = new Set<string>()
  for (const [arrowId, labels] of attachments?.labels ?? []) {
    if (!changes.has(arrowId)) continue
    const path = paths.get(arrowId)!
    const arrow = arrowsById.get(arrowId)!
    const next: Element[] = []
    const attachedIds = new Set(labels.map(({ text }) => text.id))
    for (const label of labels) {
      const previous = boundsOf(label.text)
      const item = floatingLabelPositions(label, path).find((candidate) => {
        const box = boundsOf(candidate)
        return ![...obstacles].some(([id, obstacle]) => !attachedIds.has(id) && overlaps(box, obstacle) &&
          !overlaps(previous, originalBounds.get(id) ?? obstacle)) &&
          !next.some((other) => overlaps(box, boundsOf(other))) &&
          !segments(path).some((leg) => insideBounds(leg, box)) &&
          ![...paths].some(([id, other]) => id !== arrowId && segments(other).some((leg) => insideBounds(leg, box)) &&
            !segments(originalPaths.get(id)!).some((leg) => insideBounds(leg, previous)))
      })
      if (!item) break
      next.push(item)
    }
    if (next.length === labels.length) {
      for (const item of next) {
        changes.set(item.id, item)
        obstacles.set(item.id, boundsOf(item))
      }
      continue
    }
    for (const id of endpoints.get(arrow.id)!) {
      if (id && offsets.has(id)) unsafe.add(unitByElement.get(id)!)
    }
    changes.delete(arrowId)
    paths.set(arrowId, originalPaths.get(arrowId)!)
  }
  if (offsets.size) for (const arrow of arrows) {
    const ids = endpoints.get(arrow.id)!
    const before = collisionsOf(segments(originalPaths.get(arrow.id)!), originalBounds, ids)
    const after = collisionsOf(segments(paths.get(arrow.id)!), obstacles, ids)
    const introduced = [...after].filter((id) => !before.has(id))
    if (introduced.length) {
      for (const id of [...ids, ...introduced]) {
        if (id && offsets.has(id)) unsafe.add(unitByElement.get(id)!)
      }
    }
    if (request.mode === 'light' &&
      score(arrow, paths, obstacles, ids).crossings > score(arrow, originalPaths, originalBounds, ids).crossings) {
      unsafe.add(arrow.id)
    }
  }
  if (unsafe.size) {
    if (request.mode === 'layout' && retries < 4) {
      return runTidy({ elements, selectedIds, request }, conservative, new Set([...frozen, ...unsafe]), retries + 1)
    }
    const independent = request.guidance && request.mode === 'layout'
      ? freezeConnectedUnits(endpoints, unitByElement, frozen)
      : undefined
    if (independent) return runTidy(input, conservative, independent, retries + 1)
    // If the new node positions block a connector, still offer safe routing on the original positions.
    const result = await runTidy({ elements, selectedIds, request: { ...request, mode: 'route' } }, conservative, frozen)
    return { ...result, skipped: result.skipped + offsets.size, ...(smart ? { limited: 'wiring' as const } : {}) }
  }
  const moved = [...offsets.keys()].filter((id) => {
    const element = elementById.get(id)!
    return isShape(element) || (!!request.guidance && element.type === 'text')
  }).length
  const result = { changed: [...changes.values()], moved, routed, skipped }
  if (!smart) return result
  const crossings = { before: arrowCrossings(originalPaths), after: arrowCrossings(paths) }
  if (crossings.after > crossings.before) {
    const independent = request.guidance ? freezeConnectedUnits(endpoints, unitByElement, frozen) : undefined
    if (independent) return runTidy(input, conservative, independent, retries + 1)
    return { changed: [], moved: 0, routed: 0, skipped, limited: 'wiring' }
  }
  return {
    ...result, lanes, mainCount, crossings, canExpandFrames,
    limited: request.layout === 'lanes' && !lanes.length ? 'lanes' : skipped ? 'space' : undefined,
  }
}

function freezeConnectedUnits(
  endpoints: ReadonlyMap<string, Endpoints>, units: ReadonlyMap<string, string>, frozen: ReadonlySet<string>,
) {
  const next = new Set(frozen)
  for (const endpointsOfArrow of endpoints.values()) {
    for (const id of endpointsOfArrow) {
      const unit = id && units.get(id)
      if (unit) next.add(unit)
    }
  }
  return next.size > frozen.size ? next : undefined
}
