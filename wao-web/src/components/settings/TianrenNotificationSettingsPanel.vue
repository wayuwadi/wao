<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { fetchTianrenPreference, saveTianrenPreference } from '@/api/tianren'
import { useSessionStore } from '@/stores/session'
import {
  DEFAULT_WORKDAY_RHYTHM,
  parseRhythmConfig,
  type WorkdayRhythmEnergyNotification,
} from '@/utils/workdayRhythm'
import WorkdayNotificationRoutes from '@/components/workday/WorkdayNotificationRoutes.vue'
const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })

const session = useSessionStore()
const preferenceKey = 'workday-rhythm-config-v1'
const draftKey = computed(() => 'wao-tianren-notification-draft:' + session.userId)
const draft = ref<WorkdayRhythmEnergyNotification>(
  structuredClone(DEFAULT_WORKDAY_RHYTHM.energyNotification),
)
const baseline = ref('')
const raw = ref('')
const loading = ref(false)
const loaded = ref(false)
const saving = ref(false)
const saved = ref(false)
const error = ref('')
const dirty = computed(() => loaded.value && JSON.stringify(draft.value) !== baseline.value)
const status = computed(() =>
  saved.value
    ? '卦象通知已保存'
    : loading.value
      ? '正在读取通知设置…'
      : dirty.value
        ? '通知设置尚未保存，本机草稿已保留'
        : loaded.value
          ? '已读取账号通知设置'
          : '通知设置尚未读取',
)
let generation = 0
watch(
  draft,
  () => {
    if (!loaded.value) return
    saved.value = false
    try {
      if (dirty.value) localStorage.setItem(draftKey.value, JSON.stringify(draft.value))
      else localStorage.removeItem(draftKey.value)
    } catch {
      error.value = '本机草稿保存失败，请保持页面打开并保存。'
    }
  },
  { deep: true, flush: 'sync' },
)
function confirmLeave() {
  return (
    !saving.value &&
    (!dirty.value || window.confirm('卦象通知尚未保存，本机草稿会保留。仍要离开？'))
  )
}
async function load() {
  if (
    saving.value ||
    (dirty.value && !window.confirm('重新读取将以服务端设置为准，放弃当前修改？'))
  )
    return
  const restoreDraft = !loaded.value
  const current = ++generation
  loading.value = true
  loaded.value = false
  error.value = ''
  try {
    const value = await fetchTianrenPreference(preferenceKey)
    if (current !== generation) return
    raw.value = value
    draft.value = structuredClone(
      (parseRhythmConfig(value) ?? DEFAULT_WORKDAY_RHYTHM).energyNotification,
    )
    baseline.value = JSON.stringify(draft.value)
    const cached = localStorage.getItem(draftKey.value)
    if (restoreDraft && cached) draft.value = JSON.parse(cached) as WorkdayRhythmEnergyNotification
    if (!restoreDraft) localStorage.removeItem(draftKey.value)
    loaded.value = true
  } catch {
    error.value = '卦象通知设置读取失败，请重试；本机草稿仍保留。'
  } finally {
    if (current === generation) loading.value = false
  }
}
async function save() {
  if (!loaded.value || loading.value || saving.value) return
  const current = generation
  const member = session.userId
  const key = draftKey.value
  const next = JSON.parse(raw.value || JSON.stringify(DEFAULT_WORKDAY_RHYTHM))
  next.energyNotification = JSON.parse(JSON.stringify(draft.value))
  saving.value = true
  error.value = ''
  try {
    if ((await fetchTianrenPreference(preferenceKey)) !== raw.value)
      throw new Error('设置已在别处更新，请保留草稿并核对后重试。')
    if (current !== generation || member !== session.userId) return
    const value = JSON.stringify(next)
    await saveTianrenPreference(preferenceKey, value)
    if (current !== generation) return
    raw.value = value
    baseline.value = JSON.stringify(draft.value)
    localStorage.removeItem(key)
    saved.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败，请重试。'
  } finally {
    if (current === generation) saving.value = false
  }
}
watch(
  () => session.userId,
  () => {
    generation++
    loaded.value = false
    saving.value = false
    void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  generation++
})
defineExpose({ confirmLeave, load, save, loading, loaded, saving, dirty, error, status })
</script>

<template>
  <section
    id="tianren-notifications"
    class="tianren-settings"
    :class="{ embedded: props.embedded }"
  >
    <h3 v-if="!props.embedded">天人一卦通知</h3>
    <p v-if="loading" role="status">正在读取…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <WorkdayNotificationRoutes v-if="loaded" v-model="draft" only-tianren :disabled="saving" />
    <div v-if="!props.embedded" class="actions">
      <button type="button" :disabled="loading || saving" @click="load">
        {{ loaded ? '重新读取' : '重试读取' }}
      </button>
      <button v-if="loaded" type="button" :disabled="!dirty || saving" @click="save">
        {{ saving ? '保存中…' : '保存卦象通知' }}
      </button>
      <span role="status">{{ saved ? '已保存' : dirty ? '本机草稿已保留' : '' }}</span>
    </div>
  </section>
</template>
<style scoped>
.tianren-settings {
  padding: 16px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
}
.tianren-settings.embedded {
  padding: 0;
  border: 0;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 12px;
}
button {
  padding: 6px 12px;
  border: 1px solid var(--wao-line);
  border-radius: 6px;
  cursor: pointer;
}
</style>
