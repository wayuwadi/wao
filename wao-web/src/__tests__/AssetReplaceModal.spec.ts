import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AssetReplaceModal from '@/components/files/AssetReplaceModal.vue'
import { useDataStore } from '@/stores/data'

function setup(kind: 'file' | 'html' = 'file', revision?: number) {
  const pinia = createPinia()
  const data = useDataStore(pinia)
  const wrapper = mount(AssetReplaceModal, {
    props: { target: { kind, id: 'original-id', name: '原名称', url: '/api/pages/original', revision } },
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
    await wrapper.get('.settings-dialog-mask').trigger('click')
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
    await wrapper.get('.replace-actions button').trigger('click')
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(wrapper.findAll('[role="dialog"]')).toHaveLength(2)
    await wrapper.get('.confirmation-actions button').trigger('click')
    expect(wrapper.findAll('[role="dialog"]')).toHaveLength(1)
    expect(wrapper.get('.file-picker').text()).toContain('新版.html')
    await wrapper.get('.replace-actions button').trigger('click')
    await wrapper.get('.confirmation-actions .wao-button--danger').trigger('click')
    await flushPromises()
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

  it('目标切换后清空旧文件，旧保存回执不能关闭新的替换窗口', async () => {
    const { wrapper, data } = setup()
    let finish!: (value: never) => void
    vi.spyOn(data, 'replaceFile').mockImplementation(() => new Promise(resolve => { finish = resolve }))
    await wrapper.get('.file-picker').trigger('drop', { dataTransfer: { files: [html] } })
    await wrapper.get('form').trigger('submit')
    await wrapper.setProps({ target: { kind: 'file', id: 'next-file', name: '下一文件', url: '/api/files/next-file/download' } })
    expect(wrapper.get('.file-picker').text()).not.toContain(html.name)
    finish({} as never)
    await flushPromises()
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('一次拖入多个替换文件明确拒绝，不静默使用其中一项', async () => {
    const { wrapper, data } = setup()
    const replace = vi.spyOn(data, 'replaceFile')
    await wrapper.get('.file-picker').trigger('drop', { dataTransfer: { files: [html, new File(['b'], 'second.txt')] } })
    expect(wrapper.get('[role="alert"]').text()).toContain('每次只能替换一个文件')
    await wrapper.get('form').trigger('submit')
    expect(replace).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('HTML 替换携带版本零；连续冲突保留文件并逐次要求明确确认', async () => {
    const { wrapper, data } = setup('html', 0)
    const conflict = Object.assign(new Error('网页已更新'), { status: 409 })
    const replace = vi.spyOn(data, 'replaceHostedPage')
      .mockRejectedValueOnce(conflict).mockRejectedValueOnce(conflict).mockResolvedValueOnce({} as never)
    let revision = 0
    vi.spyOn(data, 'refreshHostedPages').mockImplementation(async () => {
      data.hostedPages = [{ id: 'original-id', revision: ++revision } as never]
      return true
    })
    await wrapper.get('.file-picker').trigger('drop', { dataTransfer: { files: [html] } })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(replace).toHaveBeenLastCalledWith('original-id', html, 0)
    expect(wrapper.get('.file-picker').text()).toContain(html.name)
    expect(wrapper.get('.primary').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(replace).toHaveBeenCalledTimes(1)
    for (const expected of [1, 2]) {
      await wrapper.findAll('.replace-conflict button').find(button => button.text() === '仍替换')!.trigger('click')
      await flushPromises()
      expect(replace).toHaveBeenLastCalledWith('original-id', html, expected)
    }
    expect(wrapper.emitted('saved')).toHaveLength(1)
    wrapper.unmount()
  })

  it('冲突读取失败不能盲目替换，重新读取后可明确放弃', async () => {
    const { wrapper, data } = setup('html', 4)
    const replace = vi.spyOn(data, 'replaceHostedPage').mockRejectedValue(Object.assign(new Error('冲突'), { status: 409 }))
    const refresh = vi.spyOn(data, 'refreshHostedPages').mockResolvedValueOnce(false).mockImplementationOnce(async () => {
      data.hostedPages = [{ id: 'original-id', revision: 6 } as never]
      return true
    })
    await wrapper.get('.file-picker').trigger('drop', { dataTransfer: { files: [html] } })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('最新网页版本读取失败')
    expect(wrapper.text()).not.toContain('仍替换')
    await wrapper.get('form').trigger('submit')
    expect(replace).toHaveBeenCalledTimes(1)
    await wrapper.findAll('button').find(button => button.text() === '重新读取最新版本')!.trigger('click')
    await flushPromises()
    expect(refresh).toHaveBeenCalledTimes(2)
    await wrapper.findAll('button').find(button => button.text() === '放弃本次替换')!.trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(replace).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('冲突刷新迟到回执不能污染下一替换目标', async () => {
    const { wrapper, data } = setup('html', 0)
    vi.spyOn(data, 'replaceHostedPage').mockRejectedValue(Object.assign(new Error('冲突'), { status: 409 }))
    let finish!: (value: boolean) => void
    vi.spyOn(data, 'refreshHostedPages').mockImplementation(() => new Promise(resolve => { finish = resolve }))
    await wrapper.get('.file-picker').trigger('drop', { dataTransfer: { files: [html] } })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.setProps({ target: { kind: 'html', id: 'new-id', name: '下一网页', url: '/next', revision: 7 } })
    finish(false)
    await flushPromises()
    expect(wrapper.find('.replace-conflict').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.emitted('close')).toBeUndefined()
    wrapper.unmount()
  })
})
