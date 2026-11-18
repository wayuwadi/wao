import type { ElkExtendedEdge, ElkNode } from 'elkjs/lib/elk-api'
import { contains, overlaps, translate, unionBounds, type Bounds, type Point } from './drawingTidyGeometry'

export type DrawingLayout = 'auto' | 'right' | 'down' | 'tree'
type Node = { id: string; bounds: Bounds }
type Link = { id: string; source: string; target: string }

function direction(preset: DrawingLayout, nodes: readonly Node[], links: readonly Link[]) {
  if (preset !== 'auto') return preset === 'right' ? 'RIGHT' : 'DOWN'
  const centers = new Map(nodes.map(({ id, bounds }) =>
    [id, { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 }],
  ))
  let preference = 0
  for (const { source, target } of links) {
    const a = centers.get(source)!, b = centers.get(target)!
    const dx = Math.abs(a.x - b.x), dy = Math.abs(a.y - b.y)
    if (dx + dy > 0) preference += (dx - dy) / (dx + dy)
  }
  return preference >= 0 ? 'RIGHT' : 'DOWN'
}

function components(nodes: readonly Node[], links: readonly Link[]) {
  const neighbors = new Map(nodes.map(({ id }) => [id, new Set<string>()]))
  for (const { source, target } of links) {
    neighbors.get(source)!.add(target)
    neighbors.get(target)!.add(source)
  }
  const visited = new Set<string>()
  const result = []
  for (const { id } of nodes) {
    if (visited.has(id)) continue
    const ids = new Set([id])
    for (const member of ids) {
      visited.add(member)
      for (const neighbor of neighbors.get(member)!) ids.add(neighbor)
    }
    result.push({
      nodes: nodes.filter((node) => ids.has(node.id)),
      links: links.filter((link) => ids.has(link.source)),
    })
  }
  return result
}

/** Find nearby space without changing the geometry produced by ELK. */
function place(nodes: readonly Bounds[], anchor: Point, fixed: readonly Bounds[], frame?: Bounds) {
  const envelope = unionBounds(nodes)
  if (frame && (envelope.width > frame.width || envelope.height > frame.height)) return
  const queue: Point[] = []
  const seen = new Set<string>()
  const add = ({ x, y }: Point) => {
    if (frame) {
      x = Math.max(frame.x, Math.min(x, frame.x + frame.width - envelope.width))
      y = Math.max(frame.y, Math.min(y, frame.y + frame.height - envelope.height))
    }
    const key = `${x},${y}`
    if (seen.has(key)) return
    seen.add(key)
    queue.push({ x, y })
  }
  const distance = (point: Point) => Math.hypot(point.x - anchor.x, point.y - anchor.y)
  add(anchor)
  for (let attempt = 0; queue.length && attempt < 96; attempt++) {
    queue.sort((a, b) => distance(a) - distance(b))
    const point = queue.shift()!
    const offset = { x: point.x - envelope.x, y: point.y - envelope.y }
    let blocked = false
    for (const node of nodes) {
      const box = translate(node, offset)
      const obstacle = fixed.find((item) => overlaps(box, item))
      if (!obstacle) continue
      blocked = true
      for (const gap of frame ? [12, 0] : [12]) {
        add({ ...point, x: point.x + obstacle.x - box.x - box.width - gap })
        add({ ...point, x: point.x + obstacle.x + obstacle.width - box.x + gap })
        add({ ...point, y: point.y + obstacle.y - box.y - box.height - gap })
        add({ ...point, y: point.y + obstacle.y + obstacle.height - box.y + gap })
      }
      break
    }
    if (!blocked) return offset
  }
}

export async function planDrawingLayout(
  nodes: readonly Node[], links: readonly Link[], preset: DrawingLayout, fixed: readonly Bounds[], frame?: Bounds,
) {
  const offsets = new Map<string, Point>()
  if (nodes.length < 2) return { offsets, skipped: 0 }
  const { default: ELK } = await import('elkjs/lib/elk.bundled.js')
  const engine = new ELK()

  async function arrange(
    items: readonly Node[], edges: readonly Link[], obstacles: readonly Bounds[], flow: 'RIGHT' | 'DOWN', compact: boolean,
  ) {
    const ordered = [...items].sort((a, b) => a.bounds.y - b.bounds.y || a.bounds.x - b.bounds.x)
    const elkEdges: ElkExtendedEdge[] = edges.map(({ id, source, target }) => ({ id, sources: [source], targets: [target] }))
    const graph: ElkNode = {
      id: 'drawing',
      layoutOptions: {
        'elk.algorithm': edges.length ? (preset === 'tree' ? 'mrtree' : 'layered') : 'box',
        'elk.direction': flow,
        'elk.spacing.nodeNode': compact ? '16' : '44',
        'elk.layered.spacing.nodeNodeBetweenLayers': compact ? '28' : '76',
        'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
        'elk.padding': '[top=0,left=0,bottom=0,right=0]',
      },
      children: ordered.map(({ id, bounds }) => ({ id, width: bounds.width, height: bounds.height })),
      edges: elkEdges,
    }
    const layout = await engine.layout(graph)
    const positioned = new Map((layout.children ?? []).map((node) => [node.id, node]))
    const boxes = items.map(({ id, bounds }) => ({ ...bounds, x: positioned.get(id)!.x!, y: positioned.get(id)!.y! }))
    const shift = place(boxes, unionBounds(items.map(({ bounds }) => bounds)), obstacles, frame)
    if (!shift) return false
    const placed = boxes.map((box) => translate(box, shift))
    if (frame && placed.some((box) => !contains(frame, box))) return false
    items.forEach(({ id, bounds }, index) => {
      const box = placed[index]!
      offsets.set(id, { x: box.x - bounds.x, y: box.y - bounds.y })
    })
    return true
  }

  async function arrangeToFit(items: readonly Node[], edges: readonly Link[], obstacles: readonly Bounds[]) {
    const flow = direction(preset, items, edges)
    if (await arrange(items, edges, obstacles, flow, false)) return true
    if (!frame) return false
    if (await arrange(items, edges, obstacles, flow, true)) return true
    return preset === 'auto' && !!edges.length &&
      await arrange(items, edges, obstacles, flow === 'RIGHT' ? 'DOWN' : 'RIGHT', true)
  }

  if (await arrangeToFit(nodes, links, fixed)) return { offsets, skipped: 0 }
  let skipped = 0
  for (const component of components(nodes, links)) {
    const { nodes: members, links: edges } = component
    if (members.length === nodes.length) return { offsets, skipped: nodes.length }
    if (members.length < 2) {
      skipped++
      continue
    }
    const ids = new Set(members.map(({ id }) => id))
    const obstacles = [...fixed, ...nodes.filter(({ id }) => !ids.has(id))
      .map(({ id, bounds }) => translate(bounds, offsets.get(id) ?? { x: 0, y: 0 }))]
    if (!await arrangeToFit(members, edges, obstacles)) skipped += members.length
  }
  return { offsets, skipped }
}
