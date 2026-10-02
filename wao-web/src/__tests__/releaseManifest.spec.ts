import { describe, expect, it, vi } from 'vitest'
import axios from 'axios'
import { fetchWebRelease, parseReleaseEdition } from '@/api/version'
import { CURRENT_RELEASE } from '@/data/featureCatalog'
import { MODULES } from '@/config/nav'
vi.mock('axios', () => ({ default: { get: vi.fn() } }))
describe('公开版本清单', () => {
  it('当前说明全部对应已有路由，并保留缺少说明的旧制品兼容性', async () => {
    expect(parseReleaseEdition(CURRENT_RELEASE)).toEqual(CURRENT_RELEASE)
    for (const item of CURRENT_RELEASE.highlights)
      expect(MODULES[item.featureId]?.path).toBeTruthy()
    vi.mocked(axios.get).mockResolvedValue({ data: { version: 'old1234' } })
    expect(await fetchWebRelease()).toEqual({ version: 'old1234', release: null })
  })
  it('拒绝格式错误和外部 URL 功能 id，不传递未知字段', async () => {
    expect(parseReleaseEdition({ ...CURRENT_RELEASE, highlights: [null] })).toBeNull()
    expect(
      parseReleaseEdition({
        ...CURRENT_RELEASE,
        highlights: [{ ...CURRENT_RELEASE.highlights[0], featureId: 'https://evil.test' }],
      }),
    ).toBeNull()
    expect(parseReleaseEdition({ ...CURRENT_RELEASE, externalUrl: 'https://evil.test' })).toEqual(
      CURRENT_RELEASE,
    )
    vi.mocked(axios.get).mockResolvedValue({
      data: { version: 'bad1234', release: { title: '不完整' } },
    })
    await expect(fetchWebRelease()).rejects.toThrow('更新说明格式无效')
  })
})
