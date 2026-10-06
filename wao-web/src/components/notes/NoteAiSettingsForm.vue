<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AiSettingsLayout from '@/components/ui/AiSettingsLayout.vue'
import AIModelFields from '@/components/ui/AIModelFields.vue'
import AiSettingsToggleRow from '@/components/ui/AiSettingsToggleRow.vue'
import AiSettingsSaveStatus from '@/components/ui/AiSettingsSaveStatus.vue'
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
import NoteImageModeSwitch from './NoteImageModeSwitch.vue'
import { useNoteImageMode } from '@/composables/noteImageMode'
import { useNoteDisplayPreferences } from '@/composables/noteDisplayPreferences'
const emit = defineEmits<{ saved: []; close: [] }>()
// progressive 保留既有调用接口；所有入口统一展示分项布局。
withDefaults(defineProps<{ progressive?: boolean; closable?: boolean }>(), {
  progressive: false,
  closable: false,
})
const session = useSessionStore(),
  preferences = usePreferenceStore()
const imageMode = useNoteImageMode()
const noteDisplay = useNoteDisplayPreferences()
type Section = 'display' | 'capabilities' | 'titlePrompt' | 'polishPrompt' | 'imagePrompt' | 'execution'
const active = ref<Section>('capabilities')
const items = [
  { id: 'display', label: '显示与图片', icon: 'ri-layout-line' },
  { id: 'capabilities', label: 'AI 能力', icon: 'ri-cpu-line' },
  { id: 'titlePrompt', label: '标题提示词', icon: 'ri-heading' },
  { id: 'polishPrompt', label: '正文提示词', icon: 'ri-text' },
  { id: 'imagePrompt', label: '配图提示词', icon: 'ri-image-line' },
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
          v-if="draft && active !== 'display'"
          v-model:model="draft.model"
          v-model:reasoning-effort="draft.reasoningEffort"
          :catalog="catalog"
          :efforts="selectedEfforts"
          :loading="catalogLoading"
          :disabled="loading || saving"
          :error="catalogError || validationError"
          scope="小记"
          allow-default
          description="仅用于小记标题、正文与配图，不影响卦象。"
          @refresh="loadModels"
        />
        <p v-else-if="active !== 'display'" role="status">
          {{ loading ? '正在读取小记 AI 设置…' : '小记 AI 设置尚未读取' }}
        </p></template
      >
      <p v-if="loading && draft" role="status">正在读取小记 AI 设置…</p>
      <section v-if="active === 'display'" class="display-settings">
        <div class="section-heading"><div><h3>编辑时的图片显示</h3><p>默认在正文保留图号引用，图片展示在右侧清单。</p></div></div>
        <NoteImageModeSwitch v-model="imageMode" />
        <p class="behavior-note">当前浏览器立即生效，按账号记住；只改变显示，不重新上传图片或修改正文。</p>
        <div class="section-heading"><div><h3>系统文章的表达</h3><p>轻淡的底色用于区分系统交付文章，个人记录保持原样。</p></div></div>
        <AiSettingsToggleRow :model-value="noteDisplay.settings.value.distinguishSystem" @update:model-value="noteDisplay.update({ distinguishSystem: $event })" title="区分系统文章" description="关闭后使用普通卡片底色，系统来源和筛选继续保留。" icon="ri-palette-line" control-label="区分系统文章" />
        <label class="system-color-field">系统文章颜色<input type="color" aria-label="系统文章颜色" :value="noteDisplay.settings.value.systemColor" :disabled="!noteDisplay.settings.value.distinguishSystem" @input="noteDisplay.update({ systemColor: ($event.target as HTMLInputElement).value })"><span>{{ noteDisplay.settings.value.systemColor }}</span><button type="button" @click="noteDisplay.update({ systemColor: '#7c83b8' })">恢复默认颜色</button></label>
        <div class="system-color-preview" :style="{ background: noteDisplay.settings.value.distinguishSystem ? `color-mix(in srgb, ${noteDisplay.settings.value.systemColor} 3%, var(--wao-bg-card))` : 'var(--wao-bg-card)' }">系统 · 文章标题<span>轻描淡写地表达来源</span></div>
      </section>
      <fieldset v-if="draft && active !== 'display'" :disabled="loading || saving">
        <template v-if="active === 'capabilities'"
          ><div class="section-heading">
            <div>
              <h3>自动整理</h3>
              <p>保存小记后，按你的选择继续处理。</p>
            </div>
          </div>
          <div class="auto-list">
            <AiSettingsToggleRow
              v-model="draft.title"
              title="润色标题"
              description="保留手动标题，只整理自动生成的标题。"
              icon="ri-heading"
              control-label="保存后润色标题"
            />
            <AiSettingsToggleRow
              v-model="draft.polish"
              title="润色正文"
              description="整理表达，原稿继续保留。"
              icon="ri-text"
              control-label="保存后润色正文"
            />
            <AiSettingsToggleRow
              v-model="draft.image"
              title="自动配图"
              description="已有自动配图保留，避免重复生成。"
              icon="ri-image-line"
              control-label="保存后自动配图"
            />
          </div>
          <div class="behavior-note">
            <i class="ri-information-line" aria-hidden="true"></i
            ><span> 开启后用于新建和修改的小记，不重新处理历史内容。 </span>
          </div>
          <details class="usage-details">
            <summary>处理范围与字数限制</summary>
            <p>关闭阻止旧自动结果写回；手动单项执行仍可用。</p>
            <p>
              整篇润色最多 12000 个实现字符；超长内容可选段润色，每次最多 4000。自动配图暂需至少 80
              个实现字符，短文可手动配图。
            </p>
          </details></template
        >
        <template v-else-if="promptField"
          ><div class="section-heading">
            <div>
              <h3>{{ items.find((item) => item.id === promptField)?.label }}</h3>
              <p>用于下一次生成；留空沿用默认提示词。</p>
            </div>
            <span class="default-tag">{{
              draft[promptField] ? '自定义提示词' : '默认提示词'
            }}</span>
          </div>
          <div class="prompt-toolbar">
            <label :for="`note-${promptField}`">提示词</label
            ><button type="button" @click="draft[promptField] = ''">
              <i class="ri-history-line" aria-hidden="true"></i>恢复默认
            </button>
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
          ><div class="section-heading">
            <div>
              <h3>执行安排</h3>
              <p>把处理顺序和入队数量单独安排。</p>
            </div>
          </div>
          <div class="execution-fields">
            <label
              >执行方式<select v-model="draft.executionMode">
                <option value="parallel">一起排队</option>
                <option value="serial">按顺序入队</option></select
              ><small>安排各阶段入队顺序；本机执行器一次执行一个模型任务。</small></label
            ><label v-if="draft.executionMode === 'parallel'"
              >成员入队上限<select v-model.number="draft.maxConcurrent">
                <option v-for="n in 5" :key="n" :value="n">{{ n }}</option></select
              ><small>限制同时提交的自动处理阶段。</small></label
            >
          </div>
          <div class="behavior-note">
            <i class="ri-information-line" aria-hidden="true"></i
            ><span>修改安排用于之后的新任务，已入队任务继续原安排。</span>
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
        ><AiSettingsSaveStatus
          v-if="active !== 'display'"
          :text="saving ? '设置保存中…' : storageError || error || status"
          :dirty="dirty"
          :loading="loading"
          :saving="saving"
          :error="!!(storageError || error || conflict)"
        />
        <p v-else class="muted">显示偏好在当前浏览器立即生效，按账号记住</p>
        <div class="footer-actions">
          <button v-if="active !== 'display'" class="light-action" type="button" :disabled="loading || saving" @click="load">
            <i class="ri-refresh-line" aria-hidden="true"></i>
            {{ error ? '重新读取 / 重试' : '刷新设置' }}</button
          ><button v-if="closable" type="button" @click="close">关闭</button
          ><button
            v-if="active !== 'display'"
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
  gap: 0;
}
.usage-details {
  margin-top: 10px;
  color: var(--wao-text-3);
  font-size: 11px;
}
.usage-details summary {
  cursor: pointer;
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
  color: var(--wao-text-3);
  font-size: 12px;
}
.execution-fields small {
  font-size: 12px;
  line-height: 1.6;
}
select {
  box-sizing: border-box;
  padding: 0 10px;
  height: 40px;
  width: 100%;
  min-width: 0;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  border-radius: 8px;
  font: inherit;
  font-size: 14px;
}
@media (max-width: 650px) {
  .execution-fields {
    grid-template-columns: 1fr;
  }
  select {
    font-size: 16px;
  }
}
</style>

<style scoped>
.system-color-field{display:flex;align-items:center;flex-wrap:wrap;gap:12px;margin:18px 0;color:var(--wao-text-2);font-size:13px}.system-color-field input{width:44px;height:36px;cursor:pointer;border:1px solid var(--wao-line-control);border-radius:8px;background:var(--wao-bg-card);padding:4px}.system-color-field span{font-size:12px;color:var(--wao-text-faint)}.system-color-preview{border:1px solid var(--wao-line);border-radius:12px;padding:20px;font-weight:600}.system-color-preview span{display:block;margin-top:10px;font-size:12px;font-weight:400;color:var(--wao-text-faint)}
</style>
