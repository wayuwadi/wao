<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { copyText } from '@/utils/clipboard'
import { toast } from '@/composables/toast'
import { copyOriginalImage } from '@/utils/noteMediaClipboard'

const props = defineProps<{ src: string; x: number; y: number }>()
const emit = defineEmits<{ close: [] }>()
const firstAction = ref<HTMLButtonElement | null>(null)
const menuElement = ref<HTMLElement | null>(null)
const position = ref<{ left: number; top: number } | null>(null)
const copying = ref(false)
const copyError = ref('')
const returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
const menuStyle = computed(() => position.value ? { left: `${position.value.left}px`, top: `${position.value.top}px` } : { visibility: 'hidden' as const })
function placeMenu() {
  const box = menuElement.value?.getBoundingClientRect()
  position.value = { left: Math.max(8, Math.min(props.x, window.innerWidth - (box?.width ?? 164) - 8)), top: Math.max(8, Math.min(props.y, window.innerHeight - (box?.height ?? 92) - 8)) }
}
watch(() => [props.src, props.x, props.y], async () => {
  position.value = null
  await nextTick()
  placeMenu()
  await nextTick()
  const first = menuElement.value?.querySelector<HTMLInputElement>('input:not([disabled])') ?? menuElement.value?.querySelector<HTMLButtonElement>('button')
  first?.focus({ preventScroll: true })
}, { immediate: true })
let resizeObserver: ResizeObserver | undefined
onMounted(() => {
  if (typeof ResizeObserver !== 'undefined' && menuElement.value) {
    resizeObserver = new ResizeObserver(placeMenu)
    resizeObserver.observe(menuElement.value)
  }
  window.addEventListener('resize', placeMenu)
})
onBeforeUnmount(() => { resizeObserver?.disconnect(); window.removeEventListener('resize', placeMenu); if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true }) })

async function copyImage() {
  if (copying.value) return
  copying.value = true
  copyError.value = ''
  try {
    await copyOriginalImage(props.src)
    toast('图片已复制', 'ri-file-copy-line')
    emit('close')
  } catch (cause) { copyError.value = cause instanceof Error ? cause.message : '复制图片失败，请重试' }
  finally { copying.value = false }
}

async function copyAddress() {
  const src = props.src
  emit('close')
  await copyText(new URL(src, window.location.href).href, '图片地址已复制')
}
</script>

<template>
  <Teleport to="body">
    <div class="image-copy-mask" @pointerdown.self="emit('close')" @contextmenu.prevent="emit('close')" @keydown.esc.stop="emit('close')">
      <div ref="menuElement" class="image-copy-menu" role="dialog" aria-label="图片操作"
        :style="menuStyle">
        <slot></slot>
        <div class="image-copy-actions">
          <button ref="firstAction" type="button" role="menuitem" :disabled="copying" @click="copyImage"><i class="ri-file-copy-line" aria-hidden="true"></i>{{ copying ? '正在复制…' : copyError ? '重试复制图片' : '复制图片' }}</button>
          <p v-if="copyError" class="image-copy-error" role="alert">{{ copyError }}</p>
          <button type="button" role="menuitem" :disabled="copying" @click="copyAddress"><i class="ri-link" aria-hidden="true"></i>复制图片链接</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.image-copy-mask { position: fixed; inset: 0; z-index: 3300; }
.image-copy-menu { position: fixed; box-sizing: border-box; min-width: 148px; max-width: calc(100vw - 16px); max-height: calc(100dvh - 16px); overflow: auto; display: grid; padding: 8px; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-card); background: var(--wao-bg-card); color: var(--wao-text); box-shadow: var(--wao-shadow-pop); font-family: inherit; font-size: 13px; line-height: 1.5; }
.image-copy-menu button { display: flex; align-items: center; gap: 8px; width: 100%; border: 0; border-radius: var(--wao-radius-control); padding: 7px 8px; background: transparent; color: inherit; font: inherit; text-align: left; cursor: pointer; }
.image-copy-menu button:hover, .image-copy-menu button:focus-visible { background: var(--wao-bg-sub); }
.image-copy-actions { display: grid; }
.image-copy-menu button:disabled { opacity: .6; cursor: wait; }
.image-copy-error { margin: 4px 8px; max-width: 240px; font-size: 12px; overflow-wrap: anywhere; color: var(--wao-danger-strong); }
.image-copy-menu:has(.note-image-menu) .image-copy-actions { border-top: 1px solid var(--wao-line-control); padding-top: 5px; margin-top: 5px; }
@media (pointer: coarse) { .image-copy-menu button { min-height: 44px; } }
</style>
