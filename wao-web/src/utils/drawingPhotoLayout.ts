import { routeDrawingConnection } from './drawingOrthogonalRouter'
import { overlaps, samePoint, segments, type Bounds, type Point, type Segment } from './drawingTidyGeometry'
import type { DrawingPhotoPlan, PhotoArrow, PhotoNode, PhotoSection } from './drawingPhotoScene'

const sectionWidth = 700
const sectionGap = 112
const sectionPadding = 28
const nodeGap = 72
const nodeWidth = (sectionWidth - 2 * sectionPadding - nodeGap) / 2
const normalizeLabel = (value: string) => value.normalize('NFKC').toLowerCase().replace(/\s+/g, '')
const center = (box: Bounds): Point => ({ x: box.x + box.width / 2, y: box.y + box.height / 2 })
const toPoint = ([x, y]: [number, number]): Point => ({ x, y })

export function wrapPhotoText(text: string, limit: number) {
  const width = (value: string) => [...value].reduce((sum, char) => sum + (/^[\x00-\x7f]$/.test(char) ? 0.66 : 1), 0)
  return text.split('\n').flatMap((line) => {
    const wrapped: string[] = []
    let next = ''
    const add = (token: string) => {
      if (next && width(next + token) > limit) {
        wrapped.push(next.trimEnd())
        next = ''
      }
      next += next ? token : token.trimStart()
    }
    for (const token of line.match(/[A-Za-z0-9_./:-]+|./gu) ?? []) {
      if (width(token) > limit) [...token].forEach(add)
      else add(token)
    }
    wrapped.push(next.trimEnd())
    return wrapped
  }).join('\n')
}

function distanceToSegment(point: Point, [start, end]: Segment) {
  const dx = end.x - start.x, dy = end.y - start.y
  const ratio = Math.max(0, Math.min(1, ((point.x - start.x) * dx + (point.y - start.y) * dy) / (dx * dx + dy * dy)))
  return Math.hypot(point.x - start.x - ratio * dx, point.y - start.y - ratio * dy)
}

function deduplicate(plan: DrawingPhotoPlan) {
  const linked = new Set(plan.arrows.flatMap(({ from, to }) => [from, to]))
  const aliases = new Map<string, string>()
  const nodes: PhotoNode[] = []
  const labels = new Map<string, PhotoNode[]>()
  // Favor the visible shape over a copy of its text, and avoid merging distinct linked steps.
  for (const node of [...plan.nodes].sort((a, b) => Number(a.kind === 'text') - Number(b.kind === 'text') ||
    Number(linked.has(b.id)) - Number(linked.has(a.id)))) {
    const key = normalizeLabel(node.text)
    const existing = labels.get(key) ?? []
    const prior = key && existing.find((candidate) => candidate.section === node.section &&
      !(linked.has(node.id) && linked.has(candidate.id)) &&
      (overlaps(candidate, node) || Math.hypot(center(node).x - center(candidate).x, center(node).y - center(candidate).y) <= 18))
    if (prior) {
      aliases.set(node.id, prior.id)
      continue
    }
    nodes.push(node)
    existing.push(node)
    labels.set(key, existing)
  }

  const seen = new Set<string>()
  const arrows = plan.arrows.flatMap((arrow) => {
    const from = arrow.from && (aliases.get(arrow.from) ?? arrow.from)
    const to = arrow.to && (aliases.get(arrow.to) ?? arrow.to)
    const endpoints = arrow.uncertain ? [from, to].sort() : [from, to]
    // Unresolved edges may refer to separate notes that happen to have the same label.
    const signature = JSON.stringify([endpoints, normalizeLabel(arrow.text), arrow.uncertain])
    if (from && to && seen.has(signature)) return []
    seen.add(signature)
    return [{ ...arrow, from, to }]
  })
  const linkedRetained = new Set(arrows.flatMap(({ from, to }) => [from, to]))
  const duplicates = new Set<string>()
  for (const arrow of arrows) {
    if (!arrow.text) continue
    const candidates = (labels.get(normalizeLabel(arrow.text)) ?? [])
      .filter(({ id, kind }) => kind === 'text' && !linkedRetained.has(id) && !duplicates.has(id))
    if (!candidates.length) continue
    const route = segments(arrow.points.map(toPoint))
    const nearest = candidates.map((node) => ({
      node, distance: Math.min(...route.map((part) => distanceToSegment(center(node), part))),
    })).sort((a, b) => a.distance - b.distance)[0]!
    if (nearest.distance < 52) duplicates.add(nearest.node.id)
  }
  const retained = new Set(nodes.filter(({ id }) => !duplicates.has(id)).map(({ id }) => id))
  const result = plan.nodes.filter(({ id }) => retained.has(id))
  return { nodes: result.length ? result : nodes, arrows, linkedRetained }
}

type Lane = PhotoSection & { bounds: Bounds }
type SectionContents = { lane: Lane; nodes: PhotoNode[] }

