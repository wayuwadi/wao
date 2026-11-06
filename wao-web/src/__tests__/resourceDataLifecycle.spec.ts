import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import * as api from '@/api/board'
import type { FileItem, HostedPage, ImageItem } from '@/types'

const file: FileItem = { id: 'f1', name: 'one.txt', size: '1 KB', time: '', by: '', icon: '', link: '/f/f1' }
const image: ImageItem = { id: 'i1', name: 'one.png', url: '/i/i1', size: '1 KB', time: '', from: '', c1: '', c2: '' }
const page: HostedPage = { id: 'h1', title: 'One', slug: 'one', fileName: 'one.html', hasIcon: false, updatedAt: '', url: '/api/pages/one' }
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (cause: Error) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}
let pinia: ReturnType<typeof createPinia>
beforeEach(() => { localStorage.clear(); pinia = createPinia(); setActivePinia(pinia) })
afterEach(() => { disposePinia(pinia); vi.restoreAllMocks() })
function identity(accountId: string, member = 'member') {
  useSessionStore().account = { accountId, member: { id: member } } as never
}

describe('资源库快照与写入一致性', () => {
  it('网页编辑与内容替换透传零版本，冲突不更新本地已确认内容', async () => {
    const data = useDataStore()
    const current = { ...page, revision: 0 }
    data.hostedPages = [current]
    const conflict = Object.assign(new Error('网页已更新'), { status: 409 })
    const edit = vi.spyOn(api, 'apiSaveHostedPage').mockRejectedValueOnce(conflict)
    const replace = vi.spyOn(api, 'apiReplaceHostedPage').mockResolvedValueOnce({ ...current, revision: 1 })
    const payload = { id: page.id, slug: page.slug, title: '冲突草稿', expectedRevision: 0 }
    await expect(data.saveHostedPage(payload)).rejects.toBe(conflict)
    expect(edit).toHaveBeenCalledWith(payload)
    expect(data.hostedPages).toEqual([current])
    const html = new File(['html'], 'new.html', { type: 'text/html' })
    await data.replaceHostedPage(page.id, html, 0)
    expect(replace).toHaveBeenCalledWith(page.id, html, 0)
    expect(data.hostedPages[0]?.revision).toBe(1)
  })

  it('壳层和页面同时读取文件时只发起一次请求', async () => {
    const data = useDataStore()
    const pending = deferred<FileItem[]>()
    const read = vi.spyOn(api, 'fetchFiles').mockReturnValue(pending.promise)
    const shell = data.hydrate(['files'])
    const view = data.refreshFiles()
    expect(read).toHaveBeenCalledOnce()
    pending.resolve([file])
    await shell
    expect(await view).toBe(true)
    expect(data.files).toEqual([file])
  })

  it('慢快照不会吞掉刚上传的文件或恢复已确认删除的文件', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.files = [file]
    const pending = deferred<FileItem[]>()
    vi.spyOn(api, 'fetchFiles').mockReturnValue(pending.promise)
    vi.spyOn(api, 'apiDeleteFile').mockResolvedValue(undefined)
    const added = { ...file, id: 'f2', name: 'two.txt' }
    vi.spyOn(api, 'apiUploadFile').mockResolvedValue(added)
    const reading = data.refreshFiles()
    await data.deleteFile(file.id!)
    await data.uploadFile(new File(['new'], 'two.txt'))
    pending.resolve([file])
    expect(await reading).toBe(true)
    expect(data.files).toEqual([added])
  })

  it('图片上传和删除在读取结束后保持服务器已确认结果', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.imgs = [image]
    const pending = deferred<ImageItem[]>()
    vi.spyOn(api, 'fetchImages').mockReturnValue(pending.promise)
    vi.spyOn(api, 'apiDeleteImage').mockResolvedValue(undefined)
    const added = { ...image, id: 'i2', url: '/i/i2', name: 'two.png' }
    vi.spyOn(api, 'apiUploadImage').mockResolvedValue(added)
    const reading = data.refreshImages()
    await data.deleteImage('i1')
    await data.uploadImage(new File(['png'], 'two.png', { type: 'image/png' }), '资源库', { requirePersisted: true })
    pending.resolve([image])
    await reading
    expect(data.imgs).toEqual([added])
  })

  it('读取中的网页更新及文件改名不会被旧列表覆盖', async () => {
    const data = useDataStore()
    data.files = [file]; data.hostedPages = [page]
    const oldFiles = deferred<FileItem[]>(), oldPages = deferred<HostedPage[]>()
    vi.spyOn(api, 'fetchFiles').mockReturnValue(oldFiles.promise)
    vi.spyOn(api, 'fetchHostedPages').mockReturnValue(oldPages.promise)
    const renamed = { ...file, displayName: '新显示名称' }, edited = { ...page, title: '已确认标题' }
    vi.spyOn(api, 'apiRenameFile').mockResolvedValue(renamed)
    vi.spyOn(api, 'apiSaveHostedPage').mockResolvedValue(edited)
    const reads = [data.refreshFiles(), data.refreshHostedPages()]
    await data.renameFileDisplayName('f1', renamed.displayName)
    await data.saveHostedPage({ id: 'h1', slug: 'one', title: edited.title })
    oldFiles.resolve([file]); oldPages.resolve([page])
    await Promise.all(reads)
    expect(data.files).toEqual([renamed]); expect(data.hostedPages).toEqual([edited])
  })

  it('读取失败保留已确认内容，再次读取成功才清除错误', async () => {
    const data = useDataStore()
    data.hostedPages = [page]
    vi.spyOn(api, 'fetchHostedPages').mockRejectedValueOnce(new Error('服务繁忙')).mockResolvedValueOnce([page])
    expect(await data.refreshHostedPages()).toBe(false)
    expect(data.hostedPages).toEqual([page])
    expect(data.hostedPageLoadError).toBe('服务繁忙')
    expect(await data.refreshHostedPages()).toBe(true)
    expect(data.hostedPageLoadError).toBe('')
  })

  it('同一文件的改名与删除顺序执行，旧回执不能复活已删除项', async () => {
    const data = useDataStore(), pending = deferred<FileItem>()
    data.apiLive = true; data.files = [file]
    const rename = vi.spyOn(api, 'apiRenameFile').mockReturnValue(pending.promise)
    const remove = vi.spyOn(api, 'apiDeleteFile').mockResolvedValue(undefined)
    const renaming = data.renameFileDisplayName('f1', '新名称', null)
    const deleting = data.deleteFile('f1')
    expect(rename).toHaveBeenCalledWith('f1', '新名称', null)
    expect(remove).not.toHaveBeenCalled()
    pending.resolve({ ...file, displayName: '新名称' })
    await renaming; await deleting
    expect(remove).toHaveBeenCalledOnce()
    expect(data.files).toEqual([])
  })

  it('不同资源互不阻塞，失败写入不会卡住后续删除', async () => {
    const data = useDataStore(), pending = deferred<FileItem>()
    data.apiLive = true; data.files = [file, { ...file, id: 'f2' }]
    vi.spyOn(api, 'apiRenameFile').mockReturnValue(pending.promise)
    const remove = vi.spyOn(api, 'apiDeleteFile').mockResolvedValue(undefined)
    const renaming = data.renameFileDisplayName('f1', '失败名称')
    const expectedFailure = expect(renaming).rejects.toThrow('保存失败')
    const deletingFirst = data.deleteFile('f1')
    await data.deleteFile('f2')
    expect(remove).toHaveBeenCalledExactlyOnceWith('f2')
    pending.reject(new Error('保存失败'))
    await expectedFailure; await deletingFirst
    expect(data.files).toEqual([])
  })
})

