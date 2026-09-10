<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { functionDomain } from '@/config/navigationCatalog'
import { useNavStore } from '@/stores/nav'
import type { NavItemId } from '@/config/nav'
const route = useRoute()
const router = useRouter()
const nav = useNavStore()
defineProps<{ label: string }>()
const domain = computed(() => functionDomain(route.meta.module as NavItemId))
function open(event: Event) {
  const id = (event.target as HTMLSelectElement).value as NavItemId
  const meta = nav.itemMeta(id)
  if (meta?.path) {
    if (meta.openMode === 'NEW_PAGE')
      window.open(
        meta.newPagePath ?? router.resolve(meta.path).href,
        '_blank',
        'noopener,noreferrer',
      )
    else void router.push(meta.path)
  }
  ;(event.target as HTMLSelectElement).value = ''
}
</script>
<template>
  <div class="context-nav">
    <span>{{ label }}</span
    ><select v-if="domain" value="" :aria-label="'切换' + domain.label + '功能'" @change="open">
      <option value="" disabled>{{ domain.label }} · 切换</option>
      <option v-for="item in domain.items" :key="item.id" :value="item.id">
        {{ nav.itemMeta(item.id)?.label }}
      </option>
    </select>
  </div>
</template>
<style scoped>
.context-nav {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.context-nav > span {
  white-space: nowrap;
}
select {
  max-width: 155px;
  min-width: 65px;
  padding: 4px;
  border: 0;
  border-radius: 5px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
  font-size: 11px;
}
@media (max-width: 900px) {
  select {
    max-width: 100px;
  }
}
</style>
