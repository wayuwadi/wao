<script setup lang="ts">
import { storeToRefs } from 'pinia'
import {
  ApiOutlined,
  ArrowRightOutlined,
  CheckCircleFilled,
  CloudServerOutlined,
} from '@ant-design/icons-vue'
import { useSystemStore } from '@/stores/system'

const systemStore = useSystemStore()
const { hello, loading, error, connected } = storeToRefs(systemStore)

function formatServerTime(value?: string) {
  if (!value) return '等待首次请求'
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'medium',
  }).format(new Date(value))
}
</script>

<template>
  <main class="page">
    <section class="hero">
      <div class="eyebrow"><span></span> Infrastructure first</div>
      <h1>第一条请求，<br /><em>从这里出发。</em></h1>
      <p class="lede">
        WAO 的 Phase 0 正在搭建一条可验证的完整链路。点击按钮，让请求依次穿过前端、网关与应用服务。
      </p>

      <a-button
        class="call-button"
        type="primary"
        size="large"
        :loading="loading"
        @click="systemStore.checkConnection"
      >
        调用后端
        <template #icon><ArrowRightOutlined /></template>
      </a-button>
    </section>

    <section class="status-card" aria-live="polite">
      <div class="card-head">
        <div>
          <span class="card-label">REQUEST PATH</span>
          <h2>链路状态</h2>
        </div>
        <span class="status-dot" :class="{ online: connected }"></span>
      </div>

      <div class="path">
        <div class="node active">
          <span class="icon-box"><ApiOutlined /></span>
          <div><strong>Vue</strong><small>Browser</small></div>
        </div>
        <span class="connector"></span>
        <div class="node" :class="{ active: connected }">
          <span class="icon-box"><CloudServerOutlined /></span>
          <div><strong>Gateway</strong><small>:9000</small></div>
        </div>
        <span class="connector"></span>
        <div class="node" :class="{ active: connected }">
          <span class="icon-box"><CheckCircleFilled /></span>
          <div><strong>App</strong><small>:8080</small></div>
        </div>
      </div>

      <div v-if="error" class="result error-result">
        <span>连接失败</span>
        <strong>{{ error }}</strong>
      </div>
      <div v-else class="result" :class="{ success: connected }">
        <span>{{ connected ? 'RESPONSE RECEIVED' : 'READY TO CONNECT' }}</span>
        <strong>{{ hello?.message ?? '等待调用 /api/hello' }}</strong>
        <div class="metadata">
          <span>服务：{{ hello?.service ?? '—' }}</span>
          <span>时间：{{ formatServerTime(hello?.serverTime) }}</span>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.page {
  width: min(1120px, calc(100% - 40px));
  margin: 7vh auto 0;
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(390px, 0.95fr);
  gap: clamp(50px, 8vw, 110px);
  align-items: center;
}

.eyebrow {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--accent);
  font-size: 12px;
  font-weight: 760;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.eyebrow span {
  width: 26px;
  height: 2px;
  background: var(--accent);
}

h1 {
  max-width: 650px;
  margin: 25px 0 24px;
  font-size: clamp(52px, 7vw, 88px);
  line-height: 0.98;
  letter-spacing: -0.055em;
}

h1 em {
  color: var(--accent);
  font-family: Georgia, 'Times New Roman', serif;
  font-weight: 400;
}

.lede {
  max-width: 560px;
  margin: 0 0 36px;
  color: var(--muted);
  font-size: 17px;
  line-height: 1.8;
}

.call-button {
  height: 50px;
  padding-inline: 23px;
  border: 0;
  border-radius: 12px;
  background: var(--ink);
  box-shadow: 0 12px 30px rgba(24, 32, 44, 0.18);
  font-weight: 700;
}

.call-button:hover {
  background: var(--accent) !important;
}

.status-card {
  position: relative;
  overflow: hidden;
  padding: 32px;
  border: 1px solid rgba(255, 255, 255, 0.8);
  border-radius: 28px;
  background: var(--surface);
  box-shadow: var(--shadow);
  backdrop-filter: blur(18px);
}

.status-card::after {
  content: '';
  position: absolute;
  width: 180px;
  height: 180px;
  right: -70px;
  top: -80px;
  border-radius: 50%;
  background: var(--accent-soft);
  filter: blur(2px);
  z-index: -1;
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.card-label {
  color: var(--muted);
  font-size: 10px;
  font-weight: 760;
  letter-spacing: 0.16em;
}

h2 {
  margin: 6px 0 0;
  font-size: 24px;
}

.status-dot {
  width: 11px;
  height: 11px;
  margin-top: 9px;
  border-radius: 50%;
  background: #c6cbd2;
  box-shadow: 0 0 0 6px rgba(198, 203, 210, 0.18);
  transition: 0.3s ease;
}

.status-dot.online {
  background: var(--success);
  box-shadow: 0 0 0 6px rgba(47, 138, 108, 0.14);
}

.path {
  margin: 38px 0 30px;
  display: flex;
  align-items: center;
}

.node {
  min-width: 72px;
  color: var(--muted);
  text-align: center;
  transition: 0.3s ease;
}

.node.active {
  color: var(--ink);
}

.icon-box {
  width: 48px;
  height: 48px;
  margin: 0 auto 9px;
  display: grid;
  place-items: center;
  border: 1px solid var(--line);
  border-radius: 15px;
  background: rgba(255, 255, 255, 0.8);
  font-size: 18px;
}

.node.active .icon-box {
  border-color: rgba(239, 111, 77, 0.38);
  color: var(--accent);
  background: var(--accent-soft);
}

.node strong,
.node small {
  display: block;
}

.node strong {
  font-size: 12px;
}

.node small {
  margin-top: 2px;
  color: var(--muted);
  font-size: 10px;
}

.connector {
  height: 1px;
  flex: 1;
  margin: 0 5px 30px;
  background: repeating-linear-gradient(90deg, var(--line) 0 5px, transparent 5px 10px);
}

.result {
  min-height: 118px;
  padding: 20px;
  border-radius: 17px;
  background: rgba(24, 32, 44, 0.045);
  transition: 0.3s ease;
}

.result > span {
  display: block;
  margin-bottom: 8px;
  color: var(--muted);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 0.15em;
}

.result > strong {
  font-size: 17px;
}

.result.success {
  background: rgba(47, 138, 108, 0.09);
}

.result.success > span {
  color: var(--success);
}

.error-result {
  background: rgba(204, 66, 66, 0.09);
}

.error-result > span {
  color: #b33a3a;
}

.metadata {
  margin-top: 16px;
  display: grid;
  gap: 5px;
  color: var(--muted);
  font-size: 11px;
}

@media (max-width: 860px) {
  .page {
    margin-top: 4vh;
    grid-template-columns: 1fr;
    gap: 48px;
  }

  h1 {
    font-size: clamp(48px, 14vw, 74px);
  }
}

@media (max-width: 480px) {
  .page,
  .topbar {
    width: min(100% - 28px, 1120px);
  }

  .status-card {
    padding: 24px 18px;
  }

  .node {
    min-width: 64px;
  }
}
</style>
