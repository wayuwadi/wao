import { beforeEach, afterEach, expect, it, vi } from 'vitest'
beforeEach(() => {
  sessionStorage.clear()
  vi.resetModules()
})
afterEach(() => vi.restoreAllMocks())
it('随机选择覆盖且只覆盖四个现有角色', async () => {
  const { randomCompanion } = await import('@/utils/companionIdentity')
  const random = vi.spyOn(Math, 'random')
  const identities = [0, 0.25, 0.5, 0.999].map((value) => {
    random.mockReturnValue(value)
    return randomCompanion()
  })
  expect(identities).toEqual(['painter', 'bookworm', 'happy', 'frog'])
})
it('同一成员版本在重开与页面刷新后保持身份，其他成员与新版本独立', async () => {
  const random = vi.spyOn(Math, 'random').mockReturnValue(0)
  let identity = await import('@/utils/companionIdentity')
  expect(identity.selectCompanion('release:one', 'u1')).toBe('painter')
  random.mockReturnValue(0.999)
  expect(identity.selectCompanion('release:one', 'u1')).toBe('painter')
  expect(identity.selectCompanion('release:one', 'u2')).toBe('frog')
  expect(identity.selectCompanion('release:two', 'u1')).toBe('frog')
  vi.resetModules()
  identity = await import('@/utils/companionIdentity')
  expect(identity.selectCompanion('release:one', 'u1')).toBe('painter')
})
it('无效旧身份或禁用存储不影响业务，并在当前页面保持选择', async () => {
  sessionStorage.setItem('wao-companion-identity:u1:release:one', 'messenger')
  vi.spyOn(Math, 'random').mockReturnValue(0.5)
  const { selectCompanion } = await import('@/utils/companionIdentity')
  expect(selectCompanion('release:one', 'u1')).toBe('happy')
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new Error('unavailable')
  })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('unavailable')
  })
  expect(selectCompanion('release:two', 'u1')).toBe('happy')
  vi.spyOn(Math, 'random').mockReturnValue(0)
  expect(selectCompanion('release:two', 'u1')).toBe('happy')
})
