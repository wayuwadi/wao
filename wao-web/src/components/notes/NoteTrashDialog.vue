<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { apiRestoreTrash, fetchTrash } from '@/api/board'
import type { TrashEntry } from '@/types'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
const props = defineProps<{ kbName: string }>()
const emit = defineEmits<{ close: []; restored: [id: string] }>()
const items = ref<TrashEntry[]>([])
const entries = computed(() => items.value.filter(item => item.type === 'doc' && (item.parent === `文档 · ${props.kbName}` || item.parent.startsWith(`文档 · ${props.kbName} / `))))
const loading = ref(true), busy = ref(''), error = ref(''), status = ref('')
const root = ref<HTMLElement>()
const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
let disposed = false
async function load() {
  loading.value = true; error.value = ''
  try { const result = await fetchTrash(); if (!disposed) items.value = result }
  catch (cause) { if (!disposed) error.value = cause instanceof Error ? cause.message : '回收站读取失败，请重试' }
  finally { if (!disposed) loading.value = false }
}
async function restore(item: TrashEntry) {
  if (busy.value) return
  busy.value = item.id; error.value = ''; status.value = ''
  try {
    await apiRestoreTrash(item.type, item.id)
    if (disposed) return
    items.value = items.value.filter(entry => entry.id !== item.id)
    status.value = `已恢复「${item.name}」`
    emit('restored', item.id)
    await nextTick(); root.value?.querySelector<HTMLButtonElement>('button')?.focus()
  } catch (cause) { if (!disposed) error.value = cause instanceof Error ? cause.message : '恢复失败，文章仍保留在回收站，请重试' }
  finally { if (!disposed) busy.value = '' }
}
function close() { if (!busy.value) emit('close') }
function keys(event: KeyboardEvent) {
  if (event.key !== 'Tab') return
  const buttons = [...(root.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])]
  if (event.shiftKey && document.activeElement === buttons[0]) { event.preventDefault(); buttons[buttons.length - 1]?.focus() }
  else if (!event.shiftKey && document.activeElement === buttons[buttons.length - 1]) { event.preventDefault(); buttons[0]?.focus() }
}
useOverlayDismiss(() => true, close)
onMounted(() => { void load(); void nextTick(() => root.value?.querySelector<HTMLButtonElement>('button')?.focus()) })
onBeforeUnmount(() => { disposed = true; if (previousFocus?.isConnected) previousFocus.focus({ preventScroll: true }) })
</script>
<template>
  <Teleport to="body"><div class="trash-mask" @click.self="close" @keydown="keys">
    <section ref="root" class="trash-dialog" role="dialog" aria-modal="true" aria-label="小记回收站">
      <header><h3><i class="ri-delete-bin-line" aria-hidden="true"></i>回收站</h3><button type="button" aria-label="关闭回收站" :disabled="!!busy" @click="close"><i class="ri-close-line"></i></button></header>
      <p>最近删除的小记保留 7 天，恢复后回到原来的位置。</p>
      <p v-if="error" role="alert" class="error">{{ error }} <button v-if="!busy" type="button" @click="load">重新读取</button></p>
      <p v-if="status" role="status">{{ status }}</p>
      <p v-if="loading" role="status">正在读取回收站…</p>
      <ul v-else-if="entries.length"><li v-for="item in entries" :key="item.id"><div><strong>{{ item.name }}</strong><small>删除于 {{ new Date(item.deletedAt).toLocaleString('zh-CN') }} · {{ new Date(item.expiresAt).toLocaleDateString('zh-CN') }} 到期</small></div><button type="button" :disabled="!!busy" :aria-label="`恢复小记：${item.name}`" @click="restore(item)">{{ busy === item.id ? '恢复中…' : '恢复' }}</button></li></ul>
      <div v-else-if="!error" class="empty"><i class="ri-delete-bin-line" aria-hidden="true"></i><p>没有最近删除的小记</p></div>
    </section>
  </div></Teleport>
</template>
<style scoped>
.trash-mask{position:fixed;inset:0;z-index:4600;background:rgb(24 28 46 / .28);display:grid;place-items:center;padding:16px}.trash-dialog{width:min(560px,100%);max-height:calc(100dvh - 32px);overflow:auto;box-sizing:border-box;padding:22px;border:1px solid var(--wao-line);border-radius:16px;background:var(--wao-bg-card);color:var(--wao-text-1);box-shadow:0 20px 70px rgb(20 24 45 / .18)}header,h3,li{display:flex;align-items:center;gap:8px}header{justify-content:space-between}h3{margin:0;font-size:17px}h3 i{color:var(--wao-primary)}p,small{font-size:12px;color:var(--wao-text-2);line-height:1.8}button{border:1px solid var(--wao-line-control);border-radius:8px;padding:8px 11px;background:var(--wao-bg-card);color:var(--wao-primary-text);cursor:pointer;flex:none}button:disabled{opacity:.5;cursor:default}button:focus-visible{outline:2px solid var(--wao-primary);outline-offset:2px}ul{padding:0;list-style:none}li{justify-content:space-between;border-top:1px solid var(--wao-line);padding:15px 0}li div{min-width:0}strong{font-size:14px;overflow-wrap:anywhere}small{display:block;margin-top:5px}.error{color:var(--wao-danger-strong)}.empty{text-align:center;padding:36px 0}.empty i{font-size:32px;color:var(--wao-text-faint)}
</style>
