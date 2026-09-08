import { afterEach, describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import InfrastructureView from '@/views/InfrastructureView.vue'
import { apiInfrastructureOverview } from '@/api/infrastructure'
vi.mock('@/api/infrastructure', () => ({ apiInfrastructureOverview: vi.fn() }))
vi.mock('@/components/infrastructure/ResourceHistory.vue', () => ({ default: { template: '<div>历史趋势</div>' } }))
const sample = () => ({ generatedAt: Date.now()/1000, intervalSeconds: 60, historyHours: 24, nodes: [
  { id: 'linux2', name: 'Linux2', role: '主节点', address: 'linux2', online: true, stale: false, collectedAt: Date.now()/1000,
    memory: { total: 1073741824, available: 536870912, swapTotal: 0, swapUsed: 0 }, disks: [], history: [],
    services: [{ name: 'jms_core', group: 'JumpServer', kind: 'docker', image: 'jumpserver/core:v4', state: 'running', status: 'Up', cpuPercent: 1, memoryBytes: 500 }] },
] })
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length=0; vi.resetAllMocks() })
function view() { const w=mount(InfrastructureView); wrappers.push(w); return w }
describe('基础设施页面状态', () => {
  it('显示真实节点和组件，搜索无结果时提供反馈', async () => {
    vi.mocked(apiInfrastructureOverview).mockResolvedValue(sample())
    const w=view(); await flushPromises()
    expect(w.text()).toContain('Linux2'); expect(w.text()).toContain('jms_core')
    await w.get('input').setValue('不存在')
    expect(w.text()).toContain('没有匹配的服务')
  })
  it('刷新失败保留快照并提供重试', async () => {
    vi.mocked(apiInfrastructureOverview).mockResolvedValueOnce(sample()).mockRejectedValue(new Error('连接失败'))
    const w=view(); await flushPromises()
    await w.get('.header-actions button').trigger('click'); await flushPromises()
    expect(w.text()).toContain('已有数据保留'); expect(w.text()).toContain('jms_core')
  })
  it('权限被收回后清除已显示数据', async () => {
    vi.mocked(apiInfrastructureOverview).mockResolvedValueOnce(sample()).mockRejectedValue({ response: { status: 403 } })
    const w=view(); await flushPromises()
    await w.get('.header-actions button').trigger('click'); await flushPromises()
    expect(w.text()).toContain('仅管理员'); expect(w.text()).not.toContain('jms_core')
  })
})