function sectionContents(section: PhotoSection, nodes: PhotoNode[], linked: Set<string | null>): SectionContents {
  const title = wrapPhotoText(section.title, Math.floor((sectionWidth - 2 * sectionPadding) / 29))
  let y = 40 + 34 * title.split('\n').length
  let column = 0, rowHeight = 0
  const nextRow = () => {
    y += rowHeight + nodeGap
    column = 0
    rowHeight = 0
  }
  const placed = nodes.map((node): PhotoNode => {
    const kind = node.kind === 'text' && linked.has(node.id) ? 'rectangle' : node.kind
    const wide = node.text.length > 70 || node.text.split('\n').length > 3
    if (wide && column) nextRow()
    const width = wide ? sectionWidth - 2 * sectionPadding : nodeWidth
    const labelWidth = kind === 'diamond' || kind === 'ellipse' ? width * 0.65 : width
    const text = wrapPhotoText(node.text, Math.floor((labelWidth - 42) / 24))
    const lines = text.split('\n').length
    const height = Math.max(60, lines * (kind === 'diamond' || kind === 'ellipse' ? 44 : 33) + 20)
    const x = sectionPadding + column * (nodeWidth + nodeGap)
    const item = { ...node, kind, text, x, y, width, height }
    rowHeight = Math.max(rowHeight, height)
    if (wide || column) nextRow()
    else column++
    return item
  })
  const height = Math.max(...placed.map((node) => node.y + node.height)) + sectionPadding
  return { lane: { ...section, title, bounds: { x: 0, y: 0, width: sectionWidth, height } }, nodes: placed }
}

export function layoutDrawingPhoto(plan: DrawingPhotoPlan) {
  const { nodes, arrows, linkedRetained } = deduplicate(plan)
  const bySection = new Map<string | undefined, PhotoNode[]>()
  for (const node of nodes) {
    const list = bySection.get(node.section) ?? []
    list.push(node)
    bySection.set(node.section, list)
  }
  const sections = (plan.sections ?? []).flatMap((section) => {
    const contents = bySection.get(section.id)
    return contents?.length ? [sectionContents(section, contents, linkedRetained)] : []
  })
  const other = bySection.get(undefined)
  if (other?.length) sections.push(sectionContents({ id: '__unassigned__', title: '补充信息' }, other, linkedRetained))

  const columns = sections.length > 4 ? 3 : Math.min(2, sections.length)
  const width = columns * sectionWidth + (columns - 1) * sectionGap
  const headerHeight = 126 + 48 * (wrapPhotoText(plan.title, Math.floor(width / 42)).split('\n').length - 1)
  const arranged: PhotoNode[] = []
  const lanes: Lane[] = []
  let y = headerHeight
  for (let i = 0; i < sections.length; i += columns) {
    const row = sections.slice(i, i + columns)
    const height = Math.max(...row.map(({ lane }) => lane.bounds.height))
    row.forEach(({ lane, nodes }, index) => {
      const x = index * (sectionWidth + sectionGap)
      lanes.push({ ...lane, bounds: { x, y, width: sectionWidth, height } })
      arranged.push(...nodes.map((node) => ({ ...node, x: node.x + x, y: node.y + y })))
    })
    y += height + sectionGap
  }

  const notes = new Map(plan.uncertainties.map((item) => [normalizeLabel(item), item]))
  const review = (message: string) => notes.set(normalizeLabel(message), message)
  const byId = new Map(arranged.map((node) => [node.id, node]))
  const obstacles: Bounds[] = [...arranged, ...lanes.map(({ title, bounds }) => ({
    x: bounds.x + sectionPadding, y: bounds.y + 14, width: sectionWidth - 2 * sectionPadding,
    height: 32 * title.split('\n').length,
  })), { x: 0, y: 0, width, height: headerHeight - 28 }]
  const drawn: PhotoArrow[] = []
  const priorSegments: Segment[] = []
  for (const arrow of [...arrows].sort((a, b) => Number(a.uncertain) - Number(b.uncertain))) {
    const from = arrow.from ? byId.get(arrow.from) : undefined
    const to = arrow.to ? byId.get(arrow.to) : undefined
    const context = [from?.text, to?.text, arrow.text].filter(Boolean).join(' / ').replace(/\n/g, ' ').slice(0, 160)
    if (!from || !to) {
      review(context ? `连线端点待核对（${context}）` : '原照片还有无法确认两端的笔迹，待核对。')
      continue
    }
    const route = routeDrawingConnection(from, to, obstacles, priorSegments)
    if (!route) {
      review(`连线位置待核对（${context}）`)
      continue
    }
    const turns = route.filter((point, index) => !index || !samePoint(point, route[index - 1]!))
    priorSegments.push(...segments(turns))
    drawn.push({ ...arrow, text: wrapPhotoText(arrow.text, 12), points: turns.map(({ x, y }) => [x, y]) })
  }
  return { ...plan, width, headerHeight, lanes, nodes: arranged, arrows: drawn, uncertainties: [...notes.values()] }
}
