import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import PetCreateView from '@/components/capabilities/resources/PetCreatePanel.vue'
const api = vi.hoisted(() => ({
  createPet: vi.fn(),
  readPetDraft: vi.fn(),
  writePetDraft: vi.fn(),
  clearPetDraft: vi.fn(),
}))
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ userId: 'author' }) }))
vi.mock('@/api/skills', () => ({
  fetchPublisher: async () => ({ handle: 'author', displayName: '作者' }),
  savePublisher: vi.fn(),
}))
vi.mock('@/api/pets', () => ({ createPet: api.createPet }))
vi.mock('@/utils/petDrafts', () => ({
  readPetDraft: api.readPetDraft,
  writePetDraft: api.writePetDraft,
  clearPetDraft: api.clearPetDraft,
}))
async function render() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/pets/create', component: PetCreateView },
      { path: '/capabilities', component: { template: '<p>我的宠物</p>' } },
    ],
  })
  await router.push('/pets/create')
  await router.isReady()
  const wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}
describe('宠物创建保存与防丢', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset())
    api.readPetDraft.mockResolvedValue({
      mode: 'zip',
      slug: '',
      name: '',
      description: '',
      version: 2,
      source: '原创',
      license: 'CC0-1.0',
      file: new File(['zip'], 'pet.zip'),
    })
    api.writePetDraft.mockResolvedValue(undefined)
    api.clearPetDraft.mockResolvedValue(undefined)
  })
  it('恢复真实文件后保存成功进入我的宠物，清除本地草稿', async () => {
    api.createPet.mockResolvedValue({ id: 'saved' })
    const { wrapper, router } = await render()
    expect(wrapper.text()).toContain('pet.zip')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.createPet).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.path).toBe('/capabilities')
    expect(api.clearPetDraft).toHaveBeenCalledWith('author')
    wrapper.unmount()
  })
  it('服务端失败保留所选文件和输入，允许原样重试', async () => {
    api.createPet
      .mockRejectedValueOnce(new Error('图集格式错误'))
      .mockResolvedValueOnce({ id: 'saved' })
    const { wrapper, router } = await render()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('图集格式错误')
    expect(wrapper.text()).toContain('pet.zip')
    expect(api.clearPetDraft).not.toHaveBeenCalled()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/capabilities')
    wrapper.unmount()
  })
  it('草稿写入尚未完成时保护刷新，落地后允许刷新', async () => {
    const { wrapper } = await render()
    let finish!: () => void
    api.writePetDraft.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    await wrapper.get('input[maxlength="500"]').setValue('本地验收来源')
    await flushPromises()
    expect(wrapper.text()).toContain('正在保存本地草稿')
    const pendingUnload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(pendingUnload)
    expect(pendingUnload.defaultPrevented).toBe(true)
    finish()
    await flushPromises()
    const savedUnload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(savedUnload)
    expect(savedUnload.defaultPrevented).toBe(false)
    expect(wrapper.text()).toContain('本地草稿已保存')
    wrapper.unmount()
  })
  it('刷新屏障等待真实草稿队列，失败可在下一次刷新时重试', async () => {
    const { wrapper } = await render()
    let fail!: (error: Error) => void
    api.writePetDraft.mockImplementationOnce(
      () =>
        new Promise((_resolve, reject) => {
          fail = reject
        }),
    )
    await wrapper.get('input[maxlength="500"]').setValue('待保存来源')
    await flushPromises()
    const pending: Promise<unknown>[] = []
    window.dispatchEvent(
      new CustomEvent('wao-save-before-refresh', {
        detail: { waitUntil: (p: Promise<unknown>) => pending.push(p) },
      }),
    )
    const rejection = expect(Promise.all(pending)).rejects.toThrow('宠物本地草稿尚未保存')
    fail(new Error('quota'))
    await rejection
    await flushPromises()
    expect(wrapper.text()).toContain('本地草稿保存失败')
    pending.length = 0
    window.dispatchEvent(
      new CustomEvent('wao-save-before-refresh', {
        detail: { waitUntil: (p: Promise<unknown>) => pending.push(p) },
      }),
    )
    await Promise.all(pending)
    await flushPromises()
    expect(wrapper.text()).toContain('本地草稿已保存')
    expect((wrapper.get('input[maxlength="500"]').element as HTMLInputElement).value).toBe(
      '待保存来源',
    )
    wrapper.unmount()
  })

  it('离开等待期间新增编辑也必须写入后才导航', async () => {
    const { wrapper, router } = await render()
    let firstDone!: () => void
    let secondDone!: () => void
    api.writePetDraft
      .mockImplementationOnce(
        () =>
          new Promise<void>((resolve) => {
            firstDone = resolve
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<void>((resolve) => {
            secondDone = resolve
          }),
      )
    await wrapper.get('input[maxlength="500"]').setValue('第一版来源')
    await flushPromises()
    const navigation = router.push('/capabilities?area=published&type=Pet')
    await flushPromises()
    await wrapper.get('input[maxlength="500"]').setValue('离开前继续修改的来源')
    firstDone()
    await flushPromises()
    const routeWhilePending = router.currentRoute.value.path
    secondDone()
    await navigation
    await flushPromises()
    const lastWritten = api.writePetDraft.mock.calls[api.writePetDraft.mock.calls.length - 1]?.[1]
    const finalRoute = router.currentRoute.value.path
    wrapper.unmount()
    expect(routeWhilePending).toBe('/pets/create')
    expect(lastWritten.source).toBe('离开前继续修改的来源')
    expect(finalRoute).toBe('/capabilities')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })
  it.each([
    [1872, 1],
    [2288, 2],
  ])('自动识别高度 %s 的图集，不要求用户选择版本', async (height, version) => {
    const images: Array<{ width: number; height: number; onload: () => void; src: string }> = []
    vi.stubGlobal(
      'Image',
      class {
        width = 1536
        height = height
        onload = () => {}
        src = ''
        constructor() {
          images.push(this)
        }
      },
    )
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:valid')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    api.readPetDraft.mockResolvedValue(undefined)
    const { wrapper } = await render()
    await wrapper.get('select').setValue('create')
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', {
      value: [new File(['image'], '动作.png', { type: 'image/png' })],
      configurable: true,
    })
    await input.trigger('change')
    expect(wrapper.text()).toContain('正在识别图集')
    images[0]!.onload()
    await flushPromises()
    expect(wrapper.text()).toContain('已自动识别')
    expect((wrapper.get('input[maxlength="64"]').element as HTMLInputElement).value).toMatch(
      /^pet-[a-f0-9]{8}$/,
    )
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const body = api.createPet.mock.calls[0]![0] as FormData
    expect(JSON.parse(body.get('manifest') as string).spriteVersionNumber).toBe(version)
    wrapper.unmount()
  })
  it('无效图片说明下一步，新选择忽略旧回调并清除错误', async () => {
    const images: Array<{ width: number; height: number; onload: () => void; src: string }> = []
    vi.stubGlobal(
      'Image',
      class {
        width = 10
        height = 10
        onload = () => {}
        src = ''
        constructor() {
          images.push(this)
        }
      },
    )
    let sequence = 0
    vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:${++sequence}`)
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    api.readPetDraft.mockResolvedValue(undefined)
    const { wrapper } = await render()
    await wrapper.get('select').setValue('create')
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', {
      value: [new File(['image'], '动作.png', { type: 'image/png' })],
      configurable: true,
    })
    await input.trigger('change')
    images[0]!.onload()
    await flushPromises()
    expect(wrapper.text()).toContain('单张照片不能直接使用')
    await wrapper.get('input[maxlength="64"]').setValue('my-chosen-id')
    await input.trigger('change')
    // 同一文件对象不触发 watcher；重新选择不同文件。
    Object.defineProperty(input.element, 'files', {
      value: [new File(['new'], '新动作.png', { type: 'image/png' })],
      configurable: true,
    })
    await input.trigger('change')
    images[0]!.onload()
    await flushPromises()
    expect(wrapper.text()).not.toContain('这张图片为')
    images[1]!.width = 1536
    images[1]!.height = 1872
    images[1]!.onload()
    await flushPromises()
    expect(wrapper.text()).toContain('已自动识别基础动作图集')
    expect((wrapper.get('input[maxlength="64"]').element as HTMLInputElement).value).toBe(
      'my-chosen-id',
    )
    wrapper.unmount()
  })
})
