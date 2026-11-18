import type { OrderedExcalidrawElement } from '@excalidraw/excalidraw/element/types'
import { orthogonalRoute } from './drawingOrthogonalRouter'
import { planDrawingLayout, type DrawingLayout } from './drawingTidyLayout'
import {
  boundsOf, contains, crosses, insideBounds, overlaps, samePoint, segments, sharedLength, translate, unionBounds,
  type Bounds, type Point,
} from './drawingTidyGeometry'

type Element = OrderedExcalidrawElement
type Arrow = Extract<Element, { type: 'arrow' }>
type Shape = Extract<Element, { type: 'rectangle' | 'diamond' | 'ellipse' | 'image' }>
export type DrawingTidyMode = 'light' | 'route' | 'layout'
export type DrawingTidyRequest = {
  mode: DrawingTidyMode
  scope: 'selection' | 'all'
  layout?: DrawingLayout
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
) {
  const groups = new Map<string, Element[]>()
  const locked = new Set(elements.filter((element) => element.locked).map((element) => element.id))
  for (const element of elements) {
    const group = idOfGroup(element)
    if (group) groups.set(group, [...(groups.get(group) ?? []), element])
  }
  const pinned = new Set<string>()
  for (const arrow of elements.filter(isArrow)) {
    if (!arrow.locked && !hasFixedSegments(arrow) && !arrow.angle &&
      !arrow.boundElements?.some(({ id }) => locked.has(id))) continue
    for (const id of endpoints.get(arrow.id)!) if (id) pinned.add(id)
  }
  const units = new Map<string, Unit>()
  for (const element of elements.filter(isShape)) {
    const group = idOfGroup(element)
    const id = group ? `group:${group}` : `element:${element.id}`
    if (units.has(id)) continue
    const members = group ? groups.get(group)! : [element]
    const shapes = members.filter(isShape)
    const boxes = members.filter((member) => isShape(member) || (member.type === 'text' && !member.containerId))
    const shapeIds = new Set(shapes.map((shape) => shape.id))
    const inScope = all || members.some((member) =>
      selected.has(member.id) || (member.frameId !== null && selected.has(member.frameId)),
    ) || shapes.some((shape) => shape.boundElements?.some((item) => item.type === 'text' && selected.has(item.id)))
    const movable = inScope && !members.some((member) => member.locked || pinned.has(member.id) ||
      member.boundElements?.some(({ type, id }) => type === 'text' && locked.has(id)) ||
      (isArrow(member) && endpoints.get(member.id)!.some((id) => !id || !shapeIds.has(id))) ||
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

function layoutLinks(units: Unit[], endpoints: Map<string, Endpoints>) {
  const unitIds = new Map(units.flatMap((unit) => unit.elements.map((element) => [element.id, unit.id] as const)))
  return [...endpoints].flatMap(([id, [from, to]]) => {
    const source = from && unitIds.get(from)
    const target = to && unitIds.get(to)
    return source && target && source !== target ? [{ id, source, target }] : []
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

function arrowPoints(arrow: Arrow) {
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

function chooseRoute(
  arrow: Arrow, paths: Map<string, Point[]>, obstacles: ReadonlyMap<string, Bounds>,
  endpoints: Endpoints, conservative: boolean, frame?: Bounds,
) {
  const original = paths.get(arrow.id)!
  const before = score(arrow, paths, obstacles, endpoints)
  if (original.length === 2 && !before.collisions.size && !before.crossings && !before.shared) {
    return { needsImprovement: false }
  }
  const otherLines = [...paths.entries()].flatMap(([id, points]) => id === arrow.id ? [] : segments(points))
  const proposal = orthogonalRoute(original[0]!, original[original.length - 1]!, [...obstacles.values()], otherLines)
  let best = original
  let bestScore = before
  const candidates = proposal ? [proposal] : []
  if (before.crossings && !conservative) candidates.push(...perimeterPaths(original, obstacles))
  for (const candidate of candidates) {
    if (candidate.length < 2 || !samePoint(candidate[0]!, original[0]!) ||
      !samePoint(candidate[candidate.length - 1]!, original[original.length - 1]!)) continue
    if (frame && candidate.some((point) => !contains(frame, { ...point, width: 0, height: 0 }))) continue
    if (candidate.length === original.length && candidate.every((point, index) => samePoint(point, original[index]!))) continue
    paths.set(arrow.id, candidate)
    const next = score(arrow, paths, obstacles, endpoints)
    if (conservative && next.length > before.length * 1.5 + 32) continue
    if ([...next.collisions].some((id) => !before.collisions.has(id)) || next.crossings > before.crossings) continue
    const improvement = bestScore.collisions.size - next.collisions.size ||
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
  arrow: Arrow, ids: Endpoints, paths: Map<string, Point[]>, obstacles: ReadonlyMap<string, Bounds>, frame?: Bounds,
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
  const path = orthogonalRoute(start, end, [...obstacles.values()], lines)
  if (!path || path.length < 2 || (frame && path.some((point) => !contains(frame, { ...point, width: 0, height: 0 })))) return
  paths.set(arrow.id, path)
  const after = score(arrow, paths, obstacles, ids)
  if ([...after.collisions].some((id) => !before.collisions.has(id)) || after.crossings > before.crossings ||
    after.length > before.length * 1.25 + 24) {
    paths.set(arrow.id, original)
    return
  }
  return arrowWithPath({
    ...arrow,
    startBinding: arrow.startBinding && { ...arrow.startBinding, focus: 0 },
    endBinding: arrow.endBinding && { ...arrow.endBinding, focus: 0 },
  }, path, true)
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

export function tidyDrawing(input: DrawingTidyInput): Promise<DrawingTidyResult> {
  return runTidy(input, input.request.mode === 'light', new Set())
}

async function runTidy(
  { elements, selectedIds, request }: DrawingTidyInput,
  conservative: boolean,
  frozen: ReadonlySet<string>,
  retries = 0,
): Promise<DrawingTidyResult> {
  const live = elements.filter((element) => !element.isDeleted)
  const selected = new Set(selectedIds)
  const elementById = new Map(live.map((element) => [element.id, element]))
  const arrows = live.filter(isArrow)
  const all = request.scope === 'all'
  const originalPaths = new Map(arrows.map((arrow) => [arrow.id, arrowPoints(arrow)]))
  const shapes = request.mode === 'layout' ? live.filter(isShape) : []
  const endpoints = new Map(arrows.map((arrow): [string, Endpoints] => {
    const points = originalPaths.get(arrow.id)!
    const first = points[0], last = points.length >= 2 ? points[points.length - 1] : undefined
    const [from, to] = endpointIds(arrow)
    return [arrow.id, [
      from ?? (first && last && nearbyShape(first, shapes, arrow.frameId)),
      to ?? (last && nearbyShape(last, shapes, arrow.frameId)),
    ]]
  }))
  const units = unitsFor(live, selected, all, endpoints)
  const unitByElement = new Map(units.flatMap((unit) => unit.elements.map(({ id }) => [id, unit.id])))
  const inScope = new Set(units.filter((unit) => unit.inScope).flatMap((unit) => unit.elements.map(({ id }) => id)))
  const changes = new Map<string, Element>()
  const offsets = new Map<string, Point>()
  const originalBounds = new Map(live.filter(isObstacle).map((element) => [element.id, boundsOf(element)]))
  const obstacles = new Map(originalBounds)
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
        const layout = await planDrawingLayout(candidates, layoutLinks(candidates, endpoints), request.layout ?? 'auto',
          fixed, frame && boundsOf(frame))
        planned = layout.offsets
        skipped += layout.skipped
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
    const internal = !!from && !!to && (all || (inScope.has(from) && inScope.has(to)))
    const eligible = internal || (request.mode === 'layout' && moved !== arrow) ||
      (request.mode !== 'light' && selected.has(arrow.id)) ||
      (request.mode === 'route' && all)
    if (!eligible) continue
    const manual = !arrow.elbowed && arrow.points.length > 2 &&
      arrow.customData?.waoTidyRoute !== relativePointsKey(arrow.points)
    if (arrow.locked || arrow.boundElements?.some(({ id }) => elementById.get(id)?.locked) ||
      arrow.angle || manual || hasFixedSegments(arrow) || after.length < 2 ||
      (from && from === to) || (from && !elementById.has(from)) || (to && !elementById.has(to))) {
      skipped++
      continue
    }
    route.push(moved)
  }
  let routed = 0
  for (const arrow of route) {
    const ids = endpoints.get(arrow.id)!
    const [from, to] = ids
    const frameId = from ? elementById.get(from)?.frameId : null
    const frame = frameId && to && elementById.get(to)?.frameId === frameId ? elementById.get(frameId) : undefined
    const frameBounds = frame && boundsOf(frame)
    if (request.mode === 'layout' && from && to && inScope.has(from) && inScope.has(to) &&
      (offsets.has(from) || offsets.has(to)) && [from, to].every((id) => {
        const shape = elementById.get(id)!
        return isShape(shape) && !shape.angle
      })) {
      const aligned = alignArrowPorts(arrow, ids, paths, obstacles, frameBounds)
      if (aligned) {
        changes.set(arrow.id, aligned)
        routed++
        continue
      }
    }
    const choice = chooseRoute(arrow, paths, obstacles, ids, conservative, frameBounds)
    if (!choice.path) {
      if (choice.needsImprovement) skipped++
      continue
    }
    changes.set(arrow.id, arrowWithPath(arrow, choice.path, true))
    routed++
  }
  const unsafe = new Set<string>()
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
    // If the new node positions block a connector, still offer safe routing on the original positions.
    const result = await runTidy({ elements, selectedIds, request: { ...request, mode: 'route' } }, conservative, frozen)
    return { ...result, skipped: result.skipped + offsets.size }
  }
  return { changed: [...changes.values()], moved: [...offsets.keys()].filter((id) => isShape(elementById.get(id)!)).length, routed, skipped }
}
