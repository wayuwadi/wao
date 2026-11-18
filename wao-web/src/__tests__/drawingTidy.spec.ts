// @vitest-environment node
import { describe, expect, it } from 'vitest'
import type { OrderedExcalidrawElement } from '@excalidraw/excalidraw/element/types'
import { tidyDrawing } from '@/utils/drawingTidy'
import { boundsOf, contains, crosses, overlaps, segments } from '@/utils/drawingTidyGeometry'

type Element = OrderedExcalidrawElement
type Arrow = Extract<Element, { type: 'arrow' }>

function shape(id: string, x: number, y: number, patch: Partial<Element> = {}): Element {
  return { id, type: 'rectangle', x, y, width: 56, height: 40, angle: 0, locked: false,
    groupIds: [], frameId: null, boundElements: null, isDeleted: false, ...patch } as Element
}

function edge(id: string, from: string, to: string, x: number, y: number, dx: number, dy: number): Arrow {
  return {
    ...shape(id, x, y), type: 'arrow', elbowed: false, width: Math.abs(dx), height: Math.abs(dy),
    points: [[0, 0], [dx, dy]], lastCommittedPoint: null,
    startBinding: { elementId: from, focus: 0, gap: 4 },
    endBinding: { elementId: to, focus: 0, gap: 4 },
    startArrowhead: null, endArrowhead: 'arrow',
  } as unknown as Arrow
}

const path = (arrow: Arrow) => segments(arrow.points.map(([x, y]) => ({ x: arrow.x + x, y: arrow.y + y })))

