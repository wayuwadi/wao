<script lang="ts">
import { reactive } from 'vue'

// The same task can be reopened while its request is still in flight.
const drafts = reactive(new Map<string, string>())
const requests = reactive(new Set<string>())
const errors = reactive(new Map<string, string>())
const taskSaveErrors = reactive(new Set<string>())
const submittedDrafts = reactive(new Map<string, string>())
const cleanupWarnings = reactive(new Map<string, string>())
const imageUploads = reactive(new Set<string>())
</script>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useNow } from '@/composables/now'
import { absoluteTime, nowIso, relativeTime } from '@/utils/time'
import type { Comment } from '@/types'
import type { ConfirmAction } from '@/composables/confirmAction'
import ConfirmActionDialog from '@/components/ui/ConfirmActionDialog.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import { apiUploadTaskCommentImage } from '@/api/board'

const props = defineProps<{ taskId: string; subId?: string }>()
const data = useDataStore()
const session = useSessionStore()
const ui = useUiStore()
const now = useNow()
const account = session.account?.accountId ?? ''
const key = `wao-task-comment:${account}:${session.userId}:${props.taskId}:${props.subId ?? ''}`
const member = session.userId
const storageWarning = ref('')
if (!drafts.has(key)) {
  try { drafts.set(key, localStorage.getItem(key) ?? '') }
  catch { drafts.set(key, ''); storageWarning.value = '无法读取本机评论草稿，请保持页面打开。' }
}
const draft = computed({
  get: () => drafts.get(key) ?? '',
  set: (value: string) => {
    drafts.set(key, value)
    errors.delete(key)
    try {
      if (value) localStorage.setItem(key, value)
      else localStorage.removeItem(key)
      storageWarning.value = ''
      cleanupWarnings.delete(key)
    } catch { storageWarning.value = '评论草稿无法保存到本机，请保持页面打开。' }
  },
})
const comments = computed<Comment[]>(() => props.subId
  ? data.subById(props.taskId, props.subId)?.comments ?? []
  : data.taskById(props.taskId)?.comments ?? [])
