<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue';
import { App } from '@capacitor/app';
import type { PluginListenerHandle } from '@capacitor/core';
import { useWorkspace } from '../composables/workspace';
import { newCapture, type CaptureDraft } from '../domain/noteCapture';
import { iosCapture, isIosCapture, type SharedCapture } from '../native/noteCapture';
import NoteCaptureEditor from './NoteCaptureEditor.vue';

const w = useWorkspace(), editor = ref<CaptureDraft | null>(null), drafts = ref<CaptureDraft[]>([]);
const pending = ref<SharedCapture[]>([]), clipboard = ref<number | null>(null), error = ref(''), busy = ref(false);
const recordings = ref<string[]>([]);
let active: PluginListenerHandle | undefined, disposed = false, generation = 0;
const identity = () => `${w.session.value?.accountId}:${w.session.value?.member.id}`;
function readDrafts() {
  drafts.value = []; const prefix = `wao-capture:v1:${identity()}:`;
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (!key?.startsWith(prefix)) continue;
    try {
      const value = JSON.parse(localStorage.getItem(key)!);
      if (typeof value.id !== 'string' || typeof value.thoughts !== 'string' || typeof value.excerpt !== 'string' || key !== prefix + value.id) throw new Error();
      if (!value.importedId && (value.title || value.excerpt || value.thoughts || value.sourceUrl || value.audioId)) drafts.value.push(value);
    } catch { error.value = '有一份本机草稿无法读取，原数据已保留。'; }
  }
}
async function refresh() {
  const session = w.session.value, ticket = ++generation;
  if (!session) return;
  error.value = ''; readDrafts();
  if (!isIosCapture()) return;
  try {
    // 验证主应用登录身份后才共享 Cookie；失败仍允许看本机草稿。
    try { await iosCapture.syncSession({ accountId: session.accountId, memberId: session.member.id }); }
    catch (e) { if (ticket === generation) error.value = e instanceof Error ? e.message : '分享登录尚未就绪'; }
    const [imports, status, audio] = await Promise.all([
      iosCapture.pending({ accountId: session.accountId, memberId: session.member.id }),
      iosCapture.clipboardStatus(), iosCapture.recordings({ owner: `${session.accountId}:${session.member.id}` }),
    ]);
    if (disposed || ticket !== generation || identity() !== `${session.accountId}:${session.member.id}`) return;
    pending.value = imports.items;
    const dismissed = sessionStorage.getItem(`wao-clipboard:${identity()}`);
    clipboard.value = status.hasText && dismissed !== String(status.change) ? status.change : null;
    const referenced = new Set(drafts.value.flatMap(d => d.audioIds ?? []));
    recordings.value = audio.ids.filter(id => !referenced.has(id));
  } catch (e) { if (ticket === generation) error.value = e instanceof Error ? e.message : '读取本机导入失败，请重试'; }
}
function dismissClipboard() {
  if (clipboard.value !== null) sessionStorage.setItem(`wao-clipboard:${identity()}`, String(clipboard.value));
  clipboard.value = null;
}
async function paste() {
  if (busy.value) return;
  busy.value = true; const account = identity();
  try {
    const result = await iosCapture.readClipboard();
    if (disposed || account !== identity()) return;
    // 点击后只建立本机编辑草稿，保存前不上传剪贴板。
    const draft = { ...newCapture(), excerpt: result.text };
    localStorage.setItem(`wao-capture:v1:${identity()}:${draft.id}`, JSON.stringify(draft));
    clipboard.value = result.change; dismissClipboard(); editor.value = draft;
  } catch (e) { error.value = e instanceof Error ? e.message : '未能读取剪贴板'; }
  finally { busy.value = false; }
}
async function openImport(item: SharedCapture) {
  if (busy.value) return;
  busy.value = true; const session = w.session.value!;
  try {
    if (!item.accountId) {
      await iosCapture.claim({ id: item.id, accountId: session.accountId, memberId: session.member.id });
    }
    if (session !== w.session.value) return;
    editor.value = { ...item, importedId: item.id };
  } catch (e) { error.value = e instanceof Error ? e.message : '读取导入失败'; }
  finally { busy.value = false; }
}
function recoverAudio(id: string) {
  const draft = { ...newCapture(), audioId: id, audioIds: [id] };
  localStorage.setItem(`wao-capture:v1:${identity()}:${draft.id}`, JSON.stringify(draft)); editor.value = draft;
}
function close() { editor.value = null; void refresh(); }
function saved() { editor.value = null; void w.refresh(); void refresh(); }
function create() { editor.value = newCapture(); }
function open(event: Event) { editor.value = (event as CustomEvent<CaptureDraft>).detail; }
watch(() => w.session.value, () => { generation++; editor.value = null; clipboard.value = null; pending.value = []; recordings.value = []; void refresh(); });
onMounted(async () => {
  window.addEventListener('wao:capture-create', create); window.addEventListener('wao:capture-open', open);
  await refresh();
  if (isIosCapture()) { const handle = await App.addListener('appStateChange', ({ isActive }) => { if (isActive) void refresh(); }); if (disposed) void handle.remove(); else active = handle; }
});
onUnmounted(() => { disposed = true; generation++; void active?.remove(); window.removeEventListener('wao:capture-create', create); window.removeEventListener('wao:capture-open', open); });
</script>
<template>
  <aside v-if="clipboard !== null || pending.length || drafts.length || recordings.length || error" class="capture-inbox" aria-label="本机小记收件箱">
    <div v-if="clipboard !== null" class="clipboard-prompt"><span>剪贴板有文字，要存为小记吗？</span><button :disabled="busy" @click="paste">导入</button><button :disabled="busy" @click="dismissClipboard">暂不</button></div>
    <details v-if="pending.length || drafts.length || recordings.length || error">
      <summary>本机草稿与待导入 · {{ pending.length + drafts.length + recordings.length }}</summary>
      <p v-if="error" class="error" role="alert">{{ error }} <button @click="refresh">重试</button></p>
      <button v-for="item in pending" :key="item.id" :disabled="busy" @click="openImport(item)">{{ item.accountId ? '继续导入' : '保存到当前成员' }}：{{ (item.excerpt || item.sourceUrl || '书摘图片').slice(0, 40) }}</button>
      <button v-for="draft in drafts" :key="draft.id" @click="editor = draft">继续写：{{ (draft.title || draft.excerpt || draft.thoughts || '录音小记').slice(0, 40) }}</button>
      <button v-for="id in recordings" :key="id" @click="recoverAudio(id)">恢复尚未转写的录音 · {{ id.slice(0, 8) }}</button>
    </details>
  </aside>
  <NoteCaptureEditor v-if="editor" :key="editor.id" :initial="editor" @close="close" @saved="saved" />
</template>
<style scoped>
.capture-inbox{position:fixed;top:env(safe-area-inset-top,0px);left:50%;transform:translateX(-50%);width:min(calc(100% - 24px),596px);z-index:90;background:#f3f2ff;border:1px solid #c9c9ef;border-radius:14px;padding:10px 14px;box-shadow:0 4px 18px #32365e16;font-size:13px}.clipboard-prompt{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.clipboard-prompt span{flex:1;min-width:180px}button{border:1px solid #c9c9ef;background:#fff;border-radius:10px;padding:10px;min-height:44px}details summary{padding:8px;cursor:pointer}details button{display:block;width:100%;text-align:left;margin-top:8px}details[open]{max-height:50dvh;overflow:auto}
.capture-inbox{top:auto;bottom:calc(82px + env(safe-area-inset-bottom,0px))}
</style>
