<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const router = useRouter()
const busy = ref(false)
const error = ref('')

async function choose(memberId: string) {
  busy.value = true
  error.value = ''
  try { await session.claim(memberId); await router.replace('/') }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '账号认领失败，请重试。' }
  finally { busy.value = false }
}
</script>

<template>
  <main class="claim">
    <section class="card" v-if="session.pendingClaim">
      <h1>确认你的 WAO 成员身份</h1>
      <p>“{{ session.pendingClaim.displayName }}”首次使用 {{ session.pendingClaim.provider }} 登录。请选择已有成员，系统会永久绑定，之后各种登录方式都会进入同一账号。</p>
      <button v-for="candidate in session.pendingClaim.candidates" :key="candidate.id" :disabled="busy" @click="choose(candidate.id)">
        <span :style="{ background: candidate.color }">{{ candidate.initial }}</span>{{ candidate.name }} · {{ candidate.role }}
      </button>
      <div v-if="error" class="error">{{ error }}</div>
    </section>
    <section v-else class="card"><h1>认领请求已失效</h1><RouterLink to="/login">返回登录页重新登录</RouterLink></section>
  </main>
</template>

<style scoped>
.claim { min-height: 100vh; display: grid; place-content: center; background: var(--wao-bg-page); padding: 24px; }
.card { width: min(440px, 90vw); padding: 28px; border: 1px solid var(--wao-line); border-radius: 14px; background: var(--wao-bg-card); box-shadow: var(--wao-shadow-pop); }
h1 { margin: 0; font-size: 22px; } p { color: var(--wao-text-weak); line-height: 1.7; }
button { width: 100%; display: flex; align-items: center; gap: 10px; margin-top: 10px; padding: 12px; border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-card); cursor: pointer; }
button:hover { border-color: var(--wao-primary); } button span { width: 30px; height: 30px; display: grid; place-content: center; border-radius: 50%; color: #fff; }
.error { margin-top: 12px; color: var(--wao-danger-strong); } a { color: var(--wao-primary); }
</style>
