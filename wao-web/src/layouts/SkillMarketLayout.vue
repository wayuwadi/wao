<script setup lang="ts">
import WaoLogo from '@/components/WaoLogo.vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import SiteFooter from '@/components/SiteFooter.vue'
import { useSessionStore } from '@/stores/session'
import { useSkillMarketRoutes } from '@/composables/skillMarketRoutes'

const { embedded = false } = defineProps<{ embedded?: boolean }>()

const session = useSessionStore()
const router = useRouter()
const marketRoutes = useSkillMarketRoutes()
function login() { router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } }) }
</script>

<template>
  <div class="market-shell" :class="{ embedded }">
    <header class="market-header">
      <RouterLink v-if="!embedded" class="market-brand" :to="marketRoutes.path()"><WaoLogo wordmark /><b>Skill 市场</b></RouterLink>
      <strong v-else class="market-section-title">Skill 市场</strong>
      <nav aria-label="技能市场导航" tabindex="0">
        <RouterLink to="/hub">WAO-HUB</RouterLink>
        <RouterLink to="/pets">Pet 仓库</RouterLink>
        <RouterLink :to="marketRoutes.path()">发现 Skill</RouterLink>
        <template v-if="session.authed">
          <RouterLink :to="marketRoutes.path('/publish')">发布</RouterLink>
          <RouterLink :to="marketRoutes.path('/manage')">我的发布</RouterLink>
          <RouterLink v-if="['管理员','超级管理员'].includes(session.currentUser.role)" :to="marketRoutes.path('/reviews')">审核队列</RouterLink>
          <RouterLink v-if="!embedded" class="back-wao" to="/">返回 WAO</RouterLink>
        </template>
        <button v-else class="login-button" @click="login">登录后发布</button>
      </nav>
    </header>
    <main class="market-main"><RouterView /></main>
    <SiteFooter v-if="!embedded" />
  </div>
</template>

<style>
:root { --market-ink:#182035; --market-sub:#687087; --market-line:#e6e9f2; --market-blue:#4358d6; --market-soft:#f4f6ff; --market-page:#f6f7fc; --market-card:#fff; --market-header:rgba(255,255,255,.88); --market-danger:#fff1f2; }
:root[data-theme='dark'] { --market-ink:var(--wao-text); --market-sub:var(--wao-text-3); --market-line:var(--wao-line); --market-blue:var(--wao-primary); --market-soft:var(--wao-primary-soft); --market-page:var(--wao-bg-page); --market-card:var(--wao-bg-card); --market-header:color-mix(in srgb,var(--wao-bg-card) 88%,transparent); --market-danger:var(--wao-danger-bg); }
* { box-sizing:border-box; }
.market-shell { min-height:100vh;background:linear-gradient(180deg,var(--market-page) 0,var(--market-card) 340px);color:var(--market-ink);font-family:Inter,"PingFang SC","Microsoft YaHei",sans-serif; }
.market-header { height:68px;padding:0 max(24px,calc((100vw - 1180px)/2));display:flex;align-items:center;justify-content:space-between;gap:24px;background:var(--market-header);border-bottom:1px solid var(--market-line);backdrop-filter:blur(18px);position:sticky;top:0;z-index:20; }
.market-brand { display:flex;align-items:center;gap:10px;color:inherit;text-decoration:none;white-space:nowrap; }.market-brand b{font-size:17px}
.market-section-title{font-size:14px;white-space:nowrap}.market-shell.embedded{min-height:100%;background:linear-gradient(180deg,var(--market-page) 0,var(--market-card) 340px)}.market-shell.embedded .market-header{height:48px;top:0;padding:0 20px}.market-shell.embedded .market-main{padding-top:28px;min-height:0}
.market-header nav{display:flex;align-items:center;gap:6px;overflow:auto}.market-header nav a,.login-button{border:0;background:transparent;color:var(--market-sub);text-decoration:none;padding:9px 12px;border-radius:9px;font-size:13px;white-space:nowrap;cursor:pointer}.market-header nav a.router-link-active{background:var(--market-soft);color:var(--market-blue);font-weight:700}.market-header nav .back-wao,.login-button{background:var(--market-blue);color:white}.market-main{width:min(1180px,calc(100% - 32px));margin:0 auto;padding:42px 0 70px;min-height:calc(100vh - 120px)}
.market-title{margin:0;font-size:34px;letter-spacing:-1px}.market-lead{color:var(--market-sub);line-height:1.8;margin:10px 0 0}.market-card{background:var(--market-card);border:1px solid var(--market-line);border-radius:18px;box-shadow:var(--wao-shadow-card-hover-2)}
.market-button{display:inline-flex;align-items:center;justify-content:center;gap:7px;border:1px solid var(--market-line);background:var(--market-card);color:var(--market-ink);height:40px;padding:0 16px;border-radius:10px;text-decoration:none;cursor:pointer;font-weight:650}.market-button.primary{background:var(--market-blue);border-color:var(--market-blue);color:#fff}.market-button.danger{color:var(--wao-danger-strong);border-color:var(--wao-danger-line)}.market-button:disabled{opacity:.48;cursor:not-allowed}.market-input,.market-select,.market-textarea{width:100%;border:1px solid var(--market-line);background:var(--market-card);border-radius:10px;padding:10px 12px;color:var(--market-ink);font:inherit;outline:none}.market-input:focus,.market-select:focus,.market-textarea:focus{border-color:var(--market-blue);box-shadow:0 0 0 3px var(--market-soft)}.market-textarea{min-height:100px;resize:vertical}.market-label{display:grid;gap:7px;font-size:13px;font-weight:700}.market-muted{color:var(--market-sub)}.market-error{padding:12px 14px;border-radius:10px;background:var(--market-danger);color:var(--wao-danger-strong);font-size:13px}.market-empty{text-align:center;padding:60px 24px;color:var(--market-sub)}
@media(max-width:720px){.market-header{height:auto;min-height:60px;padding:10px 14px;align-items:flex-start}.market-brand b{display:none}.market-header nav{width:100%;justify-content:flex-end}.market-header nav a,.login-button{padding:8px 9px;font-size:12px}.market-main{width:calc(100% - 20px);padding:26px 0 50px}.market-title{font-size:27px}}
.market-header nav { min-width: 0; }
.market-header nav > * { flex-shrink: 0; }
@media (max-width: 720px) {
  .market-header, .market-shell.embedded .market-header { height: auto; padding: 10px 14px; flex-wrap: wrap; gap: 8px; }
  .market-header nav { flex: 1 1 100%; justify-content: flex-start; }
}
@container app-main (max-width: 640px) {
  .market-shell.embedded .market-header { height: auto; padding: 10px 14px; flex-wrap: wrap; gap: 8px; }
  .market-shell.embedded .market-header nav { flex: 1 1 100%; justify-content: flex-start; }
}
</style>
