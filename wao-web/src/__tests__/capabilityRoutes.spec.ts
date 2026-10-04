import { describe, expect, it } from 'vitest'
import { legacyCapabilityRedirect } from '@/utils/capabilityRoutes'
describe('历史地址收口', () => {
  it.each([
    ['/hub', '/capabilities', undefined],
    ['/wao/skills/manage', '/capabilities', 'published'],
    ['/skills/@author/slug', '/capabilities/skills/@author/slug', undefined],
    ['/pets/create', '/capabilities/publish/pet', undefined],
    ['/pets/id', '/capabilities/pets/id', undefined],
    ['/skill-connections', '/capabilities', undefined],
  ])('%s 保留详情和区域', (path, target, area) => {
    const result = legacyCapabilityRedirect({ path, query: { keyword: 'keep' } })
    expect(result.path).toBe(target)
    expect(result.query?.area).toBe(area)
    expect((result.query as Record<string, unknown>).keyword).toBe('keep')
  })
})
