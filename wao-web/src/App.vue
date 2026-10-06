<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, watch } from 'vue'
import { ConfigProvider, theme as antTheme } from 'ant-design-vue'
import { useTheme } from '@/composables/theme'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { toast, clearToasts } from '@/composables/toast'
import { RouterView } from 'vue-router'
import ToastHost from '@/components/ui/ToastHost.vue'
import SessionRecovery from '@/components/login/SessionRecovery.vue'
import { SESSION_EXPIRED_EVENT } from '@/utils/sessionRecovery'
import { restJourneyRequested } from '@/composables/restJourney'
import RestEntryTransition from '@/components/relax/RestEntryTransition.vue'
import { writingJourneyRequested } from '@/composables/writingJourney'
import WritingEntryTransition from '@/components/home/WritingEntryTransition.vue'
import {
  rememberPendingReleaseVersion,
  reportWorkspaceReloadState,
  type WorkspaceReloadDetail,
} from '@/utils/versionUpdate'
const { effective } = useTheme()
const componentTheme = computed(() => ({
  algorithm: effective.value === 'dark' ? antTheme.darkAlgorithm : antTheme.defaultAlgorithm,
  token: { colorPrimary: effective.value === 'dark' ? '#5365d8' : '#4358d6' },
}))
watch(() => useSessionStore().userId, clearToasts, { flush: 'sync' })
let refreshing = false
async function reloadWorkspace(rawEvent?: Event) {
  if (refreshing) return
  refreshing = true
  reportWorkspaceReloadState({ state: 'saving' })
  try {
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur()
    await nextTick()
    if (!(await useDataStore().flushWorkspace()))
      throw new Error('保存失败，已保留当前页面和草稿，请重试')
    const requests: Promise<unknown>[] = []
    window.dispatchEvent(
      new CustomEvent('wao-save-before-refresh', {
        detail: { waitUntil: (request: Promise<unknown>) => requests.push(request) },
      }),
    )
    await Promise.all(requests)
    if (!(await useDataStore().flushWorkspace()))
      throw new Error('保存失败，已保留当前页面和草稿，请重试')
    const leave = new Event('beforeunload', { cancelable: true })
    if (!window.dispatchEvent(leave))
      throw new Error('当前编辑尚未保存，请处理页面中的保存提示后重试')
    const targetVersion = (rawEvent as CustomEvent<WorkspaceReloadDetail> | undefined)?.detail
      ?.targetVersion
    if (targetVersion) rememberPendingReleaseVersion(targetVersion)
    reportWorkspaceReloadState({ state: 'reloading' })
    window.location.reload()
  } catch (error) {
    reportWorkspaceReloadState({
      state: 'failed',
      message: error instanceof Error ? error.message : '保存失败，当前内容已保留',
    })
    toast(
      error instanceof Error ? error.message : '保存失败，当前内容已保留',
      'ri-error-warning-line',
    )
  } finally {
    refreshing = false
  }
}
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
    <RestEntryTransition v-if="restJourneyRequested" />
    <WritingEntryTransition v-if="writingJourneyRequested" :journey="writingJourneyRequested" />
  </ConfigProvider>
</template>
