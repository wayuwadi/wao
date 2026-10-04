import { describe, expect, it, vi } from 'vitest'
import axios from 'axios'
import { fetchWebRelease, parseReleaseEdition, parseReleaseDemo } from '@/api/version'
import { CURRENT_RELEASE } from '@/data/featureCatalog'
import { MODULES } from '@/config/nav'
vi.mock('axios', () => ({ default: { get: vi.fn() } }))
describe('公开版本清单', () => {
  it('兼容旧清单并保留新步骤与专用演示，拒绝外部图和目录穿越',()=>{
    const base=CURRENT_RELEASE.highlights[0]!
    const demo={presentation:'window',kind:'notes-layout',alt:'记录区示意'}
    const enriched={...CURRENT_RELEASE,highlights:[{...base,steps:['点击记录'],demo}]}
    expect(parseReleaseEdition(enriched)).toEqual(enriched)
    for(const image of ['https://evil.test/x.png','assets/release-guides/../secret.png','assets/release-guides/x.svg','assets/release-guides/x.png?token=x'])
      expect(parseReleaseDemo({presentation:'inline',image,alt:'测试'})).toBeNull()
    expect(parseReleaseDemo({...demo,kind:'invented'})).toBeNull()
    expect(parseReleaseDemo({...demo,image:'assets/release-guides/x.png'})).toBeNull()
    expect(parseReleaseEdition({...enriched,highlights:[{...enriched.highlights[0],steps:['']}]})).toBeNull()
  })
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