describe('草图整理', () => {
  it('对齐并等距分布局部元素，忽略锁定和选区外内容', async () => {
    const elements = [
      shape('a', 0, 0), shape('b', 94, 10), shape('c', 210, 0),
      shape('fixed', 320, 9, { locked: true }), shape('outside', 420, 8),
    ]
    const input = { elements, selectedIds: ['a', 'b', 'c', 'fixed'], request: { mode: 'light', scope: 'selection' } } as const
    const result = await tidyDrawing(input)
    expect(result.changed).toEqual([expect.objectContaining({ id: 'b', x: 105, y: 0 })])
    expect(await tidyDrawing({ ...input, elements: elements.map((item) => result.changed.find(({ id }) => item.id === id) ?? item) }))
      .toMatchObject({ changed: [] })
    expect(elements[1]).toMatchObject({ x: 94, y: 10 })
  })

  it('保持分组内部距离，保护固定转折点和连着它的对象', async () => {
    const original = edge('manual', 'target', 'anchor', 310, 20, 200, 30)
    const manual = { ...original, elbowed: true, points: [[0, 0], [108, 0], [108, 30], [200, 30]],
      fixedSegments: [{ index: 2, start: [108, 0], end: [108, 30] }] } as unknown as Arrow
    const elements = [
      shape('first', 0, 0), shape('part-1', 120, 12, { groupIds: ['group'] }),
      shape('part-2', 185, 13, { groupIds: ['group'] }), shape('last', 300, 0),
      shape('target', 238, 0), shape('anchor', 530, 34), manual,
    ]
    const result = await tidyDrawing({
      elements, selectedIds: ['first', 'part-1', 'last', 'target', 'manual'],
      request: { mode: 'light', scope: 'selection' },
    })
    const a = result.changed.find((element) => element.id === 'part-1')
    const b = result.changed.find((element) => element.id === 'part-2')
    expect(a?.y).toBe(0)
    expect(b?.y).toBe(1)
    expect(result.changed.some(({ id }) => id === 'manual' || id === 'target')).toBe(false)
  })

  it('正交布线绕开批注，保留旧箭头的绑定、方向和文字容器', async () => {
    const arrow = edge('edge', 'a', 'b', 64, 20, 232, 0)
    const label = shape('label', 150, 8, { type: 'text', containerId: 'edge', text: '处理结果' } as Partial<Element>)
    const elements = [
      shape('a', 0, 0), shape('b', 300, 0),
      shape('obstacle', 145, -10, { width: 60, height: 65 }), { ...arrow, boundElements: [{ id: 'label', type: 'text' }] } as Arrow, label,
    ]
    const result = await tidyDrawing({ elements, selectedIds: ['edge'], request: { mode: 'route', scope: 'selection' } })
    expect(result).toMatchObject({ moved: 0, routed: 1 })
    const [changed] = result.changed as Arrow[]
    expect(changed).toMatchObject({
      startBinding: arrow.startBinding, endBinding: arrow.endBinding, startArrowhead: null, endArrowhead: 'arrow',
      boundElements: [{ id: 'label', type: 'text' }],
    })
    expect(path(changed!).length).toBeGreaterThan(1)
    expect(path(changed!)[0]?.[0]).toEqual({ x: 64, y: 20 })
    const routedPath = path(changed!)
    expect(routedPath[routedPath.length - 1]?.[1]).toEqual({ x: 296, y: 20 })
  })

  it('在可解的交叉图中减少连线交叉，所选图形保留原位', async () => {
    const a = edge('a-d', 'a', 'd', 60, 20, 236, 150)
    const b = edge('b-c', 'b', 'c', 60, 170, 236, -150)
    expect(crosses(path(a)[0]!, path(b)[0]!)).toBe(true)
    const result = await tidyDrawing({
      elements: [shape('a', 0, 0), shape('b', 0, 150), shape('c', 300, 0), shape('d', 300, 150), a, b],
      selectedIds: [],
      request: { mode: 'route', scope: 'all' },
    })
    expect(result.moved).toBe(0)
    const [nextA, nextB] = [a, b].map((arrow) => result.changed.find((item) => item.id === arrow.id) as Arrow | undefined ?? arrow)
    expect(path(nextA!).some((s) => path(nextB!).some((t) => crosses(s, t)))).toBe(false)
  })

  it('多组交叉与固定障碍可以同时整理，保持全部箭头的起点和终点', async () => {
    const elements: Element[] = []
    const arrows: Arrow[] = []
    for (let row = 0; row < 4; row++) {
      const y = row * 330
      const [a, b, c, d] = ['a', 'b', 'c', 'd'].map((name) => `${name}-${row}`) as [string, string, string, string]
      elements.push(shape(a, 0, y), shape(b, 0, y + 150), shape(c, 300, y), shape(d, 300, y + 150),
        shape(`memo-${row}`, 158, y + 62, { locked: true }))
      arrows.push(edge(`ad-${row}`, a, d, 60, y + 20, 236, 150), edge(`bc-${row}`, b, c, 60, y + 170, 236, -150))
    }
    const result = await tidyDrawing({ elements: [...elements, ...arrows], selectedIds: [], request: { mode: 'route', scope: 'all' } })
    const after = arrows.map((arrow) => (result.changed.find((item) => item.id === arrow.id) as Arrow | undefined) ?? arrow)
    const afterCrossings = after.flatMap((arrow, index) =>
      after.slice(index + 1).filter((other) => path(arrow).some((leg) => path(other).some((that) => crosses(leg, that)))),
    ).length
    expect(afterCrossings).toBeLessThan(4)
    expect(result.moved).toBe(0)
    after.forEach((arrow, index) => {
      expect(arrow.startBinding).toEqual(arrows[index]!.startBinding)
      expect(arrow.endBinding).toEqual(arrows[index]!.endBinding)
    })
  })

  it('按绑定生成横向流程、纵向流程和树形布局，保留选区外对象', async () => {
    const elements = [shape('a', 190, 130), shape('b', 365, 0), shape('c', 0, 340), shape('outside', 670, 0),
      edge('a-b', 'a', 'b', 250, 150, 111, -130), edge('a-c', 'a', 'c', 220, 170, -190, 166)]
    for (const layout of ['right', 'down', 'tree'] as const) {
      const result = await tidyDrawing({
        elements, selectedIds: ['a', 'b', 'c'], request: { mode: 'layout', scope: 'selection', layout },
      })
      const selected = new Map(elements.map((item) => [item.id, result.changed.find(({ id }) => id === item.id) ?? item]))
      const axis = layout === 'right' ? 'x' : 'y'
      expect(selected.get('a')![axis]).toBeLessThan(selected.get('b')![axis])
      expect(selected.get('a')![axis]).toBeLessThan(selected.get('c')![axis])
      expect(selected.get('outside')).toBe(elements[3])
      expect(selected.get('a-b')).toMatchObject({ startBinding: { elementId: 'a' }, endBinding: { elementId: 'b' } })
    }
  })

  it('自动整理整张画板，根据箭头走向排成纵向流程，并绕开锁定图形', async () => {
    const fixed = shape('fixed', 0, 108, { locked: true, width: 85, height: 60 })
    const elements = [
      shape('a', 0, 0), shape('b', 64, 190), shape('c', 7, 390), fixed,
      edge('a-b', 'a', 'b', 28, 44, 64, 142),
      edge('b-c', 'b', 'c', 92, 234, -57, 152),
    ]
    const result = await tidyDrawing({ elements, selectedIds: [], request: { mode: 'layout', scope: 'all' } })
    const [a, b, c] = elements.map((element) => result.changed.find(({ id }) => id === element.id) ?? element)
    expect(result.moved).toBeGreaterThanOrEqual(2)
    expect([b!.x, c!.x]).toEqual([a!.x, a!.x])
    expect(b!.y).toBeGreaterThan(a!.y + a!.height)
    expect(c!.y).toBeGreaterThan(b!.y + b!.height)
    expect(result.changed.some(({ id }) => id === fixed.id)).toBe(false)
    expect([a!, b!, c!].some((item) => overlaps(boundsOf(item), boundsOf(fixed)))).toBe(false)
  })

  it('根据贴近端点的未绑定箭头恢复流程方向，并带着分组内的标题一起移动', async () => {
    const arrow = { ...edge('loose', 'a', 'b', 160, 100, -84, -80), startBinding: null, endBinding: null }
    const elements = [
      shape('a', 160, 80, { groupIds: ['a-group'] }), shape('b', 16, 0),
      shape('label', 168, 87, { type: 'text', groupIds: ['a-group'], width: 32, height: 20 } as Partial<Element>),
      arrow,
    ]
    const result = await tidyDrawing({ elements, selectedIds: [], request: { mode: 'layout', scope: 'all', layout: 'right' } })
    const [a, b, label, after] = elements.map((element) => result.changed.find(({ id }) => id === element.id) ?? element)
    expect(a!.x).toBeLessThan(b!.x)
    expect([label!.x - a!.x, label!.y - a!.y]).toEqual([8, 7])
    expect(after).toMatchObject({ startBinding: null, endBinding: null })
    expect(path(after as Arrow)).toEqual([[{ x: a!.x + a!.width + 5, y: a!.y + 20 }, { x: b!.x - 5, y: b!.y + 20 }]])
  })

  it('狭窄画框使用紧凑间距，放不下的画框不会阻止其他画框整理', async () => {
    const frame = shape('frame', 400, 0, { type: 'frame', width: 150, height: 100 } as Partial<Element>)
    const cramped = shape('cramped', 0, 0, { type: 'frame', width: 124, height: 84 } as Partial<Element>)
    const elements = [
      frame, cramped,
      shape('a', 491, 53, { frameId: frame.id }), shape('b', 407, 3, { frameId: frame.id }),
      shape('c', 65, 41, { frameId: cramped.id }), shape('d', 0, 0, { frameId: cramped.id }),
      edge('a-b', 'a', 'b', 487, 73, -20, -50), edge('c-d', 'c', 'd', 61, 61, -1, -41),
    ]
    const result = await tidyDrawing({ elements, selectedIds: [], request: { mode: 'layout', scope: 'all', layout: 'right' } })
    const [a, b] = ['a', 'b'].map((id) => result.changed.find((item) => item.id === id)!)
    expect(a!.x + a!.width).toBeLessThan(b!.x)
    expect([a!, b!].every((item) => contains(boundsOf(frame), boundsOf(item)))).toBe(true)
    expect(result.changed.some(({ id }) => id === 'c' || id === 'd')).toBe(false)
  })

  it('画框内一组流程放不下时，仍会整理有空间的另一组', async () => {
    const frame = shape('frame', 0, 0, { type: 'frame', width: 160, height: 350 } as Partial<Element>)
    const frameId = frame.id
    const elements = [frame,
      shape('a', 0, 5, { frameId }), shape('b', 95, 65, { frameId }), shape('c', 3, 125, { frameId }),
      shape('d', 98, 300, { frameId }), shape('e', 5, 215, { frameId }),
      edge('a-b', 'a', 'b', 60, 25, 31, 60), edge('b-c', 'b', 'c', 123, 109, -92, 12),
      edge('d-e', 'd', 'e', 94, 320, -29, -85),
    ]
    const result = await tidyDrawing({ elements, selectedIds: [frameId], request: { mode: 'layout', scope: 'selection', layout: 'right' } })
    const positions = new Map(result.changed.map((element) => [element.id, element]))
    expect(positions.get('d')!.x + 56).toBeLessThan(positions.get('e')!.x)
    expect(positions.get('d')!.y).toBe(positions.get('e')!.y)
    expect(['a', 'b', 'c'].some((id) => positions.has(id))).toBe(false)
  })
})
