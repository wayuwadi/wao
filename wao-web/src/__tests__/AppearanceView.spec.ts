import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AppearanceView from '@/views/AppearanceView.vue'
import {
  fetchAppearanceWorkspace,
  saveAppearanceWorkspace,
  uploadAppearancePhoto,
  type AppearanceWorkspace,
} from '@/api/appearance'
import { useSessionStore } from '@/stores/session'

vi.mock('@/api/appearance', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/appearance')>()
  return {
    ...original,
    fetchAppearanceWorkspace:
      vi.fn<(typeof import('@/api/appearance'))['fetchAppearanceWorkspace']>(),
    saveAppearanceWorkspace:
      vi.fn<(typeof import('@/api/appearance'))['saveAppearanceWorkspace']>(),
    uploadAppearancePhoto: vi.fn<(typeof import('@/api/appearance'))['uploadAppearancePhoto']>(),
  }
})

const workspace = (): AppearanceWorkspace => ({
  garments: [],
  outfits: [],
  plans: [],
  wearLogs: [],
  notes: [],
  updatedAt: null,
  principles: '清爽、干净、舒服；合适比喜欢更好。',
  profile: {
    heightCm: null,
    weightKg: null,
    shoulderCm: null,
    waistCm: null,
    measuredOn: null,
    skinTone: '',
    faceShape: '',
    hairTexture: '',
    stylePreferences: '',
    avoidances: '',
    postureGoal: '',
    hairScore: null,
    clothingScore: null,
    shoesScore: null,
    accessoryScore: null,
    bodyScore: null,
    presenceScore: null,
  },
})

describe('AppearanceView', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
    setActivePinia(createPinia())
    useSessionStore().accept({
      accountId: 'account-wg',
      member: { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '成员' },
      identities: [],
      createdAt: '2026-08-29T00:00:00',
    })
    vi.mocked(fetchAppearanceWorkspace).mockReset().mockResolvedValue(workspace())
    vi.mocked(saveAppearanceWorkspace)
      .mockReset()
      .mockImplementation(async (value) => ({
        ...structuredClone(value),
        updatedAt: '2026-08-29T20:00:00',
      }))
    vi.mocked(uploadAppearancePhoto).mockReset()
  })

  it('首次使用展示真实空状态和能力边界，不填充演示衣物', async () => {
    const wrapper = mount(AppearanceView)
    await flushPromises()

    expect(wrapper.text()).toContain('衣橱还是空的')
    expect(wrapper.text()).toContain('所有记录仅当前成员可见')
    expect(wrapper.findAll('.garment-card')).toHaveLength(0)
    expect(wrapper.text()).toContain('整体结构分')
  })

  it('录入物品后自动持久化且失败时保留完整本机草稿', async () => {
    vi.mocked(saveAppearanceWorkspace).mockRejectedValueOnce(new Error('网络已断开'))
    const wrapper = mount(AppearanceView)
    await flushPromises()
    await wrapper.get('.content-section .primary').trigger('click')
    await wrapper.get('.editor-modal input[placeholder="如：浅蓝牛津纺衬衫"]').setValue('浅蓝衬衫')
    await wrapper.get('.editor-modal input[placeholder="春、秋；用逗号分隔"]').setValue('春、秋')
    await wrapper.get('.editor-modal footer .primary').trigger('click')
    await vi.advanceTimersByTimeAsync(750)
    await flushPromises()

    expect(saveAppearanceWorkspace).toHaveBeenCalled()
    expect(wrapper.text()).toContain('网络已断开')
    expect(wrapper.text()).toContain('浅蓝衬衫')
    expect(localStorage.getItem('wao-appearance-workspace-draft-v2:wg')).toContain('浅蓝衬衫')

    vi.mocked(saveAppearanceWorkspace).mockImplementation(async (value) => ({
      ...value,
      updatedAt: '2026-08-29T20:01:00',
    }))
    await wrapper.get('.save-strip button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('刷新或换设备仍可读取')
    expect(localStorage.getItem('wao-appearance-workspace-draft-v2:wg')).toBeNull()
  })

  it('优先恢复未同步草稿并明确提供重试', async () => {
    const draft = workspace()
    draft.notes.push({
      id: 'an1',
      title: '真实感受',
      content: '这条裤子坐下时紧。',
      tags: ['版型'],
      createdAt: '2026-08-29T10:00:00',
    })
    localStorage.setItem('wao-appearance-workspace-draft-v2:wg', JSON.stringify(draft))

    const wrapper = mount(AppearanceView)
    await flushPromises()

    expect(wrapper.text()).toContain('已恢复上次未同步的本机草稿')
    expect(wrapper.get('.save-strip button').text()).toContain('重试保存')
    await wrapper.findAll('.section-tabs button')[4]!.trigger('click')
    expect(wrapper.text()).toContain('这条裤子坐下时紧')
  })

  it('只恢复当前成员的本机草稿，切换账号不泄露个人记录', async () => {
    const otherDraft = workspace()
    otherDraft.notes.push({
      id: 'an-private',
      title: '另一成员的私密记录',
      content: '不应出现在当前成员页面。',
      tags: ['私密'],
      createdAt: '2026-08-29T10:00:00',
    })
    localStorage.setItem('wao-appearance-workspace-draft-v2:wd', JSON.stringify(otherDraft))
    localStorage.setItem(
      'wao-appearance-form-draft-v2:wd',
      JSON.stringify({
        kind: 'garment',
        editingId: '',
        form: { name: '另一成员未提交的外套', category: '上装' },
      }),
    )

    const wrapper = mount(AppearanceView)
    await flushPromises()
    await wrapper.get('.content-section .primary').trigger('click')

    expect(
      wrapper.get('.editor-modal input[placeholder="如：浅蓝牛津纺衬衫"]').element,
    ).toHaveProperty('value', '')
    await wrapper.findAll('.editor-modal footer button')[0]!.trigger('click')
    await wrapper.findAll('.section-tabs button')[4]!.trigger('click')

    expect(wrapper.text()).not.toContain('另一成员的私密记录')
    expect(wrapper.text()).not.toContain('已恢复上次未同步的本机草稿')
    expect(fetchAppearanceWorkspace).toHaveBeenCalledOnce()
  })

  it('计划状态严格按真实日期显示站内提醒边界', async () => {
    const data = workspace()
    data.plans.push({
      id: 'apn1',
      type: 'CLEANING',
      title: '清洗白衬衫',
      dueDate: '2020-01-01',
      scenario: '',
      outfitId: null,
      status: 'OPEN',
      note: '',
    })
    vi.mocked(fetchAppearanceWorkspace).mockResolvedValue(data)
    const wrapper = mount(AppearanceView)
    await flushPromises()
    await wrapper.findAll('.section-tabs button')[3]!.trigger('click')

    expect(wrapper.text()).toContain('1 项已逾期')
    expect(wrapper.text()).toContain('本页提醒')
    expect(wrapper.text()).not.toContain('已推送')
  })
})
