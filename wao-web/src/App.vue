<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <a class="brand" href="/" aria-label="WAO 首页">
        <span class="brand-mark">W</span>
        <span>WAO</span>
      </a>
      <span class="phase-badge">PHASE 0</span>
    </header>

    <RouterView />

    <footer class="footer">Built for a reliable first request.</footer>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.topbar {
  width: min(1120px, calc(100% - 40px));
  height: 76px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  color: var(--ink);
  font-size: 18px;
  font-weight: 760;
  letter-spacing: 0.08em;
}

.brand-mark {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 11px;
  color: white;
  background: var(--ink);
  box-shadow: 0 7px 18px rgba(25, 32, 44, 0.2);
}

.phase-badge {
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 7px 11px;
  color: var(--muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.13em;
}

.footer {
  width: min(1120px, calc(100% - 40px));
  margin: auto auto 0;
  padding: 36px 0 28px;
  color: var(--muted);
  font-size: 12px;
  letter-spacing: 0.04em;
}
</style>
