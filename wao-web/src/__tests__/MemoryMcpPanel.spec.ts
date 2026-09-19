import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MemoryMcpPanel from '@/components/memory/MemoryMcpPanel.vue'
import { createMemoryAccessToken, fetchMemoryAccessTokens, fetchMemoryGrants,
  revokeMemoryAccessToken, saveMemoryGrants } from '@/api/memory'

vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ org: { id: 'o1', name: 'WAO' } }) }))
vi.mock('@/api/board', () => ({ fetchProjects: vi.fn().mockResolvedValue([{ id: 'p1', name: 'WAO 项目' }]) }))
vi.mock('@/api/memory', () => ({
  fetchMemoryAccessTokens: vi.fn(), createMemoryAccessToken: vi.fn(), revokeMemoryAccessToken: vi.fn(),
  fetchMemoryGrants: vi.fn(), saveMemoryGrants: vi.fn(),
}))

describe('MemoryMcpPanel', () => {
  beforeEach(() => { vi.mocked(fetchMemoryAccessTokens).mockResolvedValue([]); vi.mocked(fetchMemoryGrants).mockResolvedValue([]) })
  afterEach(() => vi.clearAllMocks())

  it('展示权威源边界和个人上下文 MCP 命令', async () => {
    const wrapper = mount(MemoryMcpPanel); await flushPromises()
    expect(wrapper.text()).toContain('WAO 是跨设备长期记忆的唯一权威源')
    expect(wrapper.text()).toContain('组织和项目域只提交审核候选')
    expect(wrapper.text()).toContain('/api/mcp/memory/personal')
    expect(wrapper.text()).toContain('云端未落盘任务不在采集范围')
  })

  it('完整令牌只在本次界面显示并加入设备列表', async () => {
    vi.mocked(createMemoryAccessToken).mockResolvedValue({ token: {
      id: 'token-1', name: '家里 Windows', tokenPrefix: 'wao_mcp_abcdefgh', createdAt: '2026-08-17T18:00:00',
    }, secret: 'wao_mcp_test-only-placeholder' })
    const wrapper = mount(MemoryMcpPanel); await flushPromises()
    await wrapper.get('form input').setValue('家里 Windows'); await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(createMemoryAccessToken).toHaveBeenCalledWith('家里 Windows')
    expect(wrapper.text()).toContain('wao_mcp_test-only-placeholder')
  })

  it('可读取设备范围并立即吊销', async () => {
    vi.mocked(fetchMemoryAccessTokens).mockResolvedValue([{ id: 'token-1', name: '办公室 Mac',
      tokenPrefix: 'wao_mcp_abcdefgh', createdAt: '2026-08-17T18:00:00' }])
    vi.mocked(fetchMemoryGrants).mockResolvedValue([{ scopeType: 'project', scopeId: 'p1', canRead: true,
      canPropose: true, canDirectWrite: false }])
    vi.mocked(revokeMemoryAccessToken).mockResolvedValue({} as never)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(MemoryMcpPanel); await flushPromises()
    await wrapper.get('.grant').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('WAO 项目')
    await wrapper.get('.revoke').trigger('click'); await flushPromises()
    expect(revokeMemoryAccessToken).toHaveBeenCalledWith('token-1')
    expect(wrapper.text()).not.toContain('办公室 Mac')
  })
  it('归档权限显式保存且失败时保留勾选重试', async () => {
    vi.mocked(fetchMemoryAccessTokens).mockResolvedValue([{ id: 'token-1', name: 'Mac',
      tokenPrefix: 'prefix', createdAt: '2026-09-19' }])
    const wrapper = mount(MemoryMcpPanel); await flushPromises()
    await wrapper.get('.grant').trigger('click'); await flushPromises()
    const archive = wrapper.findAll('.grant-options label').find(item => item.text().includes('Codex 工作记录归档'))!
    expect((archive.get('input').element as HTMLInputElement).checked).toBe(false)
    await archive.get('input').setValue(true)
    vi.mocked(saveMemoryGrants).mockRejectedValueOnce(new Error('暂时断网')).mockResolvedValue([])
    await wrapper.get('.grant-actions .btn-primary').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('暂时断网')
    expect((archive.get('input').element as HTMLInputElement).checked).toBe(true)
    await wrapper.get('.grant-actions .btn-primary').trigger('click'); await flushPromises()
    expect(wrapper.text()).not.toContain('暂时断网')
    expect(saveMemoryGrants).toHaveBeenLastCalledWith('token-1', [expect.objectContaining({ scopeType: 'codex_archive', canDirectWrite: true })])
  })

})
