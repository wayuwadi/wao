<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import AiSettingsLayout from '@/components/ui/AiSettingsLayout.vue'
import AIModelFields from '@/components/ui/AIModelFields.vue'
import { useAiModelCatalog } from '@/composables/aiModelCatalog'
import {
  fetchTianrenAiSettings,
  saveTianrenAiSettings,
  type TianrenAiSettings,
} from '@/api/tianren'
import { useSessionStore } from '@/stores/session'

const emit = defineEmits<{ close: []; saved: [] }>()
const session = useSessionStore(),
  tab = ref<'capabilities' | 'interpretationPrompt' | 'imagePrompt'>('capabilities')
const items = [
  { id: 'capabilities', label: 'AI 能力', icon: 'ri-sparkling-line' },
  { id: 'interpretationPrompt', label: '卦象解读', icon: 'ri-compass-3-line' },
  { id: 'imagePrompt', label: '图片生成', icon: 'ri-image-line' },
]
type Draft = Pick<
  TianrenAiSettings,
  'version' | 'autoImage' | 'model' | 'reasoningEffort' | 'interpretationPrompt' | 'imagePrompt'
>
const draft = ref<Draft | null>(null),
  remote = ref<TianrenAiSettings | null>(null)
const loading = ref(false),
  saving = ref(false),
  error = ref(''),
  status = ref(''),
  storageError = ref('')
const conflict = ref(false),
  dirty = ref(false)
const {
  catalog,
  catalogError,
  catalogLoading,
  selectedEfforts,
  validationError,
  loadModels,
  reset,
} = useAiModelCatalog(draft)
let generation = 0,
  applying = false,
  owner = session.userId
const draftKey = (member: string) =>
  `wao-tianren-ai-settings-draft-v1:${encodeURIComponent(member)}`
const valid = computed(
  () =>
    !!draft.value &&
    [draft.value.interpretationPrompt, draft.value.imagePrompt].every(
      (value) => value.trim().length >= 1 && value.length <= 4000,
    ),
)

