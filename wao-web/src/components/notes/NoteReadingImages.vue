<script setup lang="ts">
import { ref } from 'vue'
import { notePreviewSource, type NotePreviewImage } from '@/utils/notePreviewImages'
import { imageDisplayName } from '@/utils/imageDisplayName'
defineProps<{ images: NotePreviewImage[]; active: number | null; unavailable?: boolean; editable?: boolean }>()
const emit = defineEmits<{ return: [index: number]; fullscreen: [image: NotePreviewImage, event: MouseEvent, index: number]; rename: [index: number, event: MouseEvent] }>()
const root = ref<HTMLElement | null>(null)
const failed = ref<Record<string, boolean>>({})
function locate(index: number) {
  const card = root.value?.querySelector<HTMLElement>(`[data-reading-image="${index}"]`)
  card?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
  card?.querySelector<HTMLButtonElement>('.reading-image-open')?.focus({ preventScroll: true })
}
defineExpose({ locate })
</script>
<template>
  <aside ref="root" class="reader-images" aria-label="本篇图片清单">
    <div class="reading-images-heading"><strong>图片清单</strong><span>{{ images.length }} 张</span></div>
    <p v-if="!images.length" class="reading-images-empty">{{ unavailable ? '原文图片暂不可用' : '本篇没有图片' }}</p>
    <figure v-for="(image, offset) in images" :key="`${offset}:${image.src}`" :data-reading-image="offset + 1" :class="{ active: active === offset + 1 }">
      <figcaption><span>图 {{ offset + 1 }}</span><button v-if="editable" type="button" class="image-name-button" :aria-label="`修改图 ${offset + 1} 的名称`" :title="imageDisplayName(image.alt, offset + 1)" @click="emit('rename', offset + 1, $event)">{{ imageDisplayName(image.alt, offset + 1) }}<i class="ri-edit-line" aria-hidden="true"></i></button><strong v-else :title="imageDisplayName(image.alt, offset + 1)">{{ imageDisplayName(image.alt, offset + 1) }}</strong></figcaption>
      <button class="reading-image-open" type="button" :aria-label="`全屏查看图 ${offset + 1}：${imageDisplayName(image.alt, offset + 1)}`" @click="emit('fullscreen', image, $event, offset)">
        <span v-if="failed[image.src]" class="reading-image-error">图片加载失败，点击查看原图</span>
        <img v-else :src="notePreviewSource(image.src)" :alt="imageDisplayName(image.alt, offset + 1)" loading="lazy" @error="failed[image.src] = true" />
      </button>
      <div class="reading-image-actions"><button type="button" :aria-label="`返回正文图 ${offset + 1}的位置`" @click="emit('return', offset + 1)"><i class="ri-arrow-left-up-line" aria-hidden="true"></i>返回正文</button><button type="button" :aria-label="`全屏查看图 ${offset + 1}`" @click="emit('fullscreen', image, $event, offset)"><i class="ri-fullscreen-line" aria-hidden="true"></i>全屏</button></div>
    </figure>
  </aside>
</template>
<style scoped>
.reader-images { min-width: 0; padding: 22px 16px; box-sizing: border-box; background: var(--wao-bg-sub); border-left: 1px solid var(--wao-line); }
.reading-images-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 13px; }
.reading-images-heading span, .reading-images-empty { color: var(--wao-text-faint); font-size: 12px; }
.reading-images-empty { margin-top: 24px; line-height: 1.8; }
figure { margin: 14px 0; padding: 10px; border: 1px solid var(--wao-line); border-radius: 12px; background: var(--wao-bg-card); }
figure.active { border-color: var(--wao-primary); box-shadow: 0 0 0 2px var(--wao-primary-soft); }
figcaption { display: flex; align-items: baseline; gap: 7px; margin-bottom: 10px; font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
figcaption span { flex: none; color: var(--wao-primary-text); }
figcaption strong, .image-name-button { font-weight: 500; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.image-name-button { display: block; border: 0; padding: 2px 0; color: inherit; background: transparent; font: inherit; text-align: left; cursor: text; }
.image-name-button i { margin-left: 5px; color: var(--wao-text-faint); }
.reading-image-open { display: block; width: 100%; padding: 0; border: 0; border-radius: 7px; background: var(--wao-bg-sub); cursor: zoom-in; }
img { display: block; width: 100%; height: auto; max-height: 280px; object-fit: contain; border-radius: 7px; }
.reading-image-error { display: block; padding: 28px 8px; color: var(--wao-text-weak); font-size: 12px; line-height: 1.7; }
.reading-image-actions { display: flex; justify-content: space-between; gap: 8px; margin-top: 9px; }
.reading-image-actions button { border: 0; padding: 4px 0; font: inherit; font-size: 11px; color: var(--wao-text-weak); background: transparent; cursor: pointer; }
.reading-image-actions i { margin-right: 3px; }
button:hover { color: var(--wao-primary-text); }
button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 3px; }
@media (max-width: 650px) { .reader-images { border-left: 0; border-top: 1px solid var(--wao-line); padding: 20px 18px; } figure { max-width: 440px; } }
@media (pointer: coarse) { .image-name-button { min-height: 44px; } }
</style>