describe('资源库账号隔离', () => {
  it('同成员切换账号立即清空三个资源列表，来回切换也丢弃旧响应', async () => {
    identity('a')
    const data = useDataStore()
    data.files = [file]; data.imgs = [image]; data.hostedPages = [page]
    const pending = deferred<HostedPage[]>()
    vi.spyOn(api, 'fetchHostedPages').mockReturnValue(pending.promise)
    const reading = data.refreshHostedPages()
    identity('b')
    expect(data.files).toEqual([]); expect(data.imgs).toEqual([]); expect(data.hostedPages).toEqual([])
    identity('a')
    pending.resolve([page])
    expect(await reading).toBe(false)
    expect(data.hostedPages).toEqual([])
    expect(data.hostedPageLoadState).toBe('idle')
  })

  it('旧账号失败不会覆盖新账号成功状态', async () => {
    identity('a')
    const data = useDataStore(), old = deferred<ImageItem[]>()
    vi.spyOn(api, 'fetchImages').mockReturnValueOnce(old.promise).mockResolvedValueOnce([image])
    const reading = data.refreshImages()
    identity('b')
    await data.refreshImages()
    old.reject(new Error('旧账号断线'))
    expect(await reading).toBe(false)
    expect(data.imgs).toEqual([image])
    expect(data.loadStates.images).toBe('loaded')
    expect(data.loadErrors.images).toBeUndefined()
  })

  it('旧账号的替换确认不能写入同 id 的新账号资源', async () => {
    identity('a')
    const data = useDataStore(), pending = deferred<FileItem>()
    vi.spyOn(api, 'apiReplaceFile').mockReturnValue(pending.promise)
    const replacing = data.replaceFile('f1', new File(['new'], 'new.txt'))
    identity('b')
    const current = { ...file, name: '新账号文件.txt' }
    data.files = [current]
    pending.resolve({ ...file, name: '旧账号替换.txt' })
    await expect(replacing).rejects.toThrow('账号已切换')
    expect(data.files).toEqual([current])
  })

  it('账号切换后排队中的旧写请求不会发往新账号', async () => {
    identity('a')
    const data = useDataStore(), pending = deferred<FileItem>()
    data.apiLive = true
    vi.spyOn(api, 'apiRenameFile').mockReturnValue(pending.promise)
    const remove = vi.spyOn(api, 'apiDeleteFile').mockResolvedValue(undefined)
    const renaming = data.renameFileDisplayName('f1', '旧账号名称')
    const renameRejected = expect(renaming).rejects.toThrow('账号已切换')
    const deleting = data.deleteFile('f1')
    const deleteRejected = expect(deleting).rejects.toThrow('账号已切换')
    identity('b')
    pending.resolve({ ...file, displayName: '旧账号名称' })
    await renameRejected; await deleteRejected
    expect(remove).not.toHaveBeenCalled()
  })
})

describe('上传错误可重试反馈', () => {
  it('严格上传直接使用服务器结果并保留具体失败原因', async () => {
    const data = useDataStore()
    expect(data.apiLive).toBe(false)
    vi.spyOn(api, 'apiUploadFile').mockRejectedValue(new Error('文件超过管理员设置的 12MB 限制'))
    await expect(data.uploadFile(new File(['a'], 'one.txt'), undefined, false, { throwOnError: true })).rejects.toThrow('12MB')
    vi.spyOn(api, 'apiUploadImage').mockRejectedValue(new Error('图片格式无法识别'))
    await expect(data.uploadImage(new File(['a'], 'one.png', { type: 'image/png' }), '', { requirePersisted: true, throwOnError: true })).rejects.toThrow('格式无法识别')
    expect(data.files).toEqual([]); expect(data.imgs).toEqual([])
  })
})
