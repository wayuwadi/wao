import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'remixicon/fonts/remixicon.css'
import './styles/tokens.css'
import './styles/base.css'
import './styles.css'
import App from './App.vue'

createApp(App).use(createPinia()).mount('#app')
