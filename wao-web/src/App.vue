<script setup lang="ts">
import { RouterView } from 'vue-router'
import ToastHost from '@/components/ui/ToastHost.vue'
import AiAssistantPanel from '@/components/assistant/AiAssistantPanel.vue'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
</script>

<template>
  <RouterView />
  <ToastHost />
  <AiAssistantPanel v-model="ui.assistantOpen" />
</template>