function remember() {
  if (!owner || owner !== session.userId) return
  try {
    if (dirty.value && draft.value)
      localStorage.setItem(draftKey(owner), JSON.stringify(draft.value))
    else localStorage.removeItem(draftKey(owner))
    storageError.value = ''
  } catch {
    storageError.value = '浏览器无法保存本机草稿，请保存到账号后再离开页面'
  }
}
function restoreDraft() {
  if (!owner) return
  try {
    const raw = localStorage.getItem(draftKey(owner))
    if (!raw) return
    const value = JSON.parse(raw) as Draft
    if (
      !Number.isInteger(value.version) ||
      typeof value.autoImage !== 'boolean' ||
      typeof value.interpretationPrompt !== 'string' ||
      typeof value.imagePrompt !== 'string'
    )
      return
    applying = true
    draft.value = {
      ...value,
      model: value.model ?? 'gpt-6-astra',
      reasoningEffort: value.reasoningEffort ?? 'medium',
    }
    dirty.value = true
    applying = false
    status.value = '已恢复本机未保存草稿'
  } catch {
    storageError.value = '本机草稿读取失败，已保存的账号设置仍可读取'
  }
}
watch(
  draft,
  () => {
    if (!applying && draft.value) {
      dirty.value = true
      status.value = '设置尚未保存'
      remember()
    }
  },
  { deep: true, flush: 'sync' },
)
function useRemote() {
  if (!remote.value) return
  applying = true
  const { version, autoImage, interpretationPrompt, imagePrompt, model, reasoningEffort } =
    remote.value
  draft.value = {
    version,
    autoImage,
    interpretationPrompt,
    imagePrompt,
    model: model ?? 'gpt-6-astra',
    reasoningEffort: reasoningEffort ?? 'medium',
  }
  dirty.value = false
  conflict.value = false
  applying = false
  error.value = ''
  status.value = '已读取账号保存的设置'
  remember()
}
async function load() {
  const ticket = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await fetchTianrenAiSettings()
    if (ticket !== generation) return
    remote.value = result
    if (!dirty.value) useRemote()
    else {
      conflict.value = conflict.value || draft.value?.version !== result.version
      status.value = conflict.value
        ? '账号设置已更新，请对比后选择保留草稿或使用账号设置'
        : '已读取账号设置，未保存草稿仍保留'
    }
  } catch (cause) {
    if (ticket === generation)
      error.value = cause instanceof Error ? cause.message : '设置读取失败，请重试'
  } finally {
    if (ticket === generation) loading.value = false
  }
}
function keepDraft() {
  if (!draft.value || !remote.value) return
  draft.value.version = remote.value.version
  conflict.value = false
  error.value = ''
  status.value = '草稿已基于最新版本，点击保存后生效'
  remember()
}
function restoreDefault() {
  if (!draft.value || !remote.value || tab.value === 'capabilities') return
  draft.value[tab.value] =
    tab.value === 'interpretationPrompt'
      ? remote.value.defaultInterpretationPrompt
      : remote.value.defaultImagePrompt
}
async function save() {
  if (!draft.value || loading.value || saving.value || conflict.value || !remote.value) return
  if (!valid.value) {
    error.value = '两段提示词都需要 1–4000 字，不能留空'
    return
  }
  if (validationError.value) {
    error.value = validationError.value
    return
  }
  const ticket = generation,
    original = JSON.stringify(draft.value),
    submitted = {
      ...draft.value,
      interpretationPrompt: draft.value.interpretationPrompt.trim(),
      imagePrompt: draft.value.imagePrompt.trim(),
    }
  saving.value = true
  error.value = ''
  try {
    const result = await saveTianrenAiSettings(submitted)
    if (ticket !== generation) return
    remote.value = result
    if (JSON.stringify(draft.value) === original) useRemote()
    else {
      applying = true
      if (draft.value) draft.value.version = result.version
      applying = false
      remember()
    }
    status.value = dirty.value
      ? '提交的设置已保存，最新修改仍待保存'
      : '设置已保存，将用于下一次解读或配图'
    emit('saved')
  } catch (cause) {
    if (ticket !== generation) return
    const code =
      (cause as { status?: number; response?: { status?: number } })?.status ??
      (cause as { response?: { status?: number } })?.response?.status
    if (code === 409) {
      conflict.value = true
      remote.value = null
      error.value = '其他页面或设备已修改设置，草稿仍保留。请重新读取并对比后保存。'
    } else error.value = cause instanceof Error ? cause.message : '保存失败，设置草稿仍保留，请重试'
  } finally {
    if (ticket === generation) saving.value = false
  }
}
function close() {
  remember()
  emit('close')
}
watch(
  () => session.userId,
  () => {
    generation++
    owner = session.userId
    applying = true
    draft.value = null
    remote.value = null
    dirty.value = false
    conflict.value = false
    applying = false
    saving.value = false
    storageError.value = ''
    status.value = ''
    restoreDraft()
    reset()
    void load()
  },
  { flush: 'sync' },
)
onMounted(() => {
  restoreDraft()
  void load()
})
onBeforeUnmount(() => {
  remember()
  generation++
})
</script>

