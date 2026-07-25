import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { absoluteUrl } from '@/utils/share'

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

  it('删除按 id 生效', async () => {
    const seeded = data.imgs.length
    await data.uploadImage(png())
    data.imgs[0]!.id = 'img1'

    await data.deleteImage('img1')

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
