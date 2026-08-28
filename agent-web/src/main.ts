import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'remixicon/fonts/remixicon.css'
import './styles/tokens.css'
import './styles/base.css'
import './styles.css'
import App from './App.vue'
import { installNativeContextMenuGuard } from './nativeContextMenu'

// 独立页面和嵌入 WAO 的 iframe 都使用自己的文档事件，因此在 Agent 入口单独安装。
const uninstallNativeContextMenuGuard = installNativeContextMenuGuard()
import.meta.hot?.dispose(uninstallNativeContextMenuGuard)

createApp(App).use(createPinia()).mount('#app')
