<script setup lang="ts">
import { defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { companionEditing, companionResponse, editingCompanionContent, rememberCompanionInteraction, resetCompanionContext } from '@/composables/quietCompanions'
import { companionSpace } from './companionSpace'
const Portrait = defineAsyncComponent(() => import('./CompanionPortrait.vue'))
const route = useRoute(), session = useSessionStore()
const position = ref<{ left: string; top: string }>()
let timer: ReturnType<typeof setTimeout> | undefined
function dismiss() { clearTimeout(timer); companionResponse.value = null }
function ready() { clearTimeout(timer); timer = setTimeout(dismiss, 2200) }
function focus() { companionEditing.value = editingCompanionContent(); if (companionEditing.value) dismiss() }
function blur() { void nextTick(focus) }
function keys(event: KeyboardEvent) {
  if (event.key === 'Escape') dismiss()
  else if (event.key === 'Enter' || event.key === ' ') rememberCompanionInteraction(event)
}
watch(() => route.fullPath, () => resetCompanionContext())
watch(() => session.userId, id => resetCompanionContext(id), { immediate: true })
watch(companionResponse, response => {
  clearTimeout(timer)
  position.value = response ? companionSpace(response.ticket.anchor) : undefined
  if (response && !position.value) dismiss()
  // 动态模块下载缓慢时不在很久以后补播。
  if (response) timer = setTimeout(dismiss, 3000)
}, { flush: 'post' })
onMounted(() => {
  focus()
  document.addEventListener('pointerdown', rememberCompanionInteraction, true)
  document.addEventListener('keydown', keys, true)
  document.addEventListener('focusin', focus)
  document.addEventListener('focusout', blur)
  document.addEventListener('visibilitychange', dismiss)
  window.addEventListener('resize', dismiss)
  window.addEventListener('scroll', dismiss, true)
})
onBeforeUnmount(() => {
  companionEditing.value = false
  dismiss(); resetCompanionContext()
  document.removeEventListener('pointerdown', rememberCompanionInteraction, true)
  document.removeEventListener('keydown', keys, true)
  document.removeEventListener('focusin', focus)
  document.removeEventListener('focusout', blur)
  document.removeEventListener('visibilitychange', dismiss)
  window.removeEventListener('resize', dismiss)
  window.removeEventListener('scroll', dismiss, true)
})
</script>
<template>
  <Teleport to="body"><div v-if="companionResponse && position" class="quiet-companion" :style="position" aria-hidden="true" :data-kind="companionResponse.kind">
    <Portrait :kind="companionResponse.kind" :identity="companionResponse.identity" @ready="ready" @failed="dismiss" />
  </div></Teleport>
</template>
<style scoped>
.quiet-companion { position:fixed; width:56px; height:56px; pointer-events:none; z-index:90; }
</style>
