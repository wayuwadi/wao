<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { apiInfrastructureOverview, type InfraOverview, type InfraNode } from '@/api/infrastructure'
import { infraBytes, infraFilter, infraMemoryUsed, infraPercent, infraRunning } from '@/utils/infrastructure'
import { createVisibilityPoller } from '@/composables/visibilityPolling'
import ResourceHistory from '@/components/infrastructure/ResourceHistory.vue'

const data = ref<InfraOverview>()
const busy = ref(false)
const error = ref('')
const forbidden = ref(false)
const selected = ref('linux2')
const hours = ref(24)
const query = ref('')
const state = ref('all')
const clock = ref(Date.now() / 1000)
const node = computed(() => data.value?.nodes.find(n => n.id === selected.value) ?? data.value?.nodes[0])
const stale = (n: InfraNode) => !n.collectedAt || clock.value - n.collectedAt > 180
const status = (n: InfraNode) => n.error ? '采集失败' : stale(n) ? '数据过期' : n.online ? '采集正常' : '等待采集'
const time = (value?: number) => value ? new Date(value * 1000).toLocaleString('zh-CN', { hour12: false }) : '尚未采集'
const rootDisk = (n: InfraNode) => n.disks?.find(d => d.mount === '/')
const filtered = computed(() => infraFilter(node.value?.services ?? [], query.value, state.value))
const groups = computed(() => {
  const map = new Map<string, typeof filtered.value>()
  for (const service of filtered.value) map.set(service.group, [...(map.get(service.group) ?? []), service])
  return [...map.entries()].sort(([a], [b]) => a === '其他服务' ? 1 : b === '其他服务' ? -1 : a.localeCompare(b))
})
const running = computed(() => node.value?.services?.filter(infraRunning).length ?? 0)
const days = computed(() => node.value?.uptimeSeconds == null ? '—' : `${Math.floor(node.value.uptimeSeconds / 86400)} 天 ${Math.floor(node.value.uptimeSeconds % 86400 / 3600)} 小时`)
let active = true
let requested = false
async function load() {
  if (busy.value) { requested = true; return }
  busy.value = true
  const requestedHours = hours.value
  try {
    const result = await apiInfrastructureOverview(requestedHours)
    if (requestedHours === hours.value) data.value = result
    error.value = ''; forbidden.value = false
  } catch (e) {
    const statusCode = (e as { response?: { status?: number } })?.response?.status
    forbidden.value = statusCode === 403
    if (forbidden.value) data.value = undefined
    error.value = forbidden.value ? '仅管理员和超级管理员可以查看基础设施信息。' : '总览暂时无法刷新，请重试。已有数据保留，采集时间见各节点。'
  } finally {
    busy.value = false; clock.value = Date.now() / 1000
    if (requested && active) { requested = false; void load() }
  }
}
const poller = createVisibilityPoller(load, () => 60000)
let clockTimer: ReturnType<typeof setInterval> | undefined
function start() { active = true; poller.start(); if (!clockTimer) clockTimer = setInterval(() => { clock.value = Date.now() / 1000 }, 10000) }
function stop() { active = false; poller.stop(); clearInterval(clockTimer); clockTimer = undefined }
onMounted(start); onActivated(start); onDeactivated(stop); onBeforeUnmount(stop)
watch(hours, () => void load())
watch(selected, () => { query.value = ''; state.value = 'all' })
</script>

