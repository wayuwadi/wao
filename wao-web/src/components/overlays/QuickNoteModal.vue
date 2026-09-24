<script setup lang="ts">
/**
 * 新建小记 —— 全局「新建」里最轻的一个入口。
 *
 * 小记就是「先记下来再说」：不用选知识库、不用起标题，打开就是一个空白输入框，
 * 敲完 Ctrl/⌘+Enter 就存进小记知识库。标题留空时由正文首行推导。
 */
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { toast } from '@/composables/toast'
import type { RichDocContent } from '@/types'
import { startAutoNoteEnrichment } from '@/utils/autoNoteEnrichment'
// 异步加载，理由同 TaskDrawer：ProseMirror 不进首屏包。
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))

const data = useDataStore()
const session = useSessionStore()
const ui = useUiStore()
const router = useRouter()

type QuickNoteDraft = { id: string; text: string; content?: RichDocContent }

/** UUID 去掉连字符后正好 32 位，符合 doc.id 列长度，也足以避免跨设备草稿碰撞。 */
function newNoteId(): string {
  return crypto.randomUUID().replace(/-/g, '')
}

const draftKey = `wao-quick-note-draft-v1:${session.currentUser.id || 'anonymous'}`
function loadDraft(): QuickNoteDraft {
  try {
    const saved = JSON.parse(localStorage.getItem(draftKey) || '{}') as Partial<QuickNoteDraft>
    if (typeof saved.id === 'string' && /^[A-Za-z0-9_-]{1,32}$/.test(saved.id)
        && typeof saved.text === 'string') {
      return { id: saved.id, text: saved.text, content: saved.content }
    }
  } catch {
    /* 损坏的本地草稿直接忽略。 */
  }
  return { id: newNoteId(), text: '' }
}

const draft = loadDraft()
const noteId = ref(draft.id)
const text = ref(draft.text)
const content = ref<RichDocContent | null>(draft.content ?? null)
const draftState = ref<'idle' | 'saving' | 'saved'>(draft.text ? 'saved' : 'idle')
const saveState = ref<'idle' | 'submitting' | 'error'>('idle')
const saveError = ref('')
const editorWrap = ref<HTMLElement | null>(null)
let draftTimer: ReturnType<typeof setTimeout> | null = null

const archiveName = computed(() => session.currentUser.id && session.currentUser.name
  ? `小记 / ${session.currentUser.name}`
  : '小记')

watch([text, content], ([value]) => {
  saveState.value = 'idle'
  saveError.value = ''
  if (!value) {
    localStorage.removeItem(draftKey)
    draftState.value = 'idle'
    return
  }
  draftState.value = 'saving'
  localStorage.setItem(draftKey, JSON.stringify({ id: noteId.value, text: value, content: content.value ?? undefined } satisfies QuickNoteDraft))
  if (draftTimer) clearTimeout(draftTimer)
  draftTimer = setTimeout(() => (draftState.value = 'saved'), 180)
}, { flush: 'sync' })

function onContentChange(value: RichDocContent) {
  content.value = value
}

/** AppLayout 也监听 Escape；请求飞行时在捕获阶段拦住，避免父层先把弹窗卸载。 */
function guardSubmittingEscape(event: KeyboardEvent) {
  if (event.key === 'Escape' && saveState.value === 'submitting') {
    event.preventDefault()
    event.stopImmediatePropagation()
  }
}

onMounted(() => {
  nextTick(() => editorWrap.value?.querySelector<HTMLElement>('.tiptap')?.focus())
  window.addEventListener('keydown', guardSubmittingEscape, true)
})
onBeforeUnmount(() => {
  if (draftTimer) clearTimeout(draftTimer)
  window.removeEventListener('keydown', guardSubmittingEscape, true)
})

/** 粘贴 / 拖入的图片存进图床，再把地址回插到光标处。 */
async function onUploadImage(file: File, done: (url: string | null, name: string) => void) {
  if (file.size > data.MAX_IMAGE_BYTES) {
    toast('图片超过 10MB，已跳过', 'ri-error-warning-line')
    done(null, '')
    return
  }
  const ref = await data.uploadImage(file, '小记')
  if (!ref) toast('图片上传失败，请重试', 'ri-error-warning-line')
  done(ref?.url ?? null, ref?.name ?? file.name)
}

