<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import type { Task } from '@/types'
import { useDataStore } from '@/stores/data'
import { useProjectItemActions } from '@/composables/projectItemActions'
import { taskTreeRows } from '@/utils/taskHierarchy'
import { withProjectAncestors, type ProjectItemRefs } from '@/utils/projectWorkItems'
import { isTaskFinished } from '@/utils/projectCompletion'
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
const sourceMode = ref(false)
const matches = computed(() => {
  const items = props.items.filter(t => props.showDone || !isTaskFinished(t) || data.taskSaveState(actions.sourceId(t.id)) === 'error')
  return props.query ? searchProjectTasks(items, props.query).map(hit => hit.task) : items
})
const rows = computed(() => taskTreeRows(withProjectAncestors(matches.value, props.contextItems), props.query ? [] : props.collapsed))
watch(rows, value => {
  if (!value.some(row => row.task.id === selectedId.value)) selectedId.value = value[0]?.task.id ?? ''
}, { immediate: true })
watch(selectedId, () => { sourceMode.value = false })
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
      <div class="list-caption">任务清单 <span>{{ matches.length }} 项</span></div>
      <p v-if="!rows.length" class="empty">{{ query ? '没有匹配的任务，试试其他关键词' : '当前没有待办任务' }}</p>
      <div v-for="{ task, depth } in rows" :key="task.id" class="checklist-row" :class="{ selected: selectedId === task.id }" :style="{ paddingInlineStart: `${Math.min(depth, 6) * 16 + 8}px` }">
        <button v-if="hasChildren(task.id)" class="tree-toggle" :aria-label="`${collapsed.includes(task.id) ? '展开' : '收起'} ${task.title}`" :aria-expanded="!collapsed.includes(task.id)" @click="$emit('toggle', task.id)"><i :class="collapsed.includes(task.id) ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i></button>
        <span v-else class="tree-spacer"></span>
        <input type="checkbox" :checked="task.status === '已完成'" :disabled="actions.busy(task.id)" :aria-label="`完成 ${task.title}`" @change="actions.toggle(task.id)" />
        <button class="task-select" :aria-pressed="selectedId === task.id" @click="selectedId = task.id">
          <span class="task-title" :title="task.title" :class="{ finished: isTaskFinished(task) }">{{ task.title || '未命名任务' }}</span>
          <span class="task-meta">{{ task.status }}<span v-if="task.due"> · {{ task.due }}</span></span>
        </button>
      </div>
    </WaoCard>
    <WaoCard class="checklist-detail" padding="roomy" aria-label="任务详情卡片">
      <template v-if="selected">
        <div class="detail-caption"><span>{{ source.subId ? '子任务详情' : '任务详情' }}</span><WaoButton variant="ghost" @click="actions.open(selected.id)">更多操作</WaoButton></div>
        <h2>{{ selected.title }}</h2>
        <div class="detail-meta"><span>{{ selected.status }}</span><span>{{ selected.pr }}</span><span v-if="selected.due">截止 {{ selected.due }}</span></div>
        <div class="editor-caption"><span>详情</span><button :aria-pressed="sourceMode" @click="sourceMode = !sourceMode">{{ sourceMode ? '返回排版' : 'Markdown 源码' }}</button></div>
        <textarea v-if="sourceMode" :key="selected.id + ':source'" v-model="description" class="markdown-source" aria-label="Markdown 详情源码" placeholder="用 Markdown 记录任务详情…" />
        <MarkdownEditor v-else :key="selected.id" v-model="description" :polishable="false" :min-height="280" placeholder="记录任务详情，支持 Markdown…" @upload-image="uploadImage" />
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
.tree-toggle,.task-select,.editor-caption button { border:0; background:transparent; font:inherit; color:inherit; cursor:pointer; }
.checklist-layout { display:grid; grid-template-columns:minmax(260px, 0.85fr) minmax(0, 1.3fr); gap:var(--wao-space-3); align-items:start; }
.checklist-tasks,.checklist-detail { min-width:0; max-height:calc(100dvh - 260px); overflow:auto; }
.checklist-detail { position:sticky; top:var(--wao-space-2); min-height:440px; }
.list-caption,.detail-caption,.editor-caption { display:flex; align-items:center; justify-content:space-between; gap:var(--wao-space-2); color:var(--wao-text-weak); font-size:var(--wao-font-caption); }
.list-caption { padding:var(--wao-space-2); }
.checklist-row { display:flex; align-items:center; gap:var(--wao-space-1); border-radius:var(--wao-radius-control); border-bottom:1px solid var(--wao-line); }
.checklist-row:hover { background:var(--wao-bg-sub); }
.checklist-row.selected { background:var(--wao-primary-soft); }
.checklist-row input { flex-shrink:0; accent-color:var(--wao-primary); }
.tree-toggle,.tree-spacer { width:24px; flex-shrink:0; }
.tree-toggle { height:36px; color:var(--wao-text-weak); }
.task-select { flex:1; min-width:0; padding:var(--wao-space-1); text-align:left; font-size:var(--wao-font-body); }
.task-title { display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; overflow-wrap:anywhere; line-height:1.6; }
.finished { text-decoration:line-through; color:var(--wao-text-weak); }
.task-meta { display:block; color:var(--wao-text-weak); font-size:var(--wao-font-caption); margin-top:var(--wao-space-1); }
h2 { font-size:var(--wao-font-title); line-height:1.5; overflow-wrap:anywhere; margin:var(--wao-space-2) 0; }
.detail-meta { display:flex; flex-wrap:wrap; gap:var(--wao-space-2); color:var(--wao-text-weak); font-size:var(--wao-font-caption); padding-bottom:var(--wao-space-3); border-bottom:1px solid var(--wao-line); }
.editor-caption { margin:var(--wao-space-3) 0 var(--wao-space-2); }
.editor-caption button { color:var(--wao-primary-text); }
.markdown-source { box-sizing:border-box; width:100%; min-height:300px; resize:vertical; padding:var(--wao-space-2); color:var(--wao-text); background:var(--wao-bg-sub); border:1px solid var(--wao-line); border-radius:var(--wao-radius-control); font-family:monospace; }
.save-status { margin-top:var(--wao-space-2); display:flex; gap:var(--wao-space-2); flex-wrap:wrap; }
.empty { padding:var(--wao-space-3); color:var(--wao-text-weak); }
button:focus-visible,input:focus-visible,textarea:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
@media(max-width:760px) {
  .checklist-layout { grid-template-columns:minmax(0,1fr); }
  .checklist-tasks { max-height:280px; }
  .checklist-detail { position:static; max-height:none; min-height:300px; }
}
</style>
