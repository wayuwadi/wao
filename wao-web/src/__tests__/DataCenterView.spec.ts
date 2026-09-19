import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DataCenterView from '@/views/DataCenterView.vue'
import { fetchDataCenterOverview, type DataCenterOverview } from '@/api/dataCenter'

vi.mock('@/api/dataCenter', () => ({
  fetchDataCenterOverview: vi.fn<(typeof import('@/api/dataCenter'))['fetchDataCenterOverview']>(),
}))
function view() { return mount(DataCenterView, { global: { stubs: { RouterLink: RouterLinkStub } } }) }

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

  it('展示真实记录量和 Git 最小采集边界', async () => {
    const wrapper = view()
    await flushPromises()
    expect(wrapper.text()).toContain('目录内记录总量')
    expect(wrapper.text()).toContain('15 条')
    expect(wrapper.text()).toContain('Git 只采集成功交付所需的最小更新字段')
    expect(wrapper.text()).toContain('不会生成贡献排行等伪统计')
    expect(wrapper.text()).toContain('仅登记')
  })

  it('按分类筛选且不丢失真实空数据源', async () => {
    const wrapper = view()
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
    const wrapper = view()
    await flushPromises()
    expect(wrapper.text()).toContain('还没有可统计记录')
    expect(wrapper.text()).toContain('不会填充演示数字')
  })

  it('加载失败时提供原地重试', async () => {
    vi.mocked(fetchDataCenterOverview)
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(overview)
    const wrapper = view()
    await flushPromises()
    expect(wrapper.text()).toContain('数据目录读取失败')
    await wrapper.get('.state button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('数据中心')
    expect(wrapper.text()).toContain('15 条')
  })

  it('按状态查看并说明登记与正文边界，冲突筛选可清除', async () => {
    const wrapper = view(); await flushPromises()
    expect(wrapper.text()).toContain('不能作为完成量或质量评分')
    await wrapper.get('.status-filters').findAll('button').find(button => button.text().startsWith('仅登记'))!.trigger('click')
    expect(wrapper.findAll('.source-card')).toHaveLength(1)
    expect(wrapper.get('.source-card').text()).toContain('不能据此分析来源正文')
    await wrapper.get('.filters').findAll('button').find(button => button.text() === '沟通')!.trigger('click')
    expect(wrapper.text()).toContain('当前分类和状态下没有数据源')
    await wrapper.get('.state button').trigger('click')
    expect(wrapper.findAll('.source-card')).toHaveLength(3)
  })

  it('有记录不声称持续同步，链接指向既有功能', async () => {
    const wrapper = view(); await flushPromises()
    expect(wrapper.text()).toContain('不保证持续同步或数据完整')
    expect(wrapper.findAllComponents(RouterLinkStub).map(link => link.props('to'))).toContain('/todo')
    expect(wrapper.findAllComponents(RouterLinkStub).map(link => link.props('to'))).not.toContain('/git-repositories')
  })

  it('未知状态仍显示卡片和保守提示，不中断页面', async () => {
    vi.mocked(fetchDataCenterOverview).mockResolvedValue({ ...overview, sources: [{ ...overview.sources[0]!, status: 'FUTURE' as never }] })
    const wrapper = view(); await flushPromises()
    expect(wrapper.text()).toContain('状态待确认')
    expect(wrapper.text()).toContain('当前状态尚无法解释')
  })
})
