import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { fetchPreference, savePreference } from '@/api/favorites'
import { useKnowledgeFavoritesStore } from '@/stores/knowledgeFavorites'

vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))
vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))

describe('常用知识库持久化', () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.resetAllMocks() })
  it('读取成员偏好，去重并过滤无效 id', async () => {
    vi.mocked(fetchPreference).mockResolvedValue('["k1", "k1", 123, "k2"]')
    const store = useKnowledgeFavoritesStore()
    await store.load()
    expect(store.ids).toEqual(['k1', 'k2'])
  })
  it('保存失败保留原常用列表，重试成功后才更新', async () => {
    vi.mocked(fetchPreference).mockResolvedValue('["k1"]')
    vi.mocked(savePreference).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(undefined)
    const store = useKnowledgeFavoritesStore()
    await store.load()
    await store.toggle('k2')
    expect(store.ids).toEqual(['k1'])
    await store.toggle('k2')
    expect(store.ids).toEqual(['k1', 'k2'])
    expect(savePreference).toHaveBeenLastCalledWith('knowledge-favorites', '["k1","k2"]')
  })
  it('读取失败不覆盖服务器偏好', async () => {
    vi.mocked(fetchPreference).mockRejectedValue(new Error('offline'))
    const store = useKnowledgeFavoritesStore()
    await store.toggle('k2')
    expect(savePreference).not.toHaveBeenCalled()
    expect(store.ids).toEqual([])
  })
})
