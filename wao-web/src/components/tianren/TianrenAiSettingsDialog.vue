<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fetchTianrenAiSettings, saveTianrenAiSettings, type TianrenAiSettings } from '@/api/tianren'
import { useSessionStore } from '@/stores/session'

const emit = defineEmits<{ close: [] }>()
const session = useSessionStore(), tab = ref<'interpretationPrompt' | 'imagePrompt'>('interpretationPrompt')
type Draft = Pick<TianrenAiSettings, 'version' | 'autoImage' | 'interpretationPrompt' | 'imagePrompt'>
const draft = ref<Draft | null>(null), remote = ref<TianrenAiSettings | null>(null)
const loading = ref(false), saving = ref(false), error = ref(''), status = ref(''), storageError = ref('')
const conflict = ref(false), dirty = ref(false), dialog = ref<HTMLElement | null>(null)
let generation = 0, applying = false, owner = session.userId
const trigger = document.activeElement as HTMLElement | null
const draftKey = (member: string) => `wao-tianren-ai-settings-draft-v1:${encodeURIComponent(member)}`
const valid = computed(() => !!draft.value && [draft.value.interpretationPrompt, draft.value.imagePrompt].every(value => value.trim().length >= 1 && value.length <= 4000))

function remember() {
  if (!owner || owner !== session.userId) return
  try {
    if (dirty.value && draft.value) localStorage.setItem(draftKey(owner), JSON.stringify(draft.value))
    else localStorage.removeItem(draftKey(owner))
    storageError.value = ''
  } catch { storageError.value = '浏览器无法保存本机草稿，请保存到账号后再离开页面' }
}
function restoreDraft() {
  if (!owner) return
  try {
    const raw = localStorage.getItem(draftKey(owner))
    if (!raw) return
    const value = JSON.parse(raw) as Draft
    if (!Number.isInteger(value.version) || typeof value.autoImage !== 'boolean'
      || typeof value.interpretationPrompt !== 'string' || typeof value.imagePrompt !== 'string') return
    applying = true; draft.value = value; dirty.value = true; applying = false
    status.value = '已恢复本机未保存草稿'
  } catch { storageError.value = '本机草稿读取失败，已保存的账号设置仍可读取' }
}
watch(draft, () => { if (!applying && draft.value) { dirty.value = true; status.value = '设置尚未保存'; remember() } }, { deep: true, flush: 'sync' })
function useRemote() {
  if (!remote.value) return
  applying = true
  const { version, autoImage, interpretationPrompt, imagePrompt } = remote.value
  draft.value = { version, autoImage, interpretationPrompt, imagePrompt }
  dirty.value = false; conflict.value = false; applying = false; error.value = ''; status.value = '已读取账号保存的设置'; remember()
}
async function load() {
  const ticket = ++generation; loading.value = true; error.value = ''
  try {
    const result = await fetchTianrenAiSettings()
    if (ticket !== generation) return
    remote.value = result
    if (!dirty.value) useRemote()
    else {
      conflict.value = draft.value?.version !== result.version
      status.value = conflict.value ? '账号设置已更新，请对比后选择保留草稿或使用账号设置' : '已读取账号设置，未保存草稿仍保留'
    }
  } catch (cause) { if (ticket === generation) error.value = cause instanceof Error ? cause.message : '设置读取失败，请重试' }
  finally { if (ticket === generation) loading.value = false }
}
function keepDraft() {
  if (!draft.value || !remote.value) return
  draft.value.version = remote.value.version; conflict.value = false; error.value = ''; status.value = '草稿已基于最新版本，点击保存后生效'; remember()
}
function restoreDefault() {
  if (!draft.value || !remote.value) return
  draft.value[tab.value] = tab.value === 'interpretationPrompt' ? remote.value.defaultInterpretationPrompt : remote.value.defaultImagePrompt
}
async function save() {
  if (!draft.value || loading.value || saving.value || conflict.value || !remote.value) return
  if (!valid.value) { error.value = '两段提示词都需要 1–4000 字，不能留空'; return }
  const ticket = generation, submitted = { ...draft.value, interpretationPrompt: draft.value.interpretationPrompt.trim(), imagePrompt: draft.value.imagePrompt.trim() }
  saving.value = true; error.value = ''
  try {
    const result = await saveTianrenAiSettings(submitted)
    if (ticket !== generation) return
    remote.value = result; useRemote(); status.value = '设置已保存，将用于下一次解读或配图'
  } catch (cause) {
    if (ticket !== generation) return
    const code = (cause as { status?: number; response?: { status?: number } })?.status ?? (cause as { response?: { status?: number } })?.response?.status
    if (code === 409) { conflict.value = true; remote.value = null; error.value = '其他页面或设备已修改设置，草稿仍保留。请重新读取并对比后保存。' }
    else error.value = cause instanceof Error ? cause.message : '保存失败，设置草稿仍保留，请重试'
  } finally { if (ticket === generation) saving.value = false }
}
function close() { remember(); emit('close') }
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); close(); return }
  if (event.key !== 'Tab') return
  const elements = [...(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), textarea:not(:disabled)') ?? [])]
  const first = elements[0], last = elements[elements.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
watch(() => session.userId, () => {
  generation++; owner = session.userId; applying = true; draft.value = null; remote.value = null; dirty.value = false; conflict.value = false; applying = false
  saving.value = false; storageError.value = ''; restoreDraft(); void load()
})
onMounted(async () => { restoreDraft(); void load(); await nextTick(); dialog.value?.querySelector<HTMLButtonElement>('button')?.focus() })
onBeforeUnmount(() => { remember(); generation++; trigger?.focus() })
</script>

<template>
  <Teleport to="body"><div class="settings-overlay" @click.self="close"><section ref="dialog" class="settings-dialog" role="dialog" aria-modal="true" aria-labelledby="tianren-settings-title" @keydown="onKeydown">
    <header><h2 id="tianren-settings-title">解读与配图设置</h2><button type="button" aria-label="关闭解读与配图设置" @click="close"><i class="ri-close-line"></i></button></header>
    <p class="settings-info">保存后用于下一次生成；已有解读与配图保留。恢复默认也需要保存才会生效。</p>
    <p v-if="loading" role="status">正在读取账号设置…</p>
    <template v-if="draft">
      <fieldset :disabled="saving || loading">
        <label class="auto-image"><input v-model="draft.autoImage" type="checkbox" />解读时一起生成图片</label>
        <nav class="settings-tabs" aria-label="提示词类型"><button type="button" :aria-pressed="tab === 'interpretationPrompt'" @click="tab = 'interpretationPrompt'">卦象解读</button><button type="button" :aria-pressed="tab === 'imagePrompt'" @click="tab = 'imagePrompt'">图片生成</button></nav>
        <label class="prompt-field"><strong>{{ tab === 'interpretationPrompt' ? '卦象解读提示词' : '图片生成提示词' }}</strong><textarea v-model="draft[tab]" rows="11" :aria-label="tab === 'interpretationPrompt' ? '卦象解读提示词' : '图片生成提示词'"></textarea><small>{{ draft[tab].length }} / 4000 字</small></label>
      </fieldset>
    </template>
    <div v-if="conflict && remote" class="settings-conflict" role="alert"><strong>保留了你的未保存草稿</strong><p>草稿基于版本 {{ draft?.version }}，账号当前为版本 {{ remote.version }}。</p><details><summary>查看账号当前设置</summary><p>一起生成图片：{{ remote.autoImage ? '开启' : '关闭' }}</p><h3>卦象解读</h3><pre>{{ remote.interpretationPrompt }}</pre><h3>图片生成</h3><pre>{{ remote.imagePrompt }}</pre></details><div><button type="button" :disabled="loading || saving" @click="keepDraft">保留草稿，基于最新版本</button><button type="button" :disabled="loading || saving" @click="useRemote">使用账号当前设置</button></div></div>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="storageError" class="error" role="alert">{{ storageError }}</p>
    <p class="save-status" role="status">{{ saving ? '正在保存设置…' : status }}</p>
    <footer><button type="button" :disabled="!draft || !remote || loading || saving" @click="restoreDefault">恢复当前默认</button><button type="button" :disabled="loading || saving" @click="load">重新读取设置</button><button class="primary" type="button" :disabled="!draft || !remote || loading || saving || conflict" @click="save">{{ saving ? '保存中…' : error && !conflict ? '重试保存' : '保存设置' }}</button></footer>
  </section></div></Teleport>
</template>

<style scoped>
.settings-overlay { position: fixed; inset: 0; z-index: 1200; background: #12223c70; padding: 24px 16px; box-sizing: border-box; display: flex; align-items: center; justify-content: center; }
.settings-dialog { box-sizing: border-box; width: min(660px, 100%); max-height: calc(100dvh - 48px); overflow: auto; border: 1px solid var(--wao-line); border-radius: 15px; box-shadow: 0 20px 60px #0a163d30; padding: 24px; background: var(--wao-bg-card); color: var(--wao-text); }
header, footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; } h2 { margin: 0; font-size: 18px; } h3 { font-size: 13px; } button { cursor: pointer; color: var(--wao-text); background: var(--wao-bg-card); border: 1px solid var(--wao-line); border-radius: 7px; padding: 7px 10px; font: inherit; font-size: 12px; } button:disabled { opacity: .5; cursor: default; } button:focus-visible, textarea:focus-visible, input:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.settings-info { padding: 12px; border-radius: 8px; background: var(--wao-primary-soft); color: var(--wao-primary-text); } p { font-size: 12px; line-height: 1.7; } fieldset { margin: 0; padding: 0; border: 0; min-width: 0; } .auto-image { display: flex; align-items: center; gap: 8px; font-size: 13px; margin: 15px 0; } input { accent-color: var(--wao-primary); } .settings-tabs { display: flex; gap: 6px; margin: 16px 0; } .settings-tabs button[aria-pressed=true] { background: var(--wao-primary-soft); color: var(--wao-primary-text); border-color: transparent; }
.prompt-field { display: grid; gap: 8px; font-size: 13px; } textarea { width: 100%; box-sizing: border-box; min-height: 240px; resize: vertical; font: inherit; line-height: 1.75; color: var(--wao-text); background: var(--wao-bg-card); border: 1px solid var(--wao-line-control); border-radius: 8px; padding: 10px; } small, .save-status { color: var(--wao-text-3); font-size: 12px; } .save-status { min-height: 20px; } footer { flex-wrap: wrap; justify-content: flex-end; } footer button:first-child { margin-right: auto; } .primary { background: var(--wao-primary); color: white; border-color: transparent; } .error { color: var(--wao-danger-strong); } .settings-conflict { padding: 12px; margin-top: 14px; border: 1px solid var(--wao-line); border-radius: 8px; background: var(--wao-bg-sub); font-size: 12px; } .settings-conflict summary { cursor: pointer; } .settings-conflict pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; } .settings-conflict > div { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
@media (max-width: 650px) { .settings-dialog { padding: 16px; } textarea { font-size: 16px; } }
</style>
