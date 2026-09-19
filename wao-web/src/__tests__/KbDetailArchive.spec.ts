import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import KbDetailView from '@/views/KbDetailView.vue'
import { useDataStore } from '@/stores/data'
import type { Doc, KnowledgeBase } from '@/types'

describe('知识库详情 · 文档归档', () => {
  let wrapper: VueWrapper | null = null

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.restoreAllMocks()
  })

  it('从普通目录归档后仍可打开，并能恢复到原文件夹', async () => {
    const data = useDataStore()
    const knowledgeBase: KnowledgeBase = {
      id: 'kb-archive', name: '临时资料', icon: 'ri-book-2-line', color: '#4358D6',
      project: null, desc: '', folders: [{ id: 'f1', name: '文档', docs: ['d1'] }],
      group: 'g1', perm: '仅自己',
    }
    const document: Doc = {
      id: 'd1', title: '活动临时流程', author: 'wg', created: '今天', updated: '今天',
      links: [], blocks: [{ p: '正文仍需保留' }], backlinks: [], kbId: 'kb-archive', folderId: 'f1', rev: 1,
    }
    data.kbs = [knowledgeBase]
    data.docs.d1 = document
    data.loadStates.docs = 'loaded'

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/kb/:id', component: KbDetailView },
        { path: '/knowledge', component: { template: '<div />' } },
      ],
    })
    await router.push('/kb/kb-archive?doc=d1')
    await router.isReady()
    wrapper = shallowMount(KbDetailView, { global: { plugins: [router] } })

    wrapper.find('.doc-row').findComponent({ name: 'ResourceMenu' }).vm.$emit('select', 'archive')
    await flushPromises()

    expect(knowledgeBase.folders[0]!.docs).not.toContain('d1')
    expect(wrapper.text()).toContain('已归档')
    expect(wrapper.text()).toContain('活动临时流程')
    expect(wrapper.find('.tag-archived').exists()).toBe(true)

    await wrapper.find('button[title="恢复到原文件夹"]').trigger('click')
    await flushPromises()

    expect(knowledgeBase.folders[0]!.docs).toContain('d1')
    expect(data.isDocArchived('d1')).toBe(false)
    expect(wrapper.find('.tag-archived').exists()).toBe(false)
  })
})
