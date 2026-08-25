import { beforeEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import KnowledgeView from '@/views/KnowledgeView.vue'
import { useDataStore } from '@/stores/data'
import type { Doc, KnowledgeBase } from '@/types'

function testRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/knowledge', component: KnowledgeView },
      { path: '/kb/:id', component: { template: '<div />' } },
    ],
  })
}

function knowledgeBase(patch: Partial<KnowledgeBase> & Pick<KnowledgeBase, 'id' | 'name'>): KnowledgeBase {
  return {
    icon: 'ri-book-2-line', color: '#4358D6', project: null, desc: '', group: 'g1', perm: '仅自己',
    folders: [{ id: `${patch.id}-folder`, name: '文档', docs: [] }], ...patch,
  }
}

function document(id: string, title: string): Doc {
  return { id, title, author: 'wg', created: '今天', updated: '今天', links: [], blocks: [], backlinks: [] }
}

describe('知识库真实资料检索', () => {
  let data: ReturnType<typeof useDataStore>

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
    data.myGroups = [{ id: 'g1', name: '我的知识库' }]
    data.kbs = [
      knowledgeBase({ id: 'kb-project', name: 'WAO 项目档案', desc: '工程记忆与验收记录', project: 'p1', perm: '成员可编辑' }),
      knowledgeBase({ id: 'kb-personal', name: '生活记录', folders: [{ id: 'f-personal', name: '阅读', docs: ['doc-yijing'] }] }),
      knowledgeBase({ id: 'kb-shared', name: '家庭资料', perm: '成员可编辑' }),
    ]
    data.docs['doc-yijing'] = document('doc-yijing', '易经学习笔记')
  })

  async function render() {
    const router = testRouter()
    await router.push('/knowledge')
    await router.isReady()
    return { wrapper: mount(KnowledgeView, { global: { plugins: [router] } }), router }
  }

  it('按文档标题找到所属知识库，并能直接打开命中文档', async () => {
    const { wrapper, router } = await render()

    await wrapper.find('.kb-search input').setValue('易经')

    expect(wrapper.findAll('.kb-card')).toHaveLength(1)
    expect(wrapper.find('.kb-card').text()).toContain('生活记录')
    expect(wrapper.find('.kb-doc-matches').text()).toContain('易经学习笔记')

    await wrapper.find('.kb-doc-matches button').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/kb/kb-personal?doc=doc-yijing')
  })

  it.each([
    ['知识库名称', '家庭资料', '家庭资料'],
    ['知识库简介', '工程记忆', 'WAO 项目档案'],
    ['文件夹名称', '阅读', '生活记录'],
  ])('可以按%s检索真实资料', async (_field, keyword, expected) => {
    const { wrapper } = await render()

    await wrapper.find('.kb-search input').setValue(keyword)

    expect(wrapper.findAll('.kb-card')).toHaveLength(1)
    expect(wrapper.find('.kb-card').text()).toContain(expected)
  })

  it('共享筛选只展示后端标记为成员可编辑的知识库', async () => {
    const { wrapper } = await render()

    await wrapper.findAll('.kb-scopes button')[2]!.trigger('click')

    expect(wrapper.findAll('.kb-card').map((card) => card.text())).toEqual([
      expect.stringContaining('WAO 项目档案'),
      expect.stringContaining('家庭资料'),
    ])
    expect(wrapper.find('.kb-result-count').text()).toBe('2 个知识库')
  })

  it('空结果可恢复全部资料，页面没有演示语义搜索和假邀请链接', async () => {
    const { wrapper } = await render()

    expect(wrapper.text()).not.toContain('AI 语义搜索')
    expect(wrapper.text()).not.toContain('复制邀请链接')

    await wrapper.find('.kb-search input').setValue('不存在的内容')
    expect(wrapper.find('.kb-filter-empty').text()).toContain('没有匹配的真实资料')

    await wrapper.find('.kb-filter-empty button').trigger('click')
    expect(wrapper.findAll('.kb-card')).toHaveLength(3)
  })
})
