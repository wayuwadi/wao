<script setup lang="ts">
import { RouterView } from 'vue-router'
import ToastHost from '@/components/ui/ToastHost.vue'
import VersionUpdateNotice from '@/components/ui/VersionUpdateNotice.vue'
</script>

<template>
  <RouterView />
  <ToastHost />
  <VersionUpdateNotice />
</template>
