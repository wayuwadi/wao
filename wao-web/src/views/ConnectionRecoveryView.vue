<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const retrying = ref(false)
const target = computed(() => {
  const value = route.query.redirect
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')
    && !value.startsWith('/connection-recovery') ? value : '/'
})
async function reconnect() {
  if (retrying.value) return
  retrying.value = true
  try {
    await session.initialize()
    if (!session.connectionError) await router.replace(target.value)
  } finally {
    retrying.value = false
  }
}
</script>

<template>
  <main class="connection-page">
    <section aria-labelledby="connection-heading">
      <h1 id="connection-heading">暂时无法连接 WAO</h1>
      <p>还无法确认登录状态，不代表你已经退出登录。请检查网络后重试，连接恢复后会继续打开原页面。</p>
      <p v-if="session.connectionError" class="error" role="alert">{{ session.connectionError }}</p>
      <button :disabled="retrying" @click="reconnect">{{ retrying ? '正在重新连接…' : '重新连接' }}</button>
      <p v-if="retrying" role="status">正在确认登录状态，请稍候。</p>
    </section>
  </main>
</template>

<style scoped>
.connection-page { min-height: 100dvh; display: grid; place-items: center; padding: 24px; background: var(--wao-bg-page); color: var(--wao-text); }
section { width: min(100%, 480px); padding: 28px; border: 1px solid var(--wao-line); border-radius: 12px; background: var(--wao-bg-card); }
h1 { font-size: 22px; margin: 0 0 16px; }
p { line-height: 1.7; color: var(--wao-text-weak); overflow-wrap: anywhere; }
.error { color: var(--wao-danger-strong); }
button { padding: 10px 18px; border: 0; border-radius: 8px; color: white; background: var(--wao-primary); cursor: pointer; }
button:disabled { opacity: .6; cursor: wait; }
button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 4px; }
</style>
