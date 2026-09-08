<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { benefitDaysLeft, emptyCodexBenefits, fetchCodexBenefits, saveCodexBenefits, validBenefitDate } from '@/api/codexBenefits'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
defineProps<{ compact?: boolean }>()
const saved = ref(emptyCodexBenefits())
const loading = ref(true)
const loaded = ref(false)
const error = ref('')
const editing = ref(false)
const saving = ref(false)
const status = ref('')
const draftWarning = ref('')
const draft = reactive({ resetCards: '', proExpiresOn: '' })
const now = ref(new Date())
let version = 0
const timer = setInterval(() => { now.value = new Date() }, 60_000)
onBeforeUnmount(() => { clearInterval(timer); version++ })
const daysLeft = computed(() => saved.value.proExpiresOn ? benefitDaysLeft(saved.value.proExpiresOn, now.value) : null)
const expiryLabel = computed(() => daysLeft.value === null ? '到期日未设置'
  : daysLeft.value < 0 ? `已到期 ${-daysLeft.value} 天`
    : daysLeft.value === 0 ? '今天到期' : `剩 ${daysLeft.value} 天`)
const draftKey = (memberId: string) => `wao:codex-benefits-draft:${memberId}`

async function load() {
  const requestVersion = ++version
  loading.value = true
  loaded.value = false
  error.value = ''
  try {
    const result = await fetchCodexBenefits()
    if (requestVersion !== version) return
    saved.value = result
    loaded.value = true
  } catch (cause) {
    if (requestVersion === version) error.value = cause instanceof Error ? cause.message : '权益读取失败，请重试'
  } finally {
    if (requestVersion === version) loading.value = false
  }
}

watch(() => session.userId, (id) => {
  version++
  saved.value = emptyCodexBenefits()
  editing.value = false
  saving.value = false
  status.value = ''
  draftWarning.value = ''
  if (id) void load()
  else { loading.value = false; loaded.value = false; error.value = '' }
}, { immediate: true })

function openEditor() {
  draft.resetCards = saved.value.resetCards === null ? '' : String(saved.value.resetCards)
  draft.proExpiresOn = saved.value.proExpiresOn ?? ''
  status.value = ''
  draftWarning.value = ''
  try {
    const raw = localStorage.getItem(draftKey(session.userId))
    if (raw) {
      const restored = JSON.parse(raw) as typeof draft
      if (typeof restored.resetCards === 'string' && typeof restored.proExpiresOn === 'string') {
        Object.assign(draft, restored)
        status.value = '已恢复未保存草稿'
      }
    }
  } catch { draftWarning.value = '本机草稿不可用，请保存后再离开页面' }
  editing.value = true
}

function persistDraft() {
  status.value = '未保存'
  error.value = ''
  try {
    localStorage.setItem(draftKey(session.userId), JSON.stringify({ resetCards: String(draft.resetCards), proExpiresOn: draft.proExpiresOn }))
    draftWarning.value = ''
  } catch { draftWarning.value = '本机草稿保存失败，请保存后再离开页面' }
}

async function save() {
  if (saving.value) return
  const count = String(draft.resetCards).trim()
  if (count && (!/^\d+$/.test(count) || Number(count) > 9999)) {
    error.value = '重置卡数量请输入 0 到 9999 的整数'
    return
  }
  if (draft.proExpiresOn && !validBenefitDate(draft.proExpiresOn)) {
    error.value = '请输入有效的到期日期'
    return
  }
  const memberId = session.userId
  const requestVersion = version
  const value = { resetCards: count ? Number(count) : null, proExpiresOn: draft.proExpiresOn || null }
  saving.value = true
  error.value = ''
  try {
    await saveCodexBenefits(value)
    if (requestVersion !== version || session.userId !== memberId) return
    saved.value = value
    editing.value = false
    status.value = '已保存'
    try { localStorage.removeItem(draftKey(memberId)) } catch { draftWarning.value = '已保存到账号，但本机旧草稿未能清除' }
  } catch (cause) {
    if (requestVersion === version) error.value = cause instanceof Error ? cause.message : '保存失败，请重试'
  } finally {
    if (requestVersion === version) saving.value = false
  }
}
</script>

