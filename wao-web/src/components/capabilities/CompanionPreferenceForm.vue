<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { fetchCompanion, saveCompanionMode, type CompanionOverview } from '@/api/noteAiCompanion'
import { useSessionStore } from '@/stores/session'
import AiSettingsSaveStatus from '@/components/ui/AiSettingsSaveStatus.vue'
const session = useSessionStore()
const remote = ref<CompanionOverview | null>(null),
  mode = ref<CompanionOverview['mode']>('AUTO')
const version = ref(0),
  loading = ref(false),
  saving = ref(false),
  conflict = ref(false),
  error = ref(''),
  storageError = ref('')
let generation = 0
let owner = session.userId
let restoring = false
const restored = ref(false)
const dirty = computed(() =>
  remote.value ? mode.value !== remote.value.mode || conflict.value : restored.value,
)
const key = () => `wao-companion-mode-draft-v1:${encodeURIComponent(owner)}`
function remember() {
  if (owner !== session.userId) return
  if (restoring) return
  try {
    if (dirty.value)
      localStorage.setItem(key(), JSON.stringify({ mode: mode.value, version: version.value }))
    else if (remote.value) localStorage.removeItem(key())
    storageError.value = ''
  } catch {
    storageError.value = '本机草稿保存失败，请保存到账号后再离开。'
  }
}
async function load() {
  const ticket = ++generation
  loading.value = true
  error.value = ''
  try {
    const value = await fetchCompanion()
    if (ticket !== generation) return
    const keep = dirty.value
    remote.value = value
    if (!keep) {
      mode.value = value.mode
      version.value = value.version
    }
    conflict.value = version.value !== value.version
  } catch {
    if (ticket === generation) error.value = '采用方式读取失败，请重试。'
  } finally {
    if (ticket === generation) loading.value = false
  }
}
function resolve(keep: boolean) {
  if (!remote.value) return
  if (!keep) mode.value = remote.value.mode
  version.value = remote.value.version
  conflict.value = false
  error.value = ''
  remember()
}
async function save() {
  if (!remote.value || loading.value || saving.value || conflict.value) return
  const ticket = generation
  saving.value = true
  error.value = ''
  try {
    const value = await saveCompanionMode(mode.value, version.value)
    if (ticket !== generation) return
    remote.value = { ...remote.value, ...value }
    version.value = value.version
    remember()
  } catch (cause) {
    if (ticket !== generation) return
    if ((cause as { response?: { status: number } }).response?.status === 409) {
      saving.value = false
      const member = owner
      await load()
      if (member !== owner || member !== session.userId) return
      conflict.value = true
    } else error.value = '保存失败，选择已保留，请重试。'
  } finally {
    if (ticket === generation) saving.value = false
  }
}
watch(mode, remember)
watch(
  () => session.userId,
  async () => {
    generation++
    owner = session.userId
    const member = owner
    remote.value = null
    restored.value = false
    conflict.value = false
    saving.value = false
    restoring = true
    let raw: string | null = null
    try {
      raw = localStorage.getItem(key())
    } catch {
      storageError.value = '本机草稿读取失败。'
    }
    await load()
    if (member !== owner || member !== session.userId) return
    try {
      if (!raw) return
      const draft = JSON.parse(raw)
      if (['AUTO', 'REVIEW'].includes(draft.mode) && Number.isInteger(draft.version)) {
        restored.value = true
        version.value = draft.version
        mode.value = draft.mode
        conflict.value =
          !!remote.value && version.value !== (remote.value as CompanionOverview).version
      }
    } catch {
      storageError.value = '本机草稿读取失败。'
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
  <section class="companion-preference" aria-label="成果采用方式">
    <h3>成果采用方式</h3>
    <p>仅影响之后发起的整理，已排队任务保留原策略。</p>
    <p v-if="loading" role="status">正在读取账号设置…</p>
    <div v-if="remote" class="mode-options">
      <button
        v-for="item in [
          { id: 'AUTO', name: '自动采用' },
          { id: 'REVIEW', name: '先看建议' },
        ]"
        :key="item.id"
        :aria-pressed="mode === item.id"
        :disabled="loading || saving"
        @click="mode = item.id as CompanionOverview['mode']"
      >
        {{ item.name }}
      </button>
    </div>
    <div v-if="conflict" role="alert">
      <p>
        账号已更新，草稿保留。账号当前：{{ remote?.mode === 'AUTO' ? '自动采用' : '先看建议' }}。
      </p>
      <button :disabled="loading || saving" @click="resolve(true)">保留草稿，基于最新版本</button
      ><button :disabled="loading || saving" @click="resolve(false)">使用账号当前设置</button>
    </div>
    <p v-if="error || storageError" role="alert">{{ error || storageError }}</p>
    <footer>
      <AiSettingsSaveStatus
        :text="error || storageError || (dirty ? '有未保存的选择' : '账号设置已读取')"
        :dirty="dirty"
        :loading="loading"
        :saving="saving"
        :error="!!error || !!storageError || conflict"
      />
      <div>
        <button :disabled="loading || saving" @click="load">重新读取</button
        ><button
          class="primary"
          :disabled="!remote || loading || saving || conflict || !dirty"
          @click="save"
        >
          {{ saving ? '保存中…' : '保存采用方式' }}
        </button>
      </div>
    </footer>
  </section>
</template>
<style scoped>
.companion-preference {
  padding: 24px;
  font-size: 13px;
}
.companion-preference h3 {
  margin: 0;
}
.companion-preference p {
  color: var(--wao-text-weak);
  line-height: 1.8;
}
.mode-options {
  display: flex;
  gap: 10px;
  margin: 20px 0;
}
.companion-preference button {
  padding: 9px 13px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  cursor: pointer;
}
.mode-options button[aria-pressed='true'] {
  background: var(--wao-primary-soft);
  border-color: var(--wao-primary);
  color: var(--wao-primary-text);
}
.companion-preference .primary {
  background: var(--wao-primary);
  color: white;
}
.companion-preference button:disabled {
  opacity: 0.5;
}
footer {
  margin-top: 24px;
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  justify-content: space-between;
}
footer > div {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
