<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import AiSettingsLayout from '@/components/ui/AiSettingsLayout.vue'
import AIModelFields from '@/components/ui/AIModelFields.vue'
import AiSettingsToggleRow from '@/components/ui/AiSettingsToggleRow.vue'
import AiSettingsSaveStatus from '@/components/ui/AiSettingsSaveStatus.vue'
import AiPromptEditor from '@/components/ui/AiPromptEditor.vue'
import TianrenNotificationSettingsPanel from '@/components/settings/TianrenNotificationSettingsPanel.vue'
import TianrenNotificationStatus from '@/components/workday/TianrenNotificationStatus.vue'
import { useAiModelCatalog } from '@/composables/aiModelCatalog'
import {
  fetchTianrenAiSettings,
  saveTianrenAiSettings,
  type TianrenAiSettings,
} from '@/api/tianren'
import { useSessionStore } from '@/stores/session'

const emit = defineEmits<{ close: []; saved: [] }>()
const props = withDefaults(
  defineProps<{
    readingId?: string
    interpreting?: boolean
    embedded?: boolean
    section?: 'capabilities' | 'interpretationPrompt' | 'imagePrompt'
  }>(),
  {
    readingId: '',
    interpreting: false,
    embedded: false,
    section: 'capabilities',
  },
)
const session = useSessionStore(),
  tab = ref<'capabilities' | 'interpretationPrompt' | 'imagePrompt' | 'notifications'>(
    'capabilities',
  )
