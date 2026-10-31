<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDataStore } from '@/stores/data'

const data = useDataStore()
const busy = ref<string | null>(null)
const error = ref('')

const projects = computed(() => data.projects.map((project) => ({
  id: project.id,
  name: project.name,
  color: data.areaColor(project.area),
  enabled: project.notifyEnabled !== false,
})))
const enabledCount = computed(() => projects.value.filter((project) => project.enabled).length)
const loading = computed(() => ['idle', 'loading'].includes(data.loadStates.projects))
const loadError = computed(() => data.loadStates.projects === 'error'
  ? data.loadErrors.projects || '项目读取失败'
  : '')

async function toggle(id: string) {
  const project = data.projectById(id)
  if (!project || busy.value) return
  const previous = project.notifyEnabled
  busy.value = id
  error.value = ''
  const saved = await data.updateProject(id, { notifyEnabled: project.notifyEnabled === false })
  busy.value = null
  if (!saved) {
    project.notifyEnabled = previous
    error.value = '项目通知设置没有保存，请重试'
  }
}
</script>

<template>
  <section class="project-policy">
    <header><div><h2>按项目通知</h2><p>项目静音与上方系统策略同时生效，任一关闭都不会发送任务卡片。</p></div><span>{{ enabledCount }} / {{ projects.length }} 个项目通知中</span></header>
    <div v-if="loading" class="empty"><i class="ri-loader-4-line spin"></i>正在读取真实项目…</div>
    <div v-else-if="loadError" class="error" role="alert">{{ loadError }}<button type="button" @click="data.hydrate()">重新读取</button></div>
    <div v-else-if="error" class="error" role="alert">{{ error }}</div>
    <div v-if="!loading && !loadError && projects.length" class="project-list">
      <div v-for="project in projects" :key="project.id" class="project-row">
        <i :style="{ background: project.color }"></i>
        <span><b>{{ project.name }}</b><small>{{ project.enabled ? '任务群通知中' : '任务群通知已静音' }}</small></span>
        <button type="button" :class="{ enabled: project.enabled }" :disabled="busy === project.id" :aria-label="`${project.name}${project.enabled ? '静音' : '开启通知'}`" @click="toggle(project.id)"><span></span></button>
      </div>
    </div>
    <div v-else-if="!loading && !loadError" class="empty">还没有项目；创建项目后可在这里统一管理任务群通知。</div>
  </section>
</template>

<style scoped>
.project-policy { max-width: 820px; overflow: hidden; border: 1px solid var(--wao-line); border-radius: 12px; background: var(--wao-bg-card); }
header { display: flex; align-items: center; gap: 12px; padding: 15px 16px; background: var(--wao-bg-sub); }
header > div { flex: 1; min-width: 0; }
h2, p { margin: 0; }
h2 { font-size: var(--wao-font-body); }
p { margin-top: 5px; color: var(--wao-text-weak); font-size: var(--wao-font-caption); }
header > span { color: var(--wao-primary-text); font-size: var(--wao-font-caption); }
.project-row { min-height: 54px; display: flex; align-items: center; gap: 10px; padding: 6px 16px; border-top: 1px solid var(--wao-divider); }
.project-row > i { width: 8px; height: 8px; flex: none; border-radius: 50%; }
.project-row > span { flex: 1; display: grid; gap: 2px; }
.project-row b { font-size: var(--wao-font-caption); }
.project-row small { color: var(--wao-text-3); font-size: var(--wao-font-help); }
.project-row button { width: 38px; height: 22px; padding: 2px; border: 0; border-radius: 99px; background: var(--wao-line-control); cursor: pointer; }
.project-row button span { display: block; width: 18px; height: 18px; border-radius: 50%; background: #fff; box-shadow: var(--wao-shadow-sm); transition: transform .16s; }
.project-row button.enabled { background: var(--wao-primary); }
.project-row button.enabled span { transform: translateX(16px); }
.project-row button:disabled { opacity: .55; cursor: default; }
.empty, .error { padding: 18px 16px; color: var(--wao-text-3); font-size: var(--wao-font-help); }
.error { color: var(--wao-danger-strong); background: var(--wao-danger-bg); }
.error button { margin-left: 8px; border: 0; background: transparent; color: inherit; text-decoration: underline; cursor: pointer; }
.spin { display: inline-block; margin-right: 6px; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 640px) { header { align-items: flex-start; }.project-row { padding-inline: 12px; } }
</style>
