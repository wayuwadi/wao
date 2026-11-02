import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import Workspace from '@/views/KnowledgeWorkspaceView.vue'
import { seedDataStore } from './helpers/seedDataStore'
import { useNavStore } from '@/stores/nav'
vi.mock('@/views/KbDetailView.vue', () => ({ default: { template: '<textarea class="editor" />' } }))
describe('知识库工作区', () => {
  beforeEach(() => { localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia()) })
  async function setup() {
    const data = seedDataStore()
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path:'/knowledge', component:Workspace }] })
    await router.push('/knowledge')
    const wrapper = mount(Workspace, { global:{ plugins:[router], stubs:{ KnowledgeIconPicker:true, teleport:true } } })
    return { data, router, wrapper }
  }
  it('首页汇总目录，选择文档后同一编辑器在清单折叠时保留', async () => {
    const { wrapper, router } = await setup()
    expect(wrapper.find('.home-directory').exists()).toBe(true)
    expect(wrapper.find('.knowledge-home').exists()).toBe(true)
    await wrapper.find('.index-row button').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.query.doc).toBeTruthy()
    const editor = wrapper.get('.editor').element
    await wrapper.get('[aria-label="收起知识库清单"]').trigger('click')
    expect(wrapper.get('.editor').element).toBe(editor)
    await wrapper.get('[aria-label="展开知识库清单"]').trigger('click')
    expect(wrapper.get('.editor').element).toBe(editor)
    wrapper.unmount()
  })
  it('创建失败保留输入，关闭不产生知识库', async () => {
    const { wrapper, data } = await setup()
    const add = vi.spyOn(data, 'addKb').mockRejectedValue(new Error('offline'))
    await wrapper.findAll('button').find(b=>b.text().includes('新增知识库'))!.trigger('click')
    await wrapper.get('input[required]').setValue('测试知识库')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('输入已保留')
    expect((wrapper.get('input[required]').element as HTMLInputElement).value).toBe('测试知识库')
    expect(add).toHaveBeenCalledTimes(1)
    await wrapper.get('[aria-label="关闭新增知识库"]').trigger('click')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(add).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
  it('新用户主菜单默认收起，保留已存展开偏好', () => {
    expect(useNavStore().collapsed).toBe(true)
    setActivePinia(createPinia())
    localStorage.setItem('wao-nav-v1', JSON.stringify({ collapsed:false, foldedGroups:{} }))
    expect(useNavStore().collapsed).toBe(false)
  })
})

