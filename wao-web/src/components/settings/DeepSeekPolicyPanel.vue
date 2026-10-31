<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { fetchDeepSeekPolicy, saveDeepSeekPolicy, type DeepSeekPolicy, type DeepSeekPolicySnapshot } from '@/api/deepSeekPolicy'

const draftKey = 'wao:deepseek-policy-draft'
const snapshot = ref<DeepSeekPolicySnapshot | null>(null)
const form = ref<DeepSeekPolicy | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const saved = ref(false)
const failedAction = ref<'load' | 'save'>('load')
const dirty = computed(() => !!form.value && JSON.stringify(form.value) !== JSON.stringify(snapshot.value?.policy))
const features = [
  { key: 'polishEnabled', purpose: 'POLISH', label: '文本润色', detail: '文档选区、小记、润色工作台和飞书润色' },
  { key: 'assistantEnabled', purpose: 'ASSISTANT', label: '页面与知识库问答', detail: '可能携带知识库资料和历史消息' },
  { key: 'tianrenEnabled', purpose: 'TIANREN', label: '卦象分析', detail: '自动或手动触发的卦象解读' },
  { key: 'backgroundEnabled', purpose: 'BACKGROUND', label: '后台只读分析', detail: '点子研究等已登记的后台分析任务' },
] as const
const total = computed(() => snapshot.value?.usage.reduce((sum, row) => sum + row.budgetTokens, 0) ?? 0)
const format = (n: number) => n.toLocaleString('zh-CN')
function usage(purpose: string) { return snapshot.value?.usage.find(row => row.purpose === purpose) }

watch(form, value => {
  if (!value) return
  saved.value = false
  try {
    if (dirty.value) sessionStorage.setItem(draftKey, JSON.stringify(value))
    else sessionStorage.removeItem(draftKey)
  } catch { /* 浏览器禁用存储时，当前页面仍保留草稿 */ }
}, { deep: true, flush: 'sync' })

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await fetchDeepSeekPolicy()
    const hadDraft = dirty.value
    snapshot.value = result
    if (!form.value) {
      let restored: DeepSeekPolicy | null = null
      try {
        const draft = JSON.parse(sessionStorage.getItem(draftKey) ?? 'null') as DeepSeekPolicy | null
        if (draft && typeof draft.dailyTokenLimit === 'number' && Object.keys(result.policy).every(key =>
          typeof draft[key as keyof DeepSeekPolicy] === typeof result.policy[key as keyof DeepSeekPolicy])) restored = draft
      } catch { /* 不采用无效草稿 */ }
      form.value = restored ?? { ...result.policy }
    } else if (!hadDraft) form.value = { ...result.policy }
  } catch (e) { failedAction.value = 'load'; error.value = e instanceof Error ? e.message : '读取失败，请重试' }
  finally { loading.value = false }
}
async function save() {
  if (!form.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    const result = await saveDeepSeekPolicy({ ...form.value })
    snapshot.value = result
    form.value = { ...result.policy }
    try { sessionStorage.removeItem(draftKey) } catch { /* 当前状态已落库 */ }
    saved.value = true
  } catch (e) { failedAction.value = 'save'; error.value = e instanceof Error ? e.message : '保存失败，草稿已保留，请重试' }
  finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <section class="deepseek-policy" aria-label="付费 AI 使用范围与预算">
    <div class="policy-heading">
      <div><h2>付费 AI 使用范围与预算</h2><p>默认只用于润色。其他用途需显式开启；未登记的新功能一律禁止使用这把 Key。</p></div>
      <button type="button" :disabled="loading || saving" @click="load">{{ loading ? '读取中…' : '刷新用量' }}</button>
    </div>
    <p v-if="error" role="alert" class="error">{{ error }} <button type="button" :disabled="loading || saving" @click="failedAction === 'save' ? save() : load()">重试</button></p>
    <form v-if="form" @submit.prevent="save">
      <fieldset :disabled="saving">
        <div class="policy-controls">
          <label><input v-model="form.enabled" type="checkbox"> 允许使用 DeepSeek / 醒市网关</label>
          <label>每日 Token 上限 <input v-model.number="form.dailyTokenLimit" type="number" min="1" max="1000000000" step="1" required></label>
          <span>不限制调用次数 · 每日北京时间 00:00 重置</span>
        </div>
        <div class="feature-grid">
          <label v-for="feature in features" :key="feature.key" class="feature">
            <input v-model="form[feature.key]" type="checkbox">
            <span><b>{{ feature.label }}</b><small>{{ feature.detail }}</small><small>{{ format(usage(feature.purpose)?.budgetTokens ?? 0) }} 预算占用 · {{ format(usage(feature.purpose)?.actualTokens ?? 0) }} Token 已确认 · {{ format(usage(feature.purpose)?.requestCount ?? 0) }} 次请求</small></span>
            <em>{{ form[feature.key] ? (form.enabled ? '允许' : '总开关已关闭') : '禁止' }}</em>
          </label>
        </div>
        <p>{{ snapshot?.day }} 预算占用 <strong>{{ format(total) }}</strong> / {{ format(snapshot?.policy.dailyTokenLimit ?? 0) }} Token</p>
        <p class="hint">发送前预占输入与最大输出预算，成功后按实际用量结算。超时或未返回用量时保留预占；所有 DeepSeek 与醒市网关账号共享上限。关闭后不再发送新请求，已发送请求无法撤回。</p>
        <footer><span>{{ saving ? '保存中…' : dirty ? '有未保存设置，草稿已保留' : saved ? '已保存' : '当前设置已生效' }}</span><button type="submit" :disabled="saving || !dirty">{{ saving ? '保存中…' : '保存设置' }}</button></footer>
      </fieldset>
    </form>
  </section>
</template>

<style scoped>
.deepseek-policy { border: 1px solid var(--wao-line); border-radius: 14px; padding: 18px; background: var(--wao-bg-card); }
.policy-heading,.policy-controls,footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
h2 { margin: 0; font-size: var(--wao-font-card-title); } p,small,.policy-controls span,footer span { font-size: var(--wao-font-help); color: var(--wao-text-3); }
fieldset { border: 0; margin: 0; padding: 0; min-width: 0; }
.policy-controls { justify-content: flex-start; margin: 12px 0; }
input[type=number] { width: 130px; padding: 6px 8px; border: 1px solid var(--wao-line); border-radius: 6px; background: var(--wao-bg-card); color: inherit; }
.feature-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 8px; }
.feature { display: flex; gap: 8px; padding: 10px; border: 1px solid var(--wao-line); border-radius: 8px; align-items: flex-start; }
.feature span { flex: 1; } small { display: block; margin-top: 5px; } em { font-size: var(--wao-font-caption); font-style: normal; }
.hint { line-height: 1.6; }.error { color: #dc2626; } button { padding: 6px 10px; border: 1px solid var(--wao-line); border-radius: 6px; cursor: pointer; background: var(--wao-bg-card); color: inherit; } button:disabled { opacity: .5; cursor: default; }
@media (max-width: 700px) { .feature-grid { grid-template-columns: 1fr; } }
</style>
