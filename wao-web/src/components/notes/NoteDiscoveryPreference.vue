<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import {
  fetchDiscoveryPreference,
  saveDiscoveryPreference,
  fetchDiscoveryCurrent,
  discoveryHttpStatus,
  type DiscoveryPreference,
} from '@/api/noteDiscoveries'
import AiSettingsToggleRow from '@/components/ui/AiSettingsToggleRow.vue'
const session = useSessionStore()
const preference = ref<DiscoveryPreference>(),
  draft = ref(false),
  loading = ref(false),
  saving = ref(false),
  error = ref(''),
  saved = ref(false),
  state = ref('')
let generation = 0
let owner = session.currentUser.id
let restoring = false
const restored = ref(false)
const version = ref(0),
  conflict = ref(false),
  storageError = ref('')
const draftKey = () => `wao-note-discovery-preference-draft-v1:${encodeURIComponent(owner)}`
const dirty = computed(() =>
  preference.value ? draft.value !== preference.value.enabled || conflict.value : restored.value,
)
function remember() {
  if (owner !== session.currentUser.id) return
  if (restoring) return
  try {
    if (dirty.value)
      localStorage.setItem(
        draftKey(),
        JSON.stringify({ enabled: draft.value, version: version.value }),
      )
    else if (preference.value) localStorage.removeItem(draftKey())
    storageError.value = ''
  } catch {
    storageError.value = '本机草稿保存失败，请保存到账号后再离开。'
  }
}
function resolve(keep: boolean) {
  if (!preference.value) return
  if (!keep) draft.value = preference.value.enabled
  version.value = preference.value.version
  conflict.value = false
  error.value = ''
  remember()
}
function confirmLeave() {
  return (!dirty.value && !saving.value) || window.confirm('小记顺手发现设置尚未保存，确认离开吗？')
}
defineExpose({ confirmLeave })
async function load(preserve = false) {
  const mine = generation
  loading.value = true
  error.value = ''
  try {
    const value = await fetchDiscoveryPreference()
    if (mine !== generation) return
    const retained = preserve || dirty.value
    preference.value = value
    if (!retained) {
      draft.value = value.enabled
      version.value = value.version
    }
    conflict.value = version.value !== value.version
    const current = await fetchDiscoveryCurrent()
    if (mine === generation) state.value = current.automationState
  } catch (cause) {
    if (mine === generation)
      error.value = [404, 410].includes(discoveryHttpStatus(cause) ?? 0)
        ? '服务正在更新，自动发现暂不可用。'
        : '设置读取失败，请重试。'
  } finally {
    if (mine === generation) loading.value = false
  }
}
async function save() {
  if (!preference.value || saving.value || loading.value || conflict.value) return
  const mine = generation
  saving.value = true
  error.value = ''
  saved.value = false
  try {
    const value = await saveDiscoveryPreference(draft.value, version.value)
    if (mine !== generation) return
    preference.value = value
    draft.value = value.enabled
    version.value = value.version
    saved.value = true
    remember()
  } catch (cause) {
    if (mine !== generation) return
    if (discoveryHttpStatus(cause) === 409) {
      await load(true)
      conflict.value = true
      error.value = '设置已在其他窗口更新；你的选择已保留，请选择如何处理。'
    } else error.value = '设置没有保存成功，你的选择已保留，请重试。'
  } finally {
    if (mine === generation) saving.value = false
  }
}
watch(draft, () => {
  saved.value = false
  remember()
})
watch(
  () => session.currentUser.id,
  async () => {
    generation++
    const mine = generation
    owner = session.currentUser.id
    preference.value = undefined
    restored.value = false
    conflict.value = false
    saving.value = false
    saved.value = false
    restoring = true
    let raw: string | null = null
    try {
      raw = localStorage.getItem(draftKey())
    } catch {
      storageError.value = '本机设置草稿读取失败。'
    }
    await load()
    if (mine !== generation) return
    try {
      if (!raw) return
      const value = JSON.parse(raw)
      if (typeof value.enabled === 'boolean' && Number.isInteger(value.version)) {
        restored.value = true
        version.value = value.version
        draft.value = value.enabled
        conflict.value =
          !!preference.value && version.value !== (preference.value as DiscoveryPreference).version
      }
    } catch {
      storageError.value = '本机设置草稿读取失败。'
    } finally {
      restoring = false
    }
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  remember()
  generation++
})
</script>
<template>
  <section class="discovery-preference card thin" aria-label="小记顺手发现设置">
    <strong>小记顺手发现</strong>
    <p>读取本人小记，在后台寻找有原文依据的联系。可以随时暂停，已有发现和收藏仍可查看。</p>
    <p v-if="loading" role="status">读取中…</p>
    <template v-if="preference">
      <AiSettingsToggleRow
        v-model="draft"
        title="自动分析本人小记"
        description="已有发现与收藏继续保留。"
        icon="ri-lightbulb-flash-line"
        control-label="自动分析本人小记"
        :disabled="saving || loading || (!preference.available && !preference.enabled)"
      />
      <p v-if="!preference.available">{{ preference.message }}</p>
      <p v-else-if="state === 'FAILED'">最近一次分析未完成，失败记录已保留。</p>
      <p v-else-if="state === 'DEFERRED'">分析暂时延后，稍后继续。</p>
      <button type="button" :disabled="saving || loading || conflict" @click="save">
        {{ saving ? '保存中…' : '保存顺手发现设置' }}
      </button>
    </template>
    <div v-if="conflict" role="alert">
      <p>账号当前为{{ preference?.enabled ? '开启' : '暂停' }}；本机草稿保留。</p>
      <button :disabled="loading || saving" @click="resolve(true)">保留草稿，基于最新版本</button
      ><button :disabled="loading || saving" @click="resolve(false)">使用账号当前设置</button>
    </div>
    <p v-if="storageError" role="alert">{{ storageError }}</p>
    <button type="button" :disabled="loading || saving" @click="load(true)">重新读取设置</button>
    <p v-if="saved" role="status">设置已保存</p>
    <p v-if="error" role="alert">
      {{ error }}
      <button type="button" :disabled="loading || saving" @click="preference ? save() : load()">
        重试
      </button>
    </p>
  </section>
</template>
<style scoped>
.discovery-preference {
  margin: 12px 0;
  padding: 18px;
  font-size: 13px;
  line-height: 1.8;
}
.discovery-preference p {
  color: var(--wao-text-faint);
}
.discovery-preference label {
  display: flex;
  gap: 9px;
  align-items: center;
}
.discovery-preference button {
  margin-top: 10px;
  padding: 7px 12px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-primary-text);
}
</style>
