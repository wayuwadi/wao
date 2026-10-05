<script setup lang="ts">
import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SiteFooter from '@/components/SiteFooter.vue'
import WaoLogo from '@/components/WaoLogo.vue'
const route = useRoute()
const router = useRouter()
watch(() => route.hash, hash => {
  if (['#mobile', '#desktop', '#install', '#mac-install'].includes(hash)) void router.replace({ path: '/download/archive', hash })
}, { immediate: true })
</script>

<template>
  <div class="browser-entry">
    <header><a href="/" aria-label="WAO 首页"><WaoLogo wordmark /></a></header>
    <main>
      <p class="eyebrow">WAO / 浏览器使用</p>
      <h1>打开浏览器，就能开始。</h1>
      <p class="intro">在电脑、手机和平板上使用同一个 WAO，随时记录、安排和继续手头的事。</p>
      <a class="primary" href="/">打开 WAO <i class="ri-arrow-right-up-line" aria-hidden="true"></i></a>
      <section aria-label="浏览器使用说明">
        <h2>把入口留在手边</h2>
        <p>将 WAO 保存到浏览器书签，下次可以直接打开。使用原来的账号登录，继续查看已保存的内容。</p>
      </section>
      <section class="history" aria-label="历史客户端">
        <h2>历史客户端</h2>
        <p>当前仅维护浏览器版本。旧客户端安装包与说明保留在历史下载中。</p>
        <RouterLink to="/download/archive">查看历史安装包 <i class="ri-arrow-right-line" aria-hidden="true"></i></RouterLink>
      </section>
    </main>
    <SiteFooter />
  </div>
</template>

<style scoped>
.browser-entry { min-height:100dvh; display:flex; flex-direction:column; background:var(--wao-bg-card); color:var(--wao-text); }
header { padding:24px 32px; } header a { color:inherit; text-decoration:none; }
main { flex:1; width:min(100%, 780px); box-sizing:border-box; padding:36px 28px; margin:auto; }
.eyebrow { color:var(--wao-primary-text); font-size:12px; letter-spacing:.1em; }
h1 { font-size:clamp(26px,4vw,40px); font-weight:550; margin:18px 0; line-height:1.4; }
.intro { font-size:16px; line-height:1.9; color:var(--wao-text-2); max-width:600px; }
.primary { display:inline-flex; gap:12px; align-items:center; margin:18px 0 28px; padding:12px 22px; border-radius:24px; background:var(--wao-primary); color:white; text-decoration:none; }
section { padding:22px 0; border-top:1px solid var(--wao-line); }
h2 { font-size:17px; font-weight:550; margin:0 0 10px; }
section p { color:var(--wao-text-2); font-size:14px; line-height:1.8; }
.history a { color:var(--wao-primary-text); font-size:13px; text-decoration:none; }
@media(max-width:600px) { header { padding:20px; } main { padding:20px; } }
</style>
