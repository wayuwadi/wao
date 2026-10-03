import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import ExplorerPagination from '@/components/release/ExplorerPagination.vue'
it('大页数保持有限按钮，支持跳页和上下界', async () => {
  const wrapper = mount(ExplorerPagination, { props: { page: 1, total: 1107, size: 6 } })
  expect(wrapper.findAll('.page-controls button').length).toBeLessThanOrEqual(7)
  expect(wrapper.get('[aria-label="上一页"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[aria-label="第 185 页"]').trigger('click')
  expect(wrapper.emitted('update:page')?.slice(-1)[0]).toEqual([185])
  await wrapper.get('input').setValue('95')
  await wrapper.get('form').trigger('submit')
  expect(wrapper.emitted('update:page')?.slice(-1)[0]).toEqual([95])
  await wrapper.setProps({ page: 185 })
  expect(wrapper.text()).toContain('第 1105–1107 项')
  expect(wrapper.get('[aria-label="下一页"]').attributes('disabled')).toBeDefined()
  wrapper.unmount()
})
