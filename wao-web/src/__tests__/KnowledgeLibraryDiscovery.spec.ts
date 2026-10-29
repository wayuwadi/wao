import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import KnowledgeView from '@/views/KnowledgeView.vue'
import { useDataStore } from '@/stores/data'
import type { Doc, KnowledgeBase } from '@/types'

enableAutoUnmount(afterEach)

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
    data.loadStates.knowledge = 'loaded'
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

  it('关闭设置先保存，失败保留输入，保存中等待成功再关闭', async () => {
    const update = vi.spyOn(data, 'updateKb').mockResolvedValue(false)
    const { wrapper } = await render()
    const open = () => wrapper.findAll('button[title="知识库设置"]')[0]!.trigger('click')
    const escape = () => globalThis.document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true }))
    await open(); escape(); await flushPromises()
    expect(wrapper.find('.modal').exists()).toBe(false); expect(update).not.toHaveBeenCalled()
    await open(); await wrapper.get('.modal .f-input').setValue('保留此名称')
    escape(); await flushPromises()
    expect(wrapper.find('.modal').exists()).toBe(true)
    expect((wrapper.get('.modal .f-input').element as HTMLInputElement).value).toBe('保留此名称')
    let finish!: (value: boolean) => void
    update.mockReturnValueOnce(new Promise<boolean>(resolve => { finish = resolve }))
    await wrapper.get('.modal-mask').trigger('click'); await flushPromises()
    expect(wrapper.find('.modal').exists()).toBe(true)
    finish(true); await flushPromises(); expect(wrapper.find('.modal').exists()).toBe(false)
  })

  it('项目知识库进入自定义分组，不再展示锁定项目分组', async () => {
    data.myGroups.push({ id: 'work', name: '工作资料' })
    data.kbs[0]!.group = 'work'
    const { wrapper } = await render()
    expect(wrapper.text()).not.toContain('默认分组，不可修改')
    const group = wrapper.findAll('.kgroup').find((row) => row.find('.kgroup-label').text() === '工作资料')!
    expect(group.text()).toContain('WAO 项目档案')
    await group.get('button[title="知识库设置"]').trigger('click')
    expect(wrapper.text()).toContain('所属分组')
    expect(wrapper.find('.f-input').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('.f-textarea').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).not.toContain('名称与简介不可修改')
  })
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

    await wrapper.findAll('.kb-scopes button').find(button => button.text() === '共享资料')!.trigger('click')

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
    expect(wrapper.get('.wao-empty-state').text()).toContain('没有匹配的资料')

    await wrapper.get('.wao-empty-state button').trigger('click')
    expect(wrapper.findAll('.kb-card')).toHaveLength(3)
  })
})
