<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { fetchCodexMemberships, saveCodexMembership, type CodexAccountMembership, type CodexMembership } from '@/api/codexMemberships'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const canEdit = computed(() => ['管理员', '超级管理员'].includes(session.currentUser.role))
const entries = ref<CodexAccountMembership[]>([]), loading = ref(false), error = ref('')
const forms = reactive<Record<string, CodexMembership>>({}), dirty = reactive<Record<string, boolean>>({})
const saving = reactive<Record<string, boolean>>({}), messages = reactive<Record<string, string>>({})
function storageKey(key: string) { return `wao-codex-membership-draft-v1:${session.currentUser.id}:${key}` }
function message(value: unknown) { return value instanceof Error ? value.message : '读取失败，请重试' }
async function load() {
  if (loading.value) return
  loading.value = true; error.value = ''
  try {
    entries.value = await fetchCodexMemberships()
    for (const item of entries.value) {
      const key = item.account.key
      if (dirty[key] || saving[key]) continue
      forms[key] = { ...item.membership }
      try {
        const cached = JSON.parse(localStorage.getItem(storageKey(key)) || 'null')
        if (cached?.accountKey === key) {
          forms[key] = { ...forms[key], ...cached }; dirty[key] = true; messages[key] = '已恢复本机草稿，尚未保存到 WAO'
        }
      } catch { messages[key] = '本机草稿无法读取，原缓存保留' }
    }
  } catch (value) { error.value = message(value) }
  finally { loading.value = false }
}
function edited(key: string) {
  dirty[key] = true
  try { localStorage.setItem(storageKey(key), JSON.stringify(forms[key])); messages[key] = '本机草稿已保存，尚未保存到 WAO' }
  catch { messages[key] = '本机草稿保存失败，请保持页面打开并保存到 WAO' }
}
async function save(key: string) {
  if (saving[key]) return
  saving[key] = true; messages[key] = '保存中…'
  try {
    const saved = await saveCodexMembership({ ...forms[key]! })
    forms[key] = saved; dirty[key] = false
    const entry = entries.value.find(item => item.account.key === key)
    if (entry) entry.membership = { ...saved }
    try { localStorage.removeItem(storageKey(key)) } catch { /* 服务端保存已成功。 */ }
    messages[key] = '已保存到 WAO'
  } catch (value) { messages[key] = `${message(value)} 可重试保存。` }
  finally { saving[key] = false }
}
function restore(key: string) {
  const entry = entries.value.find(item => item.account.key === key)
  if (!entry || saving[key]) return
  forms[key] = { ...entry.membership }; dirty[key] = false
  try { localStorage.removeItem(storageKey(key)) } catch { /* 当前表单已恢复。 */ }
  messages[key] = '已恢复最近读取的服务器日期'
}
function grantDate(value: number | null) {
  if (value === null) return '发放时间未知'
  return new Date(value * 1000).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}
onMounted(load)
</script>

<template>
  <section class="membership-panel" aria-label="Codex 重置卡与会员">
    <header><div><h2>Codex 重置卡与会员</h2><p>重置卡自动同步；购买日期、Pro 到期日由管理员补充。</p></div><button type="button" :disabled="loading" @click="load">{{ loading ? '读取中…' : '刷新会员信息' }}</button></header>
    <p v-if="error" role="alert">{{ error }} <button @click="load">重试</button></p>
    <p v-if="!loading && !error && !entries.length">暂无已同步的 Codex 账号；执行电脑连接后会显示真实数据。</p>
    <article v-for="entry in entries" :key="entry.account.key">
      <h3>{{ entry.account.accountEmail || entry.account.key }} <small>{{ entry.account.plan || '套餐未知' }}</small></h3>
      <div class="membership-facts"><b>可用重置卡：{{ entry.account.resetCredits ? `${entry.account.resetCredits.availableCount} 张` : '尚未同步' }}</b><span>{{ entry.account.fresh ? '最近同步' : '历史快照' }}：{{ entry.account.lastSyncedAt }}</span></div>
      <details v-if="entry.account.resetCredits?.credits.length"><summary>官方发放记录（发放时间不等于购买时间）</summary><ul><li v-for="(credit, index) in entry.account.resetCredits.credits" :key="index">{{ grantDate(credit.grantedAt) }}<span v-if="credit.expiresAt"> · 卡片到期 {{ grantDate(credit.expiresAt) }}</span></li></ul></details>
      <p v-else-if="entry.account.resetCredits">官方未返回发放明细；可用数量以官方汇总为准。</p>
      <form v-if="forms[entry.account.key]" @submit.prevent="save(entry.account.key)">
        <label>重置卡购买日期（手动）<input v-model="forms[entry.account.key]!.purchasedAt" type="date" :disabled="!canEdit || saving[entry.account.key]" @input="edited(entry.account.key)"></label>
        <label>Pro 到期日（手动）<input v-model="forms[entry.account.key]!.proExpiresAt" type="date" :disabled="!canEdit || saving[entry.account.key]" @input="edited(entry.account.key)"></label>
        <div v-if="canEdit" class="membership-actions"><button :disabled="saving[entry.account.key] || !dirty[entry.account.key]">{{ saving[entry.account.key] ? '保存中…' : '保存日期' }}</button><button v-if="dirty[entry.account.key]" type="button" :disabled="saving[entry.account.key]" @click="restore(entry.account.key)">恢复服务器日期</button></div>
        <span class="membership-status" role="status">{{ messages[entry.account.key] || '未填写的日期不推算；填写后按上海日期提前 3 天提醒。' }}</span>
      </form>
    </article>
    <footer>重置卡增加时通知 WAO 群；Pro 到期前三天提醒一次。遵循系统自动化通知与静默设置。</footer>
  </section>
</template>

<style scoped>
.membership-panel{margin:18px 0;padding:18px;border:1px solid var(--wao-line);border-radius:14px;background:var(--wao-bg-card);color:var(--wao-text)}header{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:12px}h2{font-size:16px;margin:0}h3{font-size:14px;overflow-wrap:anywhere}p,small,footer,.membership-status{font-size:12px;color:var(--wao-text-weak);line-height:1.6}article{padding:14px 0;border-top:1px solid var(--wao-line);margin-top:12px}.membership-facts{display:flex;flex-wrap:wrap;gap:8px 20px;font-size:13px}.membership-facts span{color:var(--wao-text-weak)}details{font-size:12px;margin:12px 0}li{margin:6px 0}form{display:flex;flex-wrap:wrap;gap:12px;margin-top:14px}label{display:grid;gap:6px;font-size:12px;flex:1 1 190px;min-width:0}input,button{border:1px solid var(--wao-line-control);border-radius:8px;background:var(--wao-bg-card);color:var(--wao-text-2);padding:8px;font:inherit;min-width:0}button{cursor:pointer}button:disabled{opacity:.6;cursor:default}.membership-actions{display:flex;align-items:end;gap:8px;flex-wrap:wrap}.membership-status{flex-basis:100%}footer{border-top:1px solid var(--wao-line);padding-top:12px}
</style>
