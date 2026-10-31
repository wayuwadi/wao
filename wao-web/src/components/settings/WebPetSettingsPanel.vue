<script setup lang="ts">
import { useSaveBeforeRefresh } from '@/composables/saveBeforeRefresh'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import { toast } from '@/composables/toast'
import {
  type WebPetSettings,
  normalizeWebPetSettings,
  useWebPetStore,
} from '@/stores/webPet'

const pet = useWebPetStore()
const session = useSessionStore()
const owner = session.userId
const draftKey = `wao-web-pet-settings-draft-v1:${owner}`
const draft = ref<WebPetSettings | null>(null)
const baseline = ref('')
const draftError = ref('')
const draftRestored = ref(false)
let draftReadable = false
let disposed = false
let pendingSave: Promise<void> | undefined
const dirty = computed(() => !!draft.value && JSON.stringify(draft.value) !== baseline.value)

function persistDraft(confirmed = false) {
  if (!owner || session.userId !== owner || !draft.value || !draftReadable) return
  try {
    if (dirty.value || (pendingSave && !confirmed)) localStorage.setItem(draftKey, JSON.stringify(draft.value))
    else localStorage.removeItem(draftKey)
    draftError.value = ''
  } catch {
    draftError.value = '本地草稿暂时无法保存，请保存到服务器后再离开。'
  }
}
watch(draft, () => persistDraft(), { deep: true })
watch(() => pet.settings, (value) => {
  if (!draft.value || dirty.value || pendingSave || pet.saveState === 'saving' || pet.loadState !== 'loaded') return
  draft.value = { ...value }
  baseline.value = JSON.stringify(value)
}, { deep: true })

async function load(force = false) {
  await pet.load(force)
  if (disposed || session.userId !== owner || pet.loadState !== 'loaded') return
  if (draft.value && dirty.value) return
  baseline.value = JSON.stringify(pet.settings)
  let restored: WebPetSettings | null = null
  try {
    const raw = localStorage.getItem(draftKey)
    if (raw) restored = normalizeWebPetSettings(JSON.parse(raw))
    draftReadable = true
  } catch {
    draftError.value = '本地草稿读取失败，离开前请先保存设置。'
  }
  draft.value = restored ?? { ...pet.settings }
  draftRestored.value = !!restored && dirty.value
}

function save(): Promise<void> {
  if (pendingSave) return pendingSave
  if (!draft.value || !dirty.value || session.userId !== owner) return Promise.resolve()
  const submitted = { ...draft.value }
  const request = (async () => {
    try {
      const saved = await pet.save(submitted)
      if (disposed || session.userId !== owner) return
      // 请求期间允许继续编辑；只确认本次已落库的快照。
      if (JSON.stringify(draft.value) === JSON.stringify(submitted)) draft.value = { ...saved }
      baseline.value = JSON.stringify(saved)
      draftReadable = true
      persistDraft(true)
      draftRestored.value = false
      toast(dirty.value ? '本次设置已保存，后续修改仍待保存' : '宠物设置已保存并立即生效', 'ri-bear-smile-line')
    } catch {
      // 原草稿保留在表单和本地存储中，允许原样重试。
    }
  })()
  pendingSave = request
  void request.finally(() => {
    if (pendingSave === request) {
      pendingSave = undefined
      if (!disposed) persistDraft()
    }
  })
  return request
}
function confirmLeave() {
  return (!dirty.value && !pendingSave) || window.confirm('宠物设置还有未保存的修改，确认离开吗？')
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value && !pendingSave) return
  event.preventDefault()
  event.returnValue = ''
}
defineExpose({ confirmLeave })
onMounted(() => {
  void load()
  window.addEventListener('beforeunload', beforeUnload)
})
onBeforeUnmount(() => {
  disposed = true
  window.removeEventListener('beforeunload', beforeUnload)
})
useSaveBeforeRefresh(async () => {
  if (pendingSave) await pendingSave
  if (dirty.value) await save()
  if (dirty.value) throw new Error('宠物设置尚未全部保存，草稿已保留')
})
</script>

