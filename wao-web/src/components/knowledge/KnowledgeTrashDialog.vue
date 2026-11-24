<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { apiRestoreTrash, fetchTrash } from '@/api/board'
import type { TrashEntry } from '@/types'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'

const props = defineProps<{ kbName?: string }>()
const emit = defineEmits<{ close: []; restored: [id: string] }>()
const items = ref<TrashEntry[]>([])
const notesOnly = computed(() => props.kbName !== undefined)
const scopeName = computed(() => notesOnly.value ? '小记' : '知识库')
const entryDescription = computed(() => notesOnly.value ? '小记' : '知识库内容')
const entries = computed(() => {
  if (!notesOnly.value) return items.value
  const notesParent = `文档 · ${props.kbName}`
  return items.value.filter(item => item.type === 'doc' && (item.parent === notesParent || item.parent.startsWith(`${notesParent} / `)))
})
const loading = ref(true), busy = ref(''), error = ref(''), status = ref('')
const root = ref<HTMLElement>()
let disposed = false

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await fetchTrash()
    if (!disposed) items.value = result
  } catch (cause) {
    if (!disposed) error.value = cause instanceof Error ? cause.message : '回收站读取失败，请重试'
  } finally {
    if (!disposed) loading.value = false
  }
}

async function restore(item: TrashEntry) {
  if (busy.value) return
  const key = `${item.type}:${item.id}`
  busy.value = key
  error.value = ''
  status.value = ''
  try {
    await apiRestoreTrash(item.type, item.id)
    if (disposed) return
    items.value = items.value.filter(entry => entry.type !== item.type || entry.id !== item.id)
    status.value = `已恢复「${item.name}」`
    emit('restored', item.id)
    await nextTick()
    root.value?.closest<HTMLElement>('[role="dialog"]')?.focus()
  } catch (cause) {
    if (!disposed) error.value = cause instanceof Error ? cause.message : '恢复失败，请重试'
  } finally {
    if (!disposed) busy.value = ''
  }
}

onMounted(load)
onBeforeUnmount(() => { disposed = true })
</script>

<template>
  <SettingsDialogShell :title="`${scopeName}回收站`" width="560px" height="auto" icon="ri-delete-bin-line"
    :close-disabled="!!busy" @close="emit('close')">
    <div ref="root" class="trash-content">
      <p>最近删除的{{ entryDescription }}保留 7 天，恢复后回到原来的位置。</p>
      <p v-if="error" role="alert" class="error">{{ error }} <WaoButton v-if="!busy" variant="ghost" @click="load">重新读取</WaoButton></p>
      <p v-if="status" role="status">{{ status }}</p>
      <p v-if="loading" role="status">正在读取回收站…</p>
      <ul v-else-if="entries.length">
        <li v-for="item in entries" :key="`${item.type}:${item.id}`">
          <div>
            <strong>{{ item.name }}</strong>
            <small v-if="!notesOnly">{{ item.parent }}</small>
            <small>删除于 {{ new Date(item.deletedAt).toLocaleString('zh-CN') }} · {{ new Date(item.expiresAt).toLocaleDateString('zh-CN') }} 到期</small>
          </div>
          <WaoButton variant="ghost" :disabled="!!busy" :loading="busy === `${item.type}:${item.id}`"
            :aria-label="`恢复${notesOnly ? '小记' : ''}：${item.name}`" @click="restore(item)">恢复</WaoButton>
        </li>
      </ul>
      <p v-else-if="!error">没有最近删除的{{ entryDescription }}</p>
    </div>
  </SettingsDialogShell>
</template>

<style scoped>
.trash-content { padding: var(--wao-space-3); }
.trash-content p, small { color: var(--wao-text-2); font-size: var(--wao-font-help); }
.trash-content .error { color: var(--wao-danger-strong); }
ul { padding: 0; list-style: none; }
li { display: flex; align-items: center; justify-content: space-between; gap: var(--wao-space-2); padding-block: var(--wao-space-2); border-top: 1px solid var(--wao-line); }
li > div { min-width: 0; overflow-wrap: anywhere; }
li strong { font-size: var(--wao-font-body); }
li small { display: block; margin-top: var(--wao-space-1); }
</style>
