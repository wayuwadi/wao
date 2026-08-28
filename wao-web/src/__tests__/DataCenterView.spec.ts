import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DataCenterView from '@/views/DataCenterView.vue'
import { fetchDataCenterOverview, type DataCenterOverview } from '@/api/dataCenter'

vi.mock('@/api/dataCenter', () => ({
  fetchDataCenterOverview: vi.fn<(typeof import('@/api/dataCenter'))['fetchDataCenterOverview']>(),
}))

const overview: DataCenterOverview = {
  generatedAt: '2026-08-28T10:00:00',
  sourceCount: 3,
  populatedSourceCount: 2,
  totalRecords: 17,
  sources: [
    {
      id: 'tasks',
      label: '任务',
      category: '协作',
      description: '真实任务',
      recordUnit: '条',
      recordCount: 15,
      latestAt: '2026-08-28T09:30:00',
      status: 'READY',
      coverage: 'FULL_RECORDS',
      boundary: '不返回任务正文。',
    },
    {
      id: 'git-repositories',
      label: '项目 Git 源',
      category: '外部来源',
      description: '仓库登记',
      recordUnit: '个',
      recordCount: 2,
      latestAt: '2026-08-27T18:00:00',
      status: 'REGISTERED_ONLY',
      coverage: 'REGISTRY_ONLY',
      boundary: '未采集 commit、作者、diff 或提交时间。',
    },
    {
      id: 'shared-chat-messages',
      label: '共享聊天',
      category: '沟通',
      description: '真实聊天',
      recordUnit: '条',
      recordCount: 0,
      latestAt: null,
      status: 'EMPTY',
      coverage: 'FULL_RECORDS',
      boundary: '不返回正文。',
    },
  ],
}

describe('DataCenterView', () => {
  beforeEach(() => {
    vi.mocked(fetchDataCenterOverview).mockReset().mockResolvedValue(overview)
  })

  it('展示真实记录量和 Git 仅登记边界', async () => {
    const wrapper = mount(DataCenterView)
    await flushPromises()
    expect(wrapper.text()).toContain('目录内记录总量')
    expect(wrapper.text()).toContain('15 条')
    expect(wrapper.text()).toContain('Git 数据当前只登记来源')
    expect(wrapper.text()).toContain('不会生成贡献频率和业务方向等伪统计')
    expect(wrapper.text()).toContain('仅登记')
  })

  it('按分类筛选且不丢失真实空数据源', async () => {
    const wrapper = mount(DataCenterView)
    await flushPromises()
    await wrapper
      .get('.filters')
      .findAll('button')
      .find((button) => button.text() === '沟通')!
      .trigger('click')
    expect(wrapper.text()).toContain('共享聊天')
    expect(wrapper.text()).toContain('暂无数据')
    expect(wrapper.text()).not.toContain('15 条')
  })

  it('全空时明确不填演示数字', async () => {
    vi.mocked(fetchDataCenterOverview).mockResolvedValue({
      ...overview,
      populatedSourceCount: 0,
      totalRecords: 0,
      sources: overview.sources.map((source) => ({
        ...source,
        recordCount: 0,
        latestAt: null,
        status: 'EMPTY',
      })),
    })
    const wrapper = mount(DataCenterView)
    await flushPromises()
    expect(wrapper.text()).toContain('还没有可统计记录')
    expect(wrapper.text()).toContain('不会填充演示数字')
  })

  it('加载失败时提供原地重试', async () => {
    vi.mocked(fetchDataCenterOverview)
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(overview)
    const wrapper = mount(DataCenterView)
    await flushPromises()
    expect(wrapper.text()).toContain('数据目录读取失败')
    await wrapper.get('.state button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('数据中心')
    expect(wrapper.text()).toContain('15 条')
  })
})
