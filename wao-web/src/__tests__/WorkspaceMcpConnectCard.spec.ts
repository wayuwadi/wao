import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import WorkspaceMcpConnectCard from '@/components/home/WorkspaceMcpConnectCard.vue'
import { createWorkspaceMcpToken, fetchWorkspaceMcpTokens, revokeWorkspaceMcpToken } from '@/api/workspaceMcp'

vi.mock('@/api/workspaceMcp', () => ({
  createWorkspaceMcpToken: vi.fn(), fetchWorkspaceMcpTokens: vi.fn(), revokeWorkspaceMcpToken: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(fetchWorkspaceMcpTokens).mockResolvedValue([])
  vi.mocked(createWorkspaceMcpToken).mockResolvedValue({
    token: { id: 'token-1', name: '本机 AI', tokenPrefix: 'wao_workspace_test', canWrite: true, createdAt: '2026-09-23' },
    secret: 'wao_workspace_test-secret-only-once',
  })
})
afterEach(() => vi.restoreAllMocks())

it('首页点击直接生成包含 Bearer 令牌的连接指令并在关闭时清除', async () => {
  const wrapper = mount(WorkspaceMcpConnectCard)
  await wrapper.get('.card-actions .primary').trigger('click')
  await flushPromises()

  expect(createWorkspaceMcpToken).toHaveBeenCalledWith(expect.stringContaining('首页快捷连接'), true)
  const instruction = (wrapper.get('textarea').element as HTMLTextAreaElement).value
  expect(instruction).toContain('/api/mcp/workspace')
  expect(instruction).toContain('Token：wao_workspace_test-secret-only-once')
  expect(instruction).toContain('tools/list')
  await wrapper.get('[aria-label="关闭"]').trigger('click')
  expect(wrapper.find('textarea').exists()).toBe(false)
  expect(wrapper.text()).not.toContain('test-secret-only-once')
})

it('只读选择生成只读令牌，管理入口可吊销但不重显明文', async () => {
  vi.mocked(createWorkspaceMcpToken).mockResolvedValueOnce({
    token: { id: 'token-1', name: '本机 AI', tokenPrefix: 'wao_workspace_test', canWrite: false, createdAt: '2026-09-23' },
    secret: 'wao_workspace_test-secret-only-once',
  })
  const wrapper = mount(WorkspaceMcpConnectCard)
  await wrapper.get('.card-actions input').setValue(false)
  await wrapper.get('.card-actions .primary').trigger('click')
  await flushPromises()
  expect(createWorkspaceMcpToken).toHaveBeenCalledWith(expect.any(String), false)
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toContain('这枚令牌只读')
  await wrapper.get('.card-actions input').setValue(true)
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toContain('这枚令牌只读')
  await wrapper.get('[aria-label="关闭"]').trigger('click')

  vi.mocked(fetchWorkspaceMcpTokens).mockResolvedValue([{ id: 'token-1', name: '本机 AI',
    tokenPrefix: 'wao_workspace_test', canWrite: false, createdAt: '2026-09-23' }])
  vi.mocked(revokeWorkspaceMcpToken).mockResolvedValue({} as never)
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  await wrapper.get('.manage').trigger('click')
  await flushPromises()
  expect(wrapper.find('textarea').exists()).toBe(false)
  await wrapper.get('.token button').trigger('click')
  await flushPromises()
  expect(revokeWorkspaceMcpToken).toHaveBeenCalledWith('token-1')
  expect(wrapper.find('.token').exists()).toBe(false)
})
