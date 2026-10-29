import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { flushPromises } from '@vue/test-utils'
import { PROJECTS } from '@/data/fixtures'
import type { FileItem, ImageItem, VaultGroup } from '@/types'

const api = vi.hoisted(() => ({
  fetchFiles: vi.fn<(typeof import('@/api/board'))['fetchFiles']>(),
  fetchVault: vi.fn<(typeof import('@/api/board'))['fetchVault']>(),
  fetchProjects: vi.fn<(typeof import('@/api/board'))['fetchProjects']>(),
  fetchTasks: vi.fn<(typeof import('@/api/board'))['fetchTasks']>(),
  apiDeleteImage: vi.fn<(typeof import('@/api/board'))['apiDeleteImage']>(),
  apiDeleteFile: vi.fn<(typeof import('@/api/board'))['apiDeleteFile']>(),
  apiDeleteVaultItem: vi.fn<(typeof import('@/api/board'))['apiDeleteVaultItem']>(),
  apiDeleteVaultGroup: vi.fn<(typeof import('@/api/board'))['apiDeleteVaultGroup']>(),
}))
vi.mock('@/api/board', async original => ({ ...await original<object>(), ...api }))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}
const file: FileItem = { id: 'f1', name: '示例.txt', size: '1 KB', time: '', by: '', icon: '', link: '/f/f1' }
const image: ImageItem = { id: 'i1', name: '示例.png', url: '/i/i1', size: '1 KB', time: '', from: '', c1: '', c2: '' }
const group: VaultGroup = { id: 'g1', group: '示例分组', items: [{ id: 'v1', name: '示例条目', icon: '', user: '', site: '', note: '', updated: '', hasSecret: true }] }

beforeEach(() => { vi.resetAllMocks(); localStorage.clear(); setActivePinia(createPinia()); vi.useFakeTimers() })
afterEach(() => { vi.clearAllTimers(); vi.useRealTimers() })

describe('按模块重试和失败保留', () => {
  it('快模块拿到结果就同时展示列表和已加载状态，不等待其他慢模块', async () => {
    const data = useDataStore()
    const slow = deferred<VaultGroup[]>()
    api.fetchProjects.mockResolvedValue([PROJECTS[0]!])
    api.fetchVault.mockReturnValue(slow.promise)
    const loading = data.hydrate(['projects', 'vault'])
    await flushPromises()
    expect(data.loadStates.projects).toBe('loaded')
    expect(data.projects).toEqual([PROJECTS[0]!])
    expect(data.loadStates.vault).toBe('loading')
    expect(data.apiLive).toBe(true)
    slow.resolve([])
    await loading
  })
  it('文件重试只发起文件请求，不清空知识库、密码库，也不触发任务写入', async () => {
    const data = useDataStore()
    data.files = [file]
    data.vault = [group]
    data.loadStates.files = 'error'
    data.loadErrors.files = '请求超时'
    const pending = deferred<FileItem[]>()
    api.fetchFiles.mockReturnValue(pending.promise)
    const retry = data.retryDomain('files')
    expect(data.files).toEqual([file])
    expect(data.vault).toEqual([group])
    expect(api.fetchTasks).not.toHaveBeenCalled()
    expect(api.fetchProjects).not.toHaveBeenCalled()
    expect(api.fetchVault).not.toHaveBeenCalled()
    expect(data.loadStates.files).toBe('loading')
    expect(await data.retryDomain('files')).toBe(false)
    expect(api.fetchFiles).toHaveBeenCalledOnce()
    pending.reject(new Error('仍然超时'))
    expect(await retry).toBe(false)
    expect(data.files).toEqual([file])
    expect(data.loadErrors.files).toBe('仍然超时')
    api.fetchFiles.mockResolvedValue([])
    expect(await data.retryDomain('files')).toBe(true)
    expect(data.files).toEqual([])
    expect(data.loadErrors.files).toBeUndefined()
    expect(data.apiLive).toBe(true)
  })

  it('密码库局部刷新失败仍保留上次列表，恢复后清除全局与本页错误', async () => {
    const data = useDataStore()
    data.vault = [group]
    api.fetchVault.mockRejectedValueOnce(new Error('网络中断')).mockResolvedValueOnce([group])
    expect(await data.reloadVault()).toBe(false)
    expect(data.vault).toEqual([group])
    expect(data.vaultError).toBe(true)
    expect(data.loadStates.vault).toBe('error')
    expect(await data.reloadVault()).toBe(true)
    expect(data.vaultError).toBe(false)
    expect(data.loadStates.vault).toBe('loaded')
    expect(data.loadErrors.vault).toBeUndefined()
  })

  it('切换账号后丢弃上一账号迟到的文件列表', async () => {
    const data = useDataStore()
    const session = useSessionStore()
    const pending = deferred<FileItem[]>()
    api.fetchFiles.mockReturnValue(pending.promise)
    const loading = data.refreshFiles()
    session.account = { member: { id: 'other' } } as typeof session.account
    pending.resolve([file])
    expect(await loading).toBe(false)
    expect(data.files).toEqual([])
  })
})

describe('删除必须收到服务器确认', () => {
  it.each(['image', 'file', 'vaultItem', 'vaultGroup'] as const)('%s 失败时保留条目，重试成功后才移除', async kind => {
    const data = useDataStore()
    data.apiLive = true
    data.imgs = [image]
    data.files = [file]
    data.vault = structuredClone([group])
    const operations = {
      image: { remote: api.apiDeleteImage, run: () => data.deleteImage('i1'), count: () => data.imgs.length },
      file: { remote: api.apiDeleteFile, run: () => data.deleteFile('f1'), count: () => data.files.length },
      vaultItem: { remote: api.apiDeleteVaultItem, run: () => data.deleteVaultItem('v1'), count: () => data.vault[0]!.items.length },
      vaultGroup: { remote: api.apiDeleteVaultGroup, run: () => data.deleteVaultGroup('g1'), count: () => data.vault.length },
    }
    const operation = operations[kind]
    const pending = deferred<void>()
    operation.remote.mockReturnValueOnce(pending.promise).mockResolvedValueOnce(undefined)
    const deleting = operation.run()
    expect(operation.count()).toBe(1)
    pending.reject(new Error('服务暂不可用'))
    await expect(deleting).rejects.toThrow('服务暂不可用')
    expect(operation.count()).toBe(1)
    await operation.run()
    expect(operation.count()).toBe(0)
  })

  it('离线时不制造删除成功结果', async () => {
    const data = useDataStore()
    data.imgs = [image]
    await expect(data.deleteImage('i1')).rejects.toThrow('尚未删除')
    expect(api.apiDeleteImage).not.toHaveBeenCalled()
    expect(data.imgs).toEqual([image])
  })
})
