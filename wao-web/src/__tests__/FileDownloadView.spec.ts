import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import FileDownloadView from '@/views/FileDownloadView.vue'
import { apiDownloadChallenge, fetchFileMeta } from '@/api/board'

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: 'file-1' } }) }))
vi.mock('@/api/board', () => ({
  fetchFileMeta: vi.fn<(typeof import('@/api/board'))['fetchFileMeta']>(),
  apiDownloadChallenge: vi.fn<(typeof import('@/api/board'))['apiDownloadChallenge']>(),
  apiDownloadTicket: vi.fn<(typeof import('@/api/board'))['apiDownloadTicket']>(),
}))

describe('FileDownloadView', () => {
  it('关闭算术验证码时仍要求人工点击并说明一次性票据', async () => {
    vi.mocked(fetchFileMeta).mockResolvedValue({
      id: 'file-1',
      name: '验收.zip',
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
  })
})
