import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'remixicon/fonts/remixicon.css'

import App from './App.vue'
import router from './router'
import './styles/tokens.css'
import './assets/base.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
