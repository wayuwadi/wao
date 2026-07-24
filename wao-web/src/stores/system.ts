import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getHello, type HelloData } from '@/api/hello'

export const useSystemStore = defineStore('system', () => {
  const hello = ref<HelloData | null>(null)
  const loading = ref(false)
  const error = ref('')

  const connected = computed(() => hello.value !== null && !error.value)

  async function checkConnection() {
    loading.value = true
    error.value = ''
    try {
      const response = await getHello()
      hello.value = response.data
    } catch (reason) {
      hello.value = null
      error.value = reason instanceof Error ? reason.message : '无法连接到 WAO 服务'
    } finally {
      loading.value = false
    }
  }

  return { hello, loading, error, connected, checkConnection }
})