const busy = computed(() => requests.has(key))
const imageUploading = computed(() => imageUploads.has(key))
const awaitingTaskSave = computed(() => submittedDrafts.has(key))
function commentParts(text: string) {
  const pattern = /!\[[^\]\n]*\]\((\/api\/images\/[A-Za-z0-9_-]+\/raw)\)/g
  const parts: { text?: string; image?: string }[] = []
  let start = 0
  for (const match of text.matchAll(pattern)) {
    const index = match.index ?? 0
    if (index > start) parts.push({ text: text.slice(start, index) })
    parts.push({ image: match[1] })
    start = index + match[0].length
  }
  if (start < text.length) parts.push({ text: text.slice(start) })
  return parts
}
const imageInput = ref<HTMLInputElement | null>(null)
async function uploadImage(file: File) {
  if (imageUploading.value || busy.value || awaitingTaskSave.value || !sourceAvailable()) return
  if (!file.type.startsWith('image/') || !file.size || file.size > 10 * 1024 * 1024) {
    errors.set(key, '请选择不超过 10MB 的图片')
    return
  }
  imageUploads.add(key)
  errors.delete(key)
  try {
    const uploaded = await apiUploadTaskCommentImage(props.taskId, file)
    if (!sourceAvailable()) return
    if (!/^\/api\/images\/[A-Za-z0-9_-]+\/raw$/.test(uploaded.url)) throw new Error('图片地址无效，请重试上传')
    draft.value += `${draft.value ? '\n' : ''}![图片](${uploaded.url})`
  } catch (cause) {
    if (sourceAvailable()) errors.set(key, cause instanceof Error ? cause.message : '图片上传失败，请重试')
  } finally { imageUploads.delete(key) }
}
function chooseImage(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) void uploadImage(file)
}
function pasteImage(event: ClipboardEvent) {
  const file = Array.from(event.clipboardData?.files ?? []).find(item => item.type.startsWith('image/'))
  if (file) { event.preventDefault(); void uploadImage(file) }
}
function clearSentDraft(value: string) {
  // A reopened composer may already contain newer input.
  if (drafts.get(key) !== value) return
  drafts.set(key, '')
  try { if (localStorage.getItem(key) === value) localStorage.removeItem(key); cleanupWarnings.delete(key) }
  catch { cleanupWarnings.set(key, '评论已提交，但无法清理本机草稿；刷新前请确认，避免重复发送。') }
}
function sourceAvailable() {
  return account === (session.account?.accountId ?? '') && member === session.userId && !!data.taskById(props.taskId)
    && (!props.subId || !!data.subById(props.taskId, props.subId))
}
function setComments(next: Comment[], activity: string) {
  if (props.subId) data.updateSub(props.taskId, props.subId, { comments: next })
  else data.patchTask(props.taskId, { comments: next }, activity)
}
async function send() {
  const original = draft.value
  const text = original.trim()
  if (!text || busy.value || imageUploading.value || awaitingTaskSave.value || !sourceAvailable()) return
  if (data.movingTask || data.movingSubtasks) {
    errors.set(key, '任务正在移动，请稍后重试。')
    return
  }
  requests.add(key)
  errors.delete(key)
  try {
    const id = `c${crypto.randomUUID().replace(/-/g, '')}`
    setComments([...comments.value, { id, who: member, time: nowIso(), text }], '添加了评论')
    if (!comments.value.some(comment => comment.id === id)) {
      throw new Error('评论未能添加，请稍后重试。')
    }
    // The accepted comment is already in the protected task draft: retry its save,
    // rather than submitting the same comment a second time.
    if (data.taskDraftErrors[props.taskId]) {
      submittedDrafts.set(key, original)
      taskSaveErrors.add(key)
      errors.set(key, '评论暂存在当前页面中，请保持页面打开并重试保存。')
    } else clearSentDraft(original)
    await data.flushTask(props.taskId)
    if (sourceAvailable() && data.taskSaveState(props.taskId) === 'error') {
      taskSaveErrors.add(key)
      if (!awaitingTaskSave.value) errors.set(key, '评论已保存在任务草稿中，同步失败，请重试保存任务。')
    } else if (sourceAvailable() && data.taskSaveState(props.taskId) === 'saved') {
      if (submittedDrafts.has(key)) clearSentDraft(submittedDrafts.get(key)!)
      submittedDrafts.delete(key); taskSaveErrors.delete(key); errors.delete(key)
    }
  } catch (error) {
    if (awaitingTaskSave.value || !draft.value) {
      taskSaveErrors.add(key)
      errors.set(key, '评论已加入任务草稿，同步失败，请重试保存任务。')
    } else errors.set(key, error instanceof Error ? error.message : '评论发送失败，请重试。')
  } finally { requests.delete(key) }
}
function onKey(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    void send()
  }
}
async function retryTask() {
  if (busy.value || !sourceAvailable()) return
  requests.add(key)
  try {
    await data.flushTask(props.taskId)
    if (!sourceAvailable()) return
    if (data.taskSaveState(props.taskId) === 'error') errors.set(key, '任务仍未保存，请查看保存问题。')
    else if (data.taskSaveState(props.taskId) === 'saved') {
      if (submittedDrafts.has(key)) clearSentDraft(submittedDrafts.get(key)!)
      submittedDrafts.delete(key); taskSaveErrors.delete(key); errors.delete(key)
    }
  } catch { errors.set(key, '任务仍未保存，请查看保存问题。') }
  finally { requests.delete(key) }
}
watch(() => data.taskSaveState(props.taskId), state => {
  if (state !== 'saved' || !taskSaveErrors.has(key) || !sourceAvailable()) return
  if (submittedDrafts.has(key)) clearSentDraft(submittedDrafts.get(key)!)
  submittedDrafts.delete(key); taskSaveErrors.delete(key); errors.delete(key)
}, { immediate: true })

