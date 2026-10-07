<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { fetchDigitalAssetBlob } from '@/api/digitalMembers'
import { useSessionStore } from '@/stores/session'
const props = withDefaults(defineProps<{ assetId?: string | null; alt: string; retryable?: boolean }>(), { retryable: true })
const session = useSessionStore()
const url = ref(''),
  error = ref(''),
  loading = ref(false)
let generation = 0
function clear() {
  if (url.value) URL.revokeObjectURL(url.value)
  url.value = ''
}
async function load() {
  const ticket = ++generation
  clear()
  error.value = ''
  loading.value = false
  if (!props.assetId) return
  loading.value = true
  try {
    const blob = await fetchDigitalAssetBlob(props.assetId)
    if (ticket === generation) url.value = URL.createObjectURL(blob)
  } catch {
    if (ticket === generation) error.value = '图片读取失败'
  } finally {
    if (ticket === generation) loading.value = false
  }
}
watch([() => props.assetId, () => session.userId], load, { immediate: true })
onBeforeUnmount(() => {
  generation++
  clear()
})
</script>
<template>
  <span class="private-image"
    ><img v-if="url" :src="url" :alt="alt" /><span v-else-if="error" class="image-failure"
      >{{ error }} <button v-if="retryable" type="button" @click.stop="load">重试</button></span
    ><i v-else :class="loading ? 'ri-loader-4-line' : 'ri-user-smile-line'" aria-hidden="true"></i
  ></span>
</template>
<style scoped>
.private-image {
  display: flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--wao-primary-tint);
  color: var(--wao-primary-text);
}
img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
i {
  font-size: 32px;
}
.image-failure {
  padding: 8px;
  font-size: 12px;
  text-align: center;
}
.image-failure button {
  border: 0;
  background: transparent;
  color: inherit;
  text-decoration: underline;
}
</style>
