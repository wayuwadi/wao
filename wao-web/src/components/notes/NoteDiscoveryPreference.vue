<script setup lang="ts">
import { ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import { fetchDiscoveryPreference, saveDiscoveryPreference, fetchDiscoveryCurrent, discoveryHttpStatus, type DiscoveryPreference } from '@/api/noteDiscoveries'
const session = useSessionStore()
const preference = ref<DiscoveryPreference>(), draft = ref(false), loading = ref(false), saving = ref(false), error = ref(''), saved = ref(false), state = ref('')
let generation = 0
async function load(preserve = false) {
  const mine = generation; loading.value = true; error.value = ''
  try {
    const value = await fetchDiscoveryPreference(); if (mine !== generation) return
    preference.value = value; if (!preserve) draft.value = value.enabled
    const current = await fetchDiscoveryCurrent(); if (mine === generation) state.value = current.automationState
  } catch (cause) { if (mine === generation) error.value = [404, 410].includes(discoveryHttpStatus(cause) ?? 0) ? '服务正在更新，自动发现暂不可用。' : '设置读取失败，请重试。' }
  finally { if (mine === generation) loading.value = false }
}
async function save() {
  if (!preference.value || saving.value) return
  const mine = generation; saving.value = true; error.value = ''; saved.value = false
  try {
    const value = await saveDiscoveryPreference(draft.value, preference.value.version)
    if (mine !== generation) return
    preference.value = value; draft.value = value.enabled; saved.value = true
  } catch (cause) {
    if (mine !== generation) return
    if (discoveryHttpStatus(cause) === 409) { await load(true); error.value = '设置已在其他窗口更新；你的选择已保留，请确认后再保存。' }
    else error.value = '设置没有保存成功，你的选择已保留，请重试。'
  } finally { if (mine === generation) saving.value = false }
}
watch(() => session.currentUser.id, () => { generation++; preference.value = undefined; draft.value = false; saving.value = false; saved.value = false; void load() }, { immediate: true })
</script>
<template>
  <section class="discovery-preference card thin" aria-label="小记顺手发现设置">
    <strong>小记顺手发现</strong><p>读取本人小记，在后台寻找有原文依据的联系。可以随时暂停，已有发现和收藏仍可查看。</p>
    <p v-if="loading" role="status">读取中…</p>
    <template v-if="preference">
      <label><input v-model="draft" type="checkbox" :disabled="saving || loading || (!preference.available && !preference.enabled)">自动分析本人小记</label>
      <p v-if="!preference.available">{{ preference.message }}</p>
      <p v-else-if="state === 'FAILED'">最近一次分析未完成，失败记录已保留。</p>
      <p v-else-if="state === 'DEFERRED'">分析暂时延后，稍后继续。</p>
      <button type="button" :disabled="saving || loading" @click="save">{{ saving ? '保存中…' : '保存设置' }}</button>
    </template>
    <p v-if="saved" role="status">设置已保存</p><p v-if="error" role="alert">{{ error }} <button type="button" :disabled="loading || saving" @click="preference ? save() : load()">重试</button></p>
  </section>
</template>
<style scoped>
.discovery-preference { margin: 12px 0; padding: 18px; font-size: 13px; line-height: 1.8; }.discovery-preference p { color: var(--wao-text-faint); }.discovery-preference label { display: flex; gap: 9px; align-items: center; }.discovery-preference button { margin-top: 10px; padding: 7px 12px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-primary-text); }
</style>
