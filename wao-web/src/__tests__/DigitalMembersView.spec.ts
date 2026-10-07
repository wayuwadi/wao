import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import DigitalMembersView from '@/views/DigitalMembersView.vue'
import type { DigitalLibrary, DigitalSubject } from '@/api/digitalMembers'
import { rememberDigitalDraft, readDigitalDraft } from '@/utils/digitalMemberDraft'

const api = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  patch: vi.fn(),
  upload: vi.fn(),
  remove: vi.fn(),
  look: vi.fn(),
  version: vi.fn(),
  confirm: vi.fn(),
  bind: vi.fn(),
  generate: vi.fn(),
  poll: vi.fn(),
}))
let session: { userId: string }
vi.mock('@/stores/session', () => ({ useSessionStore: () => session }))
vi.mock('@/api/digitalMembers', () => ({
  fetchDigitalLibrary: api.list,
  createDigitalSubject: api.create,
  updateDigitalSubject: api.patch,
  uploadDigitalAsset: api.upload,
  deleteDigitalAsset: api.remove,
  createDigitalLook: api.look,
  createDigitalVersion: api.version,
  confirmDigitalVersion: api.confirm,
  saveDigitalBinding: api.bind,
  generateDigitalLook: api.generate,
  fetchDigitalGeneration: api.poll,
}))
const subject = (): DigitalSubject => ({
  id: 'person-one',
  name: '真实成员',
  kind: 'human',
  relation: '家人',
  gender: 'unknown',
  traits: '',
  revision: 3,
  state: 'active',
  mainAssetId: null,
  assets: [],
  looks: [],
  history: [],
})
const library = (subjects = [subject()]): DigitalLibrary => ({
  subjects,
  binding: { subjectId: null, versionId: null, revision: 0 },
  profile: { gender: 'unknown', revision: 0 },
  capability: { referenceImage: false, message: '当前执行器未验证参考图能力' },
})
const options = { global: { stubs: { DigitalAssetImage: true } } }
beforeEach(() => {
  session = reactive({ userId: 'member-one' })
  localStorage.clear()
  vi.resetAllMocks()
  api.list.mockResolvedValue(library())
  api.patch.mockImplementation(async (_id, input) => ({
    ...subject(),
    ...input,
    revision: input.expectedRevision + 1,
  }))
})
describe('数字成员图库持久化与并发', () => {
  it('仅上传形象图片时可将其选为封面并作为来源保存候选', async () => {
    const item = subject()
    item.assets = [{ id: 'portrait-one', name: '上传形象', role: 'portrait', url: '/api/digital-assets/portrait-one/raw', width: 100, height: 100, createdAt: '2026-10-07' }]
    api.list.mockResolvedValue(library([item]))
    api.look.mockResolvedValue({ id: 'portrait-look', name: '上传形象候选', style: '', revision: 1, versions: [{ id: 'portrait-version', number: 1, state: 'candidate', assetIds: ['portrait-one'], portraitAssetId: 'portrait-one', createdAt: '2026-10-07' }] })
    const wrapper = mount(DigitalMembersView, options)
    await flushPromises()
    await wrapper.get('.member').trigger('click')
    await wrapper.findAll('.tabs button')[2]!.trigger('click')
    await wrapper.get('.look-form input').setValue('上传形象候选')
    await wrapper.get('.look-form select').setValue('portrait-one')
    await wrapper.get('.look-form .actions button.primary').trigger('click')
    await flushPromises()
    expect(api.look).toHaveBeenCalledWith('person-one', { name: '上传形象候选', style: '', assetIds: ['portrait-one'], portraitAssetId: 'portrait-one' })
    expect(wrapper.text()).toContain('候选 · 待确认')
    wrapper.unmount()
  })
  it('已归档成员仍可读取并显式恢复，不能重新绑定本人', async () => {
    api.list.mockResolvedValue(library([{ ...subject(), state: 'archived' }]))
    const wrapper = mount(DigitalMembersView, options)
    await flushPromises()
    expect(wrapper.findAll('.member')).toHaveLength(0)
    await wrapper.findAll('.filters button')[4]!.trigger('click')
    await wrapper.get('.member').trigger('click')
    expect(wrapper.text()).toContain('恢复成员')
    expect(wrapper.text()).not.toContain('这是本人')
    await wrapper.get('.savebar .actions button').trigger('click')
    await flushPromises()
    expect(api.patch).toHaveBeenCalledWith('person-one', expect.objectContaining({ state: 'active', expectedRevision: 3 }))
    wrapper.unmount()
  })
  it('恢复新版本草稿保留目标和旧CAS，显式rebase后仍添加到原形象', async () => {
    const item = subject()
    item.looks = [{ id: 'original-look', name: '原形象', style: '', revision: 4, versions: [] }]
    api.list.mockResolvedValue(library([item]))
    api.version.mockResolvedValue(item.looks[0])
    rememberDigitalDraft('member-one', item.id, { form: { name: item.name, kind: item.kind, relation: item.relation, gender: item.gender, traits: item.traits }, expectedRevision: 3,
      look: { name: '原形象', style: '', assetIds: ['photo-one'], portraitAssetId: null, lookId: 'original-look', expectedRevision: 2 } })
    const wrapper = mount(DigitalMembersView, options)
    await flushPromises()
    await wrapper.get('.member').trigger('click')
    await wrapper.findAll('.tabs button')[2]!.trigger('click')
    await wrapper.get('.look-form .actions button.primary').trigger('click')
    expect(api.version).not.toHaveBeenCalled()
    expect(readDigitalDraft('member-one', item.id)?.look.expectedRevision).toBe(2)
    await wrapper.get('.conflict button').trigger('click')
    await wrapper.get('.look-form .actions button.primary').trigger('click')
    await flushPromises()
    expect(api.version).toHaveBeenCalledWith('original-look', { assetIds: ['photo-one'], portraitAssetId: null, expectedRevision: 4 })
    expect(api.look).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('读取失败展示重试，不能当作空图库创建成员', async () => {
    api.list.mockRejectedValueOnce(new Error('读取失败'))
    const wrapper = mount(DigitalMembersView, options)
    await flushPromises()
    expect(wrapper.text()).toContain('读取失败')
    expect(wrapper.text()).not.toContain('还没有数字成员')
    expect(wrapper.get('header button').attributes('disabled')).toBeDefined()
    await wrapper.get('.state button').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.member')).toHaveLength(1)
    wrapper.unmount()
  })
  it('恢复旧草稿不更新CAS版本，显式解决冲突后才可提交', async () => {
    rememberDigitalDraft('member-one', 'person-one', {
      form: {
        name: '本机称呼',
        kind: 'human',
        relation: '家人',
        gender: 'male',
        traits: '本机特征',
      },
      expectedRevision: 2,
      look: { name: '', style: '', assetIds: [], portraitAssetId: null },
    })
    const wrapper = mount(DigitalMembersView, options)
    await flushPromises()
    await wrapper.get('.member').trigger('click')
    expect(wrapper.text()).toContain('账号资料已更新')
    expect(wrapper.get('.form input').element).toHaveProperty('value', '本机称呼')
    await wrapper.get('.savebar button.primary').trigger('click')
    expect(api.patch).not.toHaveBeenCalled()
    expect(readDigitalDraft('member-one', 'person-one')?.expectedRevision).toBe(2)
    await wrapper.get('.conflict button').trigger('click')
    await wrapper.get('.savebar button.primary').trigger('click')
    await flushPromises()
    expect(api.patch).toHaveBeenCalledWith(
      'person-one',
      expect.objectContaining({ name: '本机称呼', expectedRevision: 3, gender: 'male' }),
    )
    wrapper.unmount()
  })
  it('409保存冲突保留当前表单，并显示账号当前值', async () => {
    api.patch.mockRejectedValueOnce(Object.assign(new Error('版本冲突'), { status: 409 }))
    const wrapper = mount(DigitalMembersView, options)
    await flushPromises()
    await wrapper.get('.member').trigger('click')
    await wrapper.get('.form input').setValue('尚未保存的称呼')
    api.list.mockResolvedValueOnce(library([{ ...subject(), name: '其他设备称呼', revision: 4 }]))
    await wrapper.get('.savebar button.primary').trigger('click')
    await flushPromises()
    expect(wrapper.get('.form input').element).toHaveProperty('value', '尚未保存的称呼')
    expect(wrapper.text()).toContain('其他设备称呼')
    expect(readDigitalDraft('member-one', 'person-one')?.expectedRevision).toBe(3)
    wrapper.unmount()
  })
  it('切换账号时清空图库与表单，迟到读取不能泄露前一成员', async () => {
    let done!: (value: DigitalLibrary) => void
    api.list.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          done = resolve
        }),
    )
    const wrapper = mount(DigitalMembersView, options)
    session.userId = 'member-two'
    await flushPromises()
    done(library([{ ...subject(), name: '旧账号私有人物' }]))
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧账号私有人物')
    wrapper.unmount()
  })
  it('创建候选时明确引用照片，候选不能直接设为默认', async () => {
    const item = subject()
    item.assets = [
      {
        id: 'photo-one',
        name: '上传照片',
        role: 'reference',
        url: '/api/digital-assets/photo-one/raw',
        width: 100,
        height: 100,
        createdAt: '2026-10-07',
      },
    ]
    api.list.mockResolvedValue(library([item]))
    api.look.mockResolvedValue({
      id: 'look-one',
      name: '自然形象',
      style: '',
      revision: 1,
      versions: [
        {
          id: 'version-one',
          number: 1,
          state: 'candidate',
          assetIds: ['photo-one'],
          portraitAssetId: null,
          createdAt: '2026-10-07',
        },
      ],
    })
    const wrapper = mount(DigitalMembersView, options)
    await flushPromises()
    await wrapper.get('.member').trigger('click')
    await wrapper.findAll('.tabs button')[2]!.trigger('click')
    await wrapper.get('.look-form input').setValue('自然形象')
    await wrapper.get('.references button').trigger('click')
    await wrapper.get('.look-form .actions button.primary').trigger('click')
    await flushPromises()
    expect(api.look).toHaveBeenCalledWith('person-one', {
      name: '自然形象',
      style: '',
      assetIds: ['photo-one'],
      portraitAssetId: null,
    })
    expect(wrapper.text()).toContain('候选 · 待确认')
    expect(wrapper.text()).not.toContain('设为小记默认')
    expect(wrapper.text()).not.toContain('根据参考照片生成')
    wrapper.unmount()
  })
})
