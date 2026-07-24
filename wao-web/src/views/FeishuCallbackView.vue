<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { exchangeFeishuCode } from '@/api/feishu'
import { useSessionStore } from '@/stores/session'

const message = ref('正在完成飞书登录…')
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

onMounted(async () => {
  const code = String(route.query.code || '')
  const state = String(route.query.state || '')
  try {
    const profile = await exchangeFeishuCode(code, state)
    session.login(profile.userId)
    await router.replace('/')
  } catch {
    message.value = '飞书登录未完成，请返回后重试。'
  }
})
</script>

<template><main class="callback">{{ message }}</main></template>

<style scoped>.callback { min-height: 100vh; display: grid; place-items: center; color: #5a5e6b; }</style>
