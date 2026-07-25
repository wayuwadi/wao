<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { exchangeWechatCode } from '@/api/wechat'
import { useSessionStore } from '@/stores/session'

const message = ref('正在完成微信登录…')
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

onMounted(async () => {
  try {
    const resolution = await exchangeWechatCode(String(route.query.code || ''), String(route.query.state || ''))
    session.acceptResolution(resolution)
    await router.replace(resolution.claimRequired ? '/auth/claim' : '/')
  } catch (error) {
    message.value = error instanceof Error ? error.message : '微信登录未完成，请返回后重试。'
  }
})
</script>

<template><main class="callback"><p>{{ message }}</p><RouterLink v-if="!message.startsWith('正在')" to="/login">返回登录页</RouterLink></main></template>
<style scoped>.callback { min-height: 100vh; display: grid; place-content: center; gap: 12px; text-align: center; color: #5a5e6b; } a { color: var(--wao-primary); }</style>
