import type { ElkExtendedEdge, ElkNode } from 'elkjs/lib/elk-api'
import type { DrawingAiGuidance } from './drawingAiLayoutModel'
import { arrangeFlow, type FlowLane, type FlowLink, type FlowNode } from './drawingFlowLayout'
import { contains, overlaps, translate, unionBounds, type Bounds, type Point } from './drawingTidyGeometry'

export type DrawingLayout = 'smart' | 'main' | 'lanes' | 'auto' | 'right' | 'down' | 'tree'
type Node = FlowNode
type Link = FlowLink

export type FlowPlan = {
  offsets: Map<string, Point>
  skipped: number
  guides?: FlowLane[]
  spine?: string[]
  feedback?: string[]
  mainCount?: number
}

function direction(preset: DrawingLayout, nodes: readonly Node[], links: readonly Link[]) {
  if (preset === 'right' || preset === 'lanes') return 'RIGHT'
  if (preset === 'down' || preset === 'tree') return 'DOWN'
  const centers = new Map(nodes.map(({ id, bounds, anchor }) =>
    [id, anchor ?? { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 }],
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
export function placeDrawingBoxes(nodes: readonly Bounds[], anchor: Point, fixed: readonly Bounds[], frame?: Bounds) {
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

function planFlowLayout(
  nodes: readonly Node[], links: readonly Link[], preset: DrawingLayout, fixed: readonly Bounds[], frame?: Bounds,
): FlowPlan {
  const result: Required<FlowPlan> = {
    offsets: new Map(), skipped: 0, guides: [], spine: [], feedback: [], mainCount: 0,
  }
  const occupied = [...fixed]
  for (const { nodes: members, links: edges } of components(nodes, links)) {
    if (!edges.length) {
      occupied.push(...members.map(({ bounds }) => bounds))
      continue
    }
    const ids = new Set(members.map(({ id }) => id))
    const roleCount = new Set(members.flatMap(({ lane }) => lane ? [lane] : [])).size
    const lanes = !frame && preset !== 'main' && roleCount >= 2
    if (preset === 'lanes' && !lanes) continue
    const horizontal = lanes || direction(preset, members, edges) === 'RIGHT'
    const other = [...occupied, ...nodes.filter(({ id }) => !ids.has(id) && !result.offsets.has(id))
      .map(({ bounds }) => bounds)]
    const anchor = unionBounds(members.map(({ bounds }) => bounds))
    let placed = false
    for (const [wide, compact] of [[horizontal, false], [horizontal, true], [!horizontal, true]] as const) {
      if (wide !== horizontal && (lanes || !frame)) continue
      const { boxes, guides, spine, feedback, mainCount } = arrangeFlow(members, edges, wide, lanes, compact)
      // Place the full bands so the lane outlines don't cover neighboring content.
      const collisionBoxes = guides.length ? guides.map(({ bounds }) => bounds) : boxes
      const pad = guides.length ? { x: anchor.x - 20, y: anchor.y - 20 } : anchor
      const shift = placeDrawingBoxes(collisionBoxes, pad, other, frame)
      if (!shift) continue
      members.forEach(({ id, bounds }, index) => {
        const box = translate(boxes[index]!, shift)
        result.offsets.set(id, { x: box.x - bounds.x, y: box.y - bounds.y })
      })
      occupied.push(...collisionBoxes.map((box) => translate(box, shift)))
      result.guides.push(...guides.map((guide) => ({ ...guide, bounds: translate(guide.bounds, shift) })))
      result.spine.push(...spine)
      result.feedback.push(...feedback)
      result.mainCount += mainCount
      placed = true
      break
    }
    if (!placed) result.skipped += members.length
  }
  return result
}

function guidedGrid(nodes: readonly Node[], horizontal: boolean) {
  const columns = Math.min(nodes.length, horizontal ? 3 : 2)
  const widths = Array.from({ length: columns }, (_, column) =>
    Math.max(...nodes.filter((_, index) => index % columns === column).map(({ bounds }) => bounds.width)))
  let y = 0
  const boxes: Bounds[] = []
  for (let index = 0; index < nodes.length; index += columns) {
    const row = nodes.slice(index, index + columns)
    let x = 0
    row.forEach(({ bounds }, column) => {
      boxes.push({ ...bounds, x, y })
      x += widths[column]! + 58
    })
    y += Math.max(...row.map(({ bounds }) => bounds.height)) + 56
  }
  return boxes
}

function planGuidedLayout(
  nodes: readonly Node[], links: readonly Link[], fixed: readonly Bounds[], frame: Bounds | undefined,
  guidance: DrawingAiGuidance,
): FlowPlan {
  const offsets = new Map<string, Point>()
  if (nodes.length < 2) return { offsets, skipped: 0 }
  const remaining = new Map(nodes.map((node) => [node.id, node]))
  const groups = guidance.groups.flatMap(({ title, ids }) => {
    const members = ids.flatMap((id) => {
      const item = remaining.get(id)
      remaining.delete(id)
      return item ? [item] : []
    })
    return members.length ? [{ title, nodes: members }] : []
  })
  if (remaining.size) groups.push({ title: '补充内容', nodes: [...remaining.values()] })
  const horizontal = guidance.direction === 'right'
  const primary = new Set(guidance.main)
  const feedback: string[] = []
  const packed = groups.map(({ title, nodes: members }) => {
    const ids = new Set(members.map(({ id }) => id))
    const edges = links.filter(({ source, target }) => ids.has(source) && ids.has(target))
      .map((edge) => ({ ...edge, primary: primary.has(edge.id) }))
    const flow = edges.length ? arrangeFlow(members, edges, horizontal, false, !!frame, true) : undefined
    if (flow) feedback.push(...flow.feedback)
    const boxes = flow?.boxes ?? guidedGrid(members, horizontal)
    const envelope = unionBounds(boxes)
    return { title, members, boxes: boxes.map((box) => translate(box, { x: 24 - envelope.x, y: 48 - envelope.y })),
      bounds: { x: 0, y: 0, width: envelope.width + 48, height: envelope.height + 72 } }
  })
  const start = unionBounds(nodes.map(({ bounds }) => bounds))
  const width = frame?.width ?? Math.max(start.width, ...packed.map(({ bounds }) => bounds.width))
  let x = 0, y = 0, rowHeight = 0
  const bands = packed.map(({ bounds }) => {
    if (x && x + bounds.width > width) {
      y += rowHeight + 64
      x = 0
      rowHeight = 0
    }
    const band = { ...bounds, x, y }
    x += bounds.width + 64
    rowHeight = Math.max(rowHeight, bounds.height)
    return band
  })
  const extent = unionBounds(bands)
  const groupShift = placeDrawingBoxes(bands, start, fixed, frame)
  const footprint = frame ?? {
    x: start.x - 42, y: start.y - 42, width: Math.max(start.width + 168, extent.width + 84),
    height: Math.max(start.height + 168, extent.height + 84),
  }
  const guides: FlowLane[] = []
  const stillUnplaced = new Map(nodes.map(({ id, bounds }) => [id, bounds]))
  const blockers = [...fixed]
  let skipped = 0
  for (const [index, group] of packed.entries()) {
    for (const { id } of group.members) stillUnplaced.delete(id)
    const target = translate(bands[index]!, start)
    const shift = groupShift ?? placeDrawingBoxes([group.bounds], target, [...blockers, ...stillUnplaced.values()], footprint)
    if (!shift) {
      blockers.push(...group.members.map(({ bounds }) => bounds))
      skipped += group.members.length
      continue
    }
    const band = groupShift ? translate(bands[index]!, groupShift) : translate(group.bounds, shift)
    blockers.push(band)
    group.members.forEach(({ id, bounds }, i) => {
      const box = group.boxes[i]!
      offsets.set(id, { x: band.x + box.x - bounds.x, y: band.y + box.y - bounds.y })
    })
    if (!frame && packed.length > 1) guides.push({ name: group.title, bounds: band, units: group.members.map(({ id }) => id) })
  }
  const spine = links.filter(({ id }) => primary.has(id))
  return { offsets, guides, skipped, spine: spine.map(({ id }) => id), feedback,
    mainCount: new Set(spine.flatMap(({ source, target }) => [source, target])).size }
}

export async function planDrawingLayout(
  nodes: readonly Node[], links: readonly Link[], preset: DrawingLayout, fixed: readonly Bounds[], frame?: Bounds,
  guidance?: DrawingAiGuidance,
): Promise<FlowPlan> {
  if (guidance) return planGuidedLayout(nodes, links, fixed, frame, guidance)
  if (preset === 'smart' || preset === 'main' || preset === 'lanes') {
    return planFlowLayout(nodes, links, preset, fixed, frame)
  }
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
    const shift = placeDrawingBoxes(boxes, unionBounds(items.map(({ bounds }) => bounds)), obstacles, frame)
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
