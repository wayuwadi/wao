import { computed, ref, watch, type ComputedRef, type Ref, type WatchSource } from 'vue'

/** Paginate a result set that is already available on the client. */
export function useClientPagination<T>(items: ComputedRef<T[]>, resetOn: WatchSource<unknown>[], pageSize = 10, options: { page?: Ref<number>; ready?: () => boolean } = {}) {
  const page = options.page ?? ref(1)
  const ready = options.ready ?? (() => true)
  const pageCount = computed(() => Math.max(1, Math.ceil(items.value.length / pageSize)))
  const visible = computed(() => items.value.slice((page.value - 1) * pageSize, page.value * pageSize))

  watch([...resetOn, ready], (values, previous) => {
    if (values[values.length - 1] && previous[previous.length - 1] && values.slice(0, -1).some((value, index) => value !== previous[index])) page.value = 1
  })
  watch([pageCount, ready], ([count, loaded]) => { if (loaded && page.value > count) page.value = count }, { immediate: true })

  return { page, pageCount, visible }
}
