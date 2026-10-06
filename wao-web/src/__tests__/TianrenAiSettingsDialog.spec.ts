import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TianrenAiSettingsDialog from '@/components/tianren/TianrenAiSettingsDialog.vue'
import {
  fetchTianrenAiSettings,
  saveTianrenAiSettings,
  type TianrenAiSettings,
} from '@/api/tianren'
import { useSessionStore } from '@/stores/session'
import { fetchCodexModels } from '@/api/codexModels'
import { fetchTianrenPreference, saveTianrenPreference } from '@/api/tianren'
import { DEFAULT_WORKDAY_RHYTHM } from '@/utils/workdayRhythm'
vi.mock('@/api/codexModels', () => ({
  fetchCodexModels: vi.fn().mockResolvedValue({
    models: [
      { id: 'gpt-6-astra', label: 'GPT-6 Astra', reasoningEfforts: ['medium', 'high'] },
      { id: 'gpt-6.1-sol', label: 'GPT-6.1 Sol', reasoningEfforts: ['medium'] },
    ],
    stale: false,
    updatedAt: null,
    message: '',
  }),
}))
vi.mock('@/api/tianren', () => ({
  fetchTianrenAiSettings: vi.fn(),
  saveTianrenAiSettings: vi.fn(),
  fetchTianrenPreference: vi.fn(),
  saveTianrenPreference: vi.fn(),
}))
vi.mock('@/api/system', () => ({
  fetchWebhookEndpoints: vi.fn().mockResolvedValue([]),
  fetchFeishuChats: vi.fn().mockResolvedValue([]),
}))
const settings: TianrenAiSettings = {
  version: 2,
  autoImage: true,
  model: 'gpt-6-astra',
  reasoningEffort: 'medium',
  interpretationPrompt: '已保存解读提示',
  imagePrompt: '已保存图片提示',
  defaultInterpretationPrompt: '默认解读提示',
  defaultImagePrompt: '默认图片提示',
}
const storageKey = 'wao-tianren-ai-settings-draft-v1:member1'
function accept(id: string) {
  useSessionStore().accept({
    accountId: id,
    member: { id, name: id, initial: 'M', color: '', role: '' },
    identities: [],
    createdAt: '',
  })
}
function mountDialog() {
  return mount(TianrenAiSettingsDialog, {
    attachTo: document.body,
    global: { stubs: { Teleport: true } },
  })
}
function button(wrapper: ReturnType<typeof mountDialog>, label: string) {
  return wrapper.findAll('button').find((item) => item.text().replace(/待保存$/, '') === label)!
}
describe('TianrenAiSettingsDialog', () => {
  it('能力中心内嵌模式不创建遮罩与焦点陷阱，复用同一份卦象配置', async () => {
    const wrapper = mount(TianrenAiSettingsDialog, {
      props: { embedded: true, section: 'imagePrompt' },
      attachTo: document.body,
    })
    await flushPromises()
    expect(document.body.querySelector('[aria-modal=true]')).toBeNull()
    expect(wrapper.get('[role=region]').attributes('aria-label')).toBe('天人一卦设置')
    expect(wrapper.get('textarea').element).toHaveProperty('value', settings.imagePrompt)
    await wrapper.get('textarea').setValue('中心配图方法')
    await button(wrapper, '保存 AI 设置').trigger('click')
    await flushPromises()
    expect(saveTianrenAiSettings).toHaveBeenCalledWith(
      expect.objectContaining({
        version: 2,
        imagePrompt: '中心配图方法',
        interpretationPrompt: settings.interpretationPrompt,
      }),
    )
  })
  beforeEach(() => {
    setActivePinia(createPinia())
    accept('member1')
    localStorage.clear()
    vi.mocked(fetchTianrenAiSettings).mockResolvedValue(settings)
    vi.mocked(saveTianrenAiSettings).mockResolvedValue({ ...settings, version: 3 })
    vi.mocked(fetchTianrenPreference).mockResolvedValue(JSON.stringify(DEFAULT_WORKDAY_RHYTHM))
    vi.mocked(saveTianrenPreference).mockResolvedValue(undefined)
  })
  it('统一通知入口隐藏模型区，切项保留两类草稿，保存通知不会覆盖 AI 设置', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    await button(wrapper, '解读提示词').trigger('click')
    await wrapper.get('textarea').setValue('独立的解读草稿')
    await button(wrapper, '飞书通知').trigger('click')
    await flushPromises()
    expect(wrapper.find('.settings-engine').exists()).toBe(false)
    await wrapper.get('input[aria-label="卦象解读发送通知"]').setValue(true)
    expect(localStorage.getItem('wao-tianren-notification-draft:member1')).toContain(
      '"notifyTianren":true',
    )
    await button(wrapper, '解读提示词').trigger('click')
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('独立的解读草稿')
    await button(wrapper, '飞书通知').trigger('click')
    await flushPromises()
    expect(
      wrapper.get<HTMLInputElement>('input[aria-label="卦象解读发送通知"]').element.checked,
    ).toBe(true)
    await button(wrapper, '保存通知设置').trigger('click')
    await flushPromises()
    expect(saveTianrenAiSettings).not.toHaveBeenCalled()
    const saved = JSON.parse(vi.mocked(saveTianrenPreference).mock.calls[0]![1])
    expect(saved.energyNotification.notifyTianren).toBe(true)
    expect(saved.blocks).toEqual(DEFAULT_WORKDAY_RHYTHM.blocks)
    expect(localStorage.getItem(storageKey)).toContain('独立的解读草稿')
    expect(localStorage.getItem('wao-tianren-notification-draft:member1')).toBeNull()
    expect(wrapper.text()).toContain('卦象通知已保存')
    expect(wrapper.text()).toContain('AI 设置仍待保存')
    expect(button(wrapper, '解读提示词').attributes('aria-label')).toContain('待保存')
    expect(button(wrapper, '飞书通知').attributes('aria-label')).not.toContain('待保存')
    wrapper.unmount()
  })
  it('保存 AI 不提交通知草稿，导航和底栏继续提示另一组待保存', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    await button(wrapper, '解读提示词').trigger('click')
    await wrapper.get('textarea').setValue('独立解读修改')
    await button(wrapper, '飞书通知').trigger('click')
    await flushPromises()
    await wrapper.get('input[aria-label="卦象解读发送通知"]').setValue(true)
    await button(wrapper, '解读提示词').trigger('click')
    expect(wrapper.text()).toContain('通知设置仍待保存')
    vi.mocked(saveTianrenAiSettings).mockResolvedValueOnce({
      ...settings,
      version: 3,
      interpretationPrompt: '独立解读修改',
    })
    await button(wrapper, '保存 AI 设置').trigger('click')
    await flushPromises()
    expect(saveTianrenPreference).not.toHaveBeenCalled()
    expect(button(wrapper, '解读提示词').attributes('aria-label')).not.toContain('待保存')
    expect(button(wrapper, '飞书通知').attributes('aria-label')).toContain('待保存')
    expect(wrapper.text()).toContain('通知设置仍待保存')
    await button(wrapper, '飞书通知').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('AI 设置仍待保存')
    expect(
      wrapper.get<HTMLInputElement>('input[aria-label="卦象解读发送通知"]').element.checked,
    ).toBe(true)
    wrapper.unmount()
  })
  afterEach(() => {
    vi.clearAllMocks()
    document.body.innerHTML = ''
  })
  it('切换页签和关闭后保留两段草稿与开关，恢复默认只改草稿', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    await button(wrapper, '解读提示词').trigger('click')
    await wrapper.get('textarea').setValue('未保存的解读')
    await button(wrapper, '配图提示词').trigger('click')
    await wrapper.get('textarea').setValue('未保存的图片')
    await button(wrapper, 'AI 能力').trigger('click')
    await wrapper.get('input').setValue(false)
    await button(wrapper, '配图提示词').trigger('click')
    await button(wrapper, '恢复默认').trigger('click')
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('未保存的图片')
    await button(wrapper, '确认恢复默认').trigger('click')
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('默认图片提示')
    expect(saveTianrenAiSettings).not.toHaveBeenCalled()
    wrapper.unmount()
    const reopened = mountDialog()
    await flushPromises()
    await button(reopened, '解读提示词').trigger('click')
    expect(reopened.get<HTMLTextAreaElement>('textarea').element.value).toBe('未保存的解读')
    await button(reopened, '配图提示词').trigger('click')
    expect(reopened.get<HTMLTextAreaElement>('textarea').element.value).toBe('默认图片提示')
    await button(reopened, 'AI 能力').trigger('click')
    expect(reopened.get<HTMLInputElement>('input').element.checked).toBe(false)
    reopened.unmount()
  })
  it('加载失败保留本机草稿并禁止以默认设置覆盖账号', async () => {
    localStorage.setItem(
      storageKey,
      JSON.stringify({
        version: 2,
        autoImage: false,
        interpretationPrompt: '本机解读',
        imagePrompt: '本机图片',
      }),
    )
    vi.mocked(fetchTianrenAiSettings).mockRejectedValue(new Error('读取中断'))
    const wrapper = mountDialog()
    await flushPromises()
    await button(wrapper, '解读提示词').trigger('click')
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('本机解读')
    expect(button(wrapper, '重试保存').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('读取中断')
    expect(localStorage.getItem(storageKey)).toContain('本机图片')
    wrapper.unmount()
  })
  it('409 冲突保留基准版本与草稿，明确读取对比后才允许重新保存', async () => {
    vi.mocked(saveTianrenAiSettings)
      .mockRejectedValueOnce(Object.assign(new Error('版本冲突'), { status: 409 }))
      .mockResolvedValue({ ...settings, version: 5, interpretationPrompt: '我的改动' })
    const wrapper = mountDialog()
    await flushPromises()
    await button(wrapper, '解读提示词').trigger('click')
    await wrapper.get('textarea').setValue('我的改动')
    await button(wrapper, '保存 AI 设置').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('其他页面或设备已修改设置')
    expect(JSON.parse(localStorage.getItem(storageKey)!).version).toBe(2)
    expect(button(wrapper, '保存 AI 设置').attributes('disabled')).toBeDefined()
    vi.mocked(fetchTianrenAiSettings).mockResolvedValue({
      ...settings,
      version: 4,
      interpretationPrompt: '其他设备改动',
    })
    await button(wrapper, '重新读取设置').trigger('click')
    await flushPromises()
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('我的改动')
    expect(wrapper.text()).toContain('其他设备改动')
    expect(button(wrapper, '保存 AI 设置').attributes('disabled')).toBeDefined()
    await button(wrapper, '保留草稿，基于最新版本').trigger('click')
    await button(wrapper, '保存 AI 设置').trigger('click')
    await flushPromises()
    expect(saveTianrenAiSettings).toHaveBeenLastCalledWith({
      version: 4,
      autoImage: true,
      model: 'gpt-6-astra',
      reasoningEffort: 'medium',
      interpretationPrompt: '我的改动',
      imagePrompt: '已保存图片提示',
    })
    expect(localStorage.getItem(storageKey)).toBeNull()
    expect(wrapper.text()).toContain('设置已保存')
    wrapper.unmount()
  })
  it('加载中的账号设置不能覆盖等待期间已有草稿，跨成员草稿隔离', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    await button(wrapper, '解读提示词').trigger('click')
    await wrapper.get('textarea').setValue('第一位成员草稿')
    accept('member2')
    await flushPromises()
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe(
      settings.interpretationPrompt,
    )
    await wrapper.get('textarea').setValue('第二位成员草稿')
    expect(localStorage.getItem(storageKey)).toContain('第一位成员草稿')
    expect(localStorage.getItem('wao-tianren-ai-settings-draft-v1:member2')).toContain(
      '第二位成员草稿',
    )
    accept('member1')
    await flushPromises()
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('第一位成员草稿')
    wrapper.unmount()
  })
  it('验证空提示词和 4000 字边界，失败保存仍可重试且保留草稿', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    await button(wrapper, '解读提示词').trigger('click')
    await wrapper.get('textarea').setValue('   ')
    await button(wrapper, '保存 AI 设置').trigger('click')
    expect(wrapper.text()).toContain('1–4000 字')
    expect(saveTianrenAiSettings).not.toHaveBeenCalled()
    await wrapper.get('textarea').setValue('字'.repeat(4001))
    await button(wrapper, '重试保存').trigger('click')
    expect(saveTianrenAiSettings).not.toHaveBeenCalled()
    await wrapper.get('textarea').setValue('字'.repeat(4000))
    vi.mocked(saveTianrenAiSettings).mockRejectedValueOnce(new Error('保存连接中断'))
    await button(wrapper, '重试保存').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('保存连接中断')
    expect(JSON.parse(localStorage.getItem(storageKey)!).interpretationPrompt).toHaveLength(4000)
    await button(wrapper, '重试保存').trigger('click')
    await flushPromises()
    expect(saveTianrenAiSettings).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('天人独立模型和深度原子保存，切到提示词仍显示能力选择', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    await wrapper.get('[aria-label="天人思考深度"]').setValue('high')
    await button(wrapper, '配图提示词').trigger('click')
    expect(wrapper.find('[aria-label="天人处理模型"]').exists()).toBe(true)
    await button(wrapper, '保存 AI 设置').trigger('click')
    await flushPromises()
    expect(saveTianrenAiSettings).toHaveBeenLastCalledWith(
      expect.objectContaining({ model: 'gpt-6-astra', reasoningEffort: 'high', version: 2 }),
    )
    expect(wrapper.emitted('saved')).toHaveLength(1)
    wrapper.unmount()
  })
  it('未知模型和模型切换后不支持的深度精确保留，必须明确修正再保存', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    await wrapper.get('[aria-label="天人思考深度"]').setValue('high')
    await wrapper.get('[aria-label="天人处理模型"]').setValue('gpt-6.1-sol')
    await button(wrapper, '保存 AI 设置').trigger('click')
    await flushPromises()
    expect(wrapper.get<HTMLSelectElement>('[aria-label="天人思考深度"]').element.value).toBe('high')
    expect(wrapper.text()).toContain('当前模型不支持')
    expect(saveTianrenAiSettings).not.toHaveBeenCalled()
    vi.mocked(fetchCodexModels).mockResolvedValueOnce({
      models: [],
      stale: false,
      updatedAt: null,
      message: '',
    })
    await button(wrapper, '刷新模型目录').trigger('click')
    await flushPromises()
    expect(wrapper.get<HTMLSelectElement>('[aria-label="天人处理模型"]').element.value).toBe(
      'gpt-6.1-sol',
    )
    expect(wrapper.text()).toContain('找不到已选模型')
    wrapper.unmount()
  })
  it('保存期间改动不被响应覆盖，关闭仍保留最新草稿和新基准版本', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    await button(wrapper, '解读提示词').trigger('click')
    await wrapper.get('textarea').setValue('提交内容')
    let resolve!: (value: TianrenAiSettings) => void
    vi.mocked(saveTianrenAiSettings).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    await button(wrapper, '保存 AI 设置').trigger('click')
    await wrapper.get('textarea').setValue('保存期间的新输入')
    resolve({ ...settings, version: 3, interpretationPrompt: '提交内容' })
    await flushPromises()
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('保存期间的新输入')
    expect(JSON.parse(localStorage.getItem(storageKey)!).version).toBe(3)
    expect(wrapper.text()).toContain('最新修改仍待保存')
    wrapper.unmount()
  })
})