<template>
  <main class="infra-page">
    <header class="page-header">
      <div><span class="eyebrow">WAO · 运行环境</span><h1>基础设施总览</h1><p>机器还有多少余量，应用部署在哪里。</p></div>
      <div class="header-actions"><span>每 {{ data?.intervalSeconds ?? 60 }} 秒采集</span><button :disabled="busy" @click="load"><i class="ri-refresh-line"></i>{{ busy ? '刷新中…' : '刷新数据' }}</button></div>
    </header>
    <div v-if="error" class="notice danger" role="alert">{{ error }}</div>
    <div v-if="!data && busy" class="empty" role="status">正在读取主机与应用信息…</div>
    <div v-else-if="!data && !forbidden" class="empty">暂无采集数据。<button @click="load">重新读取</button></div>
    <template v-if="data">
      <div class="node-grid">
        <button v-for="host in data.nodes" :key="host.id" class="node-card" :class="{ selected: host.id === node?.id }" :aria-pressed="host.id === node?.id" @click="selected = host.id">
          <div class="node-title"><strong><i class="ri-server-line"></i> {{ host.name }}</strong><span class="badge" :class="{ warning: !!host.error || stale(host) }">{{ status(host) }}</span></div>
          <p>{{ host.role }}</p>
          <div class="node-metrics"><span>CPU <b>{{ infraPercent(host.cpuPercent) }}</b></span><span>内存可用 <b>{{ infraBytes(host.memory?.available) }}</b></span><span>根磁盘可用 <b>{{ infraBytes(rootDisk(host)?.available) }}</b></span></div>
          <small>最后成功 {{ time(host.collectedAt) }}</small>
        </button>
      </div>
      <template v-if="node">
        <section class="node-detail">
          <div class="section-heading"><div><h2>{{ node.name }} <span>{{ node.cpuCores ?? '—' }} 核 · {{ infraBytes(node.memory?.total) }} 内存</span></h2><p>{{ node.os ?? '系统信息待采集' }} · {{ node.hostname ?? '—' }} · {{ node.address }}</p></div><span>已运行 {{ days }}</span></div>
          <div v-if="node.error || stale(node)" class="notice warning" role="status">{{ node.error ?? '采集数据超过 3 分钟未更新。' }} 以下为最后成功快照；最近尝试 {{ time(node.lastAttemptAt) }}。</div>
          <div v-for="warning in node.warnings" :key="warning" class="notice warning">{{ warning }}</div>
          <div class="resource-grid">
            <div class="resource"><label>CPU 使用率</label><strong>{{ infraPercent(node.cpuPercent) }}</strong><progress :value="node.cpuPercent ?? 0" max="100" aria-label="CPU 使用率"></progress><small>负载（1 / 5 / 15 分钟）{{ node.loadAverage?.map(x => x.toFixed(2)).join(' / ') ?? '—' }}</small></div>
            <div class="resource"><label>内存使用率</label><strong>{{ infraPercent(infraMemoryUsed(node.memory)) }}</strong><progress :value="infraMemoryUsed(node.memory) ?? 0" max="100" aria-label="内存使用率"></progress><small>可用 {{ infraBytes(node.memory?.available) }} / 总计 {{ infraBytes(node.memory?.total) }}</small></div>
            <div class="resource"><label>交换分区已用</label><strong>{{ infraBytes(node.memory?.swapUsed) }}</strong><progress :value="node.memory?.swapTotal ? node.memory.swapUsed / node.memory.swapTotal * 100 : 0" max="100" aria-label="交换分区使用率"></progress><small>总计 {{ infraBytes(node.memory?.swapTotal) }} · 占用不等同于当前换页压力</small></div>
          </div>
          <div class="disk-list"><div v-for="disk in node.disks" :key="disk.mount" class="disk"><b><i class="ri-hard-drive-2-line"></i> {{ disk.mount }}</b><progress :value="disk.total ? disk.used / disk.total * 100 : 0" max="100" :aria-label="`${disk.mount} 磁盘使用率`"></progress><span>可用 <strong>{{ infraBytes(disk.available) }}</strong> / {{ infraBytes(disk.total) }} <small>已用 {{ infraBytes(disk.used) }}</small></span></div><p v-if="!node.disks?.length" class="muted">磁盘数据暂不可用。</p></div>
        </section>
        <section class="panel">
          <div class="section-heading"><div><h2>资源趋势</h2><p>从接入时开始积累 · {{ node.history.length }} 个采样点 · 缺测时间不补零</p></div><select v-model.number="hours" aria-label="历史时间范围"><option :value="1">最近 1 小时</option><option :value="24">最近 24 小时</option><option :value="168">最近 7 天</option></select></div>
          <div v-if="node.history.length" class="charts"><ResourceHistory :points="node.history" metric="usage" /><ResourceHistory :points="node.history" metric="capacity" /></div>
          <div v-else class="empty">还没有历史采样，首次采集完成后会在这里显示。</div>
        </section>
        <section class="panel services">
          <div class="section-heading"><div><h2>部署应用与服务 <span>{{ node.services?.length ?? 0 }} 项 · {{ running }} 项运行中</span></h2><p>按应用归类；展开可查看组件、镜像版本、运行状态与资源占用。</p></div></div>
          <div class="filters"><input v-model="query" placeholder="搜索 Jenkins、JumpServer、WeKnora 或服务名" aria-label="搜索应用与服务" /><select v-model="state" aria-label="服务运行状态"><option value="all">全部状态</option><option value="running">运行中</option><option value="stopped">未运行</option></select><span>{{ filtered.length }} 项匹配</span></div>
          <details v-for="[group, items] in groups" :key="`${node.id}:${group}`" :open="group !== '其他服务' || !!query" class="service-group"><summary><strong>{{ group }}</strong><span>{{ items.length }} 项 · {{ items.filter(infraRunning).length }} 项运行中</span><a v-if="group === 'WeKnora'" href="/apps/weknora" @click.stop>打开知识库 ↗</a><a v-if="group === 'Jenkins'" href="/jenkins/" target="_blank" rel="noopener" @click.stop>打开 Jenkins ↗</a><a v-if="group === 'JumpServer'" href="/api/auth/jumpserver" target="_blank" rel="noopener" @click.stop>打开资源管理 ↗</a></summary>
            <div class="table-scroll"><table><thead><tr><th>组件 / 镜像版本</th><th>运行方式</th><th>状态</th><th>CPU</th><th>内存</th></tr></thead><tbody><tr v-for="service in items" :key="`${service.kind}:${service.name}`"><td><strong>{{ service.name }}</strong><small v-if="service.image">{{ service.image }}</small><small v-if="service.ports">端口 {{ service.ports }}</small></td><td>{{ service.kind === 'docker' ? 'Docker' : service.kind === 'user-service' ? '用户服务' : '系统服务' }}</td><td><span class="badge" :class="{ warning: !infraRunning(service) || service.status.includes('unhealthy') }">{{ service.state }}</span><small>{{ service.status }}</small></td><td>{{ infraPercent(service.cpuPercent) }}</td><td>{{ infraBytes(service.memoryBytes) }}<small v-if="service.memoryLimitBytes && service.memoryLimitBytes < (node.memory?.total ?? 0) * 0.99">上限 {{ infraBytes(service.memoryLimitBytes) }}</small></td></tr></tbody></table></div>
          </details>
          <div v-if="!groups.length" class="empty">{{ node.services?.length ? '没有匹配的服务，请调整搜索或筛选。' : '暂无服务清单，可能尚未采集或没有读取权限。' }}</div>
          <p class="footnote">主机 CPU 100% 表示全部核心，服务 CPU 100% 表示一个核心。服务资源统计可能重叠，请以主机总量判断余量。“—”表示未取得统计，不代表零占用。停止的容器仍保留在清单中。</p>
        </section>
      </template>
    </template>
  </main>
