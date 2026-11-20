import { describe, expect, it } from 'vitest'
import { parseDrawingPhotoPlan } from '@/utils/drawingPhotoScene'
import { layoutDrawingPhoto } from '@/utils/drawingPhotoLayout'
import { overlaps } from '@/utils/drawingTidyGeometry'

const example = {
  title: '包裹处理',
  nodes: [
    { id: 'label', kind: 'rectangle', text: '核对标签', x: 20, y: 20, width: 120, height: 50 },
    { id: 'sort', kind: 'diamond', text: '符合条件？', x: 200, y: 20, width: 150, height: 50 },
  ],
  arrows: [{ from: 'label', to: 'sort', points: [[140, 45], [140, 45], [200, 45]], uncertain: true }],
  uncertainties: ['原图箭头方向不明'],
}

describe('photo drawing plan', () => {
  it('accepts a fenced plan and removes duplicate points without guessing direction', () => {
    const parsed = parseDrawingPhotoPlan('```json\n' + JSON.stringify(example) + '\n```')
    expect(parsed.arrows[0]).toMatchObject({
      from: 'label', to: 'sort', points: [[140, 45], [200, 45]], uncertain: true,
    })
    expect(parsed.uncertainties).toEqual(example.uncertainties)
  })

  it('rejects unknown endpoints, repeated element IDs and invalid coordinates before import', () => {
    const node = example.nodes[0]!
    const arrow = example.arrows[0]!
    for (const invalid of [
      { ...example, arrows: [{ ...arrow, to: 'missing' }] },
      { ...example, nodes: [node, node] },
      { ...example, nodes: [{ ...node, y: 30_000 }] },
    ]) {
      expect(() => parseDrawingPhotoPlan(JSON.stringify(invalid))).toThrow('识别结果缺少有效')
    }
  })

  it('deduplicates the source inscription, preserves separate steps and lays out only evidenced links', () => {
    const [label, sort] = example.nodes
    const raw = {
      ...example,
      sections: [{ id: 'in', title: '接收' }, { id: 'out', title: '处理' }],
      nodes: [
        { ...label, section: 'in' },
        { ...label, section: 'in', id: 'dup', kind: 'text' },
        { ...label, section: 'out', id: 'again', x: 480 },
        { ...sort, section: 'out', kind: 'text' },
        { ...sort, section: 'in', id: 'caption', kind: 'text', text: '转交', x: 145, y: 22, width: 52 },
      ],
      arrows: [
        { from: 'label', to: 'sort', text: '转交', points: [[140, 45], [200, 45]], uncertain: true },
        { from: 'sort', to: 'again', text: '', points: [[350, 45], [480, 45]], uncertain: false },
        { from: 'again', to: 'absent', text: '文字模糊', points: [[580, 45], [680, 45]], uncertain: true },
      ],
    }
    const arranged = layoutDrawingPhoto(parseDrawingPhotoPlan(JSON.stringify(raw)))
    expect(arranged.nodes.map(({ id }) => id).sort()).toEqual(['again', 'label', 'sort'])
    expect(arranged.nodes.find(({ id }) => id === 'sort')?.kind).toBe('rectangle')
    expect(arranged.arrows).toEqual([
      expect.objectContaining({ from: 'sort', to: 'again', uncertain: false }),
      expect.objectContaining({ from: 'label', to: 'sort', uncertain: true }),
    ])
    expect(arranged.uncertainties).toContainEqual(expect.stringContaining('连线端点待核对'))
    expect(arranged.lanes.map(({ title }) => title)).toEqual(['接收', '处理'])
    expect(arranged.nodes.some((node, index) => arranged.nodes.slice(index + 1).some((other) => overlaps(node, other)))).toBe(false)
  })
})
