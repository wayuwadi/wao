import { ref } from 'vue'
import { defineStore } from 'pinia'

/** 悬浮仅属于本次工作区会话，不把未保存的页面内容写入布局配置。 */
export const useFloatingTabsStore = defineStore('floating-tabs', () => {
  const keys = ref<string[]>([])
  const front = ref('')
  function float(key: string) {
    if (!keys.value.includes(key)) keys.value.push(key)
    front.value = key
  }
  function dock(key: string) {
    keys.value = keys.value.filter((item) => item !== key)
    if (front.value === key) front.value = keys.value[keys.value.length - 1] || ''
  }
  return { keys, front, float, dock }
})
