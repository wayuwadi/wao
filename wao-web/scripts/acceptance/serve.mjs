import { resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { realpathSync } from 'node:fs'
import { createServer, loadConfigFromFile } from 'vite'
import { faultMiddleware, localOrigin } from './faults.mjs'

const root = resolve(import.meta.dirname, '../..')
const target = localOrigin(process.env.WAO_ACCEPTANCE_API ?? 'http://127.0.0.1:18080', 'WAO_ACCEPTANCE_API')
const port = Number(process.env.WAO_ACCEPTANCE_PORT ?? 5231)
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('验收端口必须在 1024—65535 之间')
const faultFile = resolve(process.env.WAO_ACCEPTANCE_FAULT_FILE ?? `${tmpdir()}/wao-draft-acceptance-${port}/fault.json`)
process.env.WAO_VUE_DEVTOOLS = 'false'
const loaded = await loadConfigFromFile({ command: 'serve', mode: 'development' }, resolve(root, 'vite.config.ts'))
const server = await createServer({
  ...loaded.config, configFile: false, root,
  plugins: [...loaded.config.plugins, {
    name: 'wao-local-draft-acceptance',
    configureServer(server) {
      server.middlewares.use(faultMiddleware(faultFile))
    },
  }],
  server: { ...loaded.config.server, host: '127.0.0.1', port, strictPort: true,
    fs: { allow: [root, realpathSync(resolve(root, 'node_modules'))] },
    proxy: { '/api': { target, changeOrigin: true,
      configure(proxy) {
        proxy.on('proxyRes', (response, request) => {
          if (request.method === 'POST' && request.url?.split('?')[0] === '/api/docs') {
            console.log(`验收文档创建响应：HTTP ${response.statusCode}`)
          }
        })
      },
    } } },
})
await server.listen()
console.log(`验收入口：http://127.0.0.1:${port}`)
console.log(`业务服务：${target}`)
console.log(`单文档故障配置：${faultFile}`)
console.log('故障默认关闭；不启动数据库，不执行迁移，不改变业务服务配置。')
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => { await server.close(); process.exit(0) })
