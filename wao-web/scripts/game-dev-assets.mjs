import { readFile, stat } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

/** 本地验收只读取已独立构建的游戏，不把游戏实现并入主站构建。 */
export function gameAssetsDevPlugin() {
  const gamesRoot = fileURLToPath(new URL('../../wao-game/dist/', import.meta.url))
  return {
    name: 'wao-game-dev-assets',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use('/__game-qa/narrow', async (_req, res) => {
        res.setHeader('Content-Type', 'text/html; charset=utf-8')
        res.setHeader('Cache-Control', 'no-store')
        res.end(await readFile(new URL('../../wao-game/games/dino/narrow.html', import.meta.url)))
      })
      for (const gameId of ['dino', 'rift', 'starfield', 'beasts', 'camp']) server.middlewares.use(`/play-assets/dev/${gameId}`, async (req, res) => {
        const root = resolve(gamesRoot, gameId)
        try {
          const requestPath = decodeURIComponent((req.url ?? '/').split('?')[0])
          const path = resolve(root, '.' + (requestPath === '/' ? '/index.html' : requestPath))
          if (
            !path.startsWith(resolve(root) + sep) ||
            !/\.(html|js|css|json|svg|png|webp|woff2)$/.test(path)
          ) {
            res.statusCode = 404
            res.end()
            return
          }
          if (!(await stat(path)).isFile()) throw new Error('Not a file')
          const types = {
            html: 'text/html; charset=utf-8',
            js: 'text/javascript; charset=utf-8',
            css: 'text/css',
            json: 'application/json',
            svg: 'image/svg+xml',
            png: 'image/png',
            webp: 'image/webp',
            woff2: 'font/woff2',
          }
          res.setHeader('Content-Type', types[path.split('.').at(-1)] ?? 'application/octet-stream')
          res.setHeader('Cache-Control', 'no-store')
          res.end(await readFile(path))
        } catch {
          res.statusCode = 404
          res.end(`Run npm run build:${gameId} in wao-game first`)
        }
      })
    },
  }
}
