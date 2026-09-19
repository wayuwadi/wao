import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AssetReplaceModal from '@/components/files/AssetReplaceModal.vue'
import { useDataStore } from '@/stores/data'

function setup(kind: 'file' | 'html' = 'file') {
  const pinia = createPinia()
  const data = useDataStore(pinia)
  const wrapper = mount(AssetReplaceModal, {
    props: { target: { kind, id: 'original-id', name: '原名称', url: '/api/pages/original' } },
    global: { plugins: [pinia], stubs: { teleport: true } },
  })
  return { wrapper, data }
}
const html = new File(['<h1>新版</h1>'], '新版.html', { type: 'text/html' })
afterEach(() => vi.restoreAllMocks())

describe('保持地址重新上传', () => {
  it('普通文件只替换指定 id，保存中阻止重复提交和关闭', async () => {
    const { wrapper, data } = setup()
    let finish!: (value: never) => void
    const replace = vi.spyOn(data, 'replaceFile').mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const file = new File(['new'], 'new.pdf', { type: 'application/pdf' })
    await wrapper.get('.file-picker').trigger('drop', { dataTransfer: { files: [file] } })
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('.replace-mask').trigger('click')
    expect(replace).toHaveBeenCalledExactlyOnceWith('original-id', file)
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(wrapper.get('[role="status"]').text()).toContain('上传保存中')
    finish({} as never)
    await flushPromises()
    expect(wrapper.emitted('saved')).toHaveLength(1)
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })
  it('HTML 失败保留文件，重试仅发送 id 和正文，不发送旧标题或后缀', async () => {
    const { wrapper, data } = setup('html')
    const replace = vi
      .spyOn(data, 'replaceHostedPage')
      .mockRejectedValueOnce(new Error('网络中断'))
      .mockResolvedValueOnce({} as never)
    await wrapper.get('.file-picker').trigger('drop', { dataTransfer: { files: [html] } })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toBe('网络中断')
    expect(wrapper.get('.file-picker').text()).toContain('新版.html')
    expect(wrapper.get('.primary').text()).toBe('重试')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(replace).toHaveBeenNthCalledWith(2, 'original-id', html)
    expect(wrapper.emitted('saved')).toHaveLength(1)
    wrapper.unmount()
  })
  it('空选择、错误类型和超限不请求保存，取消保留选择需确认', async () => {
    const { wrapper, data } = setup('html')
    const replace = vi.spyOn(data, 'replaceHostedPage')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('[role="alert"]').text()).toContain('请先选择')
    for (const file of [
      new File([], 'empty.html'),
      new File(['bad'], 'bad.pdf'),
      new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'huge.html'),
    ]) {
      await wrapper.get('.file-picker').trigger('drop', { dataTransfer: { files: [file] } })
      expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    }
    expect(replace).not.toHaveBeenCalled()
    await wrapper.get('.file-picker').trigger('drop', { dataTransfer: { files: [html] } })
    vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    await wrapper.get('footer button').trigger('click')
    expect(wrapper.emitted('close')).toBeUndefined()
    await wrapper.get('footer button').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })
  it('网络故障显示可操作的中文提示并保留文件', async () => {
    const { wrapper, data } = setup()
    vi.spyOn(data, 'replaceFile').mockRejectedValue(
      new Error('Request failed with status code 502'),
    )
    await wrapper.get('.file-picker').trigger('drop', { dataTransfer: { files: [html] } })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('已保留所选文件')
    expect(wrapper.get('.primary').text()).toBe('重试')
    wrapper.unmount()
  })
})
