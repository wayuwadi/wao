<script setup lang="ts">
import WaoLogo from '@/components/WaoLogo.vue'
import { useSessionStore } from '@/stores/session'
const session = useSessionStore()
</script>
<template>
  <div class="hub-shell">
    <header class="hub-header">
      <RouterLink to="/hub" class="hub-brand"><WaoLogo wordmark /> HUB</RouterLink>
      <nav aria-label="仓库导航">
        <RouterLink to="/skills">Skill 仓库</RouterLink><RouterLink to="/pets">Pet 仓库</RouterLink>
        <RouterLink v-if="session.authed" to="/pets/create">创造 / 上传</RouterLink>
        <RouterLink v-if="session.authed" to="/pets/manage">我的宠物</RouterLink>
        <RouterLink v-if="session.authed" to="/">返回 WAO</RouterLink>
        <RouterLink v-else :to="{ path: '/login', query: { redirect: $route.fullPath } }"
          >登录后发布</RouterLink
        >
      </nav>
    </header>
    <main class="hub-main"><RouterView /></main>
  </div>
</template>
<style>
.hub-shell {
  min-height: 100%;
  background: var(--wao-bg-page, #f7f8fc);
  color: var(--wao-text, #182035);
}
.hub-header {
  padding: 20px max(20px, calc((100% - 1120px) / 2));
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  border-bottom: 1px solid var(--wao-line, #e5e7eb);
  background: var(--wao-bg-card, #fff);
}
.hub-brand {
  font-size: 21px;
  font-weight: 850;
  letter-spacing: -0.6px;
  white-space: nowrap;
}
.hub-header a {
  color: inherit;
  text-decoration: none;
}
.hub-header nav {
  display: flex;
  gap: 18px;
  overflow: auto;
  white-space: nowrap;
  font-size: 14px;
}
.hub-header nav a.router-link-active {
  color: var(--wao-primary, #4358d6);
}
.hub-main {
  max-width: 1120px;
  margin: auto;
  padding: 36px 20px 70px;
}
.hub-main h1 {
  font-size: 32px;
  margin: 0 0 14px;
}
.hub-lead {
  color: var(--wao-text-3, #687087);
  line-height: 1.8;
  max-width: 750px;
}
.hub-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin: 20px 0;
}
.hub-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--wao-line, #ddd);
  border-radius: 10px;
  padding: 10px 16px;
  background: var(--wao-bg-card, #fff);
  color: inherit;
  cursor: pointer;
  text-decoration: none;
  font: inherit;
}
.hub-button.primary {
  background: var(--wao-primary, #4358d6);
  color: white;
  border-color: transparent;
}
.hub-button:disabled {
  opacity: 0.5;
  cursor: wait;
}
.hub-card {
  border: 1px solid var(--wao-line, #e3e6ef);
  border-radius: 18px;
  background: var(--wao-bg-card, #fff);
  padding: 24px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.hub-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 270px), 1fr));
  gap: 20px;
}
.hub-field {
  display: grid;
  gap: 7px;
  margin: 16px 0;
  font-size: 14px;
}
.hub-input {
  padding: 11px;
  border: 1px solid var(--wao-line, #d9dce6);
  border-radius: 9px;
  background: var(--wao-bg-card, #fff);
  color: inherit;
  font: inherit;
  width: 100%;
  min-width: 0;
}
.hub-error {
  background: var(--wao-danger-bg, #fff1f0);
  color: var(--wao-danger-strong, #a52a2a);
  padding: 14px;
  border-radius: 10px;
  margin: 16px 0;
}
.hub-note {
  color: var(--wao-text-3, #687087);
  font-size: 13px;
  line-height: 1.8;
}
.hub-empty {
  padding: 64px 20px;
  text-align: center;
}
.hub-code {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  padding: 16px;
  border-radius: 10px;
  background: var(--wao-bg-page, #f4f5f9);
  font-size: 13px;
  line-height: 1.8;
}
.hub-status {
  font-size: 13px;
  color: var(--wao-text-3, #687087);
}
@media (max-width: 700px) {
  .hub-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 16px;
    padding: 16px;
  }
  .hub-header nav {
    max-width: 100%;
    padding-bottom: 4px;
  }
  .hub-main {
    padding: 24px 14px;
  }
  .hub-main h1 {
    font-size: 26px;
  }
}
</style>
