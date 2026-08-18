import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { beforeEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import KbDetailView from '@/views/KbDetailView.vue'

describe('知识库详情目录体验', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  async function mountView(path = '/kb/k3') {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/kb/:id', name: 'kb', component: KbDetailView }],
    })
    await router.push(path)
    await router.isReady()
    const wrapper = mount(KbDetailView, {
      global: {
        plugins: [router],
        stubs: { MarkdownEditor: true },
      },
    })
    await flushPromises()
    return wrapper
  }

  it('首次进入只展开当前文档所在文件夹，其余文件夹默认收起', async () => {
    const wrapper = await mountView('/kb/k3?doc=d7')
    const folders = wrapper.findAll('.folder-row')

    expect(folders).toHaveLength(2)
    expect(folders[0]!.get('.chev').classes()).toContain('ri-arrow-down-s-line')
    expect(folders[1]!.get('.chev').classes()).toContain('ri-arrow-right-s-line')
    expect(wrapper.findAll('.doc-tree')).toHaveLength(1)

    await folders[1]!.get('.folder-btn').trigger('click')
    expect(wrapper.findAll('.doc-tree')).toHaveLength(2)
    wrapper.unmount()
  })

  it('从链接直达另一篇文档时展开它所在的文件夹', async () => {
    const wrapper = await mountView('/kb/k3?doc=d8')
    const folders = wrapper.findAll('.folder-row')

    expect(folders[0]!.get('.chev').classes()).toContain('ri-arrow-right-s-line')
    expect(folders[1]!.get('.chev').classes()).toContain('ri-arrow-down-s-line')
    expect(wrapper.get('.doc-node.on').text()).toContain('阅读笔记：卡片盒笔记法')
    wrapper.unmount()
  })

  it('把详情页限制在应用壳剩余视口，并为左右滚动容器阻止滚动串联', () => {
    const source = readFileSync(join(process.cwd(), 'src/views/KbDetailView.vue'), 'utf8')

    expect(source).toContain('height: calc(100dvh - var(--wao-header-h) - var(--wao-tabbar-h))')
    expect(source.match(/overscroll-behavior: contain/g)).toHaveLength(2)
  })
})
