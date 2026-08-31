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

  it('把翻页导航和数量跳转分成稳定的移动端行组', () => {
    const wrapper = mount(WaoPagination, { props: { page: 5, pageCount: 10, total: 100 } })
    const navigation = wrapper.get('.wao-pagination__navigation')
    const meta = wrapper.get('.wao-pagination__meta')

    expect(navigation.find('[aria-label="上一页"]').exists()).toBe(true)
    expect(navigation.get('[aria-label="页码"]').text()).toBe('1…456…10')
    expect(navigation.find('[aria-label="下一页"]').exists()).toBe(true)
    expect(meta.text()).toContain('共 100 条')
    expect(meta.get('[aria-label="要跳转的页数"]').element).toBeInstanceOf(HTMLInputElement)
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
