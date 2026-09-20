import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

export function edgeBootstrapHtml(html) {
  const scripts = [...html.matchAll(/<script\b[^>]*\btype="module"[^>]*\bsrc="([^"]+)"[^>]*><\/script>/g)]
  if (scripts.length !== 1 || !/^\/releases\/[0-9a-f]{40}\/assets\//.test(scripts[0][1])) throw new Error('Expected one versioned application entry')
  const styles = [...html.matchAll(/<link\b[^>]*\brel="stylesheet"[^>]*\bhref="([^"]+)"[^>]*>/g)].map((match) => match[1])
  const escaped = JSON.stringify(styles).replaceAll('&', '&amp;').replaceAll('"', '&quot;')
  return html.replace(scripts[0][0], `<script id="wao-edge-bootstrap" type="module" src="/edge-bootstrap.js" data-entry="${scripts[0][1]}" data-styles="${escaped}"></script>`)
    .replace(/<link\b[^>]*\brel="(?:stylesheet|modulepreload)"[^>]*>/g, '')
    .replace(/(href=")\/releases\/[0-9a-f]{40}\/(?!assets\/)/g, '$1/')
}

export function edgeBuildPlugin(sha) {
  return {
    name: 'wao-versioned-static-bootstrap',
    apply: 'build',
    closeBundle() {
      if (!sha) return
      const path = resolve('dist/index.html')
      writeFileSync(path, edgeBootstrapHtml(readFileSync(path, 'utf8')))
    },
  }
}