<template>
  <section class="codex-benefits" :class="{ compact }" aria-label="我的 Codex 权益">
    <header><span>我的 Codex 权益</span><button type="button" :disabled="!loaded || saving" aria-label="编辑 Codex 权益" @click="openEditor"><i class="ri-edit-line"></i>编辑</button></header>
    <p v-if="loading" role="status">正在读取权益…</p>
    <template v-else-if="loaded">
      <div class="benefit-line"><span><i class="ri-ticket-2-line"></i>重置卡</span><b>{{ saved.resetCards === null ? '未设置' : `${saved.resetCards} 张` }}</b></div>
      <div class="benefit-line"><span>Pro 到期</span><b>{{ saved.proExpiresOn ? saved.proExpiresOn.replace(/-/g, '/') : '未设置' }}</b></div>
      <p class="benefit-countdown" :class="{ soon: daysLeft !== null && daysLeft <= 7 }">{{ expiryLabel }}<span v-if="daysLeft !== null && daysLeft > 0"> · 让额度变成成果</span></p>
    </template>
    <p v-else-if="!error">登录后配置你的权益</p>
    <form v-if="editing" class="benefit-editor" @submit.prevent="save">
      <label>重置卡数量<input v-model="draft.resetCards" aria-label="重置卡数量" type="number" min="0" max="9999" step="1" placeholder="未设置" :disabled="saving" @input="persistDraft" /></label>
      <label>Pro 到期日期<input v-model="draft.proExpiresOn" aria-label="Pro 到期日期" type="date" :disabled="saving" @input="persistDraft" /></label>
      <p>手动记录；使用重置卡或续费后可修改。留空可清除。</p>
      <footer><button type="button" :disabled="saving" @click="editing = false">收起</button><button type="submit" :disabled="saving">{{ saving ? '保存中…' : error ? '重试保存' : '保存' }}</button></footer>
    </form>
    <p v-if="error" class="benefit-error" role="alert">{{ error }} <button v-if="!loaded" type="button" :disabled="loading" @click="load">重试读取</button></p>
    <p v-if="draftWarning" class="benefit-error" role="alert">{{ draftWarning }}</p>
    <p v-if="status" role="status">{{ status }}</p>
  </section>
</template>

<style scoped>
.codex-benefits { margin-top: 10px; padding-top: 9px; border-top: 1px solid #ffffff30; color: #fff; min-width: 0; }
header, .benefit-line, footer { display: flex; justify-content: space-between; align-items: center; gap: 6px; }
header { margin-bottom: 8px; font-size: 10px; color: #ffffffc7; }
button { color: inherit; font: inherit; cursor: pointer; border: 1px solid #ffffff40; background: #ffffff12; border-radius: 5px; padding: 3px 6px; }
button:disabled { opacity: .5; cursor: default; }
button:hover:not(:disabled) { background: #ffffff28; }
.benefit-line { font-size: 11px; margin-top: 7px; flex-wrap: wrap; }
.benefit-line span { color: #ffffffc7; }
.benefit-line i { margin-right: 4px; }
.benefit-line b { font-size: 12px; font-variant-numeric: tabular-nums; }
p { margin: 7px 0 0; font-size: 10px; line-height: 1.5; color: #ffffffb5; }
.benefit-countdown.soon { color: #ffe9a9; }
.benefit-editor { display: grid; gap: 9px; margin-top: 12px; }
label { display: grid; gap: 4px; font-size: 11px; }
input { box-sizing: border-box; width: 100%; min-width: 0; padding: 6px; border: 1px solid #d9def6; border-radius: 5px; background: #fff; color: #20264c; font: inherit; color-scheme: light; }
footer { justify-content: flex-end; font-size: 11px; }
.benefit-error { color: #ffe9a9; }
.compact { position: relative; margin: 0; padding: 0; border: 0; display: grid; grid-template-columns: auto minmax(0, 1fr); column-gap: 24px; align-content: start; }
.compact header { grid-column: 1 / -1; order: 3; margin: 4px 0 0; justify-content: flex-end; }
.compact header > span { display: none; }
.compact header button { padding: 1px 5px; border: 0; background: transparent; font-size: 11px; }
.compact .benefit-line { margin: 0; display: grid; justify-content: start; gap: 5px; font-size: 11px; }
.compact .benefit-line b { font-size: 19px; line-height: 1.2; }
.compact .benefit-line:nth-of-type(2) b { font-size: 14px; padding-top: 3px; }
.compact .benefit-countdown { grid-column: 2; margin-top: 3px; font-size: 11px; }
.compact .benefit-countdown > span { display: none; }
.compact .benefit-editor, .compact .benefit-error, .compact > p:not(.benefit-countdown) { grid-column: 1 / -1; }
.compact .benefit-editor { order: 4; }
.compact > [role] { order: 5; }
@media (max-width: 520px) { .compact { column-gap: 12px; } .compact .benefit-line b { font-size: 17px; } .compact .benefit-line:nth-of-type(2) b { font-size: 12px; } }
</style>
