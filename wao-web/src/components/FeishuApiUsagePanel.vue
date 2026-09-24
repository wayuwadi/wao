<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchFeishuApiUsage, type FeishuApiUsage } from '@/api/system'
const rows = ref<FeishuApiUsage[]>([])
const loading = ref(false)
const error = ref('')
const total = computed(() => rows.value.reduce((sum, row) => sum + row.requests, 0))
const since = computed(() => rows.value.map(row => row.firstObservedAt).sort()[0]?.slice(0, 19).replace('T', ' '))
async function refresh() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try { rows.value = await fetchFeishuApiUsage() }
  catch (e) { error.value = e instanceof Error ? e.message : '读取失败，请重试' }
  finally { loading.value = false }
}
onMounted(refresh)
</script>

<template>
  <section class="usage" aria-label="飞书 API 调用统计">
    <div class="usage-head"><strong>飞书 API 调用统计</strong><button :disabled="loading" @click="refresh">{{ loading ? '读取中…' : '刷新统计' }}</button></div>
    <p>本月 WAO 群聊客户端 · 双节点汇总 · {{ total.toLocaleString() }} 次出站尝试（含失败）</p>
    <p v-if="since">已观测起点：{{ since }}（北京时间）</p>
    <p>只统计本功能上线后的真实请求，不含飞书登录、Webhook 和其他应用；不能替代飞书租户账单或还原此前调用量。刷新此表不调用飞书。</p>
    <p v-if="error" role="alert">{{ error }} <button :disabled="loading" @click="refresh">重试</button></p>
    <p v-else-if="loading" role="status">正在读取调用统计…</p>
    <p v-else-if="!rows.length">尚无已记录请求；后台产生真实调用后再刷新。</p>
    <div v-if="rows.length" class="table-wrap">
      <table><thead><tr><th>接口（按调用量降序）</th><th>次数</th><th>占比</th></tr></thead>
        <tbody><tr v-for="row in rows" :key="row.endpoint"><td>{{ row.endpoint }}</td><td>{{ row.requests.toLocaleString() }}</td><td>{{ total ? (row.requests / total * 100).toFixed(1) : '0.0' }}%</td></tr></tbody>
      </table>
    </div>
    <p>聊天记录同步已停用；快速收集每 10 分钟兜底读取近期消息，事件回调即时处理。失败后共享冷却，月额度超限则暂停轮询至下月。</p>
    <p>请求次数不等于额度消耗：HTTP 4xx/5xx、认证接口及自定义机器人 Webhook 不计入月额度。月额度由飞书租户统一管理，换应用不能增加总额度；额度不足请联系管理员核对官方用量与升级方案。</p>
  </section>
</template>

<style scoped>
.usage { padding: 16px; border: 1px solid var(--wao-divider); border-radius: 10px; margin: 16px 20px; }
.usage-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
p { font-size: 12px; line-height: 1.6; color: var(--wao-text-2); margin: 8px 0; }
button { border: 1px solid var(--wao-line-strong); border-radius: 6px; padding: 4px 10px; background: transparent; color: inherit; cursor: pointer; }
button:disabled { opacity: .5; cursor: wait; }
.table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; font-size: 12px; }
th, td { padding: 8px 6px; text-align: left; border-bottom: 1px solid var(--wao-divider); }
td:first-child { overflow-wrap: anywhere; min-width: 160px; } th:not(:first-child), td:not(:first-child) { white-space: nowrap; text-align: right; }
</style>
