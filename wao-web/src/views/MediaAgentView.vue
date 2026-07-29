<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { apiAgentHealth } from '@/api/agent'

type State = 'checking' | 'loading' | 'ready' | 'error'

const state = ref<State>('checking')
const frameKey = ref(0)
const errorMessage = ref('')

async function loadAgent() {
  state.value = 'checking'
  errorMessage.value = ''
  try {
    const health = await apiAgentHealth()
    if (health.status !== 'UP') throw new Error('Agent 服务尚未就绪')
    frameKey.value += 1
    state.value = 'loading'
  } catch (error) {
    state.value = 'error'
    errorMessage.value = error instanceof Error ? error.message : 'Agent 服务暂时不可用'
  }
}

function frameLoaded() {
  state.value = 'ready'
}

function frameFailed() {
  state.value = 'error'
  errorMessage.value = 'Agent 页面加载失败，请稍后重试'
}

onMounted(loadAgent)
</script>

<template>
  <section class="agent-host">
    <div class="agent-toolbar">
      <div>
        <span class="status-dot" :class="state"></span>
        <span>{{ state === 'ready' ? 'Agent 已连接' : state === 'error' ? 'Agent 不可用' : '正在连接 Agent' }}</span>
      </div>
      <div class="actions">
        <button type="button" @click="loadAgent"><i class="ri-refresh-line"></i>重新加载</button>
        <a href="/agent/" target="_blank" rel="noopener"><i class="ri-external-link-line"></i>新窗口打开</a>
      </div>
    </div>

    <div class="agent-frame-wrap">
      <iframe
        v-if="state === 'loading' || state === 'ready'"
        :key="frameKey"
        class="agent-frame"
        src="/agent/"
        title="自媒体自动化 Agent"
        @load="frameLoaded"
        @error="frameFailed"
      ></iframe>

      <div v-if="state === 'checking' || state === 'loading'" class="state-card overlay">
        <span class="spinner"></span>
        <strong>正在打开自媒体 Agent</strong>
        <p>账号与登录状态会直接沿用当前 WAO 会话。</p>
      </div>

      <div v-if="state === 'error'" class="state-card">
        <i class="ri-cloud-off-line"></i>
        <strong>Agent 暂时无法连接</strong>
        <p>{{ errorMessage }}</p>
        <button type="button" @click="loadAgent">重试</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.agent-host { min-height: 560px; height: calc(100vh - var(--wao-header-h) - 42px); display: flex; flex-direction: column; background: #f5f6fa; }
.agent-toolbar { height: 42px; padding: 0 14px; flex: none; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--wao-line); background: #fff; color: var(--wao-text-weak); font-size: 12px; }
.agent-toolbar > div { display: flex; align-items: center; gap: 7px; }
.status-dot { width: 7px; height: 7px; border-radius: 50%; background: #d59a2e; box-shadow: 0 0 0 3px #fff3dc; }
.status-dot.ready { background: #38a169; box-shadow: 0 0 0 3px #e3f7eb; }
.status-dot.error { background: #d84b4b; box-shadow: 0 0 0 3px #fde8e8; }
.actions { display: flex; gap: 5px; }
.actions button, .actions a { height: 28px; padding: 0 9px; display: inline-flex; align-items: center; gap: 5px; border: 1px solid var(--wao-line); border-radius: 7px; background: #fff; color: var(--wao-text-2); text-decoration: none; cursor: pointer; }
.actions button:hover, .actions a:hover { background: var(--wao-bg-hover-2); }
.agent-frame-wrap { position: relative; flex: 1; min-height: 0; }
.agent-frame { width: 100%; height: 100%; display: block; border: 0; background: #f5f6fa; }
.state-card { position: absolute; inset: 0; display: grid; place-items: center; align-content: center; gap: 9px; background: #f7f8fb; text-align: center; color: var(--wao-text-weak); }
.state-card.overlay { z-index: 1; }
.state-card > i { font-size: 42px; color: #8b7fe5; }
.state-card strong { color: var(--wao-text); font-size: 15px; }
.state-card p { margin: 0; font-size: 12px; }
.state-card button { height: 34px; margin-top: 7px; padding: 0 15px; border: 0; border-radius: 8px; background: var(--wao-primary); color: #fff; cursor: pointer; }
.spinner { width: 23px; height: 23px; border: 3px solid #ddd8ff; border-top-color: #6556d8; border-radius: 50%; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) { .agent-host { height: calc(100dvh - 112px); min-height: 420px; } .agent-toolbar { padding: 0 9px; } .agent-toolbar > div:first-child { display: none; } .actions { width: 100%; justify-content: flex-end; } }
</style>
