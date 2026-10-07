<script setup lang="ts">
import { computed } from 'vue'
import type { CompanionKind } from '@/composables/quietCompanions'
import { randomCompanion, type CompanionId } from '@/utils/companionIdentity'
import CompanionAvatar from './CompanionAvatar.vue'
const props = defineProps<{ kind: CompanionKind; identity?: CompanionId }>()
const emit = defineEmits<{ ready: []; failed: [] }>()
const fallback = randomCompanion()
const identity = computed(() => props.identity ?? fallback)
</script>
<template>
  <CompanionAvatar
    :identity="identity"
    :size="56"
    animate
    @ready="emit('ready')"
    @failed="emit('failed')"
  />
</template>
