<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AuthDialog from './AuthDialog.vue'
const dialog = ref<InstanceType<typeof AuthDialog> | null>(null)
onMounted(() => {
  void dialog.value?.open()
})
</script>
<template>
  <AuthDialog ref="dialog" target="" :before-navigate="() => {}" recovery />
</template>
