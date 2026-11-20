import { describe, expect, it } from 'vitest'
import { parseDrawingPhotoPlan } from '@/utils/drawingPhotoScene'

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
})
