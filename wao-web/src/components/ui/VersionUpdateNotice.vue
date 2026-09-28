<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import {
  reloadToLatestVersion,
  WEB_UPDATE_EVENT,
  type WebUpdateEventDetail,
} from '@/utils/versionUpdate'

const availableVersion = ref('')

function handleUpdate(rawEvent: Event) {
  const event = rawEvent as CustomEvent<WebUpdateEventDetail>
  const version = event.detail?.version?.trim()
  if (version) availableVersion.value = version
}

onMounted(() => document.body.addEventListener(WEB_UPDATE_EVENT, handleUpdate))
onUnmounted(() => document.body.removeEventListener(WEB_UPDATE_EVENT, handleUpdate))
</script>

<template>
  <Teleport to="body">
    <aside v-if="availableVersion" class="version-update-notice" role="status" aria-live="polite">
      <i class="ri-notification-3-fill" aria-hidden="true"></i>
      <span>发现新版本</span>
      <button
        type="button"
        :aria-label="`保存当前内容并更新到版本 ${availableVersion}`"
        @click="reloadToLatestVersion(availableVersion)"
      >
        保存并更新
      </button>
    </aside>
  </Teleport>
</template>

<style scoped>
.version-update-notice {
  position: fixed;
  top: calc(16px + env(safe-area-inset-top, 0px));
  right: calc(16px + env(safe-area-inset-right, 0px));
  z-index: 1200;
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: calc(100vw - 32px);
  padding: 9px 10px 9px 13px;
  border: 1px solid #b42330;
  border-radius: 12px;
  background: #c62835;
  box-shadow: 0 8px 24px rgb(97 20 27 / 25%);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}
.version-update-notice button {
  flex: none;
  min-height: 34px;
  padding: 0 10px;
  border: 1px solid rgb(255 255 255 / 75%);
  border-radius: 7px;
  background: #fff;
  color: #a91d2a;
  cursor: pointer;
  font: inherit;
}
.version-update-notice button:focus-visible {
  outline: 3px solid #fff;
  outline-offset: 2px;
}
</style>
