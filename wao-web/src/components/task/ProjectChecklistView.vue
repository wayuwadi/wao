<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import type { Task } from '@/types'
import { useDataStore } from '@/stores/data'
import { useProjectItemActions } from '@/composables/projectItemActions'
import { taskTreeRows } from '@/utils/taskHierarchy'
import { withProjectAncestors, type ProjectItemRefs } from '@/utils/projectWorkItems'
import { searchProjectTasks } from '@/utils/projectTaskSearch'
import WaoCard from '@/components/ui/WaoCard.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import SaveHint from '@/components/ui/SaveHint.vue'

const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
const props = defineProps<{ items: Task[]; contextItems: Task[]; itemRefs: ProjectItemRefs; showDone: boolean; collapsed: string[]; query: string }>()
defineEmits<{ toggle: [id: string] }>()
const data = useDataStore()
const actions = useProjectItemActions(() => props.itemRefs)
const selectedId = ref('')
const matches = computed(() => {
  const items = props.items.filter(t => props.showDone || t.status !== '已完成' || data.taskSaveState(actions.sourceId(t.id)) === 'error')
  return props.query ? searchProjectTasks(items, props.query).map(hit => hit.task) : items
})
const rows = computed(() => taskTreeRows(withProjectAncestors(matches.value, props.contextItems), props.query ? [] : props.collapsed))
watch(rows, value => {
  if (!value.some(row => row.task.id === selectedId.value)) selectedId.value = value[0]?.task.id ?? ''
}, { immediate: true })
const selected = computed(() => props.contextItems.find(t => t.id === selectedId.value))
const source = computed(() => actions.source(selectedId.value))
const description = computed({
  get: () => source.value.subId ? data.subById(source.value.taskId, source.value.subId)?.desc ?? '' : data.taskById(source.value.taskId)?.desc ?? '',
  set: desc => {
    if (!selected.value) return
    const { taskId, subId } = source.value
    if (subId) data.updateSub(taskId, subId, { desc })
    else data.patchTask(taskId, { desc })
  },
})
const saveState = computed(() => data.taskSaveState(source.value.taskId))
const hasChildren = (id: string) => props.contextItems.some(t => t.parentTaskId === id)
async function uploadImage(file: File, done: (url: string | null, name: string) => void) {
  if (file.size > data.MAX_IMAGE_BYTES) { done(null, file.name); return }
  const image = await data.uploadImage(file, `任务 · ${selected.value?.title ?? ''}`)
  done(image?.url ?? null, image?.name ?? file.name)
}
</script>

<template>
  <div class="checklist-layout">
    <WaoCard class="checklist-tasks">

      <p v-if="!rows.length" class="empty">{{ query ? '没有匹配的任务，试试其他关键词' : '当前没有待办任务' }}</p>
      <div v-for="{ task, depth } in rows" :key="task.id" class="checklist-row" :class="{ selected: selectedId === task.id }" :style="{ paddingInlineStart: `${Math.min(depth, 6) * 16 + 8}px` }">
        <button v-if="hasChildren(task.id)" class="tree-toggle" :aria-label="`${collapsed.includes(task.id) ? '展开' : '收起'} ${task.title}`" :aria-expanded="!collapsed.includes(task.id)" @click="$emit('toggle', task.id)"><i :class="collapsed.includes(task.id) ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i></button>
        <span v-else class="tree-spacer"></span>
        <input type="checkbox" :checked="task.status === '已完成'" :disabled="actions.busy(task.id)" :aria-label="`完成 ${task.title}`" @change="actions.toggle(task.id)" />
        <button class="task-select" :aria-pressed="selectedId === task.id" @click="selectedId = task.id">
          <span class="task-title" :title="task.title" :class="{ finished: task.status === '已完成' }">{{ task.title || '未命名任务' }}</span>
          <span v-if="task.due" class="task-meta">{{ task.due }}</span>
        </button>
      </div>
    </WaoCard>
    <WaoCard class="checklist-detail" padding="roomy" aria-label="任务详情卡片">
      <template v-if="selected">
        <div class="detail-caption"><label class="detail-completion"><input type="checkbox" :checked="selected.status === '已完成'" :disabled="actions.busy(selected.id)" @change="actions.toggle(selected.id)" /><span>{{ selected.status === '已完成' ? '已完成' : '未完成' }}</span></label><span v-if="selected.due">{{ selected.due }}</span><WaoButton class="more-actions" variant="ghost" aria-label="更多操作" title="更多操作" @click="actions.open(selected.id)"><i class="ri-more-line"></i></WaoButton></div>
        <h2>{{ selected.title }}</h2>
        <MarkdownEditor :key="selected.id" v-model="description" bare :polishable="false" :min-height="360" placeholder="记录任务详情…" @upload-image="uploadImage" />
        <div class="save-status" aria-live="polite">
          <SaveHint :saving="saveState === 'saving'" :failed="saveState === 'error'" :saved-at="data.taskSavedAt(source.taskId)" :error-message="data.taskSaveError(source.taskId)" />
          <WaoButton v-if="saveState === 'error'" variant="ghost" @click="actions.open(selected.id)">查看保存问题</WaoButton>
        </div>
      </template>
      <p v-else class="empty">选择左侧任务，在这里查看详情</p>
    </WaoCard>
  </div>
