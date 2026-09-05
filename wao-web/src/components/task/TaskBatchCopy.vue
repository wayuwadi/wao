<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { exportTasksMarkdown } from '@/utils/taskExport'
import { copyToClipboard } from '@/utils/clipboard'
import type { Task } from '@/types'

const props = withDefaults(defineProps<{ tasks: Task[]; inline?: boolean }>(), { inline: false })
const data = useDataStore()
const session = useSessionStore()
const preview = ref('')
const copying = ref(false)
const feedback = ref('')
const failed = ref(false)
const pending = computed(() => props.tasks.some((task) => ['saving', 'error'].includes(data.taskSaveState(task.id))))
async function copySnapshot() {
  if (copying.value || !preview.value) return
  copying.value = true
  try {
    const ok = await copyToClipboard(preview.value)
    failed.value = !ok
    feedback.value = ok ? '已复制，可粘贴到本地 Codex' : '复制失败：请重试，或全选下方文本后手动复制'
  } finally { copying.value = false }
}
function generate() {
  if (!props.tasks.length || pending.value || copying.value) return
  preview.value = exportTasksMarkdown(props.tasks, {
    origin: window.location.origin,
    projectName: (id) => data.projectById(id)?.name ?? id,
    memberName: (id) => session.memberById(id).name,
  })
  void copySnapshot()
}
</script>

<template>
  <div class="task-batch-copy" :class="{ inline }">
    <button class="copy-tasks" :disabled="!tasks.length || pending || copying" @click="generate">
      <i class="ri-file-copy-line"></i>{{ copying ? '复制中…' : `合并复制 · ${tasks.length}` }}
    </button>
    <span v-if="pending" role="status">所选任务尚未保存，请在详情中保存或重试后再复制</span>
    <section v-if="preview" class="copy-preview" aria-label="任务合并复制预览">
      <header><b>复制内容预览</b><button aria-label="关闭复制预览" :disabled="copying" @click="preview = ''; feedback = ''">关闭</button></header>
      <p>这是点击复制时的快照，包含完整子任务和内部评论；附件、关联资源仅列引用。再次点击“合并复制”可更新内容。</p>
      <textarea :value="preview" readonly aria-label="合并后的任务全文" spellcheck="false"></textarea>
      <footer>
        <button :disabled="copying" @click="copySnapshot">{{ copying ? '复制中…' : '重新复制' }}</button>
        <span :role="failed ? 'alert' : 'status'">{{ feedback }}</span>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.task-batch-copy { min-width: 0; }
.task-batch-copy.inline { display: contents; }
.task-batch-copy.inline .copy-preview { flex: 1 0 100%; }
button { min-height: 34px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-2); padding: 6px 12px; cursor: pointer; }
button:disabled { opacity: .55; cursor: not-allowed; }
.copy-tasks { color: var(--wao-primary-text); display: inline-flex; gap: 6px; align-items: center; }
.task-batch-copy > span { margin-left: 8px; font-size: 12px; }
.copy-preview { margin-top: 10px; border: 1px solid var(--wao-line); padding: 14px; border-radius: 12px; background: var(--wao-bg-card); }
header, footer { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
header b { flex: 1; }
p, footer span { color: var(--wao-text-weak); font-size: 12px; line-height: 1.6; }
textarea { display: block; width: 100%; box-sizing: border-box; height: 260px; resize: vertical; padding: 10px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-page); color: var(--wao-text); font-size: 13px; }
footer { margin-top: 10px; }
[role='alert'] { color: var(--wao-danger); }
@media (max-width: 760px) { button { min-height: 40px; } textarea { font-size: 16px; } }
</style>
