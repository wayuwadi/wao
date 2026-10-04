import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import NoteAiSettingsForm from '@/components/notes/NoteAiSettingsForm.vue'
import { fetchNoteAiSettings, saveNoteAiSettings } from '@/api/noteAiSettings'
import { rememberNoteSettingsDraft } from '@/utils/noteAiSettingsDraft'
import { useSessionStore } from '@/stores/session'
vi.mock('@/api/noteAiSettings', () => ({ fetchNoteAiSettings: vi.fn(), saveNoteAiSettings: vi.fn() }))
vi.mock('@/api/codexModels', () => ({ fetchCodexModels: vi.fn().mockResolvedValue({ models: [] }) }))
const view = () => ({ version: 'v1', configured: true, availability: '', settings: { title: false, polish: true, image: false, executionMode: 'parallel' as const, maxConcurrent: 3 as const, model: '', reasoningEffort: 'medium', titlePrompt: '', polishPrompt: '', imagePrompt: '', titleEpoch: 1, imageEpoch: 1, polishEpoch: 0 } })
describe('小记 AI 设置原子保存与草稿', () => {
  beforeEach(() => { setActivePinia(createPinia()); rememberNoteSettingsDraft(useSessionStore().currentUser.id,null); vi.clearAllMocks(); vi.mocked(fetchNoteAiSettings).mockResolvedValue(view()); vi.mocked(saveNoteAiSettings).mockResolvedValue(view()) })
  it('失败保留开关，读取刷新不覆盖设置草稿', async () => {
    const wrapper = mount(NoteAiSettingsForm); await flushPromises()
    await wrapper.findAll('input')[0]!.setValue(true)
    vi.mocked(saveNoteAiSettings).mockRejectedValueOnce(new Error('版本冲突'))
    await wrapper.get('footer button.primary').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('版本冲突')
    await wrapper.get('footer button:first-child').trigger('click'); await flushPromises()
    expect((wrapper.findAll('input')[0]!.element as HTMLInputElement).checked).toBe(true)
    await wrapper.get('footer button.primary').trigger('click'); await flushPromises()
    expect(saveNoteAiSettings).toHaveBeenLastCalledWith(expect.objectContaining({ title: true, image: false }), 'v1')
  })
  it('读取失败不展示默认值为真实配置', async () => {
    vi.mocked(fetchNoteAiSettings).mockRejectedValueOnce(new Error('读取失败'))
    const wrapper = mount(NoteAiSettingsForm); await flushPromises()
    expect(wrapper.findAll('input')).toHaveLength(0)
    expect(wrapper.text()).toContain('读取失败')
    expect(saveNoteAiSettings).not.toHaveBeenCalled()
  })
  it('默认中档并将用户选择随设置一起保存', async () => {
    const wrapper = mount(NoteAiSettingsForm); await flushPromises()
    expect((wrapper.get('[aria-label="小记思考深度"]').element as HTMLSelectElement).value).toBe('medium')
    await wrapper.get('[aria-label="小记思考深度"]').setValue('medium')
    await wrapper.get('footer button.primary').trigger('click'); await flushPromises()
    expect(saveNoteAiSettings).toHaveBeenCalledWith(expect.objectContaining({ reasoningEffort: 'medium' }), 'v1')
  })
  it('关闭再打开保护设置草稿，未保存开关不被重新读取覆盖', async () => {
    const first=mount(NoteAiSettingsForm);await flushPromises();await first.findAll('input')[0]!.setValue(true);first.unmount();
    const reopened=mount(NoteAiSettingsForm);await flushPromises();expect((reopened.findAll('input')[0]!.element as HTMLInputElement).checked).toBe(true);expect(reopened.text()).toContain('保留你的设置草稿');reopened.unmount()
  })
})
