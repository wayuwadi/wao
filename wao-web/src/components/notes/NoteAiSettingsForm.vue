<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AiSettingsLayout from '@/components/ui/AiSettingsLayout.vue'
import AIModelFields from '@/components/ui/AIModelFields.vue'
import { useAiModelCatalog } from '@/composables/aiModelCatalog'
import { noteSettingsDraft, rememberNoteSettingsDraft } from '@/utils/noteAiSettingsDraft'
import {
  fetchNoteAiSettings,
  saveNoteAiSettings,
  type NoteAiSettings,
  type NoteAiSettingsView,
} from '@/api/noteAiSettings'
import { useSessionStore } from '@/stores/session'
import { usePreferenceStore } from '@/stores/preferences'
const emit = defineEmits<{ saved: []; close: [] }>()
// progressive 保留既有调用接口；所有入口统一展示分项布局。
withDefaults(defineProps<{ progressive?: boolean; closable?: boolean }>(), {
  progressive: false,
  closable: false,
})
const session = useSessionStore(),
  preferences = usePreferenceStore()
type Section = 'capabilities' | 'titlePrompt' | 'polishPrompt' | 'imagePrompt' | 'execution'
const active = ref<Section>('capabilities')
const items = [
  { id: 'capabilities', label: 'AI 能力', icon: 'ri-sparkling-line' },
  { id: 'titlePrompt', label: '标题提示词', icon: 'ri-text' },
  { id: 'polishPrompt', label: '正文润色', icon: 'ri-file-text-line' },
  { id: 'imagePrompt', label: '自动配图', icon: 'ri-image-line' },
  { id: 'execution', label: '执行安排', icon: 'ri-list-check-2' },
]
const promptLabels = {
  titlePrompt: '标题提示词',
  polishPrompt: '正文润色提示词',
  imagePrompt: '自动配图提示词',
}
const promptField = computed(() =>
  active.value in promptLabels ? (active.value as keyof typeof promptLabels) : null,
)
const draft = ref<NoteAiSettings | null>(null),
  remote = ref<NoteAiSettingsView | null>(null)
const loading = ref(false),
  saving = ref(false),
  dirty = ref(false),
  conflict = ref(false)
const error = ref(''),
  status = ref(''),
  version = ref(''),
  storageError = ref('')
const {
  catalog,
  catalogError,
  catalogLoading,
  selectedEfforts,
  validationError,
  loadModels,
  reset,
} = useAiModelCatalog(draft, true)
let generation = 0,
  applying = false,
  draftOwner = session.currentUser.id
