import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { ref } from 'vue'
import { useSessionStore } from '@/stores/session'
import { useFavoritesStore } from '@/stores/favorites'
import { useMemberPreference } from '@/composables/memberPreference'
import { fetchPreference, savePreference, fetchFavorites } from '@/api/favorites'
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(), savePreference: vi.fn(), fetchFavorites: vi.fn(), apiAddFavorite: vi.fn(), apiRemoveFavorite: vi.fn() }))
function login(id: string) { useSessionStore().account = { accountId: id, member: { id, name: id } } as never }
beforeEach(() => {
  localStorage.clear(); setActivePinia(createPinia()); login('member-a')
  vi.mocked(fetchPreference).mockReset().mockResolvedValue('')
  vi.mocked(savePreference).mockReset().mockResolvedValue(undefined)
  vi.mocked(fetchFavorites).mockReset().mockResolvedValue([])
})
it('收藏项目排序独立于其他收藏，失败保留草稿，重试与再次读取恢复', async () => {
  const favorites = useFavoritesStore(); await flushPromises()
  const items = [{ type: 'project', targetId: 'p1', label: '一', parentId: null }, { type: 'task', targetId: 't1', label: '任务', parentId: 'p1' }, { type: 'project', targetId: 'p2', label: '二', parentId: null }] as typeof favorites.items
  favorites.items = items
  vi.mocked(savePreference).mockRejectedValueOnce(new Error('offline'))
  expect(favorites.reorderProject('p2', 'p1')).toBe(true)
  await flushPromises()
  expect(favorites.orderedProjects.map(item => item.targetId)).toEqual(['p2', 'p1'])
  expect(favorites.items).toEqual(items)
  expect(favorites.projectOrderError).toContain('本机选择已保留')
  await favorites.retryProjectOrder()
  expect(savePreference).toHaveBeenLastCalledWith('favorite-project-order-v1', '["p2","p1"]')
  expect(favorites.projectOrderError).toBe('')
  login('member-b'); await flushPromises()
  favorites.items = items
  expect(favorites.orderedProjects.map(item => item.targetId)).toEqual(['p1', 'p2'])
  vi.mocked(fetchPreference).mockResolvedValue('["p2","p1"]')
  login('member-a'); await flushPromises(); favorites.items = items
  expect(favorites.orderedProjects.map(item => item.targetId)).toEqual(['p2', 'p1'])
})
it('偏好迟到读取不覆盖刚调整的宽度，项目切换不串入旧请求', async () => {
  let finish!: (raw: string) => void
  vi.mocked(fetchPreference).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const key = ref('project-table-widths-v1:p1')
  const field = useMemberPreference<Record<string, number>>(key, raw => JSON.parse(raw || '{}'), value => JSON.stringify(value))
  await field.set({ title: 420 })
  finish('{"title":800}'); await flushPromises()
  expect(field.value.value).toEqual({ title: 420 })
  key.value = 'project-table-widths-v1:p2'; await flushPromises()
  expect(field.value.value).toEqual({})
  key.value = 'project-table-widths-v1:p1'; await flushPromises()
  expect(field.value.value).toEqual({ title: 420 })
})
