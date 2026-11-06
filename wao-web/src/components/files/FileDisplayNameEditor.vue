<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import WaoButton from '@/components/ui/WaoButton.vue'
import { fileDisplayName } from '@/utils/files'
import {
  clearResourceNameDraft,
  readResourceNameDraft,
  resourceNameDraftKey,
  saveResourceNameDraft,
} from '@/utils/resourceNameDraft'
import type { FileItem } from '@/types'

const props = defineProps<{ file: FileItem }>()
const data = useDataStore()
const session = useSessionStore()
const editing = ref(false)
const draft = ref('')
const baseName = ref('')
const expectedName = ref<string | null>(null)
const serverConflict = ref(false)
const readingConflict = ref(false)
const saving = ref(false)
const state = ref<'idle' | 'saved' | 'error'>('idle')
const error = ref('')
const input = ref<HTMLInputElement | null>(null)
const errorId = `file-name-error-${useId()}`
const draftKey = computed(() =>
  resourceNameDraftKey(session.account?.accountId, session.userId, props.file.id),
)
const target = computed(() => `${session.account?.accountId}:${session.userId}:${props.file.id}`)
const remoteChanged = computed(
  () =>
    editing.value &&
    (fileDisplayName(props.file) !== baseName.value ||
      (props.file.displayName ?? null) !== expectedName.value) &&
    fileDisplayName(props.file) !== draft.value.trim(),
)
const hasDraft = computed(() => editing.value && draft.value !== baseName.value)
let generation = 0
let restoring = false

watch(
  target,
  () => {
    generation++
    restoring = true
    const saved = readResourceNameDraft(draftKey.value)
    baseName.value = saved?.base ?? fileDisplayName(props.file)
    expectedName.value = saved ? saved.expectedName : (props.file.displayName ?? null)
    draft.value = saved?.value ?? ''
    editing.value = !!saved
    saving.value = false
    error.value = ''
    state.value = 'idle'
    serverConflict.value = false
    readingConflict.value = false
    restoring = false
  },
  { immediate: true, flush: 'sync' },
)

watch(
  [draft, baseName, expectedName],
  () => {
    if (restoring || !editing.value) return
    if (draft.value === baseName.value) clearResourceNameDraft(draftKey.value)
    else saveResourceNameDraft(draftKey.value, draft.value, baseName.value, expectedName.value)
  },
  { flush: 'sync' },
)

onBeforeUnmount(() => {
  generation++
})

function begin() {
  if (!props.file.id || saving.value) return
  generation++
  baseName.value = fileDisplayName(props.file)
  expectedName.value = props.file.displayName ?? null
  draft.value = fileDisplayName(props.file)
  state.value = 'idle'
  error.value = ''
  serverConflict.value = false
  readingConflict.value = false
  editing.value = true
  void nextTick(() => {
    input.value?.focus()
    input.value?.select()
  })
}

function cancel() {
  if (saving.value) return
  generation++
  clearResourceNameDraft(draftKey.value)
  editing.value = false
  state.value = 'idle'
  error.value = ''
  serverConflict.value = false
  readingConflict.value = false
}

async function save() {
  const displayName = draft.value.trim()
  if (!displayName) {
    state.value = 'error'
    error.value = '展示名称不能为空'
    return
  }
  if (!props.file.id || saving.value || remoteChanged.value || serverConflict.value) return
  const activeGeneration = generation
  const key = draftKey.value
  const revision = readResourceNameDraft(key)?.revision
  saving.value = true
  state.value = 'idle'
  error.value = ''
  try {
    await data.renameFileDisplayName(props.file.id, displayName, expectedName.value)
    if (revision) clearResourceNameDraft(key, revision)
    if (activeGeneration !== generation) return
    editing.value = false
    state.value = 'saved'
  } catch (cause) {
    if (activeGeneration !== generation) return
    state.value = 'error'
    error.value = cause instanceof Error && cause.message ? cause.message : '保存失败，请重试'
    if ((cause as { status?: number })?.status === 409) {
      serverConflict.value = true
      await refreshConflict()
    }
  } finally {
    if (activeGeneration === generation) saving.value = false
  }
}

function useLatest() {
  if (saving.value) return
  clearResourceNameDraft(draftKey.value)
  baseName.value = fileDisplayName(props.file)
  expectedName.value = props.file.displayName ?? null
  draft.value = baseName.value
  state.value = 'idle'
  error.value = ''
  input.value?.focus()
}

function overwrite() {
  if (saving.value || serverConflict.value) return
  baseName.value = fileDisplayName(props.file)
  expectedName.value = props.file.displayName ?? null
  void save()
}

async function refreshConflict() {
  if (readingConflict.value) return
  const activeGeneration = generation
  readingConflict.value = true
  const success = await data.refreshFiles().catch(() => false)
  await nextTick()
  if (activeGeneration !== generation) return
  readingConflict.value = false
  if (success) {
    serverConflict.value = false
    error.value = ''
  } else error.value = '最新名称读取失败，草稿已保留，请先重新读取。'
}
</script>

