import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import FileDownloadView from '@/views/FileDownloadView.vue'
import { apiDownloadChallenge, apiDownloadTicket, fetchFileMeta, fetchPublicFilePreview } from '@/api/board'

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: 'file-1' } }) }))
vi.mock('@/api/board', () => ({
  fetchFileMeta: vi.fn<(typeof import('@/api/board'))['fetchFileMeta']>(),
  apiDownloadChallenge: vi.fn<(typeof import('@/api/board'))['apiDownloadChallenge']>(),
  apiDownloadTicket: vi.fn<(typeof import('@/api/board'))['apiDownloadTicket']>(),
  fetchPublicFilePreview: vi.fn(),
}))

describe('FileDownloadView', () => {
  it('预览需先消费票据，并净化 Markdown 内的脚本', async () => {
    vi.mocked(fetchFileMeta).mockResolvedValue({ id: 'file-1', name: '示例.md', size: '2 KB', icon: '', time: '', by: '', link: '' })
    vi.mocked(apiDownloadChallenge).mockResolvedValue({ challengeId: 'c', question: '', captchaEnabled: false })
    vi.mocked(apiDownloadTicket).mockResolvedValue({ ticket: 'one-use' })
    vi.mocked(fetchPublicFilePreview).mockResolvedValue({ content: '# 标题\n<script>alert(1)</script>' })
    const wrapper = mount(FileDownloadView)
    await flushPromises()
    expect(fetchPublicFilePreview).not.toHaveBeenCalled()
    await wrapper.findAll('button').find(button => button.text() === '预览 Markdown')!.trigger('click')
    await flushPromises()
    expect(fetchPublicFilePreview).toHaveBeenCalledWith('file-1', 'one-use')
    expect(wrapper.get('.markdown-preview').text()).toContain('标题')
    expect(wrapper.find('.markdown-preview script').exists()).toBe(false)
    wrapper.unmount()
  })
  it('关闭算术验证码时仍要求人工点击并说明一次性票据', async () => {
    vi.mocked(fetchFileMeta).mockResolvedValue({
      id: 'file-1',
      name: '验收.zip',
      displayName: '上线验收材料',
      size: '2 MB',
      icon: 'ri-file-zip-line',
      time: '刚刚',
      by: 'wg',
      link: '',
    })
    vi.mocked(apiDownloadChallenge).mockResolvedValue({
      challengeId: 'challenge-1',
      question: '',
      captchaEnabled: false,
    })

    const wrapper = mount(FileDownloadView)
    await flushPromises()

    expect(wrapper.find('.captcha').exists()).toBe(false)
    expect(wrapper.find('.btn-primary').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).toContain('一次性票据')
    expect(wrapper.text()).toContain('上线验收材料')
    expect(wrapper.text()).toContain('原文件：验收.zip')
  })
})
