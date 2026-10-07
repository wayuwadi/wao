import { test } from 'node:test'
import assert from 'node:assert/strict'
import { styleViolations, addedViolations, validateExceptions } from './check-ui-system.mjs'

test('颜色、圆角、阴影同时识别，注释和脚本不误报', () => {
  const source = `<script setup>const example = '#fff'</script><style>/* color: red; */ .card { color: #ffffff; border: 1px solid rgb(0, 0, 0); border-radius: 12px; box-shadow: 0 2px 8px var(--shadow); }</style>`
  assert.deepEqual(
    styleViolations(source, 'Demo.vue').map((item) => item.rule),
    ['color', 'color', 'radius', 'shadow'],
  )
})
test('语义变量、几何圆角、无阴影与动画合法', () => {
  assert.equal(
    styleViolations(
      '.card { color: var(--wao-text); border-radius: var(--wao-radius-card); box-shadow: none; } .dot { border-radius: 50%; }',
      'demo.css',
    ).length,
    0,
  )
})
test('CSS 变量中的字面颜色不能成为绕过途径', () => {
  assert.equal(
    styleViolations('.card { --local: #123456; color: var(--wao-text, white); }', 'demo.css')
      .length,
    2,
  )
})
test('历史声明可移动和删除，新增重复声明仍失败', () => {
  const original = styleViolations('.old { color: red; }', 'demo.css')
  assert.equal(
    addedViolations(styleViolations('.new { color: red; }', 'demo.css'), original).length,
    0,
  )
  assert.equal(
    addedViolations(
      styleViolations('.old { color: red; } .new { color: red; }', 'demo.css'),
      original,
    ).length,
    1,
  )
  assert.equal(addedViolations([], original).length, 0)
})
test('只接受有具体文件、声明和理由的例外', () => {
  validateExceptions([
    {
      file: 'wao-web/src/views/Chart.vue',
      rule: 'color',
      declaration: 'fill: #123456',
      reason: '图表的固定领域配色，需要保留导出结果',
    },
  ])
  assert.throws(() =>
    validateExceptions([{ file: '*', rule: 'color', declaration: '*', reason: '旧代码' }]),
  )
  assert.throws(() =>
    validateExceptions([
      {
        file: 'wao-web/src/../a.vue',
        rule: 'color',
        declaration: 'fill: blue',
        reason: '图表的固定领域配色',
      },
    ]),
  )
})
