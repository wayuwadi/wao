import { unionBounds, type Bounds, type Point } from './drawingTidyGeometry'

export type FlowNode = { id: string; bounds: Bounds; lane?: string; anchor?: Point }
export type FlowLink = {
  id: string
  source: string
  target: string
  label?: string
  captionSize?: Pick<Bounds, 'width' | 'height'>
}
export type FlowLane = { name: string; bounds: Bounds; units: string[] }

const exception = /^(?:否|不通过|失败|异常|取消|撤回|重试|no|false|error|fail)(?:$|[\s：:，,（(])/i
const normal = /^(?:是|通过|成功|正常|yes|true|success)(?:$|[\s：:，,（(])/i

function linkPriority(link: FlowLink) {
  const label = link.label?.trim() ?? ''
  if (exception.test(label)) return -8
  return normal.test(label) ? 4 : 0
}

export function flowLane(label: string | undefined) {
  const firstLine = label?.trim().split('\n', 1)[0]
  return firstLine?.match(/^【?(?:角色|泳道)[：:]\s*([^】]{1,24})】?$/)?.[1]?.trim() || undefined
}

/** Use source nodes first and keep feedback edges out of the rank calculation. */
function orderFlow(nodes: readonly FlowNode[], links: readonly FlowLink[]) {
  const incoming = new Map(nodes.map(({ id }) => [id, 0]))
  const outgoing = new Map(nodes.map(({ id }) => [id, [] as FlowLink[]]))
  for (const link of links) {
    incoming.set(link.target, incoming.get(link.target)! + 1)
    outgoing.get(link.source)!.push(link)
  }
  const state = new Map<string, 'active' | 'done'>()
  const back = new Set<string>()
  const reverseOrder: string[] = []
  function visit(id: string) {
    state.set(id, 'active')
    for (const link of outgoing.get(id)!.sort((a, b) => linkPriority(b) - linkPriority(a))) {
      if (state.get(link.target) === 'active') back.add(link.id)
      if (!state.has(link.target)) visit(link.target)
    }
    state.set(id, 'done')
    reverseOrder.push(id)
  }
  for (const { id } of [...nodes].sort((a, b) => incoming.get(a.id)! - incoming.get(b.id)!)) {
    if (!state.has(id)) visit(id)
  }
  return { order: reverseOrder.reverse(), back, outgoing }
}

export function arrangeFlow(
  nodes: readonly FlowNode[], links: readonly FlowLink[], horizontal: boolean, lanes: boolean, compact: boolean,
) {
  const primary = horizontal ? 'x' : 'y', secondary = horizontal ? 'y' : 'x'
  const length = horizontal ? 'width' : 'height', breadth = horizontal ? 'height' : 'width'
  const sorted = [...nodes].sort((a, b) => a.bounds[primary] - b.bounds[primary] ||
    a.bounds[secondary] - b.bounds[secondary])
  const { order, back, outgoing } = orderFlow(sorted, links)
  const rank = new Map(order.map((id) => [id, 0]))
  const scores = new Map<string, { value: number; next?: string }>()
  for (const id of [...order].reverse()) {
    let best: { value: number; next?: string } = { value: 0 }
    for (const link of outgoing.get(id)!) {
      if (back.has(link.id)) continue
      const value = 1 + linkPriority(link) + scores.get(link.target)!.value
      if (value > best.value) best = { value, next: link.target }
    }
    scores.set(id, best)
  }
  const root = order.reduce((best, id) => scores.get(id)!.value > scores.get(best)!.value ? id : best)
  const main = new Set<string>()
  for (let id: string | undefined = root; id; id = scores.get(id)!.next) main.add(id)
  for (const id of order) {
    for (const link of outgoing.get(id)!) {
      if (!back.has(link.id)) rank.set(link.target, Math.max(rank.get(link.target)!, rank.get(id)! + 1))
    }
  }

  const byId = new Map(sorted.map((node) => [node.id, node]))
  const tracks = new Map<string, string[][]>()
  for (const id of order) {
    const name = lanes ? byId.get(id)!.lane ?? '' : ''
    if (!tracks.has(name)) tracks.set(name, [[]])
  }
  const parentRow = new Map<string, number>()
  const occupied = new Set<string>()
  for (const id of [...order].sort((a, b) => rank.get(a)! - rank.get(b)! || Number(main.has(b)) - Number(main.has(a)))) {
    const name = lanes ? byId.get(id)!.lane ?? '' : ''
    let row = main.has(id) ? 0 : parentRow.get(id) || 1
    while (occupied.has(JSON.stringify([name, rank.get(id), row]))) row++
    occupied.add(JSON.stringify([name, rank.get(id), row]))
    const track = tracks.get(name)!
    track[row] ??= []
    track[row]!.push(id)
    for (const link of outgoing.get(id)!) {
      if (!back.has(link.id) && row && !parentRow.has(link.target) &&
        (!lanes || byId.get(link.target)!.lane === byId.get(id)!.lane)) parentRow.set(link.target, row)
    }
  }

  const gap = compact ? 44 : 104
  const crossGap = compact ? 24 : 60
  const columns = Array.from({ length: Math.max(...rank.values()) + 1 }, () => 0)
  const columnGaps = columns.map(() => gap)
  for (const { source, target, captionSize } of links) {
    const sourceRank = rank.get(source)!
    if (!captionSize || rank.get(target) !== sourceRank + 1) continue
    columnGaps[sourceRank] = Math.max(columnGaps[sourceRank]!, captionSize[length] + 24)
  }
  for (const { id, bounds } of sorted) columns[rank.get(id)!] = Math.max(columns[rank.get(id)!]!, bounds[length])
  let position = 0
  const bases = columns.map((size, index) => {
    const base = position
    position += size + columnGaps[index]!
    return base + size / 2
  })
  const distanceToAnchor = ({ bounds, anchor }: FlowNode) =>
    anchor ? anchor[secondary] - bounds[secondary] : bounds[breadth] / 2
  let crossPosition = 0
  const crossCenters = new Map<string, number>()
  for (const track of tracks.values()) {
    for (const members of track) {
      if (!members?.length) continue
      const items = members.map((id) => byId.get(id)!)
      const before = Math.max(...items.map(distanceToAnchor))
      const after = Math.max(...items.map((item) => item.bounds[breadth] - distanceToAnchor(item)))
      for (const id of members) crossCenters.set(id, crossPosition + before)
      crossPosition += before + after + crossGap
    }
    crossPosition += lanes ? crossGap : 0
  }
  const boxes = nodes.map((node) => ({
    ...node.bounds,
    [primary]: bases[rank.get(node.id)!]! - node.bounds[length] / 2,
    [secondary]: crossCenters.get(node.id)! - distanceToAnchor(node),
  }))
  const envelope = unionBounds(boxes)
  const guides: FlowLane[] = []
  if (lanes) {
    for (const [name, track] of tracks) {
      const units = track.flat()
      const band = unionBounds(boxes.filter((_, index) => units.includes(nodes[index]!.id)))
      guides.push({ name: name || '未归类', units, bounds: {
        x: envelope.x - 20, width: envelope.width + 40, y: band.y - 20, height: band.height + 40,
      } })
    }
  }
  const spine = links.filter(({ source, target, id }) =>
    main.has(source) && scores.get(source)?.next === target && !back.has(id)).map(({ id }) => id)
  return { boxes, guides, spine, feedback: [...back], mainCount: main.size }
}
