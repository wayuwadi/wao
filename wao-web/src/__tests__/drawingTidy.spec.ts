// @vitest-environment node
import { describe, expect, it } from 'vitest'
import type { OrderedExcalidrawElement } from '@excalidraw/excalidraw/element/types'
import { describeDrawingForAi, tidyDrawing } from '@/utils/drawingTidy'
import { parseDrawingAiGuidance } from '@/utils/drawingAiLayoutModel'
import { boundsOf, contains, crosses, insideBounds, overlaps, segments } from '@/utils/drawingTidyGeometry'

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
  it('利用 AI 分区和节点顺序生成预览，保护锁定对象且忽略不存在的模型标识', async () => {
    const elements = [
      shape('prepare', 0, 170),
      shape('call', 200, 89),
      shape('receipt', 45, 0),
      shape('locked', 780, 150, { locked: true }),
    ]
    const selectedIds = ['prepare', 'call', 'receipt']
    const graph = describeDrawingForAi({ elements, selectedIds, request: { mode: 'ai', scope: 'selection' } })
    expect(graph.nodes.map(({ id }) => id)).toEqual(['element:prepare', 'element:call', 'element:receipt'])
    const guidance = parseDrawingAiGuidance(JSON.stringify({
      direction: 'right',
      groups: [
        { title: '处理', ids: ['element:prepare', 'element:call', 'missing'] },
        { title: '回执', ids: ['element:receipt', 'element:call', 'element:locked'] },
      ],
      main: ['invented-edge'], summary: '先处理，再查看回执。',
    }), graph)
    expect(guidance.main).toEqual([])
    const result = await tidyDrawing({ elements, selectedIds, request: { mode: 'layout', scope: 'selection', guidance } })
    expect(result.lanes?.map(({ name, units }) => [name, units])).toEqual([
      ['处理', ['prepare', 'call']], ['回执', ['receipt']],
    ])
    expect(result.changed.map(({ id }) => id).sort()).toEqual(['call', 'prepare', 'receipt'])
    expect(elements[0]).toMatchObject({ x: 0, y: 170 })
  })

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
  it('把正常链路排成主轴，将失败和重试分出，并保留箭头方向与绑定', async () => {
    const elements = [
      shape('start', 0, 120), shape('decision', 145, 65, { type: 'diamond' }),
      shape('success', 275, 130), shape('done', 400, 80), shape('retry', 280, 300),
      edge('entry', 'start', 'decision', 60, 140, 81, -55),
      edge('yes', 'decision', 'success', 205, 85, 66, 65),
      edge('finish', 'success', 'done', 335, 150, 61, -50),
      edge('no', 'decision', 'retry', 173, 108, 100, 206),
      edge('loop', 'retry', 'decision', 276, 320, -136, -218),
      shape('yes-label', 219, 119, { type: 'text', containerId: 'yes', text: '是' } as Partial<Element>),
      shape('no-label', 217, 212, { type: 'text', containerId: 'no', text: '失败' } as Partial<Element>),
      shape('note', 100, 450, { locked: true }),
    ]
    const result = await tidyDrawing({ elements, selectedIds: [], request: { mode: 'layout', scope: 'all', layout: 'main' } })
    const after = (id: string) => result.changed.find((element) => element.id === id) ?? elements.find((element) => element.id === id)!
    const chain = ['start', 'decision', 'success', 'done'].map(after)
    expect(result.mainCount).toBe(4)
    const horizontal = chain.every(({ y }) => y === chain[0]!.y)
    const axis = horizontal ? 'x' : 'y'
    const across = horizontal ? 'y' : 'x'
    expect(chain.every((item, i) => !i || item[axis] > chain[i - 1]![axis])).toBe(true)
    expect(chain.every((item) => item[across] === chain[0]![across])).toBe(true)
    expect(after('retry')[across]).not.toBe(chain[0]![across])
    expect(after('loop')).toMatchObject({ startBinding: { elementId: 'retry' }, endBinding: { elementId: 'decision' }, endArrowhead: 'arrow' })
    expect(path(after('loop') as Arrow).length).toBeGreaterThan(1)
    expect(path(after('loop') as Arrow).some((segment) => insideBounds(segment, boundsOf(after('retry'))))).toBe(false)
    expect(after('note')).toBe(elements[elements.length - 1])
    expect(result.crossings!.after).toBeLessThanOrEqual(result.crossings!.before)
  })

  it('用明确的角色标记生成泳道，跨角色连线保持原有连接关系', async () => {
    const elements = [
      shape('entry', 30, 120), shape('check', 280, 0), shape('done', 510, 150),
      shape('entry-label', 36, 124, { type: 'text', containerId: 'entry', text: '角色：门店\n下单' } as Partial<Element>),
      shape('check-label', 286, 4, { type: 'text', containerId: 'check', text: '【角色：后台】\n校验' } as Partial<Element>),
      shape('done-label', 516, 154, { type: 'text', containerId: 'done', text: '角色：门店\n完成' } as Partial<Element>),
      edge('submit', 'entry', 'check', 90, 140, 186, -120), edge('result', 'check', 'done', 340, 20, 166, 150),
    ]
    const result = await tidyDrawing({ elements, selectedIds: [], request: { mode: 'layout', scope: 'all', layout: 'smart' } })
    expect(result.lanes?.map((lane) => [lane.name, lane.units])).toEqual([
      ['门店', ['entry', 'done']], ['后台', ['check']],
    ])
    expect(result.lanes?.[0]?.bounds.width).toBe(result.lanes?.[1]?.bounds.width)
    expect(result.lanes?.[0]?.bounds.y).toBeLessThan(result.lanes?.[1]?.bounds.y ?? 0)
    const submit = result.changed.find((item) => item.id === 'submit')
    expect(submit).toMatchObject({ startBinding: { elementId: 'entry' }, endBinding: { elementId: 'check' } })
  })

  it('对齐已有分区里的截图及说明，保持箭头和区外标题', async () => {
    const elements = [
      shape('outline', 0, 0, { width: 640, height: 480, backgroundColor: 'transparent' }),
      shape('title', 0, -30, { type: 'text' } as Partial<Element>),
      shape('pic-a', 35, 85, { type: 'image', width: 100, height: 150 } as Partial<Element>),
      shape('pic-b', 280, 65, { type: 'image', width: 120, height: 180 } as Partial<Element>),
      shape('caption-a', 45, 280, { type: 'text', width: 155 } as Partial<Element>),
      shape('caption-b', 285, 293, { type: 'text', width: 150 } as Partial<Element>),
      { ...edge('next', 'pic-a', 'pic-b', 155, 143, 105, 0), startBinding: null, endBinding: null },
    ]
    const result = await tidyDrawing({ elements, selectedIds: [], request: { mode: 'layout', scope: 'all', layout: 'smart' } })
    const after = (id: string) => result.changed.find((element) => element.id === id)
    expect(result).toMatchObject({ sections: 1, moved: 4, routed: 1 })
    expect(after('pic-a')?.y).toBe(after('pic-b')?.y)
    expect(after('caption-a')?.y).toBe(after('caption-b')?.y)
    expect(after('next')).toMatchObject({ startBinding: null, endBinding: null, endArrowhead: 'arrow' })
    expect(after('title')).toBeUndefined()
    expect(after('outline')).toBeUndefined()
  })

  it('画框只在单独请求时扩展，并避免覆盖区外内容', async () => {
    const frame = shape('frame', 0, 0, { type: 'frame', width: 160, height: 120 } as Partial<Element>)
    const elements = [
      frame, shape('a', 2, 1, { frameId: frame.id }), shape('b', 94, 43, { frameId: frame.id }),
      shape('c', 2, 79, { frameId: frame.id }),
      edge('ab', 'a', 'b', 62, 21, 28, 42), edge('bc', 'b', 'c', 94, 69, -32, 29),
    ]
    const input = { elements, selectedIds: ['frame'],
      request: { mode: 'layout', layout: 'main', scope: 'selection' } } as const
    const preview = await tidyDrawing(input)
    expect(preview.canExpandFrames).toBe(true)
    expect(preview.changed.some(({ id }) => id === 'frame')).toBe(false)
    const expanded = await tidyDrawing({ ...input, request: { ...input.request, expandFrames: true } })
    const after = expanded.changed.find(({ id }) => id === 'frame')
    expect(after).toBeDefined()
    expect(after!.width * after!.height).toBeGreaterThan(frame.width * frame.height)
    const withNeighbor = await tidyDrawing({
      ...input, elements: [...elements, shape('right-neighbor', 165, 0), shape('below-neighbor', 0, 125)],
      request: { ...input.request, expandFrames: true },
    })
    expect(withNeighbor.changed.some(({ id }) => id === 'frame')).toBe(false)
  })

  it('含跨区箭头和越界截图时仍重排图片、分行并保留说明原文', async () => {
    const image = (id: string, x: number, y: number, width: number, height: number) =>
      shape(id, x, y, { type: 'image', width, height } as Partial<Element>)
    const upper = shape('sale', 0, 0, { width: 1000, height: 400, backgroundColor: 'transparent' })
    const lower = shape('redeem', 0, 550, { width: 1400, height: 720, backgroundColor: 'transparent' })
    const label = '一段需要根据新列宽重新换行但保留原文的说明。'.repeat(4)
    const elements = [
      upper, lower,
      image('sale-a', 60, 40, 122, 210), image('sale-b', 570, 60, 120, 198),
      ...[42, 265, 485, 720, 945, 1290].map((x, index) =>
        image(`redeem-${index}`, x, 600 + index * 4, 175, 310 - index * 16)),
      shape('note', 60, 960, { type: 'text', text: label, originalText: label, fontSize: 18, lineHeight: 1.25,
        width: 820, height: 23, containerId: null } as unknown as Partial<Element>),
      { ...edge('cross-panel', 'sale-b', 'redeem', 630, 260, -320, 284), endBinding: null },
    ]
    const input = { elements, selectedIds: [], request: { mode: 'layout', scope: 'all', layout: 'smart' } } as const
    const result = await tidyDrawing(input)
    const after = new Map(result.changed.map((item) => [item.id, item]))
    expect(result).toMatchObject({ sections: 2, images: 8, captions: 1, routed: 1 })
    const images = elements.filter((item) => item.type === 'image')
    for (const original of images) {
      const next = after.get(original.id)!
      const panel = original.id.startsWith('sale') ? upper : lower
      expect(contains(boundsOf(panel), boundsOf(next))).toBe(true)
      expect(next.width / next.height).toBeCloseTo(original.width / original.height)
    }
    expect(after.get('redeem-0')?.y).toBe(after.get('redeem-2')?.y)
    expect(after.get('redeem-3')?.y).toBe(after.get('redeem-5')?.y)
    expect(after.get('redeem-3')!.y).toBeGreaterThan(after.get('redeem-0')!.y)
    expect(after.get('note')).toMatchObject({ originalText: label, autoResize: false })
    expect(after.get('cross-panel')).toMatchObject({ startBinding: { elementId: 'sale-b' }, endArrowhead: 'arrow' })
    const repeat = await tidyDrawing({ ...input, elements: elements.map((item) => after.get(item.id) ?? item) })
    expect(repeat.changed).toHaveLength(0)
  })

  function diagramWithUngroupedText() {
    const text = (id: string, x: number, y: number, width: number, height = 24) =>
      shape(id, x, y, { type: 'text', containerId: null, text: id, fontSize: 20, width, height } as unknown as Partial<Element>)
    const loose = (id: string, x: number, y: number, dx: number, dy: number): Arrow =>
      ({ ...edge(id, '', '', x, y, dx, dy), startBinding: null, endBinding: null })
    return [
      shape('source', 5, 100, { width: 320, height: 230 }),
      text('source-title', 103, 58, 120),
      text('source-channels', 60, 150, 155, 100),
      shape('service', 515, 120, { width: 195, height: 165 }),
      text('service-title', 584, 80, 65),
      text('service-skus', 545, 140, 66, 35),
      text('service-list', 553, 228, 123),
      text('destination-title', 876, 66, 138),
      text('destination-channels', 870, 163, 134, 140),
      text('sync-label', 359, 186, 130),
      loose('sync', 329, 220, 180, -8),
      loose('downstream', 625, 168, 229, 8),
    ]
  }

  it('未组合的框、内部文字和标题作为整体，线旁说明及内部出线随布局保留关系', async () => {
    const elements = diagramWithUngroupedText()
    const result = await tidyDrawing({ elements, selectedIds: [], request: { mode: 'layout', scope: 'all', layout: 'smart' } })
    const byId = new Map(elements.map((element) => [element.id, element]))
    const after = new Map(result.changed.map((element) => [element.id, element]))
    const get = (id: string) => after.get(id) ?? byId.get(id)!
    const sameDelta = (id: string, parent: string) => {
      const a = byId.get(id)!, b = byId.get(parent)!
      expect(get(id).x - get(parent).x).toBeCloseTo(a.x - b.x)
      expect(get(id).y - get(parent).y).toBeCloseTo(a.y - b.y)
    }
    expect(result.mainCount).toBe(2)
    expect(result.moved).toBeGreaterThan(0)
    expect(get('source').x + get('source').width).toBeLessThan(get('service').x)
    expect(get('source').y + get('source').height / 2).toBe(get('service').y + get('service').height / 2)
    expect(path(get('sync') as Arrow)).toHaveLength(1)
    for (const id of ['source-title', 'source-channels']) sameDelta(id, 'source')
    for (const id of ['service-title', 'service-skus', 'service-list', 'downstream']) sameDelta(id, 'service')
    expect(after.has('sync-label')).toBe(true)
    expect(get('sync-label').x).toBeGreaterThan(get('source').x + get('source').width)
    expect(get('sync-label').x + get('sync-label').width).toBeLessThan(get('service').x)
    expect(get('destination-title')).toBe(byId.get('destination-title'))
    expect(get('destination-channels')).toBe(byId.get('destination-channels'))
    const downstream = path(get('downstream') as Arrow)
    expect(downstream[downstream.length - 1]![1]).toEqual({ x: 854, y: 176 })
  })

  it('框内文字锁定时保留所属框和文字', async () => {
    const elements = diagramWithUngroupedText().map((element) =>
      element.id === 'service-skus' ? { ...element, locked: true } : element)
    const result = await tidyDrawing({ elements, selectedIds: [], request: { mode: 'layout', scope: 'all', layout: 'smart' } })
    expect(result.changed.some(({ id }) => ['service', 'service-title', 'service-skus', 'service-list'].includes(id))).toBe(false)
  })

})
