<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import NotificationSettingsPanel from '@/components/settings/NotificationSettingsPanel.vue'
import ProjectNotificationSettingsPanel from '@/components/settings/ProjectNotificationSettingsPanel.vue'

const router = useRouter()
const panel = ref<{ confirmLeave: () => boolean } | null>(null)
const removeGuard = router.beforeEach(() => !panel.value || panel.value.confirmLeave())
onUnmounted(removeGuard)
</script>

<template>
  <main class="notification-settings-page">
    <NotificationSettingsPanel ref="panel" />
    <ProjectNotificationSettingsPanel />
  </main>
</template>

<style scoped>
.notification-settings-page { display: grid; gap: 14px; padding: 26px; }
@media (max-width: 640px) { .notification-settings-page { padding: 16px 12px 32px; } }
</style>