<template>
  <SettingsDialogShell
    title="解读与配图设置"
    description="保存后用于下一次生成；已有解读与配图保留。"
    @close="close"
  >
    <AiSettingsLayout :items="items" :active="tab" @update:active="tab = $event as typeof tab">
      <template #engine
        ><AIModelFields
          v-if="draft"
          v-model:model="draft.model"
          v-model:reasoning-effort="draft.reasoningEffort"
          :catalog="catalog"
          :efforts="selectedEfforts"
          :loading="catalogLoading"
          :disabled="loading || saving"
          :error="catalogError || validationError"
          scope="天人"
          description="用于卦象解读与图片生成，独立保存此处设置。"
          @refresh="loadModels"
        />
        <p v-else role="status">
          {{ loading ? '正在读取账号设置…' : '账号设置尚未读取' }}
        </p></template
      >
      <p v-if="loading && draft" role="status">正在读取账号设置…</p>
      <fieldset v-if="draft" :disabled="saving || loading">
        <template v-if="tab === 'capabilities'"
          ><h3>生成安排</h3>
          <p>解读完成后，按你的选择生成一张卦象图片。</p>
          <label class="auto-image"
            ><span
              ><strong>解读时一起生成图片</strong
              ><small>解读与图片共用上方处理模型和思考深度。</small></span
            ><input v-model="draft.autoImage" type="checkbox" aria-label="解读时一起生成图片"
          /></label>
          <p class="behavior-note">
            保存后用于下一次生成；已有解读与配图保留。恢复默认也需要保存才会生效。
          </p></template
        >
        <template v-else
          ><h3>{{ tab === 'interpretationPrompt' ? '卦象解读提示词' : '图片生成提示词' }}</h3>
          <p>用于下一次生成；切换设置项会保留你的草稿。</p>
          <div class="prompt-toolbar">
            <label for="tianren-prompt">提示词</label
            ><button type="button" :disabled="!remote" @click="restoreDefault">恢复当前默认</button>
          </div>
          <textarea
            id="tianren-prompt"
            v-model="draft[tab]"
            rows="11"
            :aria-label="tab === 'interpretationPrompt' ? '卦象解读提示词' : '图片生成提示词'"
          ></textarea>
          <div class="prompt-meta">
            <span>切换设置项会保留草稿</span><span>{{ draft[tab].length }} / 4000 字</span>
          </div></template
        >
      </fieldset>
      <div v-if="conflict && remote" class="settings-conflict" role="alert">
        <strong>保留了你的未保存草稿</strong>
        <p>草稿基于版本 {{ draft?.version }}，账号当前为版本 {{ remote.version }}。</p>
        <details>
          <summary>查看账号当前设置</summary>
          <p>模型：{{ remote.model }}；思考深度：{{ remote.reasoningEffort }}</p>
          <p>一起生成图片：{{ remote.autoImage ? '开启' : '关闭' }}</p>
          <h3>卦象解读</h3>
          <pre>{{ remote.interpretationPrompt }}</pre>
          <h3>图片生成</h3>
          <pre>{{ remote.imagePrompt }}</pre>
        </details>
        <div>
          <button type="button" :disabled="loading || saving" @click="keepDraft">
            保留草稿，基于最新版本</button
          ><button type="button" :disabled="loading || saving" @click="useRemote">
            使用账号当前设置
          </button>
        </div>
      </div>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="storageError" class="error" role="alert">{{ storageError }}</p>
      <template #footer
        ><p class="save-status" role="status">
          {{ saving ? '正在保存设置…' : storageError || error || status }}
        </p>
        <div class="footer-actions">
          <button type="button" :disabled="loading || saving" @click="load">重新读取设置</button
          ><button type="button" @click="close">关闭</button
          ><button
            class="primary"
            type="button"
            :disabled="!draft || !remote || loading || saving || conflict"
            @click="save"
          >
            {{ saving ? '保存中…' : error && !conflict ? '重试保存' : '保存设置' }}
          </button>
        </div></template
      >
    </AiSettingsLayout>
  </SettingsDialogShell>
</template>
<style scoped>
.auto-image {
  display: flex;
  align-items: center;
  gap: 15px;
  justify-content: space-between;
  padding: 18px 0;
  border-bottom: 1px solid var(--wao-divider);
}
.auto-image span {
  display: grid;
  gap: 5px;
}
.auto-image small {
  font-size: 12px;
  color: var(--wao-text-weak);
}
input {
  width: 18px;
  height: 18px;
  accent-color: var(--wao-primary);
  flex-shrink: 0;
}
.behavior-note {
  padding: 12px 14px;
  background: var(--wao-bg-sub);
  border-radius: 8px;
}
</style>
