import { expect, it } from 'vitest'
import { contrastingText, readTextStyle, setTextRange, styleSvgText } from '@/utils/drawingTextStyle.mjs'

it('重叠选区只替换选中的字符，清除后保留邻近颜色', () => {
  const runs = setTextRange([], 0, 6, '#c92a2a')
  const mixed = setTextRange(runs, 2, 4, '#245eb5')
  expect(setTextRange(mixed, 3, 5)).toEqual([
    { start: 0, end: 2, color: '#c92a2a' }, { start: 2, end: 3, color: '#245eb5' },
    { start: 5, end: 6, color: '#c92a2a' },
  ])
})

it('SVG 为多行中文匹配局部颜色，保留全部文字和高对比背景', () => {
  const element = { text: '商品\n库存预警', originalText: '商品库存预警', fontSize: 20, width: 90, height: 50,
    customData: { waoTextStyle: { source: '商品库存预警', background: '#243a70', runs: [{ start: 2, end: 4, color: '#fff0a8' }] } },
  }
  const namespace = 'http://www.w3.org/2000/svg'
  const node = document.createElementNS(namespace, 'g')
  const lines = element.text.split('\n')
  for (const line of lines) {
    const text = document.createElementNS(namespace, 'text')
    text.textContent = line
    node.append(text)
  }
  styleSvgText(node, element, lines)
  expect(node.firstElementChild?.getAttribute('fill')).toBe('#243a70')
  expect(node.querySelector('text')?.getAttribute('fill')).toBe('#ffffff')
  expect(node.querySelector('tspan')?.textContent).toBe('库存')
  expect(node.querySelector('tspan')?.getAttribute('fill')).toBe('#fff0a8')
  expect(node.textContent).toBe('商品库存预警')
  expect(contrastingText('#fff0a8')).toBe('#000000')
})

it('原生文字编辑后迁移不受影响的高亮范围，丢弃失效样式', () => {
  const element = { text: '新增：库存预警', originalText: '新增：库存预警', customData: {
    waoTextStyle: { source: '库存预警', background: { toString: null }, runs: [{ start: 2, end: 4, color: '#c92a2a' }] },
  } }
  expect(readTextStyle(element)).toEqual({
    source: '新增：库存预警', background: undefined, runs: [{ start: 5, end: 7, color: '#c92a2a' }],
  })
})
