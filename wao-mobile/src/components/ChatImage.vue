<script setup lang="ts">
import { ref, watch, onUnmounted } from "vue";
import { readChatAsset } from "../api/chat";
const props = defineProps<{ url: string; name: string }>(),
  src = ref(""),
  failed = ref(false);
let generation = 0;
async function load() {
  const ticket = ++generation;
  failed.value = false;
  try {
    const blob = await readChatAsset(props.url);
    if (ticket !== generation) return;
    if (!blob.type.startsWith("image/")) throw new Error();
    if (src.value) URL.revokeObjectURL(src.value);
    src.value = URL.createObjectURL(blob);
  } catch {
    if (ticket === generation) failed.value = true;
  }
}
watch(() => props.url, load, { immediate: true });
onUnmounted(() => {
  generation++;
  if (src.value) URL.revokeObjectURL(src.value);
});
</script>
<template>
  <img v-if="src" class="chat-image" :src="src" :alt="name" /><button
    v-else-if="failed"
    class="text-button"
    @click="load"
  >
    图片加载失败 · 重试</button
  ><span v-else class="muted">图片加载中…</span>
</template>
