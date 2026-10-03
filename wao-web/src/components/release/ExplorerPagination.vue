<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
const props = defineProps<{ page: number; total: number; size: number }>()
const emit = defineEmits<{ 'update:page': [page: number] }>()
const count = computed(() => Math.max(1, Math.ceil(props.total / props.size)))
const jump = ref(props.page)
const pagination = ref<HTMLElement>()
watch(
  () => props.page,
  (value) => {
    jump.value = value
  },
)
const pages = computed(() => {
  if (count.value <= 7) return Array.from({ length: count.value }, (_, index) => index + 1)
  const values = new Set([1, count.value, props.page - 1, props.page, props.page + 1])
  const result: (number | string)[] = []
  for (const value of [...values].filter((n) => n > 0 && n <= count.value).sort((a, b) => a - b)) {
    const previous = result[result.length - 1]
    if (typeof previous === 'number' && value - previous > 1) result.push(`gap-${value}`)
    result.push(value)
  }
  return result
})
function go(value: number) {
  const page = Number.isFinite(value)
    ? Math.min(count.value, Math.max(1, Math.trunc(value)))
    : props.page
  jump.value = page
  emit('update:page', page)
  void nextTick(() => {
    const body = pagination.value?.closest<HTMLElement>('.ant-modal-body')
    if (body) body.scrollTop = 0
    else
      pagination.value
        ?.closest('.features-page')
        ?.querySelector('.explorer-tabs')
        ?.scrollIntoView?.({ block: 'nearest' })
  })
}
</script>
<template>
  <nav ref="pagination" class="explorer-pagination" aria-label="分页">
    <span role="status"
      >第 {{ total ? (page - 1) * size + 1 : 0 }}–{{ Math.min(page * size, total) }} 项 / 共
      {{ total }} 项</span
    >
    <div class="page-controls">
      <button type="button" aria-label="上一页" :disabled="page <= 1" @click="go(page - 1)">
        ‹
      </button>
      <template v-for="item in pages" :key="item">
        <button
          v-if="typeof item === 'number'"
          type="button"
          :aria-label="`第 ${item} 页`"
          :aria-current="item === page ? 'page' : undefined"
          @click="go(item)"
        >
          {{ item }}
        </button>
        <span v-else aria-hidden="true">…</span>
      </template>
      <button type="button" aria-label="下一页" :disabled="page >= count" @click="go(page + 1)">
        ›
      </button>
    </div>
    <form v-if="count > 7" class="page-jump" @submit.prevent="go(Number(jump))">
      <label
        >跳至
        <input v-model="jump" aria-label="跳转页码" type="number" min="1" :max="count" required />
        页</label
      ><button type="submit">前往</button>
    </form>
  </nav>
</template>
<style scoped>
.explorer-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  border-top: 1px solid var(--wao-line);
  margin-top: 22px;
  padding-top: 16px;
  color: var(--wao-text-weak);
  font-size: 12px;
}
.page-controls,
.page-jump {
  display: flex;
  align-items: center;
  gap: 5px;
}
button {
  min-width: 32px;
  min-height: 32px;
  padding: 4px 8px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  cursor: pointer;
}
button[aria-current='page'] {
  background: var(--wao-primary);
  color: #fff;
  border-color: var(--wao-primary);
}
button:disabled {
  opacity: 0.35;
  cursor: default;
}
input {
  width: 62px;
  padding: 5px;
  border: 1px solid var(--wao-line);
  border-radius: 6px;
  color: var(--wao-text);
  background: var(--wao-bg-card);
}
button:focus-visible,
input:focus-visible {
  outline: 2px solid var(--wao-primary);
  outline-offset: 2px;
}
@media (pointer: coarse) {
  button {
    min-height: 44px;
  }
  input {
    font-size: 16px;
  }
}
</style>
