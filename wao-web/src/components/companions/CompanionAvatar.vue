<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { companionEditing, companionQuiet } from '@/composables/quietCompanions'
import { COMPANIONS, type CompanionId } from '@/utils/companionIdentity'
const props = withDefaults(
  defineProps<{ identity: CompanionId; size?: number; animate?: boolean }>(),
  { size: 56, animate: false },
)
const emit = defineEmits<{ ready: []; failed: [] }>()
const failed = ref(false),
  background = ref(document.hidden)
const moving = computed(
  () => props.animate && !companionQuiet.value && !companionEditing.value && !background.value,
)
const image = computed(() => COMPANIONS[props.identity].image)
watch(image, () => {
  failed.value = false
})
function visibility() {
  background.value = document.hidden
}
function imageFailed() {
  failed.value = true
  emit('failed')
}
onMounted(() => document.addEventListener('visibilitychange', visibility))
onBeforeUnmount(() => document.removeEventListener('visibilitychange', visibility))
</script>
<template>
  <img
    v-if="!failed"
    class="companion-avatar"
    :class="{ greeting: moving }"
    :src="image"
    alt=""
    aria-hidden="true"
    :data-companion="identity"
    :width="size"
    :height="size"
    :style="{ width: `${size}px`, height: `${size}px` }"
    draggable="false"
    @load="emit('ready')"
    @error="imageFailed"
  />
</template>
<style scoped>
.companion-avatar {
  display: block;
  object-fit: contain;
  flex: none;
  pointer-events: none;
  user-select: none;
}
.greeting {
  animation: companion-greeting 1.2s ease-in-out;
  transform-origin: 50% 75%;
}
@keyframes companion-greeting {
  0%,
  100% {
    transform: rotate(0);
  }
  30% {
    transform: translateY(-3px) rotate(-8deg);
  }
  65% {
    transform: rotate(6deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .greeting {
    animation: none;
  }
}
</style>