<template>
  <div class="settings-heading">
    <div>
      <h1>网页宠物设置</h1>
      <p>按当前成员跨设备保存。点击宠物打开聊天，拖动宠物调整位置。</p>
    </div>
    <button
      class="primary"
      type="button"
      :disabled="!dirty || pet.saveState === 'saving'"
      @click="save"
    >
      {{ pet.saveState === 'saving' ? '保存中…' : dirty ? '保存宠物设置' : pet.loadState === 'loaded' ? '已保存' : '等待读取' }}
    </button>
  </div>

  <div v-if="pet.loadState === 'loading' && !draft" class="state-card">
    <i class="ri-loader-4-line spin"></i>正在读取宠物设置…
  </div>
  <div v-else-if="pet.loadState === 'error' && !draft" class="state-card error" role="alert">
    <span>{{ pet.error }}</span><button type="button" @click="load(true)">重新读取</button>
  </div>
  <form v-else-if="draft" class="pet-settings" @submit.prevent="save">
    <p v-if="draftRestored && dirty" class="state-card" role="status">已恢复未保存的宠物设置，请确认后保存。</p>
    <div v-if="draftError" class="state-card error" role="alert">{{ draftError }}<button v-if="draftReadable || !dirty" type="button" @click="draftReadable ? persistDraft() : load(true)">重试草稿</button></div>
    <section class="settings-card">
      <header><div><h2>宠物提醒</h2><p>任务动态仅提示一次，8 秒后收起；聊天未读提示保留到查看。悬停不展开操作按钮。</p></div></header>
      <div class="switch-list">
        <label><input v-model="draft.showTaskNotices" type="checkbox" /><span><b>展示真实任务气泡</b><small>关闭后保留宠物和聊天消息提示。</small></span></label>
        <label><input v-model="draft.idleAnimation" type="checkbox" /><span><b>空闲基础动作</b><small>关闭后宠物保持静止；系统“减少动态效果”始终优先。</small></span></label>
      </div>
    </section>

    <div v-if="pet.saveState === 'error'" class="state-card error" role="alert">
      <span>{{ pet.error }}；未保存草稿仍在，可直接重试。</span>
      <button type="submit">重试保存</button>
    </div>
  </form>
</template>

<style scoped>
.settings-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.settings-heading h1 { margin: 0; font-size: var(--wao-font-section); }
.settings-heading p, .settings-card p { margin: 5px 0 0; color: var(--wao-text-weak); font-size: var(--wao-font-caption); line-height: 1.55; }
.primary { min-height: 34px; padding: 0 13px; border: 0; border-radius: 8px; background: var(--wao-primary); color: #fff; cursor: pointer; white-space: nowrap; }
.primary:disabled { opacity: .55; cursor: default; }
.pet-settings { display: grid; gap: 14px; margin-top: 14px; }
.settings-card { padding: 16px; border: 1px solid var(--wao-line); border-radius: 12px; background: var(--wao-bg-card); }
.settings-card h2 { margin: 0; font-size: var(--wao-font-body); }
.trigger-grid, .duration-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-top: 15px; }
.trigger-grid label, .duration-grid label { display: grid; gap: 6px; color: var(--wao-text-3); font-size: var(--wao-font-help); }
select, input[type='number'] { width: 100%; height: 36px; box-sizing: border-box; padding: 0 9px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text); }
.duration-grid label > div { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 7px; }
.duration-grid em { font-style: normal; }
.switch-list { display: grid; gap: 9px; margin-top: 15px; }
.switch-list label { display: flex; align-items: flex-start; gap: 9px; padding: 10px; border: 1px solid var(--wao-divider); border-radius: 9px; cursor: pointer; }
.switch-list input { margin-top: 2px; }
.switch-list span, .switch-list b, .switch-list small { display: block; }
.switch-list b { color: var(--wao-text); font-size: var(--wao-font-caption); }
.switch-list small { margin-top: 3px; color: var(--wao-text-3); font-size: var(--wao-font-help); line-height: 1.45; }
.state-card { display: flex; align-items: center; gap: 8px; margin-top: 14px; padding: 12px; border-radius: 9px; background: var(--wao-bg-sub); color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.state-card span { flex: 1; }.state-card button { border: 0; background: none; color: inherit; cursor: pointer; }
.state-card.error { background: var(--wao-danger-bg); color: var(--wao-danger-strong); }
.spin { display: inline-block; animation: spin .9s linear infinite; } @keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) { .settings-heading { flex-direction: column; }.primary { width: 100%; }.trigger-grid, .duration-grid { grid-template-columns: 1fr; } }
</style>
