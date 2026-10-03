<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import NoteAiSettingsForm from './NoteAiSettingsForm.vue'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()
const dialog = ref<HTMLElement>()
let trigger: HTMLElement | null = null
function close() { emit('update:open',false); void nextTick(() => trigger?.isConnected && trigger.focus({preventScroll:true})) }
useOverlayDismiss(() => props.open, close)
watch(() => props.open, async open => { if(open) { trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null; await nextTick(); dialog.value?.focus({preventScroll:true}) } })
</script>
<template>
 <Teleport to="body"><div v-if="open" class="note-settings-mask" @click.self="close"><section ref="dialog" class="note-settings-dialog" role="dialog" aria-modal="true" aria-label="小记 AI 设置" tabindex="-1"><header><h2>小记 AI 设置</h2><button type="button" aria-label="关闭小记 AI 设置" @click="close">关闭</button></header><NoteAiSettingsForm /></section></div></Teleport>
</template>
<style scoped>
.note-settings-mask { position:fixed; inset:0; z-index:1200; display:grid; place-items:center; padding:16px; background:var(--wao-mask-bg, #0005); }
.note-settings-dialog { width:min(560px,100%); max-height:85dvh; overflow:auto; padding:20px; box-sizing:border-box; border-radius:16px; background:var(--wao-bg-card); color:var(--wao-primary-text); box-shadow:var(--wao-shadow-dialog); }
header { display:flex; justify-content:space-between; align-items:center; margin-bottom:16px; } h2 { margin:0; font-size:18px; } button { background:var(--wao-bg-card); color:inherit; border:1px solid var(--wao-line-control); border-radius:6px; padding:6px 10px; }
</style>
