<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { copyText } from '@/utils/clipboard'
import { toast } from '@/composables/toast'

const props = defineProps<{ src: string; x: number; y: number }>()
const emit = defineEmits<{ close: [] }>()
const firstAction = ref<HTMLButtonElement | null>(null)
const menuStyle = computed(() => ({
  left: `${Math.max(8, Math.min(props.x, window.innerWidth - 164))}px`,
  top: `${Math.max(8, Math.min(props.y, window.innerHeight - 92))}px`,
}))
watch(() => props.src, async () => { await nextTick(); firstAction.value?.focus() }, { immediate: true })

async function copyImage() {
  const src = props.src
  emit('close')
  try {
    if (!navigator.clipboard?.write || typeof ClipboardItem === 'undefined') throw new Error('剪贴板不支持图片')
    const response = await fetch(src, { credentials: 'same-origin' })
    if (!response.ok) throw new Error('图片读取失败')
    const source = await response.blob()
    let png = source
    if (source.type !== 'image/png') {
      const bitmap = await createImageBitmap(source)
      try {
        const canvas = document.createElement('canvas')
        canvas.width = bitmap.width
        canvas.height = bitmap.height
        const context = canvas.getContext('2d')
        if (!context) throw new Error('图片转换失败')
        context.drawImage(bitmap, 0, 0)
        png = await new Promise<Blob>((resolve, reject) => canvas.toBlob(
          value => value ? resolve(value) : reject(new Error('图片转换失败')), 'image/png'))
      } finally { bitmap.close() }
    }
    await navigator.clipboard.write([new ClipboardItem({ 'image/png': png })])
    toast('图片已复制', 'ri-file-copy-line')
  } catch {
    toast('复制图片失败，请重试或复制图片地址', 'ri-error-warning-line')
  }
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
      <div class="image-copy-menu" role="menu" aria-label="图片操作"
        :style="menuStyle">
        <button ref="firstAction" type="button" role="menuitem" @click="copyImage">复制图片</button>
        <button type="button" role="menuitem" @click="copyAddress">复制图片地址</button>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.image-copy-mask { position: fixed; inset: 0; z-index: 3300; }
.image-copy-menu { position: fixed; min-width: 148px; display: grid; padding: 5px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); box-shadow: 0 12px 30px rgba(0, 0, 0, .2); }
.image-copy-menu button { border: 0; border-radius: 5px; padding: 8px 10px; background: transparent; color: var(--wao-text-1); text-align: left; cursor: pointer; }
.image-copy-menu button:hover, .image-copy-menu button:focus-visible { background: var(--wao-bg-sub); }
</style>
