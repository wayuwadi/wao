<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { RichDocContent } from '@/types'
import { notePreviewImages, notePreviewSource } from '@/utils/notePreviewImages'
import { copyOriginalImage, noteMediaCopyText } from '@/utils/noteMediaClipboard'
import { copyToClipboard } from '@/utils/clipboard'
import { noteCodexPrompt } from '@/utils/noteCodexPrompt'
const props = defineProps<{ content: RichDocContent; initialTextCopied?: boolean; noteId?: string; title?: string }>()
const mode = ref<'chatgpt' | 'codex'>(props.initialTextCopied ? 'chatgpt' : 'codex')
const prompt = computed(() => noteCodexPrompt(props.content, { id: props.noteId, title: props.title }, window.location.origin))
const emit = defineEmits<{ close: [] }>()
const images = computed(() => notePreviewImages(props.content))
const step = ref(props.initialTextCopied && images.value.length ? 1 : 0)
const copied = ref<number[]>(props.initialTextCopied ? [0] : [])
const busy = ref(false)
const error = ref('')
const status = ref(props.initialTextCopied ? '正文已复制，先到 ChatGPT 粘贴，再回来复制原图。' : '')
const dialog = ref<HTMLElement>()
const copyButton = ref<HTMLButtonElement>()
const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
onMounted(() => void nextTick(() => copyButton.value?.focus()))
onBeforeUnmount(() => { if (previousFocus?.isConnected) previousFocus.focus({ preventScroll: true }) })
function close() { if (!busy.value) emit('close') }
function keys(event: KeyboardEvent) {
  event.stopPropagation()
  if (event.key === 'Escape') { event.preventDefault(); close() }
  if (event.key !== 'Tab') return
  const buttons = [...(dialog.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])]
  if (event.shiftKey && document.activeElement === buttons[0]) { event.preventDefault(); buttons[buttons.length - 1]?.focus() }
  else if (!event.shiftKey && document.activeElement === buttons[buttons.length - 1]) { event.preventDefault(); buttons[0]?.focus() }
}
async function copyStep() {
  if (busy.value) return
  const current = step.value
  busy.value = true; error.value = ''; status.value = '正在复制…'
  try {
    if (mode.value === 'codex' && !current) {
      if (!await copyToClipboard(prompt.value)) throw new Error('提示词复制失败，请重试')
      status.value = '已复制完整内容与执行提示词，切到 Codex 粘贴后按回车。'
      copied.value = [...new Set([...copied.value, current])]
      return
    }
    if (!current) {
      if (!await copyToClipboard(noteMediaCopyText(props.content))) throw new Error('正文复制失败，请重试')
    } else await copyOriginalImage(images.value[current - 1]!.src)
    copied.value = [...new Set([...copied.value, current])]
    status.value = current ? `图 ${current} 已复制，请到 ${mode.value === 'codex' ? 'Codex' : 'ChatGPT'} 粘贴。` : '正文已复制，请到 ChatGPT 粘贴。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '复制失败，请重试'; status.value = '' }
  finally { busy.value = false; await nextTick(); copyButton.value?.focus({ preventScroll: true }) }
}
function next() { if (!busy.value && step.value < images.value.length) { step.value++; error.value = ''; status.value = ''; void nextTick(() => copyButton.value?.focus()) } }
</script>

<template>
  <Teleport to="body"><div class="note-media-copy-mask" @keydown="keys">
    <section ref="dialog" class="note-media-copy-dialog" role="dialog" aria-modal="true" aria-label="复制小记">
      <header><div><i class="ri-file-copy-line" aria-hidden="true"></i><h3>复制图文</h3></div><button type="button" aria-label="关闭图文复制" :disabled="busy" @click="close"><i class="ri-close-line"></i></button></header>
      <nav class="copy-target" aria-label="复制用途"><button type="button" :aria-current="mode === 'codex' ? 'true' : undefined" :disabled="busy" @click="mode = 'codex'; error = ''; status = ''">复制给 Codex</button><button type="button" :aria-current="mode === 'chatgpt' ? 'true' : undefined" :disabled="busy" @click="mode = 'chatgpt'; error = ''; status = ''">到 ChatGPT</button></nav>
      <p class="copy-intro">{{ mode === 'codex' ? '先复制完整正文和执行提示词，再逐张粘贴原图。本次复制不会开启公网分享。' : '先粘贴正文，再逐张粘贴原图。每次复制后，切到 ChatGPT 按 Ctrl / ⌘ + V。' }}</p>
      <nav aria-label="图文复制步骤"><button type="button" :aria-current="step === 0 ? 'step' : undefined" :disabled="busy" @click="step = 0; error = ''; status = ''">正文<i v-if="copied.includes(0)" class="ri-check-line" aria-hidden="true"></i></button><button v-for="(image, index) in images" :key="index" type="button" :aria-label="`选择图 ${index + 1}`" :aria-current="step === index + 1 ? 'step' : undefined" :disabled="busy" @click="step = index + 1; error = ''; status = ''">图 {{ index + 1 }}<i v-if="copied.includes(index + 1)" class="ri-check-line" aria-hidden="true"></i></button></nav>
      <div class="copy-content"><template v-if="step"><img :src="notePreviewSource(images[step - 1]!.src)" :alt="images[step - 1]!.alt || `图 ${step}`"><small>按原始尺寸复制为 PNG · 保留透明背景</small></template><pre v-else>{{ mode === 'codex' ? prompt : noteMediaCopyText(content) }}</pre></div>
      <p v-if="error" class="copy-error" role="alert">{{ error }}</p><p v-else class="copy-status" role="status" aria-live="polite">{{ status || (mode === 'codex' ? `完整正文 · ${images.length} 张原图` : `第 ${step + 1} / ${images.length + 1} 步`) }}</p>
      <footer><button ref="copyButton" type="button" class="copy-primary" :disabled="busy" @click="copyStep">{{ busy ? '正在复制…' : step ? `复制图 ${step} 原图` : mode === 'codex' ? '复制执行提示词' : '复制正文' }}</button><button v-if="step < images.length" type="button" :disabled="busy || !copied.includes(step)" @click="next">已粘贴，下一步 <i class="ri-arrow-right-line"></i></button><button v-else type="button" :disabled="busy" @click="close">完成</button></footer>
    </section>
  </div></Teleport>
</template>

<style scoped>
.note-media-copy-mask { position:fixed; inset:0; z-index:4600; background:rgb(24 28 46 / .28); display:grid; place-items:center; padding:16px; }
.note-media-copy-dialog { width:min(760px,100%); max-height:calc(100dvh - 32px); overflow:auto; padding:24px; box-sizing:border-box; border:1px solid var(--wao-line-control); border-radius:16px; background:var(--wao-bg-card); color:var(--wao-text-1); box-shadow:0 20px 70px rgb(20 24 45 / .18); }
header, header > div, footer { display:flex; align-items:center; gap:8px; }
header { justify-content:space-between; } header h3 { margin:0; font-size:17px; } header i { color:var(--wao-primary); }
button { border:1px solid var(--wao-line-control); border-radius:8px; padding:8px 11px; color:var(--wao-text-1); background:var(--wao-bg-card); cursor:pointer; font-size:12px; }
button:disabled { opacity:.5; cursor:default; } button:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
.copy-intro { color:var(--wao-text-2); font-size:13px; line-height:1.7; margin:16px 0; }
nav { display:flex; gap:7px; flex-wrap:wrap; max-height:110px; overflow:auto; padding:3px; }
nav button { display:inline-flex; align-items:center; gap:5px; } nav button[aria-current] { background:var(--wao-bg-hover); border-color:var(--wao-primary); color:var(--wao-primary-text); }
nav i { color:#248455; }
.copy-target { margin-top:16px; }
.copy-content { height:clamp(240px,46dvh,440px); margin:16px 0 12px; display:flex; flex-direction:column; align-items:center; justify-content:center; background:var(--wao-bg-sub); border:1px solid var(--wao-line); border-radius:10px; overflow:hidden; }
.copy-content img { max-width:100%; max-height:180px; object-fit:contain; } .copy-content small { margin:8px; font-size:11px; color:var(--wao-text-faint); }
.copy-content pre { white-space:pre-wrap; overflow-wrap:anywhere; overflow:auto; width:100%; height:100%; box-sizing:border-box; padding:20px; margin:0; font-family:inherit; font-size:13px; line-height:1.8; }
.copy-error, .copy-status { font-size:12px; line-height:1.7; min-height:20px; color:var(--wao-text-2); } .copy-error { color:var(--wao-danger-strong); }
footer { justify-content:space-between; flex-wrap:wrap; margin-top:18px; } .copy-primary { background:var(--wao-primary); color:white; border-color:var(--wao-primary); }
@media (max-height:550px) { .copy-content { height:120px; } .copy-content img { max-height:90px; } }
</style>

<style scoped>
.copy-target{display:inline-flex;background:var(--wao-bg-sub);border:1px solid var(--wao-line);border-radius:10px;padding:4px;gap:4px}.copy-target button{border-color:transparent;background:transparent;min-height:36px;padding:8px 16px}.copy-target button[aria-current]{background:var(--wao-bg-card);border-color:var(--wao-line-control);box-shadow:0 2px 6px rgb(20 24 45 / .05)}footer{border-top:1px solid var(--wao-line);padding-top:16px}footer button{min-height:40px;padding:9px 16px}.copy-content img{max-height:calc(100% - 38px)}@media(max-width:600px){.note-media-copy-dialog{padding:20px 16px}.copy-content{height:38dvh}.copy-content pre{padding:14px}button{min-height:40px}}
</style>
