<script setup lang="ts">
import { ref, watch } from 'vue'
import { fetchDiscoveryPage, type Discovery } from '@/api/noteDiscoveries'
import NoteDiscoveryInline from './NoteDiscoveryInline.vue'
const props = defineProps<{ mode: 'saved' | 'legacy'; memberId: string }>()
const emit = defineEmits<{ source: [itemId: string, docId: string, event: Event] }>()
const items = ref<Discovery[]>([]), cursor = ref<string | null>(null), loading = ref(false), error = ref('')
let generation = 0
async function load(more = false) {
  const mine = generation
  loading.value = true; error.value = ''
  try {
    const page = await fetchDiscoveryPage(props.mode, more ? cursor.value ?? undefined : undefined)
    if (mine !== generation) return
    items.value = more ? [...items.value, ...page.items] : page.items; cursor.value = page.nextCursor
  } catch { if (mine === generation) error.value = '读取失败，请重试' }
  finally { if (mine === generation) loading.value = false }
}
function changed(item: Discovery) { items.value = items.value.map(value => value.id === item.id ? item : value).filter(value => props.mode !== 'saved' || value.feedback.saved) }
watch(() => [props.mode, props.memberId], () => { generation++; items.value = []; cursor.value = null; void load() }, { immediate: true })
</script>
<template>
  <section aria-label="已保留的发现">
    <p v-if="loading" role="status">读取中…</p>
    <p v-if="error" role="alert">{{ error }} <button type="button" :disabled="loading" @click="load(!!items.length)">重试</button></p>
    <p v-if="!loading && !error && !items.length">{{ mode === 'saved' ? '还没有收藏的发现。收藏只是保留，不代表确认结论正确。' : '没有旧版生成内容。' }}</p>
    <NoteDiscoveryInline v-for="item in items" :key="item.id" :item="item" @changed="changed" @source="(id, doc, event) => emit('source', id, doc, event)" />
    <button v-if="cursor" type="button" :disabled="loading" @click="load(true)">加载更多</button>
  </section>
</template>
