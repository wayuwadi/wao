import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiReplaceHostedPage, apiSaveHostedPage } from '@/api/board'
import { http } from '@/api/http'

vi.mock('@/api/http', () => ({ http: { put: vi.fn(), post: vi.fn() } }))

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(http.put).mockResolvedValue({ data: { data: { id: 'h1', revision: 1 } } })
  vi.mocked(http.post).mockResolvedValue({ data: { data: { id: 'h1', revision: 0 } } })
})

describe('托管网页版本请求', () => {
  it('编辑与替换都发送明确的零修订号，保留原始文件与元信息', async () => {
    const html = new File(['<h1>new</h1>'], 'new.html', { type: 'text/html' })
    await apiSaveHostedPage({ id: 'h1', slug: 'new', title: '新标题', html, expectedRevision: 0 })
    await apiReplaceHostedPage('h1', html, 0)
    const [edit, replace] = vi.mocked(http.put).mock.calls
    expect(edit![0]).toBe('/api/hosted-pages/h1')
    expect(replace![0]).toBe('/api/hosted-pages/h1/content')
    for (const [, body] of [edit!, replace!]) {
      expect((body as FormData).get('expectedRevision')).toBe('0')
      expect((body as FormData).get('html')).toBe(html)
    }
    expect((edit![1] as FormData).get('slug')).toBe('new')
    expect((edit![1] as FormData).get('title')).toBe('新标题')
  })

  it('旧服务的无修订号数据与新建网页不发送空版本字段', async () => {
    const html = new File(['html'], 'one.html', { type: 'text/html' })
    await apiSaveHostedPage({ id: 'h1', slug: 'one', title: 'One' })
    await apiReplaceHostedPage('h1', html)
    await apiSaveHostedPage({ slug: 'one', title: 'One', html })
    for (const [, body] of [...vi.mocked(http.put).mock.calls, ...vi.mocked(http.post).mock.calls]) {
      expect((body as FormData).has('expectedRevision')).toBe(false)
    }
  })

  it('409原样传回调用者，不自动重新提交覆盖', async () => {
    const conflict = Object.assign(new Error('网页已更新'), { status: 409 })
    vi.mocked(http.put).mockRejectedValueOnce(conflict)
    await expect(apiSaveHostedPage({ id: 'h1', slug: 'one', title: 'One', expectedRevision: 2 })).rejects.toBe(conflict)
    expect(http.put).toHaveBeenCalledTimes(1)
  })
})
