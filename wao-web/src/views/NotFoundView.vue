<script setup lang="ts">
import { RouterLink, useRouter } from 'vue-router'
const router = useRouter()
const previous = router.options.history.state.back
const canGoBack = typeof previous === 'string' && previous.startsWith('/') && !previous.startsWith('//')
</script>

<template>
  <main class="missing-page">
    <section aria-labelledby="missing-heading">
      <p class="eyebrow">WAO · 404</p>
      <h1 id="missing-heading">找不到这个页面</h1>
      <p>地址可能有误，或入口已经调整。你可以检查链接，或从全部功能重新查找。</p>
      <nav aria-label="页面恢复操作">
        <RouterLink class="primary" to="/navigation">查找功能</RouterLink>
        <RouterLink to="/">返回首页</RouterLink>
        <button v-if="canGoBack" @click="router.back()">返回上一页</button>
      </nav>
    </section>
  </main>
</template>

<style scoped>
.missing-page { min-height: 100dvh; display: grid; place-items: center; padding: 24px; background: var(--wao-bg-page); color: var(--wao-text); }
section { width: min(100%, 520px); padding: 28px; border: 1px solid var(--wao-line); border-radius: 12px; background: var(--wao-bg-card); }
.eyebrow { font-size: 12px; color: var(--wao-text-weak); }
h1 { font-size: 24px; margin: 12px 0; }
p { line-height: 1.7; color: var(--wao-text-weak); }
nav { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 22px; }
a, button { padding: 9px 14px; border: 1px solid var(--wao-line); border-radius: 8px; color: var(--wao-text); background: var(--wao-bg-card); font: inherit; font-size: 14px; text-decoration: none; cursor: pointer; }
.primary { background: var(--wao-primary); border-color: var(--wao-primary); color: white; }
a:focus-visible, button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 3px; }
</style>
