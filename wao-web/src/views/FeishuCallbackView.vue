<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { exchangeFeishuCode } from '@/api/feishu'
import { useSessionStore } from '@/stores/session'
import { rememberLoginMethod } from '@/utils/loginPreference'
import { isReauthPopup, completeReauthPopup } from '@/utils/reauthPopup'

import { receiveAuthHandoff, authReturnTarget } from '@/composables/breathingAuth'

const message = ref('正在完成飞书登录…')
const route = useRoute()
const router = useRouter()
const session = useSessionStore()
let disposed = false
onBeforeUnmount(() => {
  disposed = true
})

onMounted(async () => {
  const code = String(route.query.code || '')
  const state = String(route.query.state || '')
  const recovery = isReauthPopup(state)
  if (!recovery) receiveAuthHandoff(state)
  try {
    const resolution = await exchangeFeishuCode(code, state)
    if (disposed) return
    if (recovery) {
      completeReauthPopup(state, !!resolution.session && !resolution.claimRequired)
      message.value = '请回到原页面继续。'
      return
    }
    await session.acceptResolution(resolution)
    if (resolution.session) rememberLoginMethod('feishu')
    if (disposed) return
    await router.replace(resolution.claimRequired ? '/auth/claim' : authReturnTarget())
  } catch (error) {
    if (recovery) completeReauthPopup(state, false)
    message.value = error instanceof Error ? error.message : '飞书登录未完成，请返回后重试。'
  }
})
</script>

<template>
  <main class="callback">
    <p>{{ message }}</p>
    <RouterLink
      v-if="message !== '正在完成飞书登录…'"
      :to="
        authReturnTarget() === '/breathe'
          ? '/breathe'
          : { path: '/login', query: { redirect: authReturnTarget() } }
      "
      >返回登录页</RouterLink
    >
  </main>
</template>

<style scoped>
.callback {
  min-height: 100vh;
  display: grid;
  place-content: center;
  gap: 12px;
  text-align: center;
  color: #5a5e6b;
}
a {
  color: var(--wao-primary-text);
}
</style>
