<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import AppLayout from '@/layouts/AppLayout.vue'
import SiteFooter from '@/components/SiteFooter.vue'
const route = useRoute(),
  session = useSessionStore()
const publicCenter = computed(() => route.meta.module === 'capabilities' && !session.authed)
</script>
<template>
  <div v-if="publicCenter" class="cap-public-shell">
    <header class="cap-public-header">
      <RouterLink class="cap-public-brand" to="/capabilities">WAO · 能力中心</RouterLink
      ><RouterLink :to="{ path: '/login', query: { redirect: route.fullPath } }"
        >登录 WAO</RouterLink
      >
    </header>
    <RouterView />
    <SiteFooter />
  </div>
  <AppLayout v-else />
</template>
<style scoped>
.cap-public-shell {
  min-height: 100vh;
  background: var(--wao-bg);
  color: var(--wao-text);
}
.cap-public-header {
  padding: 17px 28px;
  border-bottom: 1px solid var(--wao-line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 15px;
  background: var(--wao-bg-card);
  font-size: 13px;
}
.cap-public-header a {
  color: var(--wao-primary-text);
  text-decoration: none;
}
.cap-public-brand {
  font-weight: 700;
}
@media (max-width: 650px) {
  .cap-public-header {
    padding: 15px 12px;
  }
}
</style>
