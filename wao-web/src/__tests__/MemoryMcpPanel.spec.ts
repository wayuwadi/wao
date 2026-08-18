import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MemoryMcpPanel from '@/components/settings/MemoryMcpPanel.vue'
import {
  createMemoryAccessToken,
  fetchMemoryAccessTokens,
  revokeMemoryAccessToken,
} from '@/api/memory'

vi.mock('@/api/memory', () => ({
  fetchMemoryAccessTokens: vi.fn<(typeof import('@/api/memory'))['fetchMemoryAccessTokens']>(),
  createMemoryAccessToken: vi.fn<(typeof import('@/api/memory'))['createMemoryAccessToken']>(),
  revokeMemoryAccessToken: vi.fn<(typeof import('@/api/memory'))['revokeMemoryAccessToken']>(),
}))

describe('MemoryMcpPanel', () => {
  beforeEach(() => {
    vi.mocked(fetchMemoryAccessTokens).mockResolvedValue([])
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('展示真实 MCP 地址、无向量伪造说明和跨端接入命令', async () => {
    const wrapper = mount(MemoryMcpPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('WAO 是账号私有的跨设备记忆源')
    expect(wrapper.text()).toContain('没有稳定 embedding 来源时不建立会过期的空向量副本')
    expect(wrapper.text()).toContain('codex mcp add wao-memory --url http://localhost:3000/api/mcp')
    expect(wrapper.text()).toContain('外部 Codex 历史没有官方可靠导出接口')
  })

  it('生成的完整令牌只在本次界面显示并加入真实设备列表', async () => {
    vi.mocked(createMemoryAccessToken).mockResolvedValue({
      token: {
        id: 'token-1', name: '家里 Windows', tokenPrefix: 'wao_mcp_abcdefgh',
        createdAt: '2026-08-17T18:00:00',
      },
      secret: 'wao_mcp_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG',
    })
    const wrapper = mount(MemoryMcpPanel)
    await flushPromises()

    await wrapper.get('input').setValue('家里 Windows')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(createMemoryAccessToken).toHaveBeenCalledWith('家里 Windows')
    expect(wrapper.text()).toContain('wao_mcp_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG')
    expect(wrapper.text()).toContain('家里 Windows')
    expect(wrapper.text()).not.toContain('演示环境')
  })

  it('吊销前确认并立即移除对应设备', async () => {
    vi.mocked(fetchMemoryAccessTokens).mockResolvedValue([{
      id: 'token-1', name: '办公室 Mac', tokenPrefix: 'wao_mcp_abcdefgh',
      createdAt: '2026-08-17T18:00:00', lastUsedAt: '2026-08-17T18:02:00',
    }])
    vi.mocked(revokeMemoryAccessToken).mockResolvedValue({} as never)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(MemoryMcpPanel)
    await flushPromises()

    await wrapper.get('.revoke').trigger('click')
    await flushPromises()

    expect(revokeMemoryAccessToken).toHaveBeenCalledWith('token-1')
    expect(wrapper.text()).not.toContain('办公室 Mac')
  })
})