const editing = ref<Comment | null>(null)
const editingText = ref('')
const confirmation = ref<ConfirmAction | null>(null)
function isEditing(comment: Comment) {
  const previous = editing.value
  if (!previous) return false
  return previous.id ? previous.id === comment.id : previous.who === comment.who && previous.time === comment.time && previous.text === comment.text
}
const editingMissing = computed(() => !!editing.value && !comments.value.some(isEditing))
function locate(comment: Comment) {
  if (comment.id) return comments.value.findIndex(item => item.id === comment.id && item.who === comment.who && item.time === comment.time && item.text === comment.text)
  const matches = comments.value.map((item, index) => ({ item, index })).filter(({ item }) =>
    item.who === comment.who && item.time === comment.time && item.text === comment.text)
  return matches.length === 1 ? matches[0]!.index : -1
}
function beginEdit(comment: Comment) { editing.value = { ...comment }; editingText.value = comment.text; errors.delete(key) }
async function saveEdit() {
  const comment = editing.value
  const text = editingText.value.trim()
  if (!comment || !text || busy.value || !sourceAvailable() || comment.who !== member) return
  if (data.movingTask || data.movingSubtasks) { errors.set(key, '任务正在移动，请稍后重试。'); return }
  const index = locate(comment)
  if (index < 0) { errors.set(key, '评论已变化，请重新选择后编辑。'); return }
  requests.add(key)
  errors.delete(key)
  try {
    setComments(comments.value.map((item, i) => i === index ? { ...item, text } : item), '修改了评论')
    if (comments.value[index]?.text !== text) throw new Error('评论未能更新，请重试。')
    if (data.taskDraftErrors[props.taskId]) editing.value = { ...comment, text }
    else editing.value = null
    await data.flushTask(props.taskId)
    if (sourceAvailable() && data.taskSaveState(props.taskId) === 'error') {
      taskSaveErrors.add(key)
      errors.set(key, data.taskDraftErrors[props.taskId] ? '修改仅保存在当前页面，请保持页面打开并重试保存任务。' : '修改已保存在任务草稿中，同步失败，请重试保存任务。')
    } else if (sourceAvailable() && data.taskSaveState(props.taskId) === 'saved') editing.value = null
  } catch {
    if (data.taskDraftErrors[props.taskId]) { taskSaveErrors.add(key); errors.set(key, '修改仅保存在当前页面，请保持页面打开并重试保存任务。') }
    else if (!editing.value) { taskSaveErrors.add(key); errors.set(key, '修改已保存在任务草稿中，同步失败，请重试保存任务。') }
    else errors.set(key, '评论修改失败，请重试。')
  }
  finally { requests.delete(key) }
}
function requestDelete(comment: Comment) {
  if (busy.value || comment.who !== member) return
  const snapshot = { ...comment }
  let removedLocally = false
  confirmation.value = {
    title: '删除评论', description: '确定删除这条评论？此操作不可恢复。',
    run: async () => {
      if (!sourceAvailable()) throw new Error('任务已不存在或账号已切换。')
      if (data.movingTask || data.movingSubtasks) throw new Error('任务正在移动，请稍后重试。')
      if (!removedLocally) {
        const index = locate(snapshot)
        if (index < 0) throw new Error('评论已变化，请关闭后重新选择。')
        setComments(comments.value.filter((_, i) => i !== index), '删除了评论')
        if (locate(snapshot) !== -1) throw new Error('评论未能删除，请重试。')
        removedLocally = true
      }
      await data.flushTask(props.taskId)
      if (data.taskSaveState(props.taskId) === 'error') {
        const message=data.taskDraftErrors[props.taskId]
          ? '删除仅保存在当前页面，请保持页面打开并重试保存。' : '删除已保留在任务草稿中，请重试保存。'
        taskSaveErrors.add(key);errors.set(key,message)
        throw new Error(message)
      }
    },
  }
}
</script>