</template>

<style scoped>
.infra-page { padding: 26px 28px 40px; max-width: none; margin: auto; color: var(--wao-text); }
.page-header,.section-heading,.header-actions,.node-title { display:flex; align-items:center; justify-content:space-between; gap:16px; }
h1 { font-size:26px; margin:5px 0 7px; letter-spacing:-.5px; } h2 { font-size:17px; margin:0 0 7px; } h2 span { font-size:12px; font-weight:400; margin-left:8px; color:var(--wao-text-weak); }
p { margin:0; color:var(--wao-text-weak); font-size:12px; line-height:1.7; }.eyebrow { font-size:11px; letter-spacing:1px; color:var(--wao-primary); }.header-actions { color:var(--wao-text-weak); font-size:12px; }
button,select,input { font:inherit; color:inherit; border:1px solid var(--wao-line); border-radius:8px; background:var(--wao-bg-card); }button,select { cursor:pointer; }button { padding:8px 13px; }button:disabled { opacity:.6; cursor:wait; }button:focus-visible,summary:focus-visible { outline:2px solid var(--wao-primary); outline-offset:3px; }select { padding:8px 10px; }
.node-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:14px; margin:24px 0 20px; }.node-card { padding:18px; text-align:left; border-radius:13px; transition:border-color .15s; min-width:0; }.node-card.selected { border-color:var(--wao-primary); box-shadow:0 0 0 1px var(--wao-primary); }.node-title strong { font-size:17px; }.node-card p { margin:10px 0 16px; }.node-card small { font-size:10px; color:var(--wao-text-weak); }.node-metrics { display:grid; gap:8px; margin-bottom:16px; }.node-metrics span { display:flex; justify-content:space-between; font-size:12px; color:var(--wao-text-weak); }.node-metrics b { color:var(--wao-text); font-variant-numeric:tabular-nums; }
.badge { display:inline-block; border-radius:20px; padding:3px 8px; background:#edf8f2; color:#257952; font-size:10px; white-space:nowrap; }.badge.warning { color:#a06e24; background:#fff5df; }.panel,.node-detail { padding:21px; border:1px solid var(--wao-line); border-radius:13px; background:var(--wao-bg-card); margin-bottom:18px; }.section-heading>span { font-size:12px; color:var(--wao-text-weak); }.resource-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:26px; margin:24px 0; }.resource { display:flex; flex-direction:column; gap:9px; }.resource label { color:var(--wao-text-weak); font-size:12px; }.resource>strong { font-size:26px; font-weight:600; font-variant-numeric:tabular-nums; }.resource small,.muted { font-size:11px; color:var(--wao-text-weak); }progress { appearance:none; width:100%; height:5px; border:0; border-radius:10px; overflow:hidden; background:var(--wao-bg-page); }progress::-webkit-progress-bar { background:var(--wao-bg-page); }progress::-webkit-progress-value { background:#8677da; border-radius:10px; }.resource:nth-child(2) progress::-webkit-progress-value { background:#44a89b; }.resource:nth-child(3) progress::-webkit-progress-value { background:#d5a05c; }.disk { display:grid; grid-template-columns:minmax(50px,100px) minmax(60px,1fr) auto; align-items:center; gap:20px; padding-top:12px; font-size:12px; }.disk small { color:var(--wao-text-weak); margin-left:8px; }.charts { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); margin-top:18px; }.filters { display:flex; gap:10px; align-items:center; margin:17px 0; font-size:12px; }.filters input { padding:9px 11px; flex:1; min-width:100px; }.filters>span { color:var(--wao-text-weak); }.service-group { border-top:1px solid var(--wao-line); }.service-group summary { cursor:pointer; padding:14px 0; font-size:13px; }.service-group summary span { color:var(--wao-text-weak); margin-left:12px; font-size:11px; }.service-group a { float:right; color:var(--wao-primary); font-size:11px; }.table-scroll { overflow-x:auto; }table { width:100%; min-width:580px; border-collapse:collapse; font-size:12px; text-align:left; }th { background:var(--wao-bg-page); font-size:10px; color:var(--wao-text-weak); font-weight:500; }th,td { padding:10px 12px; border-bottom:1px solid var(--wao-line); }td:first-child { min-width:230px; }td strong { font-weight:500; overflow-wrap:anywhere; }td small { display:block; font-size:10px; color:var(--wao-text-weak); margin-top:4px; overflow-wrap:anywhere; }td:nth-last-child(-n+2) { white-space:nowrap; font-variant-numeric:tabular-nums; }.notice { margin:14px 0; padding:12px 15px; border-radius:8px; font-size:12px; background:#fff5df; color:#8a601c; }.notice.danger { background:#fff0f0; color:#ad4040; }.empty { padding:40px 16px; text-align:center; font-size:13px; color:var(--wao-text-weak); }.footnote { font-size:10px; margin-top:18px; }
@media(max-width:1000px){.infra-page { padding:20px 16px; }.node-grid { gap:9px; }.node-card { padding:13px; }.node-title { flex-wrap:wrap; gap:8px; }.disk { grid-template-columns:60px 1fr; }.disk>span { grid-column:2; }.resource-grid { gap:16px; }}
@media(max-width:700px){.page-header,.section-heading { align-items:flex-start; flex-direction:column; gap:12px; }.header-actions { width:100%; }.node-grid { grid-template-columns:1fr; }.node-metrics { grid-template-columns:1fr 1fr 1fr; gap:14px; }.node-metrics span { flex-direction:column; gap:5px; }.node-card p { margin:8px 0; }.node-card small { font-size:10px; }.resource-grid { grid-template-columns:1fr; }.resource>strong { font-size:22px; }.charts { grid-template-columns:minmax(0,1fr); }.panel,.node-detail { padding:16px; }.filters { flex-wrap:wrap; }.filters input { flex-basis:100%; }.disk { gap:10px; }.service-group summary span { margin-left:5px; }h2 span { display:block; margin:6px 0 0; }}
</style>
