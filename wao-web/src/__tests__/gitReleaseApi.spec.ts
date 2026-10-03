import { describe, it, expect, vi } from 'vitest'
import axios from 'axios'
import { fetchGitReleaseHistory } from '@/api/version'
vi.mock('axios', () => ({ default: { get: vi.fn() } }))
describe('提交档案版本边界', () => {
  const sha = 'a'.repeat(40)
  const item = { sha, subject: 'docs(notes): 文档更新', committedAt: '2026-10-03T06:00:00Z' }
  it('接受当前制品并保留真实提交标题', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { buildSha: sha, commits: [item] } })
    expect((await fetchGitReleaseHistory(sha)).commits[0]).toEqual(item)
  })
  it.each([
    { buildSha: 'b'.repeat(40), commits: [item] },
    { buildSha: sha, commits: [] },
    { buildSha: sha, commits: [item, item] },
    { buildSha: sha, commits: [{ ...item, committedAt: 'bad' }] },
    { buildSha: sha, commits: [{ ...item, sha: 'b'.repeat(40) }] },
  ])('拒绝错误版本、缺失、重复或损坏记录', async data => {
    vi.mocked(axios.get).mockResolvedValue({ data })
    await expect(fetchGitReleaseHistory(sha)).rejects.toThrow()
  })
})
