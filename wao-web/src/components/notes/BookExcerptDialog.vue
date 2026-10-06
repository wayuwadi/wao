<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import { useMobileVisualViewport } from '@/composables/mobileVisualViewport'
import { readNoteDraft, writeNoteDraft, removeNoteDraft } from '@/utils/noteDraftStorage'

const props = defineProps<{ draftKey: string; insertExcerpt: (text: string, source: string, reflection: string) => boolean }>()
const emit = defineEmits<{ 'open-change': [open: boolean] }>()
const open = ref(false)
const { fullscreenStyle } = useMobileVisualViewport()
const excerpt = ref('')
const source = ref('')
const reflection = ref('')
const reading = ref(false)
const notice = ref('')
const storageError = ref('')
const input = ref<HTMLTextAreaElement>()
const dialog = ref<HTMLElement>()
let trigger: HTMLElement | null = null
let request = 0
let revision = 0
try {
  const saved = JSON.parse(readNoteDraft(props.draftKey) || 'null')
  if (saved && typeof saved.excerpt === 'string' && typeof saved.source === 'string' && typeof saved.reflection === 'string') {
    excerpt.value = saved.excerpt; source.value = saved.source; reflection.value = saved.reflection
  }
} catch { storageError.value = '书摘草稿读取失败，请保留当前输入。' }
watch([excerpt, source, reflection], () => {
  try {
    if (excerpt.value || source.value || reflection.value) writeNoteDraft(props.draftKey, JSON.stringify({ excerpt: excerpt.value, source: source.value, reflection: reflection.value }))
    else removeNoteDraft(props.draftKey)
    storageError.value = ''
  } catch { storageError.value = '书摘草稿保护失败，请保留输入并重试。' }
}, { flush: 'sync' })
watch(open, value => emit('open-change', value), { flush: 'sync' })
watch(excerpt, () => { revision++ }, { flush: 'sync' })

async function openDialog() {
  trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  open.value = true
  notice.value = ''
  const ticket = ++request
  const startedAt = revision
  if (!excerpt.value && navigator.clipboard?.readText && window.isSecureContext) {
    reading.value = true
    try {
      // 必须从点击处理函数直接发起；不能等渲染完成后再请求 Safari 剪贴板。
      const text = await navigator.clipboard.readText()
      if (ticket !== request) return
      if (revision === startedAt) excerpt.value = text
      if (!text.trim()) notice.value = '剪贴板没有文字，请在书摘框中手动粘贴。'
    } catch {
      if (ticket === request) notice.value = '未能读取剪贴板，请长按书摘框选择“粘贴”。'
    } finally {
      if (ticket === request) reading.value = false
    }
  } else if (!excerpt.value) notice.value = '请长按书摘框选择“粘贴”。'
  if (ticket === request && open.value) { await nextTick(); input.value?.focus() }
}
async function close() {
  open.value = false
  request++
  reading.value = false
  await nextTick()
  trigger?.focus({ preventScroll: true })
}
function insert() {
  if (!excerpt.value.trim()) return
  if (!props.insertExcerpt(excerpt.value, source.value, reflection.value)) {
    notice.value = '编辑器尚未就绪，请稍后重试；书摘仍在。'
    return
  }
  open.value = false
  request++
  reading.value = false
  excerpt.value = source.value = reflection.value = ''
}
function trapFocus(event: KeyboardEvent) {
  if (event.key !== 'Tab') return
  const controls = [...(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled), textarea, input') ?? [])]
  const first = controls[0], last = controls[controls.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
useOverlayDismiss(() => open.value, close)
onBeforeUnmount(() => { request++ })
defineExpose({ openDialog })
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="excerpt-mask" :style="fullscreenStyle" @click.self="close">
      <section ref="dialog" class="excerpt-dialog" role="dialog" aria-modal="true" aria-label="粘贴为书摘" @keydown="trapFocus">
        <header><h2><i class="ri-double-quotes-l" aria-hidden="true"></i>粘贴为书摘</h2><button type="button" aria-label="关闭书摘导入" @click="close"><i class="ri-close-line" aria-hidden="true"></i></button></header>
        <p class="excerpt-hint">书里的话保留为引用，你的感想接在下方。</p>
        <label>书摘<textarea ref="input" v-model="excerpt" aria-label="书摘原文" placeholder="长按此处粘贴书摘" rows="6" /></label>
        <label>来源（可选）<input v-model="source" aria-label="书摘来源" placeholder="书名、作者或页码" maxlength="1000" /></label>
        <label>我的感想（可选）<textarea v-model="reflection" aria-label="书摘感想" placeholder="此刻想到什么？也可以导入后继续写" rows="3" /></label>
        <p v-if="reading || notice" class="excerpt-notice" role="status" aria-live="polite">{{ reading ? '正在读取剪贴板；也可以手动粘贴…' : notice }}</p>
        <p v-if="storageError" class="excerpt-notice" role="alert">{{ storageError }}</p>
        <footer><span>追加到当前草稿，确认后再保存</span><button class="excerpt-insert" type="button" :disabled="!excerpt.trim()" @click="insert">加入小记</button></footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.excerpt-mask { position:fixed; inset:0; z-index:2400; display:grid; place-items:center; padding:16px; background:rgb(24 28 46 / .3); }
.excerpt-dialog { box-sizing:border-box; width:min(520px,100%); max-height:calc(100dvh - 32px); overflow:auto; padding:20px; border-radius:16px; background:var(--wao-bg-card); color:var(--wao-text-2); box-shadow:var(--wao-shadow-pop); }
header, footer { display:flex; align-items:center; justify-content:space-between; gap:12px; }
h2 { display:flex; align-items:center; gap:8px; margin:0; font-size:17px; color:var(--wao-text-1); }
h2 i { color:var(--wao-primary-text); }
button { min-width:44px; min-height:44px; border:0; border-radius:8px; background:var(--wao-bg-sub); color:var(--wao-text-2); cursor:pointer; }
.excerpt-hint, .excerpt-notice { font-size:12px; line-height:1.6; }
label { display:grid; gap:7px; margin:14px 0; font-size:13px; }
textarea, input { box-sizing:border-box; width:100%; min-width:0; border:1px solid var(--wao-line-control); border-radius:8px; padding:10px; background:var(--wao-bg-card); color:var(--wao-text-1); font:inherit; font-size:16px; line-height:1.6; }
textarea { resize:vertical; }
button:focus-visible, input:focus-visible, textarea:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
footer { padding-top:8px; } footer span { font-size:12px; } .excerpt-insert { flex:none; padding:10px 16px; color:white; background:var(--wao-primary); } button:disabled { opacity:.5; cursor:default; }
@media(max-width:760px) { .excerpt-mask { box-sizing:border-box; top:var(--note-visible-top,0px); bottom:auto; height:var(--note-visible-height,100dvh); padding:max(12px,env(safe-area-inset-top)) 12px max(12px,env(safe-area-inset-bottom)); } .excerpt-dialog { max-height:100%; padding:16px; } }
</style>
