import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'remixicon/fonts/remixicon.css'

import App from './App.vue'
import router from './router'
import './styles/tokens.css'
import './styles/knowledge-icons.css'
import './assets/base.css'
import { useTheme } from './composables/theme'
import { installNativeContextMenuGuard } from './utils/nativeContextMenu'
import { installPreloadErrorRecovery } from './utils/preloadErrorRecovery'

// 分包失败只提示手动恢复，避免节点切换导致页面刷新并丢失未保存输入。
installPreloadErrorRecovery()
// WAO 自己承载右键交互；只屏蔽浏览器菜单，不阻断业务 contextmenu 事件。
const uninstallNativeContextMenuGuard = installNativeContextMenuGuard()
import.meta.hot?.dispose(uninstallNativeContextMenuGuard)

if (location.pathname === '/desktop-pet') document.documentElement.classList.add('desktop-pet-surface')
const app = createApp(App)

app.use(createPinia())
app.use(router)

// 在挂载前初始化主题，避免首屏闪烁
useTheme()

app.mount('#app')
