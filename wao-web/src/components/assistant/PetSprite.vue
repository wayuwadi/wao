<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import atlasUrl from '@/assets/pets/zhang-sanfeng/spritesheet.webp'
import { PET_SEQUENCES, petBackgroundPosition, type PetSpriteState } from '@/utils/petSprite'

const props = withDefaults(defineProps<{ state?: PetSpriteState; paused?: boolean }>(), {
  state: 'idle', paused: false,
})
const emit = defineEmits<{ finished: [] }>()
const column = ref(0)
const loadState = ref<'loading' | 'ready' | 'error'>('loading')
const reducedMotion = ref(false)
const hidden = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined
let image: HTMLImageElement | undefined
let motion: MediaQueryList | undefined
let mounted = false
const sequence = computed(() => PET_SEQUENCES[props.state])
const spriteStyle = computed(() => ({
  backgroundImage: `url("${atlasUrl}")`,
  backgroundPosition: petBackgroundPosition({ row: sequence.value.row, column: column.value }),
}))

function clearTimer() { if (timer !== undefined) clearTimeout(timer); timer = undefined }
function schedule() {
  clearTimer()
  if (!mounted || props.paused || reducedMotion.value || hidden.value || loadState.value !== 'ready') return
  timer = setTimeout(() => {
    if (column.value + 1 < sequence.value.durations.length) column.value += 1
    else if (sequence.value.loop) column.value = 0
    else { emit('finished'); return }
    schedule()
  }, sequence.value.durations[column.value])
}
function reset() { column.value = 0; schedule() }
function updateVisibility() { hidden.value = document.hidden; schedule() }
function updateMotion() { reducedMotion.value = motion?.matches ?? false; reset() }
function loadImage() {
  loadState.value = 'loading'
  image = new Image()
  image.onload = () => { loadState.value = 'ready'; schedule() }
  image.onerror = () => { loadState.value = 'error'; clearTimer() }
  image.src = atlasUrl
}
watch(() => props.state, reset)
watch(() => props.paused, reset)
onMounted(() => {
  mounted = true
  motion = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  motion?.addEventListener('change', updateMotion)
  updateMotion()
  document.addEventListener('visibilitychange', updateVisibility)
  updateVisibility()
  loadImage()
})
onBeforeUnmount(() => {
  mounted = false
  clearTimer()
  if (image) { image.onload = null; image.onerror = null }
  motion?.removeEventListener('change', updateMotion)
  document.removeEventListener('visibilitychange', updateVisibility)
})
</script>

<template>
  <span class="pet-sprite" :data-state="state" :data-frame="column" :data-load-state="loadState"
    :style="spriteStyle" aria-hidden="true">
    <span v-if="loadState !== 'ready'" class="pet-sprite-placeholder">張</span>
  </span>
</template>

<style scoped>
.pet-sprite { display: inline-block; width: 100%; height: 100%; background-size: 800% 1100%; background-repeat: no-repeat; pointer-events: none; }
.pet-sprite-placeholder { display: grid; place-items: center; width: 100%; height: 100%; color: var(--wao-primary-text); font-size: 22px; }
</style>
