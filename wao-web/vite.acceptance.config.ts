import { mergeConfig } from 'vite'
import base from './vite.config'
export default mergeConfig(base, { server: { host: '127.0.0.1', port: 15173, strictPort: true, proxy: { '/api': { target: 'http://127.0.0.1:19080', changeOrigin: true } } } })
