import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import FileDisplayNameEditor from '@/components/files/FileDisplayNameEditor.vue'
import { useDataStore } from '@/stores/data'

const file = {
  id: 'f1',
  name: 'search_recommendation_product_architecture.html',
  displayName: null,
  size: '2 KB',
  icon: 'ri-file-line',
  time: '刚刚',
  by: 'wg',
  link: '/api/files/f1/download',
}

describe('FileDisplayNameEditor', () => {
  it('保存展示名后保留并提示原文件名', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    const rename = vi.spyOn(data, 'renameFileDisplayName').mockImplementation(async (_, name) => ({
      ...file,
      displayName: name,
    }))
    const wrapper = mount(FileDisplayNameEditor, {
      props: { file: { ...file } },
      global: { plugins: [pinia] },
    })

    await wrapper.find('[title="修改 WAO 展示名称"]').trigger('click')
    await wrapper.find('input').setValue('  商品推荐架构  ')
    await wrapper.find('.rename-row button').trigger('click')
    await wrapper.setProps({ file: { ...file, displayName: '商品推荐架构' } })
    await flushPromises()

    expect(rename).toHaveBeenCalledWith('f1', '商品推荐架构')
    expect(wrapper.text()).toContain('商品推荐架构')
    expect(wrapper.text()).toContain('原文件：search_recommendation_product_architecture.html')
    expect(wrapper.text()).toContain('已保存')
  })

  it('空名称不提交，保存失败时保留输入并提供重试', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    const rename = vi
      .spyOn(data, 'renameFileDisplayName')
      .mockRejectedValueOnce(new Error('服务暂不可用'))
      .mockResolvedValueOnce({ ...file, displayName: '新名称' })
    const wrapper = mount(FileDisplayNameEditor, {
      props: { file: { ...file } },
      global: { plugins: [pinia] },
    })

    await wrapper.find('[title="修改 WAO 展示名称"]').trigger('click')
    await wrapper.find('input').setValue('   ')
    await wrapper.find('.rename-row button').trigger('click')
    expect(rename).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('展示名称不能为空')

    await wrapper.find('input').setValue('新名称')
    await wrapper.find('.rename-row button').trigger('click')
    await flushPromises()
    expect(wrapper.find('input').exists()).toBe(true)
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('新名称')
    expect(wrapper.text()).toContain('服务暂不可用')
    expect(wrapper.find('.rename-row button').text()).toBe('重试')

    await wrapper.find('.rename-row button').trigger('click')
    await flushPromises()
    expect(rename).toHaveBeenCalledTimes(2)
  })
})
