import { computed, ref, watch, type ComputedRef, type WatchSource } from 'vue'

/** Paginate a result set that is already available on the client. */
export function useClientPagination<T>(items: ComputedRef<T[]>, resetOn: WatchSource<unknown>[], pageSize = 10) {
  const page = ref(1)
  const pageCount = computed(() => Math.max(1, Math.ceil(items.value.length / pageSize)))
  const visible = computed(() => items.value.slice((page.value - 1) * pageSize, page.value * pageSize))

  watch(resetOn, () => { page.value = 1 })
  watch(pageCount, (count) => { if (page.value > count) page.value = count })

  return { page, pageCount, visible }
}
