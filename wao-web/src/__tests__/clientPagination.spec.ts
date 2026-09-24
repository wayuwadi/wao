import { computed, nextTick, ref } from 'vue'
import { describe, expect, it } from 'vitest'
import { useClientPagination } from '@/composables/clientPagination'

describe('client result pagination', () => {
  it('resets after a new query and clamps the current page after records disappear', async () => {
    const all = ref(Array.from({ length: 25 }, (_, index) => index + 1))
    const query = ref('')
    const results = computed(() => all.value.filter((number) => !query.value || String(number).includes(query.value)))
    const { page, pageCount, visible } = useClientPagination(results, [query])

    page.value = 3
    expect(visible.value).toEqual([21, 22, 23, 24, 25])

    query.value = '2'
    await nextTick()
    expect(page.value).toBe(1)
    expect(visible.value).toEqual([2, 12, 20, 21, 22, 23, 24, 25])

    query.value = ''
    await nextTick()
    page.value = 3
    all.value = all.value.slice(0, 11)
    await nextTick()
    expect(pageCount.value).toBe(2)
    expect(page.value).toBe(2)
    expect(visible.value).toEqual([11])
  })
})
