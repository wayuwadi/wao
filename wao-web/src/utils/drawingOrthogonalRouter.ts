import { crosses, insideBounds, overlaps, sharedLength, type Bounds, type Point, type Segment } from './drawingTidyGeometry'

type SearchState = { cell: number; axis: number; cost: number; rank: number; from?: SearchState }

function insert(queue: SearchState[], state: SearchState) {
  let index = queue.length
  queue.push(state)
  while (index > 0) {
    const parent = Math.floor((index - 1) / 2)
    if (queue[parent]!.rank <= state.rank) break
    queue[index] = queue[parent]!
    index = parent
  }
  queue[index] = state
}

function take(queue: SearchState[]) {
  const first = queue[0]!
  const last = queue.pop()!
  if (queue.length) {
    let index = 0
    while (index * 2 + 1 < queue.length) {
      const left = index * 2 + 1
      const right = left + 1
      const child = right < queue.length && queue[right]!.rank < queue[left]!.rank ? right : left
      if (last.rank <= queue[child]!.rank) break
      queue[index] = queue[child]!
      index = child
    }
    queue[index] = last
  }
  return first
}

const distance = (a: Point, b: Point) => Math.abs(a.x - b.x) + Math.abs(a.y - b.y)

function expand(bounds: Bounds, padding: number) {
  return { x: bounds.x - padding, y: bounds.y - padding,
    width: bounds.width + 2 * padding, height: bounds.height + 2 * padding }
}

const inside = (point: Point, box: Bounds) => point.x > box.x && point.x < box.x + box.width &&
  point.y > box.y && point.y < box.y + box.height
const near = (point: Point, box: Bounds) => inside(point, expand(box, 0.5))

/** Search the grid formed by obstacle borders and connection points. Only the route changes. */
export function orthogonalRoute(start: Point, end: Point, shapes: readonly Bounds[], otherLines: readonly Segment[]) {
  const bounds = {
    x: Math.min(start.x, end.x), y: Math.min(start.y, end.y),
    width: Math.abs(end.x - start.x), height: Math.abs(end.y - start.y),
  }
  const region = expand(bounds, Math.max(80, Math.max(bounds.width, bounds.height) / 3))
  const obstacles = shapes.filter((shape) => overlaps(shape, region)).map((shape) => {
    const padded = expand(shape, 8)
    return near(start, padded) || near(end, padded) ? shape : padded
  }).filter((shape) => !inside(start, shape) && !inside(end, shape))
  const lines = otherLines.filter(([a, b]) => overlaps(expand({
    x: Math.min(a.x, b.x), y: Math.min(a.y, b.y),
    width: Math.abs(a.x - b.x), height: Math.abs(a.y - b.y),
  }, 1), region))
  const xs = new Set([start.x, end.x, region.x, region.x + region.width])
  const ys = new Set([start.y, end.y, region.y, region.y + region.height])
  for (const box of obstacles) {
    xs.add(box.x); xs.add(box.x + box.width)
    ys.add(box.y); ys.add(box.y + box.height)
  }
  // Parallel channels give overlapping lines alternatives even in a wide-open area.
  for (const [a, b] of lines) {
    if (a.x === b.x) { xs.add(a.x - 12); xs.add(a.x + 12) }
    if (a.y === b.y) { ys.add(a.y - 12); ys.add(a.y + 12) }
  }
  const x = [...xs].sort((a, b) => a - b)
  const y = [...ys].sort((a, b) => a - b)
  if (x.length * y.length > 40000) return undefined
  const columns = x.length
  const pointOf = (cell: number) => ({ x: x[cell % columns]!, y: y[Math.floor(cell / columns)]! })
  const startCell = y.indexOf(start.y) * columns + x.indexOf(start.x)
  const endCell = y.indexOf(end.y) * columns + x.indexOf(end.x)
  const queue: SearchState[] = [{ cell: startCell, axis: 0, cost: 0, rank: distance(start, end) }]
  const costs = new Map<number, number>()
  while (queue.length) {
    const current = take(queue)
    if (current.cell === endCell) {
      const turns = [end]
      for (let state: SearchState | undefined = current; state?.from; state = state.from) {
        if (!state.from.from || state.axis !== state.from.axis) turns.push(pointOf(state.from.cell))
      }
      return turns.reverse()
    }
    const currentKey = current.cell * 3 + current.axis
    if (current.cost > (costs.get(currentKey) ?? Infinity)) continue
    const col = current.cell % columns
    const row = Math.floor(current.cell / columns)
    const from = pointOf(current.cell)
    const candidates = [
      [col - 1, row, 1], [col + 1, row, 1], [col, row - 1, 2], [col, row + 1, 2],
    ] as const
    for (const [i, j, axis] of candidates) {
      if (i < 0 || i >= x.length || j < 0 || j >= y.length) continue
      const to = { x: x[i]!, y: y[j]! }
      const leg: Segment = [from, to]
      if (obstacles.some((shape) => insideBounds(leg, shape))) continue
      const bend = current.axis && current.axis !== axis ? 28 : 0
      let cost = current.cost + distance(from, to) + bend
      for (const line of lines) {
        if (crosses(leg, line)) cost += 180
        cost += sharedLength(leg, line) * 2
      }
      const cell = j * columns + i
      const key = cell * 3 + axis
      if (cost >= (costs.get(key) ?? Infinity)) continue
      costs.set(key, cost)
      insert(queue, { cell, axis, cost, rank: cost + distance(to, end), from: current })
    }
  }
}
