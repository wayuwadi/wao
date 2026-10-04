<script setup lang="ts">
import { computed, ref } from 'vue'
import { usePreferenceStore } from '@/stores/preferences'
import { useSessionStore } from '@/stores/session'
import { PAGE_LAYOUT_OPTIONS, readPageLayoutAttempt, rememberPageLayoutAttempt, type PageLayoutStyle } from '@/utils/pageLayout'

const preferences = usePreferenceStore()
const session = useSessionStore()
// The parent keys this component by member, so pending UI never crosses accounts.
const memberId = session.currentUser.id
const pending = ref(readPageLayoutAttempt(memberId))
const saving = ref(false)
const error = ref('')
const storageError = ref('')
const pendingName = computed(() => PAGE_LAYOUT_OPTIONS.find(option => option.value === pending.value)?.name)
const ready = computed(() => preferences.loaded && !preferences.loading && !preferences.loadError)

async function save(style: PageLayoutStyle) {
  if (saving.value || !ready.value) return
  pending.value = style
  storageError.value = rememberPageLayoutAttempt(memberId, style) ? '' : '浏览器暂不能保留这次选择，刷新前请重试保存。'
  saving.value = true
  error.value = ''
  try {
    await preferences.setPageLayoutStyle(style)
    if (session.currentUser.id !== memberId) return
    pending.value = null
    storageError.value = rememberPageLayoutAttempt(memberId, null) ? '' : '布局已保存，但浏览器未能清理上次选择。'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '页面布局保存失败'
  } finally { saving.value = false }
}
</script>

<template>
<section class="page-layout-preference" aria-labelledby="page-layout-title">
  <header>
    <div><h2 id="page-layout-title">页面布局</h2><p>按当前成员跨设备保存，切换时保留正在编辑的内容。</p></div>
    <span class="layout-status" role="status" aria-live="polite">{{ saving ? '保存中…' : preferences.loadError ? '读取失败' : !ready ? '读取中…' : error || pending ? '尚未保存' : '已保存' }}</span>
  </header>
  <div v-if="preferences.loadError" class="layout-message" role="alert"><span>{{ preferences.loadError }}</span><button type="button" :disabled="preferences.loading" @click="preferences.load(true)">重新读取</button></div>
  <p v-else-if="!ready" class="layout-loading" role="status">正在读取当前成员的页面布局…</p>
  <div v-else class="layout-choices" role="radiogroup" aria-label="页面布局">
    <button v-for="option in PAGE_LAYOUT_OPTIONS" :key="option.value" type="button" role="radio"
      :aria-checked="preferences.pageLayoutStyle === option.value" :disabled="saving"
      :class="['layout-choice', option.value, { selected: preferences.pageLayoutStyle === option.value }]"
      @click="save(option.value)">
      <span class="layout-thumbnail" aria-hidden="true"><span class="thumbnail-side"></span><span class="thumbnail-frame"><span class="thumbnail-title"></span><span class="thumbnail-content"></span></span></span>
      <span class="choice-title"><i :class="preferences.pageLayoutStyle === option.value ? 'ri-radio-button-line' : 'ri-checkbox-blank-circle-line'" aria-hidden="true"></i>{{ option.name }}</span>
      <small>{{ option.description }}</small>
    </button>
  </div>
  <div v-if="pending && !saving && ready" class="layout-message" role="alert"><span>{{ error ? `${error}，已保留原有布局。` : `上次选择“${pendingName}”尚未保存，当前仍使用已保存布局。` }}</span><button type="button" @click="save(pending)">重试保存“{{ pendingName }}”</button></div>
  <p v-if="storageError" class="layout-message" role="alert">{{ storageError }}</p>
</section>
</template>

<style scoped>
.page-layout-preference { margin: 16px 0; padding: 18px; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-card); container: page-layout-options / inline-size; }
header { display: flex; justify-content: space-between; align-items: start; gap: 12px; }
h2 { font-size: 14px; margin: 0; }
header p { color: var(--wao-text-3); font-size: 12px; margin: 7px 0 0; }
.layout-status { flex: none; color: var(--wao-text-3); font-size: 12px; }
.layout-choices { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-top: 16px; }
.layout-choice { display: flex; flex-direction: column; gap: 8px; min-width: 0; text-align: left; border: 1px solid var(--wao-line-control); border-radius: 9px; background: var(--wao-bg-card); color: var(--wao-text); padding: 11px; font: inherit; cursor: pointer; }
.layout-choice.selected { border-color: var(--wao-primary); background: var(--wao-primary-tint); }
.layout-choice:disabled { opacity: .65; cursor: wait; }
.choice-title { display: flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; }
.choice-title i { color: var(--wao-primary); }
.layout-choice small { font-size: 12px; line-height: 1.6; color: var(--wao-text-3); }
.layout-thumbnail { width: 100%; height: 58px; display: flex; gap: 6px; padding: 6px; border: 1px solid var(--wao-line); border-radius: 6px; background: var(--wao-bg-sub); }
.thumbnail-side { width: 15%; flex: none; border-radius: 3px; background: var(--wao-line-control); }
.thumbnail-frame { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.thumbnail-title { height: 8px; background: var(--wao-primary-soft); border-radius: 2px; }
.thumbnail-content { height: 26px; width: 68%; background: var(--wao-primary-soft); border-radius: 3px; }
.fluid .thumbnail-content { width: 100%; }
.centered .thumbnail-frame { flex: 0 0 62%; margin-inline: auto; }
.layout-message { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 12px 0 0; color: var(--wao-danger-strong); font-size: 12px; }
.layout-message button { border: 1px solid var(--wao-line-control); border-radius: 6px; padding: 6px 9px; background: var(--wao-bg-card); color: var(--wao-text); font: inherit; cursor: pointer; }
.layout-loading { margin: 14px 0 0; color: var(--wao-text-3); font-size: 12px; }
@container page-layout-options (max-width: 480px) { .layout-choices { grid-template-columns: minmax(0, 1fr); } .layout-thumbnail { width: 120px; } }
@container page-layout-options (min-width: 340px) and (max-width: 480px) { .layout-choice { display: grid; grid-template-columns: 100px minmax(0, 1fr); column-gap: 12px; } .layout-thumbnail { grid-row: span 2; width: 100px; } }
@media (pointer: coarse) { .layout-message button { min-height: 44px; } }
</style>
