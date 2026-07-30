<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { RouterView } from 'vue-router'
import ToastHost from '@/components/ui/ToastHost.vue'
import CommandPalette from '@/components/command/CommandPalette.vue'

const paletteOpen = ref(false)

function onKeydown(e: KeyboardEvent) {
  const isCmdOrCtrl = e.metaKey || e.ctrlKey
  if (isCmdOrCtrl && (e.key === 'k' || e.key === 'K' || e.key === 'p' || e.key === 'P')) {
    e.preventDefault()
    paletteOpen.value = true
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <RouterView />
  <ToastHost />
  <CommandPalette v-model="paletteOpen" />
</template>
