import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import NoteMiningHomeCard from '@/components/home/NoteMiningHomeCard.vue'
import { fetchNoteMining } from '@/api/noteMining'
import { useSessionStore } from '@/stores/session'

vi.mock('@/api/noteMining', () => ({ fetchNoteMining: vi.fn() }))

describe('首页小记挖掘卡片', () => {
  beforeEach(() => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useSessionStore().accept({ member: { id: 'wg', name: '娃哥' } } as never)
    vi.mocked(fetchNoteMining).mockReset()
  })

  it('只总览最新洞察和依据，不在首页承载挖掘操作', async () => {
    vi.mocked(fetchNoteMining).mockResolvedValue({
      job: { id: 'nm1', status: 'SUCCEEDED', message: '已生成', sourceCount: 3, createdAt: '2026-09-27T08:00:00' },
      artifacts: [{ id: 'a1', type: 'INSIGHT', content: '忙起来时，你最先忽略的是身体的细小信号。', rationale: '三篇小记反复出现相同变化。', feedbackStatus: 'CANDIDATE',
        sources: [{ docId: 'd1', docRev: 2, title: '周五', excerpt: '忘记喝水' }], createdAt: '2026-09-27T08:00:00' }],
    })
    const wrapper = mount(NoteMiningHomeCard)
    await flushPromises()
    expect(wrapper.text()).toContain('忙起来时，你最先忽略的是身体的细小信号')
    expect(wrapper.text()).toContain('三篇小记反复出现相同变化')
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(wrapper.get('a').attributes('href')).toBe('/notes')
    wrapper.unmount()
  })

  it('运行中只提示后台继续，不伪造内容结果', async () => {
    vi.mocked(fetchNoteMining).mockResolvedValue({
      job: { id: 'nm1', status: 'RUNNING', message: '分析中', sourceCount: 4, createdAt: '2026-09-27T08:00:00' }, artifacts: [],
    })
    const wrapper = mount(NoteMiningHomeCard)
    await flushPromises()
    expect(wrapper.text()).toContain('AI 正在连接小记里的线索')
    expect(wrapper.text()).toContain('4 篇小记分析中')
    expect(wrapper.text()).toContain('查看进度')
    wrapper.unmount()
  })
})
