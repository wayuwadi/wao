import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import NoteAiSettingsForm from '@/components/notes/NoteAiSettingsForm.vue'
import { fetchNoteAiSettings, saveNoteAiSettings } from '@/api/noteAiSettings'
import { rememberNoteSettingsDraft } from '@/utils/noteAiSettingsDraft'
import { useSessionStore } from '@/stores/session'
import { fetchCodexModels } from '@/api/codexModels'
vi.mock('@/api/noteAiSettings', () => ({
  fetchNoteAiSettings: vi.fn(),
  saveNoteAiSettings: vi.fn(),
}))
vi.mock('@/api/codexModels', () => ({ fetchCodexModels: vi.fn() }))
const view = () => ({
  version: 'v1',
  configured: true,
  availability: '',
  defaultPrompts: {
    titlePrompt: '服务端默认标题',
    polishPrompt: '服务端默认正文',
    imagePrompt: '服务端默认配图',
  },
  settings: {
    title: false,
    polish: true,
    image: false,
    executionMode: 'parallel' as const,
    maxConcurrent: 3 as const,
    model: '',
    reasoningEffort: 'medium',
    titlePrompt: '',
    polishPrompt: '',
    imagePrompt: '',
    titleEpoch: 1,
    imageEpoch: 1,
    polishEpoch: 0,
  },
})
describe('小记 AI 设置原子保存与草稿', () => {
  const wrappers: ReturnType<typeof mount>[] = []
  function form() {
    const wrapper = mount(NoteAiSettingsForm)
    wrappers.push(wrapper)
    return wrapper
  }
  function button(wrapper: ReturnType<typeof mount>, label: string) {
    return wrapper.findAll('button').find((item) => item.text().replace(/待保存$/, '') === label)!
  }
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    rememberNoteSettingsDraft(useSessionStore().currentUser.id, null)
    vi.clearAllMocks()
    vi.mocked(fetchCodexModels).mockResolvedValue({
      models: [
        { id: 'gpt-6-astra', label: 'GPT-6 Astra', reasoningEfforts: ['low', 'medium', 'high'] },
        { id: 'gpt-6-luna', label: 'GPT-6 Luna', reasoningEfforts: ['low', 'medium'] },
      ],
      stale: false,
      updatedAt: null,
      message: '',
    })
    vi.mocked(fetchNoteAiSettings).mockResolvedValue(view())
    vi.mocked(saveNoteAiSettings).mockResolvedValue(view())
  })
  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    vi.restoreAllMocks()
  })
  it('只读默认不产生草稿，明确修改后标记分项待保存，恢复确认不隐式写入', async () => {
    const wrapper = form()
    await flushPromises()
    await button(wrapper, '标题提示词').trigger('click')
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('服务端默认标题')
    expect(wrapper.get('textarea').attributes('readonly')).toBeDefined()
    expect(button(wrapper, '标题提示词').attributes('aria-label')).not.toContain('待保存')
    await button(wrapper, '基于默认修改').trigger('click')
    await wrapper.get('textarea').setValue('定制标题')
    expect(button(wrapper, '标题提示词').attributes('aria-label')).toContain('待保存')
    expect(button(wrapper, '正文润色').attributes('aria-label')).not.toContain('待保存')
    await button(wrapper, '恢复默认').trigger('click')
    expect(wrapper.get('[aria-label="恢复默认前的内容对比"]').text()).toContain('定制标题')
    await button(wrapper, '确认恢复默认').trigger('click')
    expect(button(wrapper, '标题提示词').attributes('aria-label')).not.toContain('待保存')
    expect(saveNoteAiSettings).not.toHaveBeenCalled()
  })
  it('图片显示在设置内立即生效，不提交或改动 AI 设置', async () => {
    const wrapper = form()
    await flushPromises()
    await button(wrapper, '显示与图片').trigger('click')
    expect(button(wrapper, '预览引入').attributes('aria-pressed')).toBe('true')
    await button(wrapper, '图片嵌入').trigger('click')
    expect(button(wrapper, '图片嵌入').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('footer').text()).toContain('立即生效')
    expect(wrapper.find('footer button.primary').exists()).toBe(false)
    expect(saveNoteAiSettings).not.toHaveBeenCalled()
    wrapper.unmount()
    const reopened = form()
    await flushPromises()
    await button(reopened, '显示与图片').trigger('click')
    expect(button(reopened, '图片嵌入').attributes('aria-pressed')).toBe('true')
  })
  it('系统颜色和区分开关立即生效、重开保留且成员隔离，不提交 AI 设置', async () => {
    const wrapper = form()
    await flushPromises()
    await button(wrapper, '显示与图片').trigger('click')
    await wrapper.get('[aria-label="系统文章颜色"]').setValue('#bbaacc')
    await wrapper.get('[aria-label="区分系统文章"]').setValue(false)
    wrapper.unmount()
    const reopened = form()
    await flushPromises()
    await button(reopened, '显示与图片').trigger('click')
    expect((reopened.get('[aria-label="系统文章颜色"]').element as HTMLInputElement).value).toBe(
      '#bbaacc',
    )
    expect((reopened.get('[aria-label="区分系统文章"]').element as HTMLInputElement).checked).toBe(
      false,
    )
    useSessionStore().account = {
      member: { ...useSessionStore().currentUser, id: 'other-display-member' },
    } as NonNullable<ReturnType<typeof useSessionStore>['account']>
    await flushPromises()
    expect((reopened.get('[aria-label="系统文章颜色"]').element as HTMLInputElement).value).toBe(
      '#7c83b8',
    )
    expect((reopened.get('[aria-label="区分系统文章"]').element as HTMLInputElement).checked).toBe(
      true,
    )
    expect(saveNoteAiSettings).not.toHaveBeenCalled()
  })
  it('失败保留开关，读取刷新不覆盖设置草稿', async () => {
    const wrapper = form()
    await flushPromises()
    await wrapper.findAll('input')[0]!.setValue(true)
    vi.mocked(saveNoteAiSettings).mockRejectedValueOnce(new Error('版本冲突'))
    await wrapper.get('footer button.primary').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('版本冲突')
    await wrapper.get('footer button:first-child').trigger('click')
    await flushPromises()
    expect((wrapper.findAll('input')[0]!.element as HTMLInputElement).checked).toBe(true)
    await wrapper.get('footer button.primary').trigger('click')
    await flushPromises()
    expect(saveNoteAiSettings).toHaveBeenLastCalledWith(
      expect.objectContaining({ title: true, image: false }),
      'v1',
    )
  })
  it('读取失败不展示默认值为真实配置', async () => {
    vi.mocked(fetchNoteAiSettings).mockRejectedValueOnce(new Error('读取失败'))
    const wrapper = form()
    await flushPromises()
    expect(wrapper.findAll('input')).toHaveLength(0)
    expect(wrapper.text()).toContain('读取失败')
    expect(saveNoteAiSettings).not.toHaveBeenCalled()
  })
  it('默认中档并将用户选择随设置一起保存', async () => {
    const wrapper = form()
    await flushPromises()
    expect((wrapper.get('[aria-label="小记思考深度"]').element as HTMLSelectElement).value).toBe(
      'medium',
    )
    await wrapper.get('[aria-label="小记思考深度"]').setValue('medium')
    await wrapper.get('footer button.primary').trigger('click')
    await flushPromises()
    expect(saveNoteAiSettings).toHaveBeenCalledWith(
      expect.objectContaining({ reasoningEffort: 'medium' }),
      'v1',
    )
  })
  it('正文润色提供独立轻量默认，保存和重开保留选择且不改标题配图参数', async () => {
    const wrapper = form()
    await flushPromises()
    await button(wrapper, '正文润色').trigger('click')
    expect(
      (wrapper.get('[aria-label="正文润色处理模型"]').element as HTMLSelectElement).value,
    ).toBe('gpt-6-luna')
    expect(
      (wrapper.get('[aria-label="正文润色思考深度"]').element as HTMLSelectElement).value,
    ).toBe('low')
    expect(wrapper.get('[aria-label="正文润色思考深度"]').text()).toContain('低 · 默认')
    await wrapper.get('[aria-label="正文润色处理模型"]').setValue('gpt-6-astra')
    await wrapper.get('[aria-label="正文润色思考深度"]').setValue('high')
    expect(button(wrapper, '正文润色').attributes('aria-label')).toContain('待保存')
    wrapper.unmount()
    const reopened = form()
    await flushPromises()
    await button(reopened, '正文润色').trigger('click')
    expect(
      (reopened.get('[aria-label="正文润色处理模型"]').element as HTMLSelectElement).value,
    ).toBe('gpt-6-astra')
    vi.mocked(saveNoteAiSettings).mockResolvedValue({
      ...view(),
      settings: { ...view().settings, polishModel: 'gpt-6-astra', polishReasoningEffort: 'high' },
    })
    await button(reopened, '保存小记 AI 设置').trigger('click')
    await flushPromises()
    expect(saveNoteAiSettings).toHaveBeenLastCalledWith(
      expect.objectContaining({
        model: '',
        reasoningEffort: 'medium',
        polishModel: 'gpt-6-astra',
        polishReasoningEffort: 'high',
      }),
      'v1',
    )
    expect(reopened.text()).toContain('设置已保存')
  })
  it('润色目录不可用或深度不兼容时保留原选择并阻止保存', async () => {
    vi.mocked(fetchNoteAiSettings).mockResolvedValue({
      ...view(),
      settings: {
        ...view().settings,
        polishModel: 'missing-polish-model',
        polishReasoningEffort: 'high',
      },
    })
    const wrapper = form()
    await flushPromises()
    await button(wrapper, '正文润色').trigger('click')
    expect(wrapper.text()).toContain('missing-polish-model')
    await button(wrapper, '保存小记 AI 设置').trigger('click')
    expect(saveNoteAiSettings).not.toHaveBeenCalled()
    await wrapper.get('[aria-label="正文润色处理模型"]').setValue('gpt-6-luna')
    await wrapper.get('footer button.primary').trigger('click')
    expect(wrapper.text()).toContain('润色模型不支持所选思考深度')
    expect(saveNoteAiSettings).not.toHaveBeenCalled()
  })
  it('关闭再打开保护设置草稿，未保存开关不被重新读取覆盖', async () => {
    const first = form()
    await flushPromises()
    await first.findAll('input')[0]!.setValue(true)
    first.unmount()
    const reopened = form()
    await flushPromises()
    expect((reopened.findAll('input')[0]!.element as HTMLInputElement).checked).toBe(true)
    expect(reopened.text()).toContain('保留你的设置草稿')
    reopened.unmount()
  })
  it('刷新只读最新配置，不能把草稿旧版本悄悄重基；明确对比后才能保存', async () => {
    const wrapper = form()
    await flushPromises()
    await wrapper.get('input').setValue(true)
    vi.mocked(fetchNoteAiSettings).mockResolvedValue({
      ...view(),
      version: 'v2',
      settings: { ...view().settings, titlePrompt: '其他设备提示' },
    })
    await button(wrapper, '刷新设置').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('其他设备提示')
    expect(button(wrapper, '保存小记 AI 设置').attributes('disabled')).toBeDefined()
    expect(JSON.parse(localStorage.getItem('wao-note-ai-settings-draft-v2:')!).version).toBe('v1')
    await button(wrapper, '保留草稿，基于最新版本').trigger('click')
    await button(wrapper, '保存小记 AI 设置').trigger('click')
    await flushPromises()
    expect(saveNoteAiSettings).toHaveBeenLastCalledWith(
      expect.objectContaining({ title: true, titlePrompt: '' }),
      'v2',
    )
  })
  it('409保留基准版本，重新读取后必须明确选择重基', async () => {
    const wrapper = form()
    await flushPromises()
    await wrapper.get('input').setValue(true)
    vi.mocked(saveNoteAiSettings).mockRejectedValueOnce(
      Object.assign(new Error('冲突'), { status: 409 }),
    )
    await button(wrapper, '保存小记 AI 设置').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('其他页面或设备')
    expect(JSON.parse(localStorage.getItem('wao-note-ai-settings-draft-v2:')!).version).toBe('v1')
    vi.mocked(fetchNoteAiSettings).mockResolvedValue({ ...view(), version: 'v3' })
    await button(wrapper, '重新读取 / 重试').trigger('click')
    await flushPromises()
    expect(button(wrapper, '保存小记 AI 设置').attributes('disabled')).toBeDefined()
    await button(wrapper, '保留草稿，基于最新版本').trigger('click')
    await button(wrapper, '保存小记 AI 设置').trigger('click')
    await flushPromises()
    expect(saveNoteAiSettings).toHaveBeenLastCalledWith(
      expect.objectContaining({ title: true }),
      'v3',
    )
  })
  it('本机草稿可恢复原版本和提示词，分项切换模型字段始终可见', async () => {
    rememberNoteSettingsDraft('', {
      draft: { ...view().settings, titlePrompt: '刷新前的提示' },
      version: 'v0',
    })
    const wrapper = form()
    await flushPromises()
    await button(wrapper, '标题提示词').trigger('click')
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('刷新前的提示')
    expect(wrapper.find('[aria-label="小记处理模型"]').exists()).toBe(true)
    expect(button(wrapper, '保存小记 AI 设置').attributes('disabled')).toBeDefined()
  })
  it('切换成员保留各自草稿，迟到的旧账号读取不能覆盖新账号', async () => {
    const accept = (id: string) =>
      useSessionStore().accept({
        accountId: id,
        member: { id, name: id, initial: 'M', color: '', role: '' },
        identities: [],
        createdAt: '',
      })
    accept('one')
    const wrapper = form()
    await flushPromises()
    await button(wrapper, '标题提示词').trigger('click')
    await wrapper.get('textarea').setValue('成员一草稿')
    let resolve!: (value: ReturnType<typeof view>) => void
    vi.mocked(fetchNoteAiSettings).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    await button(wrapper, '刷新设置').trigger('click')
    accept('two')
    await flushPromises()
    await wrapper.get('textarea').setValue('成员二草稿')
    resolve({ ...view(), version: 'late' })
    await flushPromises()
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('成员二草稿')
    accept('one')
    await flushPromises()
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('成员一草稿')
    expect(localStorage.getItem('wao-note-ai-settings-draft-v2:two')).toContain('成员二草稿')
  })
  it('存储失败保留输入并清楚报告不能跨刷新保留', async () => {
    const wrapper = form()
    await flushPromises()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('full')
    })
    await wrapper.get('input').setValue(true)
    expect(wrapper.text()).toContain('浏览器无法保存本机草稿')
    expect(wrapper.get<HTMLInputElement>('input').element.checked).toBe(true)
  })
})