<template>
  <section class="task-comments" aria-label="任务评论">
    <h3>评论 <span v-if="comments.length">{{ comments.length }}</span></h3>
    <div class="comment-list">
      <article v-for="(comment, index) in comments" :key="comment.id ?? index" class="comment">
        <div class="comment-heading"><strong>{{ session.memberById(comment.who).name }}</strong><time :title="absoluteTime(comment.time)">{{ relativeTime(comment.time, now) }}</time>
          <template v-if="comment.who === member && !editing">
            <button type="button" :disabled="busy" aria-label="修改评论" @click="beginEdit(comment)">编辑</button>
            <button type="button" :disabled="busy" aria-label="删除评论" @click="requestDelete(comment)">删除</button>
          </template>
        </div>
        <template v-if="isEditing(comment)">
          <textarea v-model="editingText" aria-label="修改评论内容" rows="2" :disabled="busy" />
          <div class="comment-edit-actions"><WaoButton variant="ghost" :disabled="busy" @click="editing = null">取消</WaoButton><WaoButton variant="primary" :disabled="!editingText.trim() || busy" @click="saveEdit">保存</WaoButton></div>
        </template>
        <p v-else class="comment-body"><template v-for="(part, partIndex) in commentParts(comment.text)" :key="partIndex"><img v-if="part.image" :src="part.image" alt="评论图片" loading="lazy" /><span v-else>{{ part.text }}</span></template></p>
      </article>
    </div>
    <div v-if="editingMissing" class="missing-comment-edit" role="status">
      <p>评论已变化或删除，编辑草稿仍保留在这里。</p>
      <textarea v-model="editingText" aria-label="修改评论内容" rows="2" />
      <WaoButton variant="ghost" @click="editing = null">关闭编辑</WaoButton>
    </div>
    <div class="comment-compose">
      <textarea v-model="draft" rows="1" aria-label="评论内容" placeholder="写下评论…" :disabled="busy || imageUploading || awaitingTaskSave" @keydown="onKey" @paste="pasteImage" />
      <input ref="imageInput" type="file" accept="image/*" hidden @change="chooseImage" />
      <button type="button" class="comment-image-button" aria-label="添加评论图片" :disabled="busy || imageUploading || awaitingTaskSave" @click="imageInput?.click()"><i :class="imageUploading ? 'ri-loader-4-line spin' : 'ri-image-add-line'" aria-hidden="true"></i></button>
      <WaoButton variant="primary" :loading="busy" :disabled="!draft.trim() || imageUploading || awaitingTaskSave" @click="send">{{ busy ? '处理中' : '发送' }}</WaoButton>
    </div>
    <div v-if="draft && commentParts(draft).some(part => part.image)" class="comment-draft-preview" aria-label="评论图片预览"><img v-for="(part, index) in commentParts(draft).filter(part => part.image)" :key="index" :src="part.image" alt="待发送图片" /></div>
    <p v-if="draft" class="comment-help">Enter 发送 · Shift + Enter 换行</p>
    <p v-if="errors.get(key) || storageWarning || cleanupWarnings.get(key)" class="comment-error" role="alert">{{ data.taskConflicts[taskId] ? (data.taskDraftErrors[taskId] ? '任务已在别处更新，修改仅保存在当前页面，请保持页面打开并查看保存问题。' : '任务已在别处更新，请查看保存问题；本机修改仍保留。') : errors.get(key) || storageWarning || cleanupWarnings.get(key) }}</p>
    <WaoButton v-if="taskSaveErrors.has(key)" variant="ghost" :disabled="busy" @click="data.taskConflicts[taskId] ? ui.openTask(taskId, subId) : retryTask()">{{ data.taskConflicts[taskId] ? '查看保存问题' : '重试保存任务' }}</WaoButton>
    <ConfirmActionDialog v-if="confirmation" :action="confirmation" @close="confirmation = null" />
  </section>
</template>

<style scoped>
.task-comments { margin-top: var(--wao-space-3); padding-top: var(--wao-space-2); border-top: 1px solid var(--wao-line); }
h3 { margin: 0 0 var(--wao-space-1); font-size: var(--wao-font-section); font-weight: var(--wao-weight-heading); }
h3 span, time, .comment-help { color: var(--wao-text-3); font-size: var(--wao-font-help); font-weight: 400; }
.comment-list { display: flex; flex-direction: column; gap: var(--wao-space-2); }
.comment-heading { display: flex; align-items: center; flex-wrap: wrap; gap: var(--wao-space-1); }
.comment-heading button { border: 0; background: transparent; color: var(--wao-primary-text); font: inherit; cursor: pointer; }
.comment p { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.7; }
.comment-body img, .comment-draft-preview img { display: block; max-width: min(100%, 360px); max-height: 360px; object-fit: contain; }
.comment-draft-preview { display: flex; flex-wrap: wrap; gap: var(--wao-space-1); margin-top: var(--wao-space-1); }
.comment-image-button { flex: none; min-width: 42px; min-height: 42px; border: 0; border-radius: var(--wao-radius-control); background: transparent; color: var(--wao-primary-text); cursor: pointer; font-size: var(--wao-font-section); }
.comment-image-button:disabled { opacity: .5; cursor: not-allowed; }
.comment-compose { position: relative; display: flex; align-items: flex-end; gap: var(--wao-space-1); margin-top: var(--wao-space-1); }
.comment-compose > .wao-button { flex-shrink: 0; white-space: nowrap; }
textarea { box-sizing: border-box; width: 100%; min-width: 0; min-height: 42px; padding: var(--wao-space-1); resize: vertical; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text); font: 400 var(--wao-font-body)/1.7 var(--wao-font); }
textarea:focus-visible, button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.comment-compose textarea:focus, .comment-compose textarea:not(:placeholder-shown) { min-height: 72px; }
.comment-help, .comment-error { margin: var(--wao-space-1) 0 0; }
.comment-error { color: var(--wao-danger-strong); font-size: var(--wao-font-help); }
.comment-edit-actions { display: flex; justify-content: flex-end; gap: var(--wao-space-1); }
.missing-comment-edit p { color: var(--wao-text-3); }
@media (max-width: 760px) { textarea { font-size: var(--wao-font-card-title); } }
@media (max-width: 390px) { .comment-compose { flex-wrap: wrap; } .comment-compose textarea { flex-basis: 100%; } }
@media (pointer: coarse) { .comment-heading button { min-height: 44px; } }
</style>
