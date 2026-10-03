import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ArticleImageAction from '@/components/ui/ArticleImageAction.vue'
import { fetchCodexModels } from '@/api/codexModels'
import { fetchPreference, savePreference } from '@/api/favorites'
import { resolveNoteModel } from '@/utils/noteAiModel'

vi.mock('@/api/codexModels', () => ({ fetchCodexModels: vi.fn() }))
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))
vi.mock('@/api/articleImages', () => ({ fetchArticleImage: vi.fn(), startArticleImage: vi.fn() }))
vi.mock('@/api/textPolish', () => ({ fetchTextPolishWorkspace: vi.fn().mockResolvedValue({ prompt: '保留事实' }), saveTextPolishPrompt: vi.fn() }))
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.clearAllMocks(); document.body.innerHTML = '' })
async function open(saved = 'gpt-5.6-sol') {
  vi.mocked(fetchPreference).mockImplementation(async key => key === 'note-ai-model-v1' ? saved : '')
  const wrapper = mount(ArticleImageAction, { props: { scopeKey: 'synthetic', getText: () => '', insertImage: () => true, generateTitle: true }, global: { stubs: { teleport: true } } })
  wrappers.push(wrapper); await flushPromises()
  await wrapper.get('[aria-label="小记 AI 设置"]').trigger('click'); await flushPromises()
  return wrapper
}
describe('本机动态模型目录', () => {
  it('显示执行器的新模型，保存并重新打开保留精确选择', async () => {
    vi.mocked(fetchCodexModels).mockResolvedValue({ models: [{ id: 'gpt-6.1-sol', label: 'GPT-6.1 Sol' }, { id: 'gpt-5.6-sol', label: 'GPT-5.6 Sol' }], stale: false, updatedAt: '2026-10-03', message: '自动刷新' })
    const wrapper = await open()
    expect((wrapper.get('select').element as HTMLSelectElement).value).toBe('gpt-5.6-sol')
    await wrapper.get('select').setValue('gpt-6.1-sol')
    await wrapper.get('footer button.primary').trigger('click'); await flushPromises()
    expect(savePreference).toHaveBeenCalledWith('note-ai-model-v1', 'gpt-6.1-sol')
    await wrapper.get('[aria-label="小记 AI 设置"]').trigger('click'); await flushPromises()
    expect((wrapper.get('select').element as HTMLSelectElement).value).toBe('gpt-6.1-sol')
  })
  it('目录断网或不含当前模型时保留选择并给出恢复入口', async () => {
    vi.mocked(fetchCodexModels).mockRejectedValue(new Error('offline'))
    const wrapper = await open('gpt-future-model')
    expect((wrapper.get('select').element as HTMLSelectElement).value).toBe('gpt-future-model')
    expect(wrapper.text()).toContain('已有选择保留')
    vi.mocked(fetchCodexModels).mockResolvedValue({ models: [{ id: 'gpt-future-model', label: '未来模型' }], stale: false, updatedAt: 'now', message: '自动刷新' })
    await wrapper.get('.model-status button').trigger('click'); await flushPromises()
    expect(wrapper.get('select option').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).toContain('未来模型')
  })
  it('后台小记操作不把新模型静默降回默认模型', () => {
    expect(resolveNoteModel('gpt-6.1-sol')).toBe('gpt-6.1-sol')
    expect(resolveNoteModel('gpt-future-model')).toBe('gpt-future-model')
    expect(resolveNoteModel('')).toBe('gpt-6-astra')
  })
})
