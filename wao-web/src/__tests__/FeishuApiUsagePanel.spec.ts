import { nextTick } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import FeishuApiUsagePanel from '@/components/FeishuApiUsagePanel.vue'
const fetchUsage = vi.hoisted(() => vi.fn())
vi.mock('@/api/system', () => ({ fetchFeishuApiUsage: fetchUsage }))
describe('飞书调用统计', () => {
  beforeEach(() => fetchUsage.mockReset())
  it('显示真实排名、统计边界且刷新失败保留已有数据供重试', async () => {
    fetchUsage.mockResolvedValueOnce([{ endpoint: 'GET /open-apis/im/v1/messages', requests: 12, firstObservedAt: '2026-09-10T12:00:00', lastObservedAt: '2026-09-10T12:01:00' }])
    const wrapper = mount(FeishuApiUsagePanel)
    await nextTick()
    expect(wrapper.text()).toContain('正在读取')
    await flushPromises()
    expect(wrapper.text()).toContain('12 次出站尝试')
    expect(wrapper.text()).toContain('100.0%')
    expect(wrapper.text()).toContain('不含飞书登录、Webhook 和其他应用')
    fetchUsage.mockRejectedValueOnce(new Error('连接中断'))
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('连接中断')
    expect(wrapper.get('tbody').text()).toContain('messages')
    fetchUsage.mockResolvedValueOnce([])
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('尚无已记录请求')
    wrapper.unmount()
  })
})
