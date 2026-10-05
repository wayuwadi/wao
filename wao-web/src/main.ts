import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'remixicon/fonts/remixicon.css'

import App from './App.vue'
import router from './router'
import './styles/tokens.css'
import './styles/page-layout.css'
import './styles/knowledge-icons.css'
import './assets/base.css'
import { useTheme } from './composables/theme'
import { installPreloadErrorRecovery } from './utils/preloadErrorRecovery'
import { retireUnusedStorage } from './utils/retireUnusedStorage'
import { prepareNoteDraftStorage } from './utils/noteDraftStorage'
import { installStandalonePhoneViewport } from './utils/standalonePhoneViewport'

const disposePhoneViewport = installStandalonePhoneViewport()
if (import.meta.hot) import.meta.hot.dispose(disposePhoneViewport)

// 分包失败只提示手动恢复，避免节点切换导致页面刷新并丢失未保存输入。
installPreloadErrorRecovery()
retireUnusedStorage()
if (location.pathname === '/desktop-pet') document.documentElement.classList.add('desktop-pet-surface')
const app = createApp(App)

app.use(createPinia())
app.use(router)

// 在挂载前初始化主题，避免首屏闪烁
useTheme()

void prepareNoteDraftStorage().then(() => app.mount('#app'))
