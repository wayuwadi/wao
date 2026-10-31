import { test } from 'node:test'
import assert from 'node:assert/strict'
import { styleViolations, addedViolations, validateExceptions } from './check-ui-system.mjs'

test('字号规则拦截新增绝对字号和字体简写，保留相对内容标题与零字号图标容器', () => {
  const source = '.a { font-size: 11.5px } .b { font: 400 14px/1.6 sans-serif } .c { font-size: 0.875rem } .d { font-size: var(--wao-font-body) } .e { font-size: 1.5em } .f { font-size: 0 }'
  assert.deepEqual(styleViolations(source, 'style.css').map(v=>v.declaration), [
    'font-size: 11.5px', 'font: 400 14px/1.6 sans-serif', 'font-size: 0.875rem',
  ])
})
test('字号回退值不能绕过规则，Vue 脚本和注释不误报', () => {
  const source='<script setup>const example="font-size: 10px"</script><style>/* font-size: 10px */ .a { font-size: var(--size, 13px); }</style>'
  assert.equal(styleViolations(source, 'Demo.vue').length, 1)
})
test('存量字号可删除但不允许新增重复，图标例外必须逐声明登记', () => {
  const old=styleViolations('.a { font-size: 10px }', 'style.css')
  const current=styleViolations('.a { font-size: 10px } .b { font-size: 10px }', 'style.css')
  assert.equal(addedViolations(current, old).length, 1)
  validateExceptions([{file:'wao-web/src/components/Icon.vue',rule:'typography',declaration:'font-size: 20px',reason:'固定几何图标使用二十像素尺寸，不承载正文'}])
})
