import { expect, it } from 'vitest'
import { sameDrawingContent } from '@/utils/drawingScene'

it('忽略导出格式、视口状态和对象键顺序，按服务端持久字段比较', () => {
  const left = '{"elements":[{"id":"one","x":2}],"appState":{},"files":{"a":{"id":"a","dataURL":"image"}}}'
  const right = '{"source":"import","files":{"a":{"dataURL":"image","id":"a"}},"appState":{"zoom":2,"viewBackgroundColor":"#ffffff"},"elements":[{"x":2,"id":"one"}]}'
  expect(sameDrawingContent(left, right)).toBe(true)
  expect(sameDrawingContent(left, right.replace('"x":2', '"x":3'))).toBe(false)
  expect(sameDrawingContent(left, right.replace('"image"', '"new-image"'))).toBe(false)
  expect(sameDrawingContent(left, right.replace('#ffffff', '#000000'))).toBe(false)
  expect(sameDrawingContent(left, 'invalid')).toBe(false)
})
