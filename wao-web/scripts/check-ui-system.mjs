import { execFileSync } from 'node:child_process'
import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse } from 'vue/compiler-sfc'

const color =
  /#[\da-f]{3,8}\b|\b(?:rgb|hsl|hwb|lab|lch|oklab|oklch|color)\s*\(|\b(?:white|black|red|blue|green|gray|grey|purple|orange|yellow|pink|navy|teal)\b/i
const colorProperty =
  /^(?:color|background(?:-.+)?|border(?:-.+)?|outline(?:-.+)?|box-shadow|text-shadow|fill|stroke|caret-color|accent-color|--.+)$/

/** 检查通用样式声明；不宣称覆盖 JS 动态样式或视觉语义。 */
export function styleViolations(source, filename) {
  const blocks = filename.endsWith('.vue')
    ? parse(source, { filename }).descriptor.styles.map((style) => ({
        text: style.content,
        offset: style.loc.start.line - 1,
      }))
    : [{ text: source, offset: 0 }]
  const violations = []
  for (const block of blocks) {
    // 保留行号，去掉注释，避免示例文本造成误报。
    const text = block.text.replace(/\/\*[\s\S]*?\*\//g, (match) => match.replace(/[^\n]/g, ' '))
    for (const match of text.matchAll(/([\w-]+)\s*:\s*([^;{}]+)(?:;|(?=}))/g)) {
      const property = match[1].toLowerCase(),
        value = match[2].trim()
      const declaration = `${property}: ${value.replace(/\s+/g, ' ')}`
      const line = block.offset + text.slice(0, match.index).split('\n').length
      if (colorProperty.test(property) && color.test(value))
        violations.push({ rule: 'color', declaration, line })
      if (
        /^border(?:-(?:top|bottom)-(?:left|right))?-radius$/.test(property) &&
        !/^(?:0|50%|inherit|initial|unset|var\(--[\w-]+\))$/.test(value)
      )
        violations.push({ rule: 'radius', declaration, line })
      if (
        property === 'box-shadow' &&
        !/^(?:none|inherit|initial|unset|var\(--[\w-]+\))$/.test(value)
      )
        violations.push({ rule: 'shadow', declaration, line })
      // New UI text must use the shared scale. Existing geometry (icons, charts,
      // display numerals) remains in the diff baseline or a specific exception.
      if (
        /^(?:font-size|font)$/.test(property) &&
        /(?:^|[\s(,])\d*\.?\d+(?:px|rem|pt)\b/.test(value)
      )
        violations.push({ rule: 'typography', declaration, line })
    }
  }
  return violations
}

/** 旧声明按次数保留；同一句增加到另一个位置也属于新增。 */
export function addedViolations(current, baseline) {
  const counts = new Map()
  for (const item of baseline) {
    const key = `${item.rule}:${item.declaration}`
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  return current.filter((item) => {
    const key = `${item.rule}:${item.declaration}`,
      remaining = counts.get(key) ?? 0
    if (!remaining) return true
    counts.set(key, remaining - 1)
    return false
  })
}

export function validateExceptions(exceptions) {
  if (!Array.isArray(exceptions)) throw new Error('样式例外必须是数组')
  for (const item of exceptions) {
    if (
      !item ||
      !/^wao-web\/src\/.+\.(vue|css)$/.test(item.file) ||
      item.file.includes('..') ||
      !['color', 'radius', 'shadow', 'typography'].includes(item.rule) ||
      typeof item.declaration !== 'string' ||
      !item.declaration.trim() ||
      typeof item.reason !== 'string' ||
      item.reason.trim().length < 8
    )
      throw new Error('例外必须逐文件、逐声明登记规则和具体理由')
  }
}

function run() {
  const root = resolve(import.meta.dirname, '../..')
  const git = (...args) =>
    execFileSync('git', args, {
      cwd: root,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    }).trim()
  const index = process.argv.indexOf('--base')
  let base = index >= 0 ? process.argv[index + 1] : process.env.WAO_CHANGE_BASE || 'origin/main'
  if (!base || base.startsWith('-')) throw new Error('请提供有效的 --base Git 引用')
  let sha = git('rev-parse', '--verify', `${base}^{commit}`)
  if (index < 0 && sha === git('rev-parse', 'HEAD')) {
    try {
      base = 'HEAD^'
      sha = git('rev-parse', '--verify', `${base}^{commit}`)
    } catch {
      base = 'HEAD'
    }
  }
  const files = new Set(
    [
      ...git('diff', '--name-only', '--diff-filter=ACMR', sha, '--', 'wao-web/src').split('\n'),
      ...git('ls-files', '--others', '--exclude-standard', '--', 'wao-web/src').split('\n'),
    ].filter((file) => /\.(vue|css)$/.test(file)),
  )
  const exceptions = JSON.parse(
    readFileSync(resolve(import.meta.dirname, 'ui-style-exceptions.json'), 'utf8'),
  )
  validateExceptions(exceptions)
  const errors = []
  let checked = 0
  for (const file of files) {
    if (file === 'wao-web/src/styles/tokens.css' || !existsSync(resolve(root, file))) continue
    let original = ''
    try {
      original = git('show', `${sha}:${file}`)
    } catch {
      /* 新文件没有历史基线 */
    }
    const added = addedViolations(
      styleViolations(readFileSync(resolve(root, file), 'utf8'), file),
      styleViolations(original, file),
    )
    for (const item of added) {
      if (
        !exceptions.some(
          (exception) =>
            exception.file === file &&
            exception.rule === item.rule &&
            exception.declaration === item.declaration,
        )
      )
        errors.push(`${file}:${item.line} [${item.rule}] ${item.declaration}`)
    }
    checked++
  }
  console.log(`UI 增量规范检查：${checked} 个样式文件，基线 ${base}`)
  if (errors.length) {
    console.error(errors.join('\n'))
    console.error('请复用语义 token / 公共组件；必要例外逐声明记录理由。视觉验收仍须执行。')
    process.exitCode = 1
  }
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) run()
