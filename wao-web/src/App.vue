<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { ConfigProvider, theme as antTheme } from 'ant-design-vue'
import { useTheme } from '@/composables/theme'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { clearToasts } from '@/composables/toast'
import { RouterView } from 'vue-router'
import ToastHost from '@/components/ui/ToastHost.vue'
import FocusDock from '@/components/focus/FocusDock.vue'
import SessionRecovery from '@/components/login/SessionRecovery.vue'
import { SESSION_EXPIRED_EVENT } from '@/utils/sessionRecovery'
import { restJourneyRequested } from '@/composables/restJourney'
import RestEntryTransition from '@/components/relax/RestEntryTransition.vue'
import { writingJourneyRequested } from '@/composables/writingJourney'
import WritingEntryTransition from '@/components/home/WritingEntryTransition.vue'
import { createWorkspaceReloader } from '@/utils/workspaceReload'
const { effective } = useTheme()
const componentTheme = computed(() => ({
  algorithm: effective.value === 'dark' ? antTheme.darkAlgorithm : antTheme.defaultAlgorithm,
  token: { colorPrimary: effective.value === 'dark' ? '#5365d8' : '#4358d6' },
}))
watch(() => useSessionStore().userId, clearToasts, { flush: 'sync' })
const reloadWorkspace = createWorkspaceReloader({
  flush: () => useDataStore().flushWorkspace(),
  member: () => useSessionStore().userId,
})
function openTask(event: Event) {
  const id = (event as CustomEvent<unknown>).detail
  if (typeof id === 'string' && /^[A-Za-z0-9_-]{1,100}$/.test(id)) useUiStore().openTask(id)
}
function legacyPetOpen(event: StorageEvent) {
  if (event.key !== 'wao-desktop-open-request' || !event.newValue) return
  try {
    const request = JSON.parse(event.newValue)
    if (request.member !== useSessionStore().userId || Math.abs(Date.now() - request.time) > 5000)
      return
    if (request.action === 'open-chat') useUiStore().openChat()
    else if (/^task\/[A-Za-z0-9_-]{1,100}$/.test(request.action))
      useUiStore().openTask(request.action.slice(5))
  } catch {
    /* Ignore malformed signals from old windows. */
  }
}
function openChat() {
  useUiStore().openChat()
}
function recoverSession() {
  useSessionStore().requireRecovery()
}
onMounted(() => {
  window.addEventListener(SESSION_EXPIRED_EVENT, recoverSession)
  window.addEventListener('wao-workspace-reload', reloadWorkspace)
  window.addEventListener('wao-open-chat', openChat)
  window.addEventListener('wao-open-task', openTask)
  window.addEventListener('storage', legacyPetOpen)
})
onBeforeUnmount(() => {
  window.removeEventListener(SESSION_EXPIRED_EVENT, recoverSession)
  window.removeEventListener('wao-workspace-reload', reloadWorkspace)
  window.removeEventListener('wao-open-chat', openChat)
  window.removeEventListener('wao-open-task', openTask)
  window.removeEventListener('storage', legacyPetOpen)
})
</script>

<template>
  <ConfigProvider :theme="componentTheme">
    <RouterView />
    <SessionRecovery v-if="useSessionStore().recoveryRequired" />
    <ToastHost />
    <FocusDock />
    <RestEntryTransition v-if="restJourneyRequested" />
    <WritingEntryTransition v-if="writingJourneyRequested" :journey="writingJourneyRequested" />
  </ConfigProvider>
</template>
