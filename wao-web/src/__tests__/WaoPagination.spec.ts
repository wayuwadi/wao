import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WaoPagination from '@/components/ui/WaoPagination.vue'

describe('WaoPagination', () => {
  it('长列表展示首三页、省略号和末页', () => {
    const wrapper = mount(WaoPagination, { props: { page: 1, pageCount: 10, total: 100 } })

    expect(wrapper.find('.wao-pagination__pages').text()).toBe('123…10')
    expect(wrapper.text()).toContain('共 100 条')
    expect(wrapper.get('[aria-current="page"]').text()).toBe('1')
  })

  it('可以通过页码和指定页输入跳转并把越界值收口到末页', async () => {
    const wrapper = mount(WaoPagination, { props: { page: 1, pageCount: 10 } })

    await wrapper.get('[aria-label="第 3 页"]').trigger('click')
    expect(wrapper.emitted('update:page')?.[0]).toEqual([3])

    await wrapper.get('[aria-label="要跳转的页数"]').setValue('99')
    await wrapper.get('.wao-pagination__jump button').trigger('click')
    expect(wrapper.emitted('update:page')?.[1]).toEqual([10])
  })
})
