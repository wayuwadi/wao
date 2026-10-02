import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import * as auth from '@/api/auth'
import * as board from '@/api/board'
vi.mock('@/api/auth', () => ({
  getSession: vi.fn<typeof auth.getSession>(),
  loginWithEmail: vi.fn<typeof auth.loginWithEmail>(),
  logoutSession: vi.fn<typeof auth.logoutSession>(),
  registerWithEmail: vi.fn<typeof auth.registerWithEmail>(),
  claimMember: vi.fn<typeof auth.claimMember>(),
}))
vi.mock('@/api/board', () => ({
  fetchMembers: vi.fn<typeof board.fetchMembers>().mockResolvedValue([]),
  fetchOrg: vi
    .fn<typeof board.fetchOrg>()
    .mockResolvedValue({ id: 'org', name: '组织', memberCount: 0 }),
}))
vi.mock('@/api/desktopCache', () => ({ setDesktopCacheMember: vi.fn<(member: string) => void>() }))
const account = { member: { id: 'member', name: '成员' } } as auth.AuthSession
beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})
describe('公开页会话的数据边界', () => {
  it('识别会话和邮箱登录不读取成员目录，工作区显式加载仍可用', async () => {
    vi.mocked(auth.getSession).mockResolvedValue(account)
    vi.mocked(auth.loginWithEmail).mockResolvedValue(account)
    const store = useSessionStore()
    await store.initialize()
    await store.emailLogin('user@example.com', 'test-password')
    expect(store.authed).toBe(true)
    expect(board.fetchMembers).not.toHaveBeenCalled()
    expect(board.fetchOrg).not.toHaveBeenCalled()
    await store.refreshDirectory()
    expect(board.fetchMembers).toHaveBeenCalledOnce()
    expect(board.fetchOrg).toHaveBeenCalledOnce()
  })
  it('OAuth 与账号认领仅接收会话，不自动读取私有目录', async () => {
    const store = useSessionStore()
    await store.acceptResolution({ session: account } as auth.AuthResolution)
    vi.mocked(auth.claimMember).mockResolvedValue(account)
    await store.claim('member')
    expect(board.fetchMembers).not.toHaveBeenCalled()
    expect(board.fetchOrg).not.toHaveBeenCalled()
  })
})
