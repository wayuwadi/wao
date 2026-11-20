type Point = [number, number]
type NodeKind = 'text' | 'rectangle' | 'diamond' | 'ellipse'
type PhotoNode = { id: string; kind: NodeKind; text: string; x: number; y: number; width: number; height: number }
type PhotoArrow = { from: string | null; to: string | null; text: string; points: Point[]; uncertain: boolean }
export interface DrawingPhotoPlan {
  title: string
  nodes: PhotoNode[]
  arrows: PhotoArrow[]
  uncertainties: string[]
}

const kinds = new Set(['text', 'rectangle', 'diamond', 'ellipse'])
const isObject = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object' && !Array.isArray(value)
const coordinate = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value) && Math.abs(value) <= 16000
const text = (value: unknown, max: number): string | undefined =>
  typeof value === 'string' && value.length <= max ? value.replace(/\r\n?/g, '\n').trim() : undefined

function invalid(): never {
  throw new Error('识别结果缺少有效的图形或连线，请重新识别。')
}

export function parseDrawingPhotoPlan(response: string): DrawingPhotoPlan {
  if (response.length > 130_000) return invalid()
  let value: unknown
  try {
    const json = response.trim().replace(/^```(?:json)?\s*([\s\S]+?)\s*```$/i, '$1')
    value = JSON.parse(json)
  } catch {
    return invalid()
  }
  if (!isObject(value) || !Array.isArray(value.nodes) || !value.nodes.length || value.nodes.length > 120 ||
      !Array.isArray(value.arrows) || value.arrows.length > 120) return invalid()

  const ids = new Set<string>()
  const nodes = value.nodes.map((node): PhotoNode => {
    if (!isObject(node)) return invalid()
    const { id, kind, x, y, width, height } = node
    const label = text(node.text, 500)
    if (typeof id !== 'string' || !/^[a-z0-9_-]{1,40}$/i.test(id) || ids.has(id) ||
        typeof kind !== 'string' || !kinds.has(kind) || label === undefined ||
        (kind === 'text' && !label) || !coordinate(x) || !coordinate(y) ||
        !coordinate(width) || !coordinate(height) || width < 12 || height < 12) return invalid()
    ids.add(id)
    return { id, kind: kind as NodeKind, x, y, width, height, text: label }
  })

  const endpoint = (id: unknown) => {
    if (id === undefined || id === null || id === '') return null
    if (typeof id !== 'string' || !ids.has(id)) return invalid()
    return id
  }
  const arrows = value.arrows.map((arrow): PhotoArrow => {
    if (!isObject(arrow)) return invalid()
    const { points } = arrow
    if (!Array.isArray(points) || points.length < 2 || points.length > 18) return invalid()
    const route = points.map((point): Point => {
      if (!Array.isArray(point) || point.length !== 2 || !coordinate(point[0]) || !coordinate(point[1])) return invalid()
      return [point[0], point[1]]
    }).filter((point, index, items) => !index || point[0] !== items[index - 1]![0] || point[1] !== items[index - 1]![1])
    if (route.length < 2) return invalid()
    return { from: endpoint(arrow.from), to: endpoint(arrow.to), points: route,
      text: text(arrow.text, 200) ?? '', uncertain: arrow.uncertain === true }
  })
  const uncertainties = Array.isArray(value.uncertainties)
    ? value.uncertainties.slice(0, 30).flatMap((item) => text(item, 200) || [])
    : []
  return { title: text(value.title, 128) || '白板草图', nodes, arrows, uncertainties }
}

export async function createDrawingPhotoScene(plan: DrawingPhotoPlan) {
  const { convertToExcalidrawElements, FONT_FAMILY } = await import('@excalidraw/excalidraw')
  type Skeleton = NonNullable<Parameters<typeof convertToExcalidrawElements>[0]>[number]
  const nativeId = (id: string) => `photo-node-${id}`
  const fontFamily = FONT_FAMILY.Excalifont
  const left = Math.min(...plan.nodes.map(({ x }) => x))
  const right = Math.max(...plan.nodes.map(({ x, width }) => x + width))
  const fontSize = Math.max(22, Math.min(52, Math.round((right - left) / 44)))
  const skeletons: Skeleton[] = plan.nodes.map((node) => {
    const base = { id: nativeId(node.id), x: node.x, y: node.y, strokeColor: '#26324a', roughness: 0 }
    const lines = node.text.split('\n')
    const labelSize = Math.max(14, Math.min(fontSize, Math.floor((node.height - 8) / (1.25 * lines.length))))
    if (node.kind === 'text') return { ...base, type: 'text', text: node.text, fontFamily, fontSize: labelSize }
    return {
      ...base, type: node.kind, width: node.width, height: node.height,
      backgroundColor: node.text ? '#edf3ff' : 'transparent', fillStyle: 'solid',
      label: node.text ? { text: node.text, fontFamily, fontSize: labelSize } : undefined,
    }
  })
  const shapes = new Set(plan.nodes.filter(({ kind }) => kind !== 'text').map(({ id }) => id))
  plan.arrows.forEach((arrow, index) => {
    const [x, y] = arrow.points[0]!
    type LinearPoints = Extract<Skeleton, { type: 'arrow' | 'line' }>['points']
    const points = arrow.points.map(([px, py]) => [px - x, py - y]) as unknown as LinearPoints
    if (arrow.uncertain) {
      skeletons.push({ id: `photo-line-${index}`, type: 'line', x, y, points, roughness: 0,
        strokeStyle: 'dashed', strokeColor: '#9a6708' })
      if (arrow.text) {
        const middle = Math.floor(arrow.points.length / 2)
        const [ax, ay] = arrow.points[middle - 1]!
        const [bx, by] = arrow.points[middle]!
        skeletons.push({ type: 'text', x: (ax + bx) / 2 + 12, y: (ay + by) / 2 + 12,
          fontFamily, fontSize: Math.round(fontSize * 0.74),
          text: arrow.text, strokeColor: '#9a6708' })
      }
      return
    }
    skeletons.push({
      id: `photo-arrow-${index}`, type: 'arrow', x, y, points, roughness: 0, strokeColor: '#345988',
      endArrowhead: 'arrow',
      start: arrow.from && shapes.has(arrow.from) ? { id: nativeId(arrow.from) } : undefined,
      end: arrow.to && shapes.has(arrow.to) ? { id: nativeId(arrow.to) } : undefined,
      label: arrow.text ? { text: arrow.text, fontFamily, fontSize: Math.round(fontSize * 0.74) } : undefined,
    })
  })
  if (plan.uncertainties.length) {
    const bottom = Math.max(...plan.nodes.map(({ y, height }) => y + height),
      ...plan.arrows.flatMap(({ points }) => points.map(([, y]) => y)))
    skeletons.push({ type: 'text', x: left, y: bottom + 60,
      fontFamily, fontSize: Math.round(fontSize * 0.78), strokeColor: '#9a6708',
      text: ['待核对', ...plan.uncertainties.map((item) => `• ${item}`)].join('\n') })
  }
  const elements = convertToExcalidrawElements(skeletons, { regenerateIds: false })
  return JSON.stringify({ type: 'excalidraw', version: 2, source: 'https://wao.wayuwa.cn',
    elements, appState: { viewBackgroundColor: '#ffffff' }, files: {} })
}
