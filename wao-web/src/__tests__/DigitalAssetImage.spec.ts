import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import DigitalAssetImage from '@/components/ui/DigitalAssetImage.vue'
import { fetchDigitalAssetBlob } from '@/api/digitalMembers'
vi.mock('@/api/digitalMembers', () => ({ fetchDigitalAssetBlob: vi.fn() }))
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ userId: 'member-one' }) }))
beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal(
    'URL',
    Object.assign(URL, {
      createObjectURL: vi.fn().mockReturnValue('blob:private-photo'),
      revokeObjectURL: vi.fn(),
    }),
  )
})
it('私有照片使用带鉴权的Blob接口，卸载后释放本机URL', async () => {
  vi.mocked(fetchDigitalAssetBlob).mockResolvedValue(new Blob(['photo'], { type: 'image/png' }))
  const wrapper = mount(DigitalAssetImage, { props: { assetId: 'private-photo', alt: '参考照片' } })
  await flushPromises()
  expect(fetchDigitalAssetBlob).toHaveBeenCalledWith('private-photo')
  expect(wrapper.get('img').attributes('src')).toBe('blob:private-photo')
  wrapper.unmount()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:private-photo')
})
it('切换照片后的迟到响应不能覆盖当前图片', async () => {
  let done!: (value: Blob) => void
  vi.mocked(fetchDigitalAssetBlob)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          done = resolve
        }),
    )
    .mockResolvedValue(new Blob(['new'], { type: 'image/png' }))
  const wrapper = mount(DigitalAssetImage, { props: { assetId: 'old-private', alt: '参考照片' } })
  await wrapper.setProps({ assetId: 'new-private' })
  await flushPromises()
  done(new Blob(['old'], { type: 'image/png' }))
  await flushPromises()
  expect(URL.createObjectURL).toHaveBeenCalledOnce()
  wrapper.unmount()
})
