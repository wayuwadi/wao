<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { fileDisplayName } from '@/utils/files'
import type { FileItem } from '@/types'

const props = defineProps<{ file: FileItem }>()
const data = useDataStore()
const editing = ref(false)
const draft = ref('')
const saving = ref(false)
const state = ref<'idle' | 'saved' | 'error'>('idle')
const error = ref('')
const input = ref<HTMLInputElement | null>(null)

function begin() {
  if (!props.file.id || saving.value) return
  draft.value = fileDisplayName(props.file)
  state.value = 'idle'
  error.value = ''
  editing.value = true
  void nextTick(() => {
    input.value?.focus()
    input.value?.select()
  })
}

function cancel() {
  if (saving.value) return
  editing.value = false
  state.value = 'idle'
  error.value = ''
}

async function save() {
  const displayName = draft.value.trim()
  if (!displayName) {
    state.value = 'error'
    error.value = '展示名称不能为空'
    return
  }
  if (!props.file.id || saving.value) return
  saving.value = true
  state.value = 'idle'
  error.value = ''
  try {
    await data.renameFileDisplayName(props.file.id, displayName)
    editing.value = false
    state.value = 'saved'
  } catch (cause) {
    state.value = 'error'
    error.value = cause instanceof Error && cause.message ? cause.message : '保存失败，请重试'
  } finally {
    saving.value = false
  }
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
          :disabled="saving"
          @keydown.enter.prevent="save"
          @keydown.esc.prevent="cancel"
        />
        <button type="button" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : state === 'error' ? '重试' : '保存' }}
        </button>
        <button type="button" :disabled="saving" @click="cancel">取消</button>
      </div>
      <small v-if="error" class="rename-state error" role="alert">{{ error }}</small>
    </template>
    <template v-else>
      <div class="name-row">
        <b :title="fileDisplayName(file)">{{ fileDisplayName(file) }}</b>
        <button v-if="file.id" type="button" title="修改 WAO 展示名称" @click="begin">
          <i class="ri-edit-line"></i><span>改名</span>
        </button>
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
  font-size: 13px;
  font-weight: 600;
}
.name-row button,
.rename-row button {
  flex: none;
  border: 0;
  border-radius: 6px;
  padding: 3px 6px;
  background: transparent;
  color: var(--wao-text-weak);
  cursor: pointer;
  font-size: 11px;
}
.name-row button:hover,
.rename-row button:hover {
  background: var(--wao-bg-hover);
  color: var(--wao-primary);
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
  border-radius: 7px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  outline: none;
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
  color: var(--wao-text-weak);
  font-size: 11px;
}
.rename-state.saved {
  flex: none;
  color: var(--wao-success, #2c8a5b);
}
.rename-state.error {
  color: var(--wao-danger);
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
  }
}
</style>