function restore() {
  try {
    const retained = noteSettingsDraft(draftOwner)
    if (retained) {
      applying = true
      draft.value = retained.draft
      version.value = retained.version
      dirty.value = true
      applying = false
      status.value = '已恢复本机未保存草稿'
    }
  } catch {
    storageError.value = '本机设置草稿读取失败，请保存到账号后再离开页面'
  }
}
function remember() {
  try {
    rememberNoteSettingsDraft(
      draftOwner,
      dirty.value && draft.value
        ? { draft: JSON.parse(JSON.stringify(draft.value)), version: version.value }
        : null,
    )
    storageError.value = ''
  } catch {
    storageError.value = '浏览器无法保存本机草稿，请保存到账号后再离开页面'
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
  draft.value = JSON.parse(JSON.stringify(remote.value.settings))
  version.value = remote.value.version
  dirty.value = false
  conflict.value = false
  applying = false
  error.value = ''
  status.value = '已读取保存的设置'
  remember()
}
async function load() {
  const ticket = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await fetchNoteAiSettings()
    if (ticket !== generation) return
    remote.value = result
    preferences.noteAiDefaults = { ...result.settings }
    if (!dirty.value) {
      useRemote()
      status.value = result.configured ? '已读取保存的设置' : '尚无历史设置，三项默认开启'
    } else {
      conflict.value = conflict.value || version.value !== result.version
      status.value = conflict.value
        ? '账号设置已更新，请对比后明确选择'
        : '最新配置已读取，保留你的设置草稿'
    }
  } catch (cause) {
    if (ticket === generation)
      error.value = cause instanceof Error ? cause.message : '读取失败，请重试'
  } finally {
    if (ticket === generation) loading.value = false
  }
}
function keepDraft() {
  if (!remote.value) return
  version.value = remote.value.version
  conflict.value = false
  error.value = ''
  status.value = '草稿已基于最新版本，点击保存后生效'
  remember()
}
async function save() {
  if (!draft.value || !remote.value || saving.value || loading.value || conflict.value) return
  if (validationError.value) {
    error.value = validationError.value
    return
  }
  const ticket = generation,
    submitted = JSON.parse(JSON.stringify(draft.value)) as NoteAiSettings
  saving.value = true
  error.value = ''
  try {
    const result = await saveNoteAiSettings(submitted, version.value)
    if (ticket !== generation) return
    remote.value = result
    version.value = result.version
    preferences.noteAiDefaults = { ...result.settings }
    dirty.value = JSON.stringify(draft.value) !== JSON.stringify(submitted)
    if (!dirty.value) {
      applying = true
      draft.value = structuredClone(result.settings)
      applying = false
    }
    status.value = dirty.value ? '提交的设置已保存，最新修改仍待保存' : '设置已保存'
    remember()
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
    } else error.value = cause instanceof Error ? cause.message : '保存失败，设置草稿仍保留'
  } finally {
    if (ticket === generation) saving.value = false
  }
}
function close() {
  remember()
  emit('close')
}
watch(
  () => session.currentUser.id,
  () => {
    generation++
    draftOwner = session.currentUser.id
    applying = true
    draft.value = null
    dirty.value = false
    conflict.value = false
    version.value = ''
    remote.value = null
    applying = false
    saving.value = false
    status.value = ''
    storageError.value = ''
    restore()
    reset()
    void load()
  },
  { flush: 'sync' },
)
restore()
onMounted(load)
onBeforeUnmount(() => {
  generation++
  remember()
})
</script>
<template>
  <section class="note-ai-settings-form">
    <AiSettingsLayout :items="items" :active="active" @update:active="active = $event as Section">
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
          scope="小记"
          allow-default
          description="用于标题、正文与配图；新任务使用保存时所选深度。"
          @refresh="loadModels"
        />
        <p v-else role="status">
          {{ loading ? '正在读取小记 AI 设置…' : '小记 AI 设置尚未读取' }}
        </p></template
      >
      <p v-if="loading && draft" role="status">正在读取小记 AI 设置…</p>
      <fieldset v-if="draft" :disabled="loading || saving">
        <template v-if="active === 'capabilities'"
          ><h3>自动整理</h3>
          <p>保存小记后，按你的选择继续处理。</p>
          <div class="auto-list">
            <label
              ><span><strong>润色标题</strong><small>保存后润色标题，手动标题保留。</small></span
              ><input v-model="draft.title" type="checkbox" aria-label="保存后润色标题" /></label
            ><label
              ><span><strong>润色正文</strong><small>保存后润色正文，保留原稿与结果。</small></span
              ><input v-model="draft.polish" type="checkbox" aria-label="保存后润色正文" /></label
            ><label
              ><span
                ><strong>自动配图</strong><small>保存后自动配图，已有配图不重复生成。</small></span
              ><input v-model="draft.image" type="checkbox" aria-label="保存后自动配图"
            /></label>
          </div>
          <p class="behavior-note">
            开启不重跑历史小记，关闭阻止旧自动结果写回；手动单项执行仍可用。
          </p>
          <p>
            整篇润色最多 12000 个实现字符；超长内容可选段润色，每次最多 4000。自动配图暂需至少 80
            个实现字符，短文可手动配图。
          </p></template
        >
        <template v-else-if="promptField"
          ><h3>{{ promptLabels[promptField] }}</h3>
          <p>用于下一次生成；留空沿用默认提示词。</p>
          <div class="prompt-toolbar">
            <label :for="`note-${promptField}`">提示词</label
            ><button type="button" @click="draft[promptField] = ''">恢复默认</button>
          </div>
          <textarea
            :id="`note-${promptField}`"
            v-model="draft[promptField]"
            :aria-label="promptLabels[promptField]"
            maxlength="4000"
            rows="11"
            placeholder="留空沿用默认"
          />
          <div class="prompt-meta">
            <span>切换设置项会保留草稿</span><span>{{ draft[promptField].length }} / 4000 字</span>
          </div></template
        >
        <template v-else
          ><h3>执行安排</h3>
          <p>设置用于之后的新任务，已入队任务继续原安排。</p>
          <div class="execution-fields">
            <label
              >执行方式<select v-model="draft.executionMode">
                <option value="parallel">并行</option>
                <option value="serial">串行</option>
              </select></label
            ><label v-if="draft.executionMode === 'parallel'"
              >成员入队上限<select v-model.number="draft.maxConcurrent">
                <option v-for="n in 5" :key="n" :value="n">{{ n }}</option>
              </select></label
            >
          </div></template
        >
      </fieldset>
      <div v-if="conflict && remote" class="settings-conflict" role="alert">
        <strong>保留了你的未保存草稿</strong>
        <p>草稿基于版本 {{ version }}，账号当前为版本 {{ remote.version }}。</p>
        <details>
          <summary>查看账号当前设置</summary>
          <p>
            模型：{{ remote.settings.model || '默认模型' }}；思考深度：{{
              remote.settings.reasoningEffort
            }}
          </p>
          <p>
            润色标题：{{ remote.settings.title ? '开启' : '关闭' }}；润色正文：{{
              remote.settings.polish ? '开启' : '关闭'
            }}；自动配图：{{ remote.settings.image ? '开启' : '关闭' }}
          </p>
          <p>
            执行方式：{{ remote.settings.executionMode }}；成员入队上限：{{
              remote.settings.maxConcurrent
            }}
          </p>
          <template v-for="(label, field) in promptLabels" :key="field"
            ><h4>{{ label }}</h4>
            <pre>{{ remote.settings[field] || '沿用默认' }}</pre>
          </template>
        </details>
        <button type="button" :disabled="loading || saving" @click="keepDraft">
          保留草稿，基于最新版本</button
        ><button type="button" :disabled="loading || saving" @click="useRemote">
          使用账号当前设置
        </button>
      </div>
      <p v-if="error" class="error" role="alert">{{ error }} · 未保存设置仍保留</p>
      <p v-if="storageError" class="error" role="alert">{{ storageError }}</p>
      <template #footer
        ><p class="save-status" role="status">
          {{ saving ? '设置保存中…' : storageError || error || status }}
        </p>
        <div class="footer-actions">
          <button type="button" :disabled="loading || saving" @click="load">
            {{ error ? '重新读取 / 重试' : '刷新设置' }}</button
          ><button v-if="closable" type="button" @click="close">关闭</button
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
  </section>
</template>
<style scoped>
.note-ai-settings-form {
  width: 100%;
  min-width: 0;
  display: flex;
  flex: 1;
  min-height: 0;
  height: 100%;
}
.auto-list {
  display: grid;
}
.auto-list label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 14px 0;
  border-bottom: 1px solid var(--wao-divider);
}
.auto-list label:last-child {
  border: 0;
}
.auto-list span {
  display: grid;
  gap: 4px;
}
.auto-list small {
  color: var(--wao-text-weak);
  font-size: 12px;
}
input {
  accent-color: var(--wao-primary);
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}
.behavior-note {
  background: var(--wao-bg-sub);
  border-radius: 8px;
  padding: 12px 14px;
}
.execution-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-top: 24px;
}
.execution-fields label {
  display: grid;
  gap: 8px;
  color: var(--wao-text-weak);
}
select {
  padding: 10px;
  width: 100%;
  min-width: 0;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  border-radius: 8px;
  font: inherit;
}
@media (max-width: 650px) {
  .execution-fields {
    grid-template-columns: 1fr;
  }
}
</style>
