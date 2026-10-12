<script setup lang="ts">
import { onMounted, ref } from 'vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import { createNoteShare, listNoteShares, revokeNoteShare, publicNoteUrl, type NoteShareEntry, type CreatedNoteShare } from '@/api/notePublicShare'
import { copyToClipboard } from '@/utils/clipboard'
const props = defineProps<{ noteId: string; title?: string }>()
const emit = defineEmits<{ close: [] }>()
const entries = ref<NoteShareEntry[]>([])
const created = ref<CreatedNoteShare | null>(null)
const duration = ref(168)
const consent = ref(false)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const status = ref('')
const loaded = ref(false)
function message(cause: unknown, fallback: string): string {
  const response = (cause as { response?: { data?: { message?: string } } })?.response
  return response?.data?.message || fallback
}
async function load() {
  loading.value = true; error.value = ''
  try { entries.value = await listNoteShares(props.noteId); loaded.value = true }
  catch (cause) { loaded.value = false; error.value = message(cause, '分享状态加载失败，请重试') }
  finally { loading.value = false }
}
onMounted(load)
async function create() {
  if (busy.value || !loaded.value || !consent.value) return
  busy.value = true; error.value = ''; status.value = '正在生成…'
  try {
    const result = await createNoteShare(props.noteId, duration.value)
    created.value = result; entries.value = [result, ...entries.value]; consent.value = false
    status.value = '限时链接已生成，请复制后发送给对方。'
  } catch (cause) { error.value = message(cause, '生成失败，请重试'); status.value = '' }
  finally { busy.value = false }
}
async function revoke(entry: NoteShareEntry) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    await revokeNoteShare(props.noteId, entry.id)
    entries.value = entries.value.filter(value => value.id !== entry.id)
    if (created.value?.id === entry.id) created.value = null
    status.value = '链接已撤销，正文与配图均无法再通过它读取。'
  } catch (cause) { error.value = message(cause, '撤销失败，请重试') }
  finally { busy.value = false }
}
async function copy(markdown: boolean) {
  if (!created.value) return
  status.value = await copyToClipboard(publicNoteUrl(created.value.token, markdown)) ? '链接已复制' : '复制失败，请选中下面的地址手动复制'
}
function date(value: string): string { return new Date(value).toLocaleString() }
function close() { if (!busy.value) emit('close') }
</script>
<template>
  <SettingsDialogShell title="小记分享设置" :description="title || '未命名小记'" width="640px" height="auto" @close="close">
    <div class="note-sharing">
      <p class="notice">小记默认私有。持有分享链接的人无需登录即可读取。</p>
      <p>分享已保存的正文和配图，不包含草稿、后续修改或原文对照。附件仍需登录。撤销不能收回对方已下载的内容。</p>
      <p v-if="loading" role="status">正在加载分享状态…</p>
      <template v-else-if="loaded">
        <label class="expiry">有效期 <select v-model.number="duration" :disabled="busy"><option :value="1">1 小时</option><option :value="24">1 天</option><option :value="168">7 天（默认）</option></select></label>
        <label class="consent"><input v-model="consent" type="checkbox" :disabled="busy">我理解持有链接的人可读取，主动开启限时分享</label>
        <WaoButton variant="primary" :disabled="!consent || busy" :loading="busy" @click="create">生成限时分享链接</WaoButton>
        <section v-if="created" class="created" aria-label="新生成的分享链接">
          <label>分享页地址<input :value="publicNoteUrl(created.token)" readonly @focus="($event.target as HTMLInputElement).select()"></label>
          <WaoButton :disabled="busy" @click="copy(false)">复制分享页链接</WaoButton>
          <label>给 AI 的完整原文地址<input :value="publicNoteUrl(created.token, true)" readonly @focus="($event.target as HTMLInputElement).select()"></label>
          <WaoButton :disabled="busy" @click="copy(true)">复制 Markdown 地址</WaoButton>
        </section>
        <section class="active" aria-label="有效分享链接">
          <h3>有效链接 · {{ entries.length }}</h3>
          <p v-if="!entries.length">当前没有免登录分享链接。</p>
          <p v-else>可随时撤销。地址仅在生成时提供，请及时复制保存。</p>
          <div v-for="entry in entries" :key="entry.id" class="entry"><span>生成于 {{ date(entry.createdAt) }}<br>到期于 {{ date(entry.expiresAt) }}</span><WaoButton variant="danger" :disabled="busy" @click="revoke(entry)">撤销链接</WaoButton></div>
        </section>
      </template>
      <div v-if="error" role="alert" class="error">{{ error }} <WaoButton v-if="!loaded" :disabled="loading" @click="load">重试加载</WaoButton></div>
      <p role="status" aria-live="polite">{{ status }}</p>
    </div>
    <template #footer><WaoButton :disabled="busy" @click="close">关闭</WaoButton></template>
  </SettingsDialogShell>
</template>
<style scoped>
.note-sharing { display:grid; gap:var(--wao-space-3); padding:var(--wao-space-4); color:var(--wao-text); font-size:var(--wao-font-body); line-height:1.7; }
p,h3 { margin:0; overflow-wrap:anywhere; }
p { color:var(--wao-text-2); }
.notice { color:var(--wao-text); font-weight:600; }
.expiry,.consent { display:flex; gap:var(--wao-space-2); align-items:center; }
.consent { align-items:flex-start; }
select,input[readonly] { min-height:var(--wao-control-height); box-sizing:border-box; background:var(--wao-bg-card); color:var(--wao-text); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); padding:var(--wao-space-2); }
input[readonly] { display:block; width:100%; }
.created,.active { display:grid; gap:var(--wao-space-2); border-top:1px solid var(--wao-line); padding-top:var(--wao-space-3); }
.entry { display:flex; gap:var(--wao-space-2); justify-content:space-between; align-items:center; flex-wrap:wrap; }
.error { color:var(--wao-danger-strong); }
input:focus-visible,select:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
</style>
