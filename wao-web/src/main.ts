import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'remixicon/fonts/remixicon.css'

import App from './App.vue'
import router from './router'
import './styles/tokens.css'
import './assets/base.css'
import { useTheme } from './composables/theme'

const app = createApp(App)

app.use(createPinia())
app.use(router)

// 在挂载前初始化主题，避免首屏闪烁
useTheme()

app.mount('#app')
