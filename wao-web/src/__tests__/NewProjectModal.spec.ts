import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import NewProjectModal from '@/components/overlays/NewProjectModal.vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
import { useProjectListLayoutStore } from '@/stores/projectListLayout'

enableAutoUnmount(afterEach)
async function open() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/projects')
  return mount(NewProjectModal, { global: { plugins: [router] } })
}
describe('新建项目显式创建', () => {
  beforeEach(() => { sessionStorage.clear(); setActivePinia(createPinia()) })
  afterEach(() => vi.restoreAllMocks())

  it.each(['.close', '.mask', '.btn-ghost'])('%s 关闭只保留草稿，重开恢复名称和目标', async (selector) => {
    const create = vi.spyOn(useDataStore(), 'addProject')
    const close = vi.spyOn(useUiStore(), 'closeModal')
    const first = await open()
    await first.get('.title-input').setValue('我的新项目')
    await first.get('.goal').setValue('下一步目标')
    await first.get(selector).trigger('click')
    expect(create).not.toHaveBeenCalled()
    expect(close).toHaveBeenCalledOnce()
    first.unmount()
    const second = await open()
    expect(second.get<HTMLInputElement>('.title-input').element.value).toBe('我的新项目')
    expect(second.get<HTMLTextAreaElement>('.goal').element.value).toBe('下一步目标')
  })

  it('草稿写入失败阻止有内容的关闭；失败创建保留输入，重试成功清理草稿', async () => {
    const close = vi.spyOn(useUiStore(), 'closeModal')
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('full') })
    const wrapper = await open()
    await wrapper.get('.title-input').setValue('保存验证')
    await wrapper.get('.close').trigger('click')
    expect(close).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('浏览器未能保存草稿')
    storage.mockRestore()
    const create = vi.spyOn(useDataStore(), 'addProject').mockRejectedValueOnce(new Error('offline'))
    await wrapper.get('.btn-primary').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('保存失败，输入已保留')
    expect(wrapper.get<HTMLInputElement>('.title-input').element.value).toBe('保存验证')
    create.mockResolvedValueOnce({ id: 'new', name: '保存验证' } as Awaited<ReturnType<typeof create>>)
    vi.spyOn(useProjectListLayoutStore(), 'consumeCreateGroupTarget').mockResolvedValue()
    vi.spyOn(useUiStore(), 'celebrate').mockImplementation(() => {})
    await wrapper.get('.btn-primary').trigger('click')
    await flushPromises()
    expect(close).toHaveBeenCalledOnce()
    expect(sessionStorage.length).toBe(0)
  })

  it('成员切换不带入前一成员草稿', async () => {
    const session = useSessionStore()
    const wrapper = await open()
    await wrapper.get('.title-input').setValue('成员甲草稿')
    session.account = { accountId: 'account-b', member: { id: 'b', name: '乙', initial: '乙', color: '#000', role: '' }, identities: [], createdAt: '' }
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('.title-input').element.value).toBe('')
    session.account = null
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('.title-input').element.value).toBe('成员甲草稿')
  })
})
