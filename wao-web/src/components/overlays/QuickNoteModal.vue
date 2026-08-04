<script setup lang="ts">
/**
 * 新建小记 —— 全局「新建」里最轻的一个入口。
 *
 * 小记就是「先记下来再说」：不用选知识库、不用起标题，打开就是一个空白输入框，
 * 敲完 Ctrl/⌘+Enter 就存进小记知识库。标题留空时由正文首行推导。
 */
import { defineAsyncComponent, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { toast } from '@/composables/toast'
// 异步加载，理由同 TaskDrawer：ProseMirror 不进首屏包。
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))

const data = useDataStore()
const ui = useUiStore()
const router = useRouter()

const text = ref('')
const saving = ref(false)
const editorWrap = ref<HTMLElement | null>(null)

onMounted(() => nextTick(() => editorWrap.value?.querySelector<HTMLElement>('.tiptap')?.focus()))

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
  if (!text.value.trim() || saving.value) return
  saving.value = true
  const id = await data.addNote(text.value)
  saving.value = false
  if (!id) return
  ui.closeModal()
  toast('已存进「小记」', 'ri-sticky-note-line')
  if (open) router.push({ path: `/kb/${data.notesKb().id}`, query: { doc: id } })
}
</script>

<template>
  <div class="mask" @click="ui.closeModal()"></div>
  <div class="modal">
    <div class="head">
      <i class="ri-sticky-note-line head-icon"></i>
      <div class="title">新建小记</div>
      <span class="hint">存进「小记」知识库，稍后可以再整理到别处</span>
      <div class="spacer"></div>
      <button class="close" title="关闭（Esc）" @click="ui.closeModal()"><i class="ri-close-line"></i></button>
    </div>

    <div ref="editorWrap" class="body" @keydown.ctrl.enter.prevent="save()" @keydown.meta.enter.prevent="save()">
      <MarkdownEditor
        v-model="text"
        placeholder="随手记点什么…敲 ## 变标题、- 变列表，可直接粘贴截图"
        :min-height="180"
        @upload-image="onUploadImage"
      />
    </div>

    <div class="foot">
      <span class="foot-tip">⌘/Ctrl + Enter 保存</span>
      <div class="spacer"></div>
      <button class="btn-ghost" :disabled="!text.trim()" @click="save(true)">保存并打开</button>
      <button class="btn-primary" :disabled="!text.trim() || saving" @click="save()">保存</button>
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
  font-size: 11.5px;
  color: var(--wao-text-faint);
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
