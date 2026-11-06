import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { absoluteUrl } from '@/utils/share'
import { useSessionStore } from '@/stores/session'
import { apiDeleteImage, apiUploadImage, fetchImages, fetchFiles } from '@/api/board'
vi.mock('@/api/board', async original => ({ ...await original<object>(), apiDeleteImage: vi.fn<typeof apiDeleteImage>().mockResolvedValue(undefined), apiUploadImage: vi.fn<typeof apiUploadImage>(), fetchImages: vi.fn<typeof fetchImages>(), fetchFiles: vi.fn<typeof fetchFiles>() }))

/** 图床：所有上传口子的图都要能在「文件与图床」里看到。 */
describe('data store · 图床', () => {
  let data: ReturnType<typeof useDataStore>

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
  })

  const png = (name = 'a.png', bytes = 100) =>
    new File([new Uint8Array(bytes)], name, { type: 'image/png' })

  it('已取消的图片不发起上传或创建本地图片，严格调用保留取消结论', async () => {
    const controller = new AbortController()
    controller.abort()
    const beforeCalls = vi.mocked(apiUploadImage).mock.calls.length
    expect(await data.uploadImage(png(), '', { signal: controller.signal })).toBeNull()
    await expect(data.uploadImage(png(), '', { signal: controller.signal, throwOnError: true }))
      .rejects.toMatchObject({ name: 'AbortError' })
    expect(vi.mocked(apiUploadImage).mock.calls).toHaveLength(beforeCalls)
    expect(data.imgs).toEqual([])
  })

  it('服务器上传取消后不能回退为本地 data URL', async () => {
    const controller = new AbortController()
    let fail!: (cause: Error) => void
    vi.mocked(apiUploadImage).mockImplementationOnce(() => new Promise((_, reject) => { fail = reject }))
    data.apiLive = true
    const upload = data.uploadImage(png(), '', { signal: controller.signal })
    controller.abort()
    fail(new DOMException('上传已取消', 'AbortError'))
    expect(await upload).toBeNull()
    expect(data.imgs).toEqual([])
  })

  it('本地图片读取期间取消也不回写图床', async () => {
    const controller = new AbortController()
    const upload = data.uploadImage(png(), '', { signal: controller.signal })
    controller.abort()
    expect(await upload).toBeNull()
    expect(data.imgs).toEqual([])
  })

  it('切换账号后丢弃上传图片的迟到结果', async () => {
    const session = useSessionStore()
    session.account = { accountId: 'a', member: { id: 'same-member' } } as never
    let finish!: (image: import('@/types').ImageItem) => void
    vi.mocked(apiUploadImage).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    data.apiLive = true
    const upload = data.uploadImage(png(), '图床', { requirePersisted: true })
    session.account = { accountId: 'b', member: { id: 'same-member' } } as never
    finish({ id: 'old', name: '旧图片', url: '/old.png' } as import('@/types').ImageItem)
    expect(await upload).toBeNull()
    expect(data.imgs).toEqual([])
  })

  it('新账号可以独立刷新，旧文件与图床回执不会覆盖新账号列表', async () => {
    const session = useSessionStore()
    session.account = { accountId: 'a', member: { id: 'm' } } as never
    let oldImages!: (images: import('@/types').ImageItem[]) => void
    let oldFiles!: (files: import('@/types').FileItem[]) => void
    vi.mocked(fetchImages).mockImplementationOnce(() => new Promise(resolve => { oldImages = resolve })).mockResolvedValueOnce([])
    vi.mocked(fetchFiles).mockImplementationOnce(() => new Promise(resolve => { oldFiles = resolve })).mockResolvedValueOnce([])
    const beforeImages = data.refreshImages(), beforeFiles = data.refreshFiles()
    session.account = { accountId: 'b', member: { id: 'm' } } as never
    await Promise.all([data.refreshImages(), data.refreshFiles()])
    oldImages([{ id: 'private-image' } as import('@/types').ImageItem])
    oldFiles([{ id: 'private-file' } as import('@/types').FileItem])
    expect(await beforeImages).toBe(false); expect(await beforeFiles).toBe(false)
    expect(data.imgs).toEqual([]); expect(data.files).toEqual([])
    expect(data.loadStates.images).toBe('loaded'); expect(data.fileLoadState).toBe('loaded')
  })

  it('后端离线时回落成 data URL，但仍然进图床列表', async () => {
    const before = data.imgs.length

    const ref = await data.uploadImage(png('截图.png'), '任务 · 整理证件盒')

    expect(ref?.url.startsWith('data:')).toBe(true)
    expect(data.imgs).toHaveLength(before + 1)
    expect(data.imgs[0]!.name).toBe('截图.png')
    expect(data.imgs[0]!.from).toBe('任务 · 整理证件盒')
  })

  it('没写来源时标明是未持久化的本地上传，不冒充已归档', async () => {
    await data.uploadImage(png())
    expect(data.imgs[0]!.from).toContain('未持久化')
  })

  it('业务正文要求持久化时，后端离线不会把 Base64 编码塞回编辑器', async () => {
    const before = data.imgs.length

    const ref = await data.uploadImage(png('目标截图.png'), '自主目标', { requirePersisted: true })

    expect(ref).toBeNull()
    expect(data.imgs).toHaveLength(before)
  })

  it('超过 10MB 的图直接拒绝，也不会污染图床列表', async () => {
    const before = data.imgs.length

    expect(await data.uploadImage(png('big.png', 10 * 1024 * 1024 + 1))).toBeNull()
    expect(data.imgs).toHaveLength(before)
  })

  it('非图片文件不接受', async () => {
    const pdf = new File([new Uint8Array(10)], 'a.pdf', { type: 'application/pdf' })
    expect(await data.uploadImage(pdf)).toBeNull()
  })

  it('文件大小按人类可读格式记录', async () => {
    await data.uploadImage(png('kb.png', 2048))
    expect(data.imgs[0]!.size).toBe('2 KB')
  })

  it('服务器确认后按 id 移除图片', async () => {
    const seeded = data.imgs.length
    await data.uploadImage(png())
    data.imgs[0]!.id = 'img1'
    data.apiLive = true

    await data.deleteImage('img1')
    expect(apiDeleteImage).toHaveBeenCalledWith('img1')

    expect(data.imgs).toHaveLength(seeded)
    expect(data.imgs.every((i) => i.id !== 'img1')).toBe(true)
  })

  it('图床与文件的演示数据已清空——列表初始为空，只剩用户自己上传的', () => {
    expect(data.imgs).toHaveLength(0)
    expect(data.files).toHaveLength(0)
  })
})

describe('absoluteUrl', () => {
  it('站内相对地址补成绝对地址——分享出去的内容不能带相对路径', () => {
    expect(absoluteUrl('/api/images/img1/raw')).toBe(`${window.location.origin}/api/images/img1/raw`)
  })

  it('已经是绝对地址或 data URL 的原样返回', () => {
    expect(absoluteUrl('https://x.example/y.png')).toBe('https://x.example/y.png')
    expect(absoluteUrl('data:image/png;base64,AAAA')).toBe('data:image/png;base64,AAAA')
  })
})
