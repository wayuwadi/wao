<script setup lang="ts">
import { computed, ref, watch } from 'vue'

type PageItem = number | 'ellipsis-left' | 'ellipsis-right'

const props = withDefaults(
  defineProps<{
    page: number
    pageCount: number
    total?: number
    disabled?: boolean
    ariaLabel?: string
  }>(),
  {
    total: undefined,
    disabled: false,
    ariaLabel: '分页导航',
  },
)

const emit = defineEmits<{
  'update:page': [page: number]
}>()

const jumpPage = ref(String(props.page))
const normalizedPageCount = computed(() => Math.max(1, Math.floor(props.pageCount)))

const pageItems = computed<PageItem[]>(() => {
  const total = normalizedPageCount.value
  if (total <= 7) return Array.from({ length: total }, (_, index) => index + 1)

  const current = Math.min(total, Math.max(1, props.page))
  if (current <= 3) return [1, 2, 3, 'ellipsis-right', total]
  if (current >= total - 2) return [1, 'ellipsis-left', total - 2, total - 1, total]
  return [1, 'ellipsis-left', current - 1, current, current + 1, 'ellipsis-right', total]
})

watch(
  () => props.page,
  (page) => {
    jumpPage.value = String(page)
  },
)

function changePage(page: number) {
  if (props.disabled) return
  const next = Math.min(normalizedPageCount.value, Math.max(1, Math.floor(page)))
  jumpPage.value = String(next)
  if (next !== props.page) emit('update:page', next)
}

function jump() {
  const requested = Number(jumpPage.value)
  if (!Number.isFinite(requested)) {
    jumpPage.value = String(props.page)
    return
  }
  changePage(requested)
}
</script>

<template>
  <nav class="wao-pagination" :aria-label="ariaLabel">
    <div class="wao-pagination__navigation">
      <button
        type="button"
        class="wao-pagination__direction"
        :disabled="disabled || page <= 1"
        aria-label="上一页"
        @click="changePage(page - 1)"
      >
        <i class="ri-arrow-left-line"></i><span>上一页</span>
      </button>

      <div class="wao-pagination__pages" aria-label="页码">
        <template v-for="item in pageItems" :key="item">
          <span
            v-if="typeof item !== 'number'"
            class="wao-pagination__ellipsis"
            aria-hidden="true"
            >…</span
          >
          <button
            v-else
            type="button"
            class="wao-pagination__page"
            :class="{ active: item === page }"
            :disabled="disabled"
            :aria-current="item === page ? 'page' : undefined"
            :aria-label="`第 ${item} 页`"
            @click="changePage(item)"
          >
            {{ item }}
          </button>
        </template>
      </div>

      <button
        type="button"
        class="wao-pagination__direction"
        :disabled="disabled || page >= normalizedPageCount"
        aria-label="下一页"
        @click="changePage(page + 1)"
      >
        <span>下一页</span><i class="ri-arrow-right-line"></i>
      </button>
    </div>

    <div class="wao-pagination__meta">
      <span v-if="total !== undefined" class="wao-pagination__total">共 {{ total }} 条</span>
      <label class="wao-pagination__jump">
        <span>跳至</span>
        <input
          v-model="jumpPage"
          type="number"
          inputmode="numeric"
          min="1"
          :max="normalizedPageCount"
          :disabled="disabled"
          aria-label="要跳转的页数"
          @keydown.enter.prevent="jump"
        />
        <span>页</span>
        <button type="button" :disabled="disabled" @click="jump">跳转</button>
      </label>
    </div>
  </nav>
</template>

<style scoped>
.wao-pagination {
  min-height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 6px;
  color: var(--wao-text-weak);
  font-size: 11px;
}
.wao-pagination__navigation,
.wao-pagination__meta {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.wao-pagination button,
.wao-pagination input {
  height: 29px;
  border: 1px solid var(--wao-line-control);
  border-radius: 7px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
}
.wao-pagination button {
  padding: 0 9px;
  cursor: pointer;
}
.wao-pagination button:hover:not(:disabled),
.wao-pagination__page.active {
  border-color: var(--wao-primary-line);
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.wao-pagination button:disabled,
.wao-pagination input:disabled {
  opacity: 0.45;
  cursor: default;
}
.wao-pagination__direction {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.wao-pagination__pages {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.wao-pagination__page {
  min-width: 29px;
  padding: 0 6px !important;
}
.wao-pagination__page.active {
  font-weight: 700;
}
.wao-pagination__ellipsis {
  min-width: 18px;
  text-align: center;
}
.wao-pagination__total {
  margin-left: 3px;
  white-space: nowrap;
}
.wao-pagination__jump {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}
.wao-pagination__jump input {
  width: 52px;
  padding: 0 5px;
  text-align: center;
}
.wao-pagination__jump button {
  padding: 0 8px;
}
@media (max-width: 560px) {
  .wao-pagination {
    width: 100%;
    max-width: 100%;
    box-sizing: border-box;
    flex: 1 0 100%;
    flex-direction: column;
    align-items: stretch;
    justify-content: center;
    gap: 8px;
  }
  .wao-pagination__navigation {
    width: 100%;
    display: grid;
    grid-template-columns: 29px minmax(0, 1fr) 29px;
    gap: 4px;
  }
  .wao-pagination__pages {
    min-width: 0;
    justify-content: center;
    gap: 2px;
  }
  .wao-pagination__direction span {
    display: none;
  }
  .wao-pagination__direction {
    justify-content: center;
    padding: 0 !important;
  }
  .wao-pagination__page {
    min-width: 27px;
  }
  .wao-pagination__meta {
    width: 100%;
    justify-content: space-between;
  }
  .wao-pagination__jump {
    margin-left: auto;
  }
}
</style>
