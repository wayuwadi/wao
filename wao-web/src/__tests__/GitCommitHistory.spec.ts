import { nextTick } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import GitCommitHistory from '@/components/release/GitCommitHistory.vue'
import { fetchGitReleaseHistory } from '@/api/version'
vi.mock('@/api/version', () => ({ fetchGitReleaseHistory: vi.fn() }))
beforeEach(() => vi.mocked(fetchGitReleaseHistory).mockReset())
describe('自动提交记录', () => {
  it('首次失败可重试，所有类型记录可以逐页看全', async () => {
    const commits = Array.from({ length: 23 }, (_, index) => ({ sha: index.toString(16).padStart(40, '0'), committedAt: '2026-10-03T06:00:00+00:00', subject: `${index === 0 ? 'docs' : 'fix'}(notes): 提交 ${index}` }))
    let rejectFirst!: (cause: Error) => void
    vi.mocked(fetchGitReleaseHistory).mockReturnValueOnce(new Promise((_, reject) => { rejectFirst = reject })).mockResolvedValue({ buildSha: 'a'.repeat(40), commits })
    const wrapper = mount(GitCommitHistory)
    await nextTick()
    expect(wrapper.text()).toContain('正在读取')
    rejectFirst(new Error('网络中断'))
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('网络中断')
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(wrapper.findAll('li')).toHaveLength(20)
    expect(wrapper.text()).toContain('docs(notes): 提交 0')
    await wrapper.get('button').trigger('click')
    expect(wrapper.findAll('li')).toHaveLength(23)
    expect(wrapper.text()).toContain('已展示该版本全部提交')
    wrapper.unmount()
  })
})
