import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ConnectionSetup from '@/components/capabilities/ConnectionSetup.vue'
import type { WorkspaceMcpToken } from '@/api/workspaceMcp'
const api = vi.hoisted(() => ({ fetchWorkspaceMcpTokens: vi.fn() }))
vi.mock('@/api/workspaceMcp', () => api)
const token: WorkspaceMcpToken = {
  id: 't',
  name: '本机',
  tokenPrefix: 'prefix',
  canWrite: false,
  canPublishHostedPage: false,
  createdAt: '2026-10-06',
  lastUsedAt: '2026-10-06T01:00:00',
  clientType: 'CLI',
}

describe('客户端接入真实验证', () => {
  it('签发、已有访问和刷新列表不会冒充成功；收到实际工具目录结果才验证', async () => {
    api.fetchWorkspaceMcpTokens.mockResolvedValueOnce([token])
    const wrapper = mount(ConnectionSetup, { props: { token } })
    expect(wrapper.text()).toContain('尚未验证')
    expect(wrapper.text()).toContain('wao auth status')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('尚未收到该连接')
    api.fetchWorkspaceMcpTokens.mockResolvedValueOnce([
      { ...token, lastVerifiedAt: '2026-10-06T02:00:00', verifiedToolCount: 5 },
    ])
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已验证')
    expect(wrapper.text()).toContain('最近成功读取 5 项')
    expect(wrapper.emitted('refreshed')).toHaveLength(2)
    wrapper.unmount()
  })
  it('区分服务端目录失败、网络读取失败和已吊销，支持重试且不泄露明文', async () => {
    const wrapper = mount(ConnectionSetup, {
      props: {
        token: {
          ...token,
          clientType: 'MCP',
          lastVerifiedAt: '2026-10-06T02:00:00',
          lastVerificationFailedAt: '2026-10-06T03:00:00',
        },
      },
    })
    expect(wrapper.text()).toContain('验证失败')
    expect(wrapper.text()).toContain('Streamable HTTP')
    api.fetchWorkspaceMcpTokens.mockRejectedValueOnce(new Error('网络暂不可用'))
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('网络暂不可用')
    api.fetchWorkspaceMcpTokens.mockResolvedValueOnce([])
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('该连接已吊销')
    expect(wrapper.text()).not.toContain('prefix')
    wrapper.unmount()
  })
  it('切换连接后丢弃原连接的延迟响应', async () => {
    let resolve!: (tokens: WorkspaceMcpToken[]) => void
    api.fetchWorkspaceMcpTokens.mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    const wrapper = mount(ConnectionSetup, { props: { token } })
    await wrapper.get('button').trigger('click')
    await wrapper.setProps({ token: { ...token, id: 'other', name: '另一个客户端' } })
    resolve([{ ...token, lastVerifiedAt: '2026-10-06T02:00:00' }])
    await flushPromises()
    expect(wrapper.text()).toContain('尚未验证')
    expect(wrapper.emitted('refreshed')).toBeUndefined()
    wrapper.unmount()
  })
})