</template>

<style scoped>
.tree-toggle,.task-select { border:0; background:transparent; font:inherit; color:inherit; cursor:pointer; }
.checklist-layout { display:grid; grid-template-columns:minmax(240px, 0.85fr) minmax(0, 1.3fr); align-items:start; }
.checklist-tasks,.checklist-detail { min-width:0; max-height:calc(100dvh - 220px); overflow:auto; border:0; border-radius:0; box-shadow:none; }
.checklist-tasks { padding:0 var(--wao-space-2) 0 0; }
.checklist-detail { position:sticky; top:var(--wao-space-1); min-height:calc(100dvh - 240px); border-left:1px solid var(--wao-line); padding:0 0 0 var(--wao-space-3); }
.detail-caption { display:flex; align-items:center; gap:var(--wao-space-2); color:var(--wao-text-weak); font-size:var(--wao-font-caption); border-bottom:1px solid var(--wao-line); min-height:36px; }
.detail-completion { display:flex; align-items:center; gap:var(--wao-space-1); cursor:pointer; }
.more-actions { margin-left:auto; padding:0 var(--wao-space-1); min-height:32px; }
.checklist-row { display:flex; align-items:center; gap:var(--wao-space-1); border-radius:var(--wao-radius-control); border-bottom:1px solid var(--wao-line); min-height:38px; }
.checklist-row:hover,.checklist-row.selected { background:var(--wao-bg-sub); }
input[type=checkbox] { flex-shrink:0; accent-color:var(--wao-primary); width:14px; height:14px; margin:0; }
.tree-toggle,.tree-spacer { width:16px; flex-shrink:0; }
.tree-toggle { height:32px; padding:0; color:var(--wao-text-weak); }
.task-select { display:flex; align-items:center; gap:var(--wao-space-1); flex:1; min-width:0; min-height:38px; padding:0 var(--wao-space-1) 0 0; text-align:left; font-size:var(--wao-font-body); }
.task-title { flex:1; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; line-height:1.5; }
.finished { text-decoration:line-through; color:var(--wao-text-weak); }
.task-meta { flex-shrink:0; color:var(--wao-text-weak); font-size:var(--wao-font-caption); }
h2 { font-size:calc(var(--wao-font-body) + 2px); line-height:1.5; overflow-wrap:anywhere; margin:var(--wao-space-2) 0 var(--wao-space-1); }
.save-status { margin-top:var(--wao-space-1); display:flex; gap:var(--wao-space-2); flex-wrap:wrap; }
.empty { padding:var(--wao-space-2); color:var(--wao-text-weak); }
button:focus-visible,input:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
@media(max-width:760px) {
  .checklist-layout { grid-template-columns:minmax(0,1fr); }
  .checklist-tasks { max-height:260px; padding:0; }
  .checklist-detail { position:static; max-height:none; min-height:300px; border-left:0; border-top:1px solid var(--wao-line); padding:var(--wao-space-2) 0 0; }
  .task-select { min-height:44px; }
}
</style>
