<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { noteSettingsDraft, rememberNoteSettingsDraft } from '@/utils/noteAiSettingsDraft'
import { fetchNoteAiSettings, saveNoteAiSettings, type NoteAiSettings } from '@/api/noteAiSettings'
import { fetchCodexModels, type CodexModelCatalog } from '@/api/codexModels'
import { useSessionStore } from '@/stores/session'
import { usePreferenceStore } from '@/stores/preferences'
const emit = defineEmits<{ saved: [] }>()
const session = useSessionStore(), preferences = usePreferenceStore()
const draft = ref<NoteAiSettings | null>(null), loading = ref(false), saving = ref(false), dirty = ref(false)
const error = ref(''), status = ref(''), version = ref(''), catalog = ref<CodexModelCatalog | null>(null)
const modelError = ref('')
const selectedEfforts = computed(() => catalog.value?.models.find(model => model.id === (draft.value?.model || 'gpt-6-astra'))?.reasoningEfforts || ['medium'])
const effortLabels: Record<string, string> = { none: '无', minimal: '极低', low: '低', medium: '中', high: '高', xhigh: '极高', max: '最高' }
async function loadModels() {
  const ticket = generation; modelError.value = ''
  try { const value = await fetchCodexModels(); if(ticket === generation) catalog.value = value }
  catch { if(ticket === generation) modelError.value = '模型目录暂不可读取，已有选择保留' }
}
let generation = 0, applying = false, draftOwner = session.currentUser.id
const retained = noteSettingsDraft(session.currentUser.id)
if(retained) { draft.value = retained.draft; version.value = retained.version; dirty.value = true }
function remember() { if(draftOwner !== session.currentUser.id) return; rememberNoteSettingsDraft(draftOwner, dirty.value && draft.value ? {draft:JSON.parse(JSON.stringify(draft.value)),version:version.value} : null) }
watch(draft, () => { if (!applying && draft.value) { dirty.value = true; status.value = '设置尚未保存'; remember() } }, { deep: true })
async function load() {
  const ticket = ++generation; loading.value = true; error.value = ''
  try {
    const result = await fetchNoteAiSettings()
    if (ticket !== generation) return
    version.value = result.version
    if (!dirty.value) { applying = true; draft.value = structuredClone(result.settings); await Promise.resolve(); applying = false }
    status.value = dirty.value ? '最新配置已读取，保留你的设置草稿' : result.configured ? '已读取保存的设置' : '尚无历史设置，三项默认开启'
    preferences.noteAiDefaults = { ...result.settings }
    void loadModels()
  } catch (cause) { if (ticket === generation) error.value = cause instanceof Error ? cause.message : '读取失败，请重试' }
  finally { if (ticket === generation) loading.value = false }
}
async function save() {
  if (!draft.value || saving.value || loading.value) return
  if (catalog.value?.models.some(model => model.id === (draft.value?.model || 'gpt-6-astra'))
    && !selectedEfforts.value.includes(draft.value.reasoningEffort)) {
    error.value = '当前模型不支持所选思考深度，请重新选择'; return
  }
  const ticket = generation, submitted = JSON.parse(JSON.stringify(draft.value)) as NoteAiSettings
  saving.value = true; error.value = ''
  try {
    const result = await saveNoteAiSettings(submitted, version.value)
    if (ticket !== generation) return
    version.value = result.version; preferences.noteAiDefaults = { ...result.settings }
    dirty.value = JSON.stringify(draft.value) !== JSON.stringify(submitted)
    status.value = dirty.value ? '提交的设置已保存，最新修改仍待保存' : '设置已保存'
    remember()
    emit('saved')
  } catch (cause) { if (ticket === generation) error.value = cause instanceof Error ? cause.message : '保存失败，设置草稿仍保留' }
  finally { if (ticket === generation) saving.value = false }
}
watch(() => session.currentUser.id, () => { generation++; draftOwner = session.currentUser.id; rememberNoteSettingsDraft(draftOwner,null); draft.value = null; dirty.value = false; version.value = ''; catalog.value = null; void load() })
onMounted(load)
onBeforeUnmount(() => { generation++; remember() })
</script>
<template>
  <section class="note-ai-settings-form">
    <p v-if="loading" role="status">正在读取小记 AI 设置…</p>
    <template v-if="draft">
      <fieldset :disabled="loading || saving">
        <label><input v-model="draft.title" type="checkbox">保存后润色标题</label>
        <label><input v-model="draft.polish" type="checkbox">保存后润色正文</label>
        <label><input v-model="draft.image" type="checkbox">保存后自动配图</label>
        <p>新建和修改正文按已保存的设置整理；手动标题保留，已有自动配图不重复生成。开启不重跑历史小记，关闭阻止旧自动结果写回；手动单项执行仍可用。</p>
        <label>执行方式<select v-model="draft.executionMode"><option value="parallel">并行</option><option value="serial">串行</option></select></label>
        <label v-if="draft.executionMode === 'parallel'">成员入队上限<select v-model.number="draft.maxConcurrent"><option v-for="n in 5" :key="n" :value="n">{{ n }}</option></select></label>
        <label>标题、正文和配图模型<select aria-label="小记处理模型" v-model="draft.model"><option value="">沿用已配置的默认模型</option><option v-if="draft.model && !catalog?.models.some(m => m.id === draft?.model)" :value="draft.model">{{ draft.model }} · 已保留，待确认可用</option><option v-for="model in catalog?.models || []" :key="model.id" :value="model.id">{{ model.label }}</option></select></label>
        <label>思考深度<select aria-label="小记思考深度" v-model="draft.reasoningEffort"><option v-if="!selectedEfforts.includes(draft.reasoningEffort)" :value="draft.reasoningEffort" disabled>{{ effortLabels[draft.reasoningEffort] || draft.reasoningEffort }} · 当前模型不支持</option><option v-for="effort in selectedEfforts" :key="effort" :value="effort">{{ effortLabels[effort] || effort }}{{ effort === 'medium' ? ' · 默认' : '' }}</option></select></label>
        <p>默认“中”；新提交的标题、正文和配图任务会使用保存时所选深度。</p>
        <p class="model-status">{{ modelError || catalog?.message }} <button type="button" @click="loadModels">刷新模型目录</button></p>
        <label>标题提示词<textarea v-model="draft.titlePrompt" maxlength="4000" rows="2" placeholder="留空沿用默认" /></label>
        <label>正文润色提示词<textarea v-model="draft.polishPrompt" maxlength="4000" rows="3" placeholder="留空沿用默认" /></label>
        <label>自动配图提示词<textarea v-model="draft.imagePrompt" maxlength="4000" rows="3" placeholder="留空沿用默认" /></label>
      </fieldset>
      <p>整篇润色最多 12000 个实现字符；超长内容可选段润色，每次最多 4000。自动配图暂需至少 80 个实现字符，短文可手动配图。</p>
    </template>
    <p v-if="error" role="alert">{{ error }} · 未保存设置仍保留</p>
    <p v-else role="status">{{ saving ? '设置保存中…' : status }}</p>
    <footer><button type="button" :disabled="loading || saving" @click="load">{{ error ? '重新读取 / 重试' : '刷新设置' }}</button><button class="primary" type="button" :disabled="!draft || loading || saving" @click="save">{{ saving ? '保存中…' : error ? '重试保存' : '保存设置' }}</button></footer>
  </section>
</template>
<style scoped>
fieldset { border: 0; padding: 0; display: grid; gap: 10px; }
label { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
textarea { width: 100%; box-sizing: border-box; padding: 8px; resize: vertical; }
select, textarea { color: var(--wao-text-1); background: var(--wao-bg-card); border: 1px solid var(--wao-divider); border-radius: 6px; }
p { font-size: 12px; line-height: 1.6; color: var(--wao-text-weak); }
[role=alert] { color: var(--wao-danger-strong); }
footer { display: flex; justify-content: flex-end; gap: 8px; }
button { padding: 6px 10px; border: 1px solid var(--wao-divider); border-radius: 6px; background: var(--wao-bg-card); color: var(--wao-text-1); }
</style>