async function save(open = false) {
  if (!text.value.trim() || saveState.value === 'submitting') return
  saveState.value = 'submitting'
  saveError.value = ''
  try {
    const id = content.value
      ? await data.addNote(text.value, '', noteId.value, content.value)
      : await data.addNote(text.value, '', noteId.value)
    if (!id) throw new Error('小记正文为空')
    const doc = data.docById(id)
    localStorage.removeItem(draftKey)
    if (data.apiLive && doc?.rev) startAutoNoteEnrichment(session.currentUser.id, id, doc.rev)
    ui.closeModal()
    toast(`已存进「${archiveName.value}」`, 'ri-sticky-note-line')
    if (open) router.push({ path: `/kb/${doc?.kbId ?? data.notesKb().id}`, query: { doc: id } })
  } catch (cause: unknown) {
    saveState.value = 'error'
    saveError.value = cause instanceof Error && cause.message ? cause.message : '服务暂不可用'
    toast('小记没有保存到服务器，请检查网络后重试', 'ri-error-warning-line')
  }
}

/** 请求飞行中不关闭弹窗；失败或普通编辑时可放心关闭，草稿已经同步保存在浏览器。 */
function requestClose() {
  if (saveState.value !== 'submitting') ui.closeModal()
}
</script>

<template>
  <div class="mask" @click="requestClose"></div>
  <div class="modal">
    <div class="head">
      <i class="ri-sticky-note-line head-icon"></i>
      <div class="title">新建小记</div>
      <span class="hint">将归档到「{{ archiveName }}」，稍后可以再整理</span>
      <div class="spacer"></div>
      <button class="close" title="关闭（Esc）" :disabled="saveState === 'submitting'" @click="requestClose"><i class="ri-close-line"></i></button>
    </div>

    <div ref="editorWrap" class="body" @keydown.ctrl.enter.prevent="save()" @keydown.meta.enter.prevent="save()">
      <MarkdownEditor
        v-model="text"
        :document="content?.document"
        advanced
        compact
        placeholder="随手记点什么…敲 ## 变标题、- 变列表，可直接粘贴截图"
        :min-height="180"
        :readonly="saveState === 'submitting'"
        @content-change="onContentChange"
        @upload-image="onUploadImage"
      />
    </div>

    <div class="foot">
      <span v-if="saveState === 'error'" class="save-error" role="alert" :title="saveError"><i class="ri-error-warning-line"></i>未保存到服务器，草稿仍在</span>
      <span v-else-if="draftState !== 'idle'" class="draft-state"><i :class="draftState === 'saving' ? 'ri-loader-4-line spin' : 'ri-checkbox-circle-line'"></i>{{ draftState === 'saving' ? '正在保存草稿' : '草稿已保存' }}</span>
      <span v-else class="foot-tip">⌘/Ctrl + Enter 保存</span>
      <div class="spacer"></div>
      <button class="btn-ghost" :disabled="!text.trim() || saveState === 'submitting'" @click="save(true)">保存并打开</button>
      <button class="btn-primary" :disabled="!text.trim() || saveState === 'submitting'" @click="save()">
        <i v-if="saveState === 'submitting'" class="ri-loader-4-line spin"></i>
        {{ saveState === 'submitting' ? '正在保存' : saveState === 'error' ? '重试保存' : '保存' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(23, 25, 35, 0.32);
  z-index: 100;
  animation: waoFade 0.15s ease;
}
.modal {
  position: fixed;
  top: 14vh;
  left: 0;
  right: 0;
  margin-inline: auto;
  width: min(560px, calc(100vw - 40px));
  background: var(--wao-bg-card);
  border-radius: 14px;
  z-index: 101;
  box-shadow: var(--wao-shadow-modal);
  animation: waoPop 0.16s ease;
  padding: 18px 20px 14px;
}
.spacer {
  flex: 1;
}
.head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.head-icon {
  font-size: 17px;
  color: #7a5cc6;
}
.title {
  font-size: 15.5px;
  font-weight: 700;
}
.hint {
  min-width: 0;
  overflow: hidden;
  font-size: 11.5px;
  color: var(--wao-text-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.close {
  width: 28px;
  height: 28px;
  border: none;
  background: none;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
}
.close:hover {
  background: var(--wao-bg-hover-2);
}
.close:disabled {
  cursor: wait;
  opacity: 0.45;
}
.close i {
  font-size: 16px;
}
.body {
  margin-top: 12px;
  border: 1px solid var(--wao-line-control);
  border-radius: 10px;
  padding: 4px 6px;
}
.body:focus-within {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.1);
}
.foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}
.foot-tip {
  font-size: 11.5px;
  color: var(--wao-text-faint);
}
.draft-state,
.save-error {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
}
.draft-state {
  color: var(--wao-text-faint);
}
.draft-state i:not(.spin) {
  color: var(--wao-success-strong);
}
.save-error {
  color: var(--wao-danger-strong);
}
.spin {
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.btn-ghost {
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--wao-text-2);
  cursor: pointer;
}
.btn-ghost:hover:not(:disabled) {
  background: var(--wao-bg-hover);
}
.btn-primary {
  height: 32px;
  padding: 0 14px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
}
.btn-primary:hover:not(:disabled) {
  background: var(--wao-primary-hover);
}
.btn-ghost:disabled,
.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
