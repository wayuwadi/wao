<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchPreference, savePreference } from '@/api/favorites'
import { toast } from '@/composables/toast'
import { CURRENT_RELEASE } from '@/data/featureCatalog'
import { useSessionStore } from '@/stores/session'
import { consumePendingReleaseVersion, OPEN_RELEASE_HIGHLIGHTS_EVENT } from '@/utils/versionUpdate'
import ReleaseHighlightsTour from './ReleaseHighlightsTour.vue'

const PREFERENCE_KEY = 'release-highlights-state-v1'
const LOCAL_KEY = 'wao-release-highlights-seen-v1'
const open = ref(false)
const router = useRouter()
const session = useSessionStore()

interface ReleaseState {
  lastSeenReleaseId?: string
  seenAt?: string
}

function localKey() {
  return `${LOCAL_KEY}:${session.userId}`
}

function parseState(raw: string): ReleaseState {
  try {
    const parsed = JSON.parse(raw) as ReleaseState
    return typeof parsed?.lastSeenReleaseId === 'string' ? parsed : {}
  } catch {
    return {}
  }
}

function localSeen() {
  try {
    return localStorage.getItem(localKey()) === CURRENT_RELEASE.id
  } catch {
    return false
  }
}

function markLocalSeen() {
  try {
    localStorage.setItem(localKey(), CURRENT_RELEASE.id)
  } catch {
    /* 服务端仍会保存 */
  }
}

async function markSeen() {
  markLocalSeen()
  try {
    await savePreference(
      PREFERENCE_KEY,
      JSON.stringify({
        lastSeenReleaseId: CURRENT_RELEASE.id,
        seenAt: new Date().toISOString(),
      }),
    )
  } catch {
    toast('更新介绍已关闭；已读状态暂未同步到其他设备', 'ri-information-line')
  }
}

async function maybeOpenAfterUpdate() {
  const pendingVersion = consumePendingReleaseVersion()
  if (!pendingVersion || localSeen()) return
  try {
    const state = parseState(await fetchPreference(PREFERENCE_KEY))
    if (state.lastSeenReleaseId === CURRENT_RELEASE.id) {
      markLocalSeen()
      return
    }
  } catch {
    // 偏好暂不可读时仍展示本次更新；关闭会先在本机记录，避免循环打扰。
  }
  open.value = true
}

function showManually() {
  open.value = true
}

async function dismiss(mode: 'seen' | 'later') {
  open.value = false
  if (mode === 'seen') await markSeen()
}

async function openFeatures() {
  open.value = false
  await markSeen()
  await router.push('/features')
}

async function navigate(path: string) {
  open.value = false
  await router.push(path)
}

onMounted(() => {
  window.addEventListener(OPEN_RELEASE_HIGHLIGHTS_EVENT, showManually)
  void maybeOpenAfterUpdate()
})
onBeforeUnmount(() => window.removeEventListener(OPEN_RELEASE_HIGHLIGHTS_EVENT, showManually))
</script>

<template>
  <ReleaseHighlightsTour
    :open="open"
    :edition="CURRENT_RELEASE"
    @dismiss="dismiss"
    @features="openFeatures"
    @navigate="navigate"
  />
</template>
