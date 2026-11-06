import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import FileDisplayNameEditor from '@/components/files/FileDisplayNameEditor.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import type { FileItem } from '@/types'
import {
  clearResourceNameDraft,
  readResourceNameDraft,
  resourceNameDraftKey,
} from '@/utils/resourceNameDraft'

const file = {
  id: 'f1',
  name: 'search_recommendation_product_architecture.html',
  displayName: null,
  size: '2 KB',
  icon: 'ri-file-line',
  time: '刚刚',
  by: 'wg',
  link: '/api/files/f1/download',
}

describe('FileDisplayNameEditor', () => {
  function createSession(accountId: string) {
    const pinia = createPinia()
    const session = useSessionStore(pinia)
    session.account = {
      accountId,
      member: { id: 'member-a', name: '甲', initial: '甲', color: '', role: '' },
      identities: [],
      createdAt: '',
    }
    const key = resourceNameDraftKey(accountId, 'member-a', 'f1')
    clearResourceNameDraft(key)
    const options = { props: { file: { ...file } }, global: { plugins: [pinia] } }
    return { pinia, session, key, options }
  }

  it('翻页卸载后恢复改名草稿，取消明确清理', async () => {
    const { options, key } = createSession('draft-navigation')
    const first = mount(FileDisplayNameEditor, options)
    await first.get('[title="修改 WAO 展示名称"]').trigger('click')
    await first.get('input').setValue('尚未保存的名称')
    expect(readResourceNameDraft(key)?.value).toBe('尚未保存的名称')
    first.unmount()
    const second = mount(FileDisplayNameEditor, options)
    expect((second.get('input').element as HTMLInputElement).value).toBe('尚未保存的名称')
    expect(second.text()).toContain('草稿保留')
    await second.findAll('.rename-row button')[1]!.trigger('click')
    expect(readResourceNameDraft(key)).toBeNull()
    expect(second.find('input').exists()).toBe(false)
    second.unmount()
  })

  it('保存失败草稿跨卸载保留，成功后清理', async () => {
    const { pinia, options, key } = createSession('draft-failure')
    const data = useDataStore(pinia)
    const rename = vi
      .spyOn(data, 'renameFileDisplayName')
      .mockRejectedValueOnce(new Error('服务暂不可用'))
      .mockResolvedValueOnce({ ...file, displayName: '新草稿' })
    const first = mount(FileDisplayNameEditor, options)
    await first.get('[title="修改 WAO 展示名称"]').trigger('click')
    await first.get('input').setValue('新草稿')
    await first.get('.rename-row button').trigger('click')
    await flushPromises()
    expect(first.get('[role="alert"]').text()).toContain('服务暂不可用')
    first.unmount()
    const second = mount(FileDisplayNameEditor, options)
    expect((second.get('input').element as HTMLInputElement).value).toBe('新草稿')
    await second.get('.rename-row button').trigger('click')
    await flushPromises()
    expect(rename).toHaveBeenCalledTimes(2)
    expect(readResourceNameDraft(key)).toBeNull()
    second.unmount()
  })

  it('相同文件在不同账号间隔离草稿', async () => {
    const { session, options, key } = createSession('draft-account-a')
    const wrapper = mount(FileDisplayNameEditor, options)
    await wrapper.get('[title="修改 WAO 展示名称"]').trigger('click')
    await wrapper.get('input').setValue('甲的私有草稿')
    session.account = { ...session.account!, accountId: 'draft-account-b' }
    await flushPromises()
    expect(wrapper.find('input').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('甲的私有草稿')
    session.account = { ...session.account!, accountId: 'draft-account-a' }
    await flushPromises()
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('甲的私有草稿')
    clearResourceNameDraft(key)
    wrapper.unmount()
  })

  it('已知服务端名称变化时阻止直接覆盖，明确确认后才保存草稿', async () => {
    const { pinia, options } = createSession('draft-conflict')
    const rename = vi
      .spyOn(useDataStore(pinia), 'renameFileDisplayName')
      .mockResolvedValue({ ...file, displayName: '我的草稿' })
    const wrapper = mount(FileDisplayNameEditor, options)
    await wrapper.get('[title="修改 WAO 展示名称"]').trigger('click')
    await wrapper.get('input').setValue('我的草稿')
    await wrapper.setProps({ file: { ...file, displayName: '其他设备的新名称' } })
    expect(wrapper.get('[role="alert"]').text()).toContain('其他设备的新名称')
    expect(wrapper.get('.rename-row button').attributes('disabled')).toBeDefined()
    await wrapper.get('input').trigger('keydown', { key: 'Enter' })
    expect(rename).not.toHaveBeenCalled()
    await wrapper.findAll('.rename-conflict button')[1]!.trigger('click')
    await flushPromises()
    expect(rename).toHaveBeenCalledWith('f1', '我的草稿', '其他设备的新名称')
    wrapper.unmount()
  })

  it('旧保存回执不清掉翻页后重新编辑的新草稿', async () => {
    const { pinia, options, key } = createSession('draft-late-save')
    let resolve!: (value: FileItem) => void
    vi.spyOn(useDataStore(pinia), 'renameFileDisplayName').mockImplementation(
      () =>
        new Promise((result) => {
          resolve = result
        }),
    )
    const first = mount(FileDisplayNameEditor, options)
    await first.get('[title="修改 WAO 展示名称"]').trigger('click')
    await first.get('input').setValue('正在保存的名称')
    await first.get('.rename-row button').trigger('click')
    first.unmount()
    const second = mount(FileDisplayNameEditor, options)
    await second.get('input').setValue('更晚输入的新名称')
    resolve({ ...file, displayName: '正在保存的名称' })
    await flushPromises()
    expect(readResourceNameDraft(key)?.value).toBe('更晚输入的新名称')
    expect((second.get('input').element as HTMLInputElement).value).toBe('更晚输入的新名称')
    clearResourceNameDraft(key)
    second.unmount()
  })

  it('409冲突保留原始比较值，刷新失败不覆盖，读到最新值后显式覆盖继续使用CAS', async () => {
    const { pinia, options, key } = createSession('draft-server-conflict')
    const data = useDataStore(pinia)
    const rename = vi
      .spyOn(data, 'renameFileDisplayName')
      .mockRejectedValueOnce(Object.assign(new Error('名称已被其他人修改'), { status: 409 }))
      .mockResolvedValueOnce({ ...file, displayName: '我的名称' })
    const refresh = vi.spyOn(data, 'refreshFiles').mockResolvedValueOnce(false)
    const wrapper = mount(FileDisplayNameEditor, options)
    await wrapper.get('[title="修改 WAO 展示名称"]').trigger('click')
    await wrapper.get('input').setValue('我的名称')
    expect(readResourceNameDraft(key)?.expectedName).toBeNull()
    await wrapper.get('.rename-row button').trigger('click')
    await flushPromises()
    expect(rename).toHaveBeenCalledWith('f1', '我的名称', null)
    expect(wrapper.get('[role="alert"]').text()).toContain('最新名称读取失败')
    expect(wrapper.get('.rename-row button').attributes('disabled')).toBeDefined()
    refresh.mockImplementationOnce(async () => {
      await wrapper.setProps({ file: { ...file, displayName: '最新服务器名称' } })
      return true
    })
    await wrapper.get('.rename-conflict button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('最新服务器名称')
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('我的名称')
    await wrapper.findAll('.rename-conflict button')[1]!.trigger('click')
    await flushPromises()
    expect(rename).toHaveBeenLastCalledWith('f1', '我的名称', '最新服务器名称')
    expect(readResourceNameDraft(key)).toBeNull()
    wrapper.unmount()
  })

  it('取消冲突重读后重新编辑，旧读取失败不污染新会话', async () => {
    const { pinia, options, key } = createSession('draft-canceled-conflict')
    const data = useDataStore(pinia)
    vi.spyOn(data, 'renameFileDisplayName').mockRejectedValueOnce(
      Object.assign(new Error('名称已修改'), { status: 409 }),
    )
    let resolveRefresh!: (value: boolean) => void
    vi.spyOn(data, 'refreshFiles').mockResolvedValueOnce(false).mockImplementationOnce(
      () => new Promise(resolve => { resolveRefresh = resolve }),
    )
    const wrapper = mount(FileDisplayNameEditor, options)
    await wrapper.get('[title="修改 WAO 展示名称"]').trigger('click')
    await wrapper.get('input').setValue('上一轮草稿')
    await wrapper.get('.rename-row button').trigger('click')
    await flushPromises()
    await wrapper.get('.rename-conflict button').trigger('click')
    await wrapper.findAll('.rename-row button')[1]!.trigger('click')
    await wrapper.get('[title="修改 WAO 展示名称"]').trigger('click')
    await wrapper.get('input').setValue('本轮新草稿')
    resolveRefresh(false)
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('本轮新草稿')
    expect(readResourceNameDraft(key)?.value).toBe('本轮新草稿')
    clearResourceNameDraft(key)
    wrapper.unmount()
  })

  it('会话存储不可用时仍能在当前页签翻页恢复', async () => {
    const { options, key } = createSession('draft-storage-failure')
    const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota')
    })
    const first = mount(FileDisplayNameEditor, options)
    await first.get('[title="修改 WAO 展示名称"]').trigger('click')
    await first.get('input').setValue('内存中的草稿')
    first.unmount()
    const second = mount(FileDisplayNameEditor, options)
    expect((second.get('input').element as HTMLInputElement).value).toBe('内存中的草稿')
    write.mockRestore()
    clearResourceNameDraft(key)
    second.unmount()
  })

  it('保存展示名后保留并提示原文件名', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    const rename = vi.spyOn(data, 'renameFileDisplayName').mockImplementation(async (_, name) => ({
      ...file,
      displayName: name,
    }))
    const wrapper = mount(FileDisplayNameEditor, {
      props: { file: { ...file } },
      global: { plugins: [pinia] },
    })

    await wrapper.find('[title="修改 WAO 展示名称"]').trigger('click')
    await wrapper.find('input').setValue('  商品推荐架构  ')
    await wrapper.find('.rename-row button').trigger('click')
    await wrapper.setProps({ file: { ...file, displayName: '商品推荐架构' } })
    await flushPromises()

    expect(rename).toHaveBeenCalledWith('f1', '商品推荐架构', null)
    expect(wrapper.text()).toContain('商品推荐架构')
    expect(wrapper.text()).toContain('原文件：search_recommendation_product_architecture.html')
    expect(wrapper.text()).toContain('已保存')
  })

  it('空名称不提交，保存失败时保留输入并提供重试', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    const rename = vi
      .spyOn(data, 'renameFileDisplayName')
      .mockRejectedValueOnce(new Error('服务暂不可用'))
      .mockResolvedValueOnce({ ...file, displayName: '新名称' })
    const wrapper = mount(FileDisplayNameEditor, {
      props: { file: { ...file } },
      global: { plugins: [pinia] },
    })

    await wrapper.find('[title="修改 WAO 展示名称"]').trigger('click')
    await wrapper.find('input').setValue('   ')
    await wrapper.find('.rename-row button').trigger('click')
    expect(rename).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('展示名称不能为空')

    await wrapper.find('input').setValue('新名称')
    await wrapper.find('.rename-row button').trigger('click')
    await flushPromises()
    expect(wrapper.find('input').exists()).toBe(true)
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('新名称')
    expect(wrapper.text()).toContain('服务暂不可用')
    expect(wrapper.find('.rename-row button').text()).toBe('重试')

    await wrapper.find('.rename-row button').trigger('click')
    await flushPromises()
    expect(rename).toHaveBeenCalledTimes(2)
  })
})
