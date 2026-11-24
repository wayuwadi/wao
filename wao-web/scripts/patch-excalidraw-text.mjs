import { readFileSync, writeFileSync, copyFileSync } from 'node:fs'

// Apply the same small renderer hook to the locked development and production distributions.
// Fail on an unexpected distribution so a dependency update cannot silently drop highlights.
const packageRoot = new URL('../node_modules/@excalidraw/excalidraw/', import.meta.url)
const modulePath = new URL('../src/utils/drawingTextStyle.mjs', import.meta.url)
const importLine = 'import {paintStyledText,styleSvgText} from "./wao-drawing-text-style.mjs";\n'
const toolbars = [
  ['dist/dev/index.js', 'renderTopRightUI?.(device.editor.isMobile, appState)',
    'renderTopRightUI?.(device.editor.isMobile, appState, name => actionManager.executeAction(actionManager.actions[name], "ui"))'],
  ['dist/prod/index.js', 'm?.(E.editor.isMobile,o),!o.viewModeEnabled',
    'm?.(E.editor.isMobile,o,name=>e.executeAction(e.actions[name],"ui")),!o.viewModeEnabled'],
]
const targets = [
  { path: 'dist/dev/chunk-4FTI6OG3.js',
    canvas: ['for (let index = 0; index < lines.length; index++) {\n          context.fillText(',
      'if (!paintStyledText(context, element, lines, horizontalOffset, lineHeightPx, verticalOffset, rtl)) for (let index = 0; index < lines.length; index++) {\n          context.fillText('],
    svg: ['node.appendChild(text);\n        }\n        const g = maybeWrapNodesInFrameClipPath(',
      'node.appendChild(text);\n        }\n        styleSvgText(node, element, lines);\n        const g = maybeWrapNodesInFrameClipPath('] },
  { path: 'dist/prod/chunk-K2UTITRG.js',
    canvas: ['for(let U=0;U<s.length;U++)n.fillText(s[U],d,U*c+l);',
      'if(!paintStyledText(n,e,s,d,c,l,i))for(let U=0;U<s.length;U++)n.fillText(s[U],d,U*c+l);'],
    svg: ['y.appendChild(_)}let N=eo(e,g,[y],s.frameRendering,t);',
      'y.appendChild(_)}styleSvgText(y,e,w);let N=eo(e,g,[y],s.frameRendering,t);'] },
]

for (const { path, canvas, svg } of targets) {
  const file = new URL(path, packageRoot)
  let code = readFileSync(file, 'utf8')
  if (!code.startsWith(importLine)) {
    for (const [before, after] of [canvas, svg]) {
      if (code.split(before).length !== 2) throw new Error(`Excalidraw text renderer changed: ${path}`)
      code = code.replace(before, after)
    }
    writeFileSync(file, importLine + code)
  }
  copyFileSync(modulePath, new URL('./wao-drawing-text-style.mjs', file))
}

for (const [path, before, after] of toolbars) {
  const file = new URL(path, packageRoot)
  const code = readFileSync(file, 'utf8')
  if (code.includes(after)) continue
  if (code.split(before).length !== 2) throw new Error(`Excalidraw selection toolbar changed: ${path}`)
  writeFileSync(file, code.replace(before, after))
}