<template>
  <div class="file-display-name">
    <template v-if="editing">
      <div class="rename-row">
        <input
          ref="input"
          v-model="draft"
          maxlength="255"
          aria-label="文件展示名称"
          :aria-invalid="!!error || remoteChanged || serverConflict"
          :aria-describedby="error || remoteChanged || serverConflict ? errorId : undefined"
          :disabled="saving"
          @keydown.enter.prevent="save"
          @keydown.esc.prevent="cancel"
        />
        <WaoButton
          variant="ghost"
          :disabled="saving || remoteChanged || serverConflict"
          @click="save"
        >
          {{ saving ? '保存中…' : state === 'error' ? '重试' : '保存' }}
        </WaoButton>
        <WaoButton variant="ghost" :disabled="saving" @click="cancel">取消</WaoButton>
      </div>
      <div v-if="serverConflict" :id="errorId" class="rename-conflict" role="alert">
        <span>{{
          readingConflict
            ? '名称已在另一处修改，正在读取最新名称…'
            : error || '名称已在另一处修改，请先读取最新名称。'
        }}</span>
        <WaoButton variant="ghost" :disabled="readingConflict" @click="refreshConflict"
          >重新读取最新名称</WaoButton
        >
      </div>
      <div v-else-if="remoteChanged" :id="errorId" class="rename-conflict" role="alert">
        <span>名称已更新为「{{ fileDisplayName(file) }}」，你的草稿已保留。</span>
        <div>
          <WaoButton variant="ghost" :disabled="saving" @click="useLatest">使用最新名称</WaoButton
          ><WaoButton variant="ghost" :disabled="saving" @click="overwrite">用草稿覆盖</WaoButton>
        </div>
      </div>
      <small v-else-if="error" :id="errorId" class="rename-state error" role="alert">{{
        error
      }}</small>
      <small v-else-if="hasDraft && !saving" class="rename-state">{{
        draftKey ? '草稿保留在当前浏览器页签' : '名称尚未保存'
      }}</small>
    </template>
    <template v-else>
      <div class="name-row">
        <b :title="fileDisplayName(file)">{{ fileDisplayName(file) }}</b>
        <WaoButton
          v-if="file.id"
          variant="ghost"
          title="修改 WAO 展示名称"
          :aria-label="`修改 ${fileDisplayName(file)} 的展示名称`"
          @click="begin"
        >
          <i aria-hidden="true" class="ri-edit-line"></i><span>改名</span>
        </WaoButton>
        <small v-if="state === 'saved'" class="rename-state saved" role="status">已保存</small>
      </div>
      <small v-if="file.displayName" class="original-name" :title="file.name"
        >原文件：{{ file.name }}</small
      >
    </template>
  </div>
</template>

<style scoped>
.file-display-name {
  min-width: 0;
  display: grid;
  gap: 3px;
}
.name-row,
.rename-row {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}
.name-row b {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--wao-text);
  font-size: var(--wao-font-body);
  font-weight: 600;
}
.name-row :deep(button),
.rename-row :deep(button) {
  flex: none;
  border: 0;
  border-radius: var(--wao-radius-control);
  padding: 3px 6px;
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
  font-size: var(--wao-font-body);
  min-height: 32px;
}
.name-row button:hover,
.rename-row button:hover {
  background: var(--wao-bg-hover);
  color: var(--wao-primary-text);
}
.name-row button span {
  margin-left: 3px;
}
.rename-row input {
  width: min(360px, 100%);
  min-width: 100px;
  height: 30px;
  padding: 0 8px;
  border: 1px solid var(--wao-primary);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  outline: none;
  font: inherit;
  box-sizing: border-box;
}
.rename-row button:disabled {
  cursor: progress;
  opacity: 0.65;
}
.original-name,
.rename-state {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
}
.rename-state.saved {
  flex: none;
  color: var(--wao-success-strong);
}
.rename-state.error {
  color: var(--wao-danger-strong);
  white-space: normal;
  overflow-wrap: anywhere;
}
.rename-conflict {
  color: var(--wao-warning-strong);
  font-size: var(--wao-font-help);
  overflow-wrap: anywhere;
}
.rename-conflict > div {
  display: flex;
  flex-wrap: wrap;
}
.rename-row input:focus-visible {
  outline: 2px solid var(--wao-primary-soft);
}
@media (max-width: 640px) {
  .name-row button span {
    display: none;
  }
  .rename-row {
    flex-wrap: wrap;
  }
  .rename-row input {
    flex-basis: 100%;
    font-size: var(--wao-font-reading);
    height: 44px;
  }
}
@media (max-width: 640px), (hover: none), (pointer: coarse) {
  .name-row :deep(button),
  .rename-row :deep(button) {
    min-height: 44px;
    min-width: 44px;
  }
}
</style>
