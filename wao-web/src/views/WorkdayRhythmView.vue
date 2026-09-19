<script setup lang="ts">
import WorkdayRhythmPage from '@/components/workday/WorkdayRhythmPage.vue'
import { useSessionStore } from '@/stores/session'
const session = useSessionStore()
</script>

<template>
  <WorkdayRhythmPage v-if="session.userId" :key="session.userId" />
</template>
