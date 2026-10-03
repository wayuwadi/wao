<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useFeatureExperience } from '@/composables/featureExperience'
import { MODULES } from '@/config/nav'
import type { ReleaseEdition } from '@/types/featureExplorer'
import ExplorerPagination from './ExplorerPagination.vue'
const props = defineProps<{ edition: ReleaseEdition }>()
const emit = defineEmits<{ navigate: [] }>()
const page = ref(1)
const tryFeature = useFeatureExperience()
async function experience(id: string) { if (await tryFeature(id)) emit('navigate') }
watch(
  () => props.edition.id,
  () => {
    page.value = 1
  },
)
const visible = computed(() => props.edition.highlights.slice((page.value - 1) * 3, page.value * 3))
</script>
<template>
  <div class="edition-content">
    <article v-for="(item, index) in visible" :key="`${item.featureId}-${index}`">
      <span class="edition-eyebrow">{{ item.eyebrow }}</span>
      <h3>{{ item.title }}</h3>
      <p>{{ item.summary }}</p>
      <ul v-if="item.points.length">
        <li v-for="point in item.points" :key="point">{{ point }}</li>
      </ul>
      <button type="button"
        v-if="MODULES[item.featureId] && item.featureId !== 'changelog'"

        @click="experience(item.featureId)"
        >去体验 {{ MODULES[item.featureId].label }}
        <i class="ri-arrow-right-up-line" aria-hidden="true"></i
      ></button>
    </article>
    <p v-if="!edition.highlights.length">本次版本暂未附带详细说明。</p>
    <ExplorerPagination
      v-if="edition.highlights.length > 3"
      v-model:page="page"
      :total="edition.highlights.length"
      :size="3"
    />
  </div>
</template>
<style scoped>
article {
  padding: 20px 0;
  border-bottom: 1px solid var(--wao-line);
  overflow-wrap: anywhere;
}
.edition-eyebrow {
  color: var(--wao-primary-text);
  font-size: 11px;
}
h3 {
  font-size: 15px;
  margin: 6px 0 8px;
}
p,
ul {
  color: var(--wao-text-3);
  font-size: 13px;
  line-height: 1.8;
}
ul {
  padding-left: 20px;
}
li {
  margin-bottom: 5px;
}
button {
  border:0; background:transparent; padding:0; cursor:pointer;
  display: inline-flex;
  gap: 5px;
  color: var(--wao-primary-text);
  font-size: 12px;
}
</style>
