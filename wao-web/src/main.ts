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

// 新版本发布后，长驻旧页面可能仍引用已删除的哈希分包；在任何懒加载发生前安装恢复。
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
