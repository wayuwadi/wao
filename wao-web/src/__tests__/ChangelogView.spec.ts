import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ChangelogView from '@/views/ChangelogView.vue'
import { fetchChangelog } from '@/api/changelog'
import { mockChangelogEntries } from '@/mocks/changelog'

vi.mock('@/api/changelog', () => ({ fetchChangelog: vi.fn() }))

function render() {
  return mount(ChangelogView, {
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

describe('更新日志示例预览', () => {
  beforeEach(() => {
    vi.mocked(fetchChangelog).mockReset().mockResolvedValue(structuredClone(mockChangelogEntries))
    vi.stubGlobal(
      'matchMedia',
      vi.fn().mockReturnValue({ matches: false, addListener: vi.fn(), removeListener: vi.fn() }),
    )
  })

  it('展示跨月时间线和示例标识，详情可访问', async () => {
    const wrapper = render()
    await flushPromises()
    expect(wrapper.findAll('.change-card')).toHaveLength(6)
    expect(wrapper.findAll('.month-heading').map((item) => item.text())).toEqual([
      '2026 年 9 月5 条更新',
      '2026 年 8 月1 条更新',
    ])
    expect(wrapper.text()).toContain('示例预览')
    expect(wrapper.find('details summary').text()).toContain('查看详情')
    expect(wrapper.find('details p').text()).toContain('此条内容用于演示')
    wrapper.unmount()
  })

  it('分类、版本、搜索组合筛选，无结果时能恢复全部', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper
      .findAll('.filters button')
      .find((button) => button.text() === 'AI能力')!
      .trigger('click')
    expect(wrapper.findAll('.change-card')).toHaveLength(2)
    await wrapper.find('.versions button').trigger('click')
    expect(wrapper.findAll('.change-card')).toHaveLength(1)
    await wrapper.find('input').setValue('不存在的功能')
    expect(wrapper.findAll('.change-card')).toHaveLength(0)
    expect(wrapper.text()).toContain('没有找到匹配的更新')
    await wrapper.find('.results-meta button').trigger('click')
    expect(wrapper.findAll('.change-card')).toHaveLength(6)
    expect(wrapper.find('input').element.value).toBe('')
    expect(wrapper.find('.versions button').attributes('aria-pressed')).toBe('false')
    wrapper.unmount()
  })

  it('订阅只演示本页状态，取消及重新进入均不冒充真实订阅', async () => {
    const wrapper = render()
    await flushPromises()
    const button = wrapper.find('.subscription button')
    await button.trigger('click')
    expect(button.attributes('aria-pressed')).toBe('true')
    expect(wrapper.find('.subscription-status').text()).toContain('不会发送通知')
    await button.trigger('click')
    expect(button.attributes('aria-pressed')).toBe('false')
    wrapper.unmount()
  })

  it('读取失败提供重试，真实空结果不填充示例', async () => {
    vi.mocked(fetchChangelog).mockRejectedValueOnce(new Error('读取失败')).mockResolvedValueOnce([])
    const wrapper = render()
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('读取失败')
    expect(wrapper.findAll('.change-card')).toHaveLength(0)
    await wrapper.find('.error button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('还没有可展示的更新')
    expect(wrapper.text()).toContain('暂无版本')
    expect(wrapper.findAll('.change-card')).toHaveLength(0)
    wrapper.unmount()
  })
})
