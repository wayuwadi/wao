import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
const fragment = new URL('./overall.html', import.meta.url)
const server = createServer(async (req, res) => {
  if (req.url !== '/' && req.url !== '/index.html') {
    res.writeHead(404); res.end('Not found'); return
  }
  try {
    const body = await readFile(fragment, 'utf8')
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' })
    res.end(`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>WAO 七款游戏 · 整体设计 A2</title><style>body{margin:0;padding:12px;background:#eff1ed}button{cursor:pointer}@media(max-width:500px){body{padding:5px}}</style><body>${body}</body></html>`)
  } catch { res.writeHead(500); res.end('Preview unavailable') }
})
server.listen(5268, '127.0.0.1', () => console.log('Design preview: http://127.0.0.1:5268/'))
