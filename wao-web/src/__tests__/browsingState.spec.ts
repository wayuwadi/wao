import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { effectScope } from 'vue'
import { useSessionStore } from '@/stores/session'
import { useBrowsingState, validPage, oneOf } from '@/composables/browsingState'

beforeEach(() => { sessionStorage.clear(); setActivePinia(createPinia()) })
const login = (id: string) => useSessionStore().accept({ accountId: id, member: { id, name: id, initial: id, role: '', color: '' }, identities: [], createdAt: '' })
const create = () => {
  const scope = effectScope()
  const state = scope.run(() => useBrowsingState('test', { page: 1, mode: 'all' }, { page: validPage, mode: oneOf('all', 'mine') }))!
  return { scope, state }
}
describe('会话浏览条件', () => {
  it('重新挂载恢复；切账号不串数据，返回原账号仍保留原条件', () => {
    login('a'); const first = create(); first.state.page = 8; first.state.mode = 'mine'; first.scope.stop()
    const second = create(); expect(second.state).toEqual({ page: 8, mode: 'mine' })
    login('b'); expect(second.state).toEqual({ page: 1, mode: 'all' }); second.state.page = 3
    login('a'); expect(second.state).toEqual({ page: 8, mode: 'mine' })
    useSessionStore().account = null; expect(second.state).toEqual({ page: 1, mode: 'all' })
    second.scope.stop()
  })
  it('损坏字段逐项降级，未知字段不带入；存储被禁用仍可浏览', () => {
    login('a')
    sessionStorage.setItem('wao-browse-v1:a:a:test', JSON.stringify({ page: -1, mode: 'mine', secret: 'ignored' }))
    const first = create(); expect(first.state).toEqual({ page: 1, mode: 'mine' }); first.scope.stop()
    const blocked = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    const writes = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    const second = create(); second.state.page = 2; expect(second.state.page).toBe(2); second.scope.stop()
    blocked.mockRestore(); writes.mockRestore()
  })
})