watch(
  () => props.section,
  (value) => {
    tab.value = value
  },
  { immediate: true },
)
const items = reactive<{ id: string; label: string; icon: string; pending?: boolean }[]>([
  { id: 'capabilities', label: 'AI 能力', icon: 'ri-cpu-line' },
  { id: 'interpretationPrompt', label: '解读提示词', icon: 'ri-book-open-line' },
  { id: 'imagePrompt', label: '配图提示词', icon: 'ri-image-line' },
  { id: 'notifications', label: '飞书通知', icon: 'ri-notification-3-line' },
])
const notificationPanel = ref<InstanceType<typeof TianrenNotificationSettingsPanel> | null>(null)
const notificationDirty = ref(false)
const notificationsVisited = ref(false)
const isNotification = computed(() => tab.value === 'notifications')
function selectSection(value: string) {
  if (value === 'notifications') notificationsVisited.value = true
  tab.value = value as typeof tab.value
}
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
const aiPending = computed(() =>
  remote.value ? items.some((item) => item.id !== 'notifications' && item.pending) : dirty.value,
)
watch(
  [draft, remote, notificationDirty],
  () => {
    for (const item of items)
      item.pending =
        item.id === 'notifications'
          ? notificationDirty.value
          : !!draft.value &&
            !!remote.value &&
            (item.id === 'capabilities'
              ? ['model', 'reasoningEffort', 'autoImage'].some(
                  (key) => draft.value![key as keyof Draft] !== remote.value![key as keyof Draft],
                )
              : draft.value[item.id as 'interpretationPrompt' | 'imagePrompt'] !==
                remote.value[item.id as 'interpretationPrompt' | 'imagePrompt'])
  },
  { deep: true },
)
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
  if (notificationPanel.value?.saving) return
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
    :embedded="embedded"
    title="天人一卦设置"
    description="把解读、配图和飞书通知，在这里一起安排。"
    @close="close"
  >
    <AiSettingsLayout
      :items="items"
      :active="tab"
      :engine-visible="!isNotification"
      :scope-note="
        isNotification ? '行动通知总开关\n也影响精力补给和重点' : '仅用于天人一卦\n不会修改小记设置'
      "
      @update:active="selectSection"
    >
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
          description="仅用于卦象解读与配图，不影响小记。"
          @refresh="loadModels"
        />
        <p v-else role="status">
          {{ loading ? '正在读取账号设置…' : '账号设置尚未读取' }}
        </p></template
      >
      <p v-if="loading && draft && !isNotification" role="status">正在读取账号设置…</p>
      <div v-show="isNotification" class="notification-holder">
        <TianrenNotificationSettingsPanel
          key="notification-settings"
          v-if="notificationsVisited"
          ref="notificationPanel"
          embedded
          @dirty-change="notificationDirty = $event"
        />
      </div>
      <TianrenNotificationStatus
        v-if="isNotification && props.readingId"
        :reading-id="props.readingId"
        :interpreting="props.interpreting"
        embedded
      />
      <div v-else-if="isNotification" class="behavior-note">
        <i class="ri-information-line" aria-hidden="true"></i
        ><span>今天尚未起卦，起卦后可在这里查看飞书投递状态。保存设置不会立即发送通知。</span>
      </div>
      <fieldset v-if="draft && !isNotification" :disabled="saving || loading">
        <template v-if="tab === 'capabilities'"
          ><div class="section-heading">
            <div>
              <h3>解读与配图</h3>
              <p>文字保存后，图片继续在后台生成。</p>
            </div>
          </div>
          <AiSettingsToggleRow
            v-model="draft.autoImage"
            title="解读时一起配图"
            description="配图与本次解读对应，展示变与不变。"
            icon="ri-image-line"
            control-label="解读时一起生成图片"
          />
          <div class="behavior-note">
            <i class="ri-information-line" aria-hidden="true"></i
            ><span> 只影响之后的新解读，已有解读与配图保留。配图失败可单独重试。 </span>
          </div></template
        >
        <template v-else-if="tab === 'interpretationPrompt' || tab === 'imagePrompt'"
          ><div class="section-heading">
            <div>
              <h3>{{ tab === 'interpretationPrompt' ? '解读提示词' : '配图提示词' }}</h3>
              <p>完整编辑这一步的表达方式。</p>
            </div>
            <span class="default-tag">{{
              draft[tab] ===
              (tab === 'interpretationPrompt'
                ? remote?.defaultInterpretationPrompt
                : remote?.defaultImagePrompt)
                ? '默认提示词'
                : '自定义提示词'
            }}</span>
          </div>
          <AiPromptEditor
            v-model="draft[tab]"
            :editor-id="`tianren-${tab}`"
            :label="tab === 'interpretationPrompt' ? '卦象解读提示词' : '图片生成提示词'"
            :default-value="
              tab === 'interpretationPrompt'
                ? remote?.defaultInterpretationPrompt
                : remote?.defaultImagePrompt
            "
        /></template>
      </fieldset>
      <div v-if="conflict && remote && !isNotification" class="settings-conflict" role="alert">
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
      <p v-if="error && !isNotification" class="error" role="alert">{{ error }}</p>
      <p v-if="storageError && !isNotification" class="error" role="alert">{{ storageError }}</p>
      <template #footer
        ><template v-if="isNotification">
          <div class="footer-status">
            <AiSettingsSaveStatus
              :text="notificationPanel?.error || notificationPanel?.status || '正在读取通知设置…'"
              :dirty="!!notificationPanel?.dirty"
              :loading="!!notificationPanel?.loading"
              :saving="!!notificationPanel?.saving"
              :error="!!notificationPanel?.error"
              hint="只保存通知这一组，跨设备生效；不会立即发送通知"
            />
            <p v-if="aiPending" class="other-pending" role="status">
              AI 设置仍待保存，请切回 AI 分项保存。
            </p>
          </div>
          <div class="footer-actions">
            <button
              class="light-action"
              type="button"
              :disabled="notificationPanel?.loading || notificationPanel?.saving"
              @click="notificationPanel?.load()"
            >
              <i class="ri-refresh-line" aria-hidden="true"></i>重新读取设置
            </button>
            <button
              v-if="!embedded"
              type="button"
              :disabled="notificationPanel?.saving"
              @click="close"
            >
              关闭
            </button>
            <button
              class="primary"
              type="button"
              :disabled="
                !notificationPanel?.loaded ||
                !notificationPanel?.dirty ||
                notificationPanel?.loading ||
                notificationPanel?.saving
              "
              @click="notificationPanel?.save()"
            >
              {{
                notificationPanel?.saving
                  ? '保存中…'
                  : notificationPanel?.error
                    ? '重试保存'
                    : '保存通知设置'
              }}
            </button>
          </div> </template
        ><template v-else
          ><div class="footer-status">
            <AiSettingsSaveStatus
              :text="saving ? '正在保存设置…' : storageError || error || status"
              :dirty="dirty"
              :loading="loading"
              :saving="saving"
              :error="!!(storageError || error || conflict)"
              hint="本次保存模型、深度与两段提示词；通知这一组单独保存"
            />
            <p v-if="notificationDirty" class="other-pending" role="status">
              通知设置仍待保存，请到飞书通知保存。
            </p>
          </div>
          <div class="footer-actions">
            <button class="light-action" type="button" :disabled="loading || saving" @click="load">
              <i class="ri-refresh-line" aria-hidden="true"></i>重新读取设置</button
            ><button v-if="!embedded" type="button" @click="close">关闭</button
            ><button
              class="primary"
              type="button"
              :disabled="!draft || !remote || loading || saving || conflict"
              @click="save"
            >
              {{ saving ? '保存中…' : error && !conflict ? '重试保存' : '保存 AI 设置' }}
            </button>
          </div></template
        ></template
      >
    </AiSettingsLayout>
  </SettingsDialogShell>
</template>
<style scoped>
.other-pending {
  margin: 5px 0 0;
  font-size: 11px;
  color: var(--wao-text-3);
}
</style>
