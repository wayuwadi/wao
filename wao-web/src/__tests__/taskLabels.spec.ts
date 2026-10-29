import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ref } from 'vue'
import { useTaskLabelsStore } from '@/stores/taskLabels'
import { fetchTaskLabels, saveTaskLabels, type TaskLabels } from '@/api/taskLabels'
const identity = vi.hoisted(() => ({ session: null as any }))
vi.mock('@/stores/session', () => ({ useSessionStore: () => identity.session }))
vi.mock('@/api/taskLabels', () => ({ fetchTaskLabels: vi.fn(), saveTaskLabels: vi.fn() }))
function deferred<T>() { let resolve!: (value:T)=>void; let reject!: (value:unknown)=>void; const promise=new Promise<T>((a,b)=>{resolve=a;reject=b}); return {promise,resolve,reject} }
const row = (revision:number, tags=['one']):TaskLabels => ({taskId:'t1',subId:'',revision,tags})
beforeEach(() => { setActivePinia(createPinia()); vi.resetAllMocks(); identity.session = { account:{accountId:'a1'}, userId:ref('member') } })
// A reactive session matches Pinia's unwrapped identity fields.
import { reactive } from 'vue'
describe('task label persistence', () => {
 beforeEach(() => { identity.session=reactive(identity.session) })
 it('preserves a successful save against an older delayed read', async () => {
  const read=deferred<TaskLabels[]>(); vi.mocked(fetchTaskLabels).mockReturnValueOnce(read.promise)
  const store=useTaskLabelsStore(), loading=store.load()
  vi.mocked(saveTaskLabels).mockResolvedValueOnce(row(2,['new']))
  await store.save(row(1,['new'])); read.resolve([row(1,['old'])]); await loading
  expect(store.get('t1').tags).toEqual(['new']); expect(store.get('t1').revision).toBe(2); expect(store.ready).toBe(true)
 })
 it('does not replace labels or report readiness when a read fails', async () => {
  vi.mocked(fetchTaskLabels).mockRejectedValueOnce(new Error('offline'))
  const store=useTaskLabelsStore(); await store.load()
  expect(store.ready).toBe(false); expect(store.error).not.toBe(''); expect(store.rows).toEqual({})
 })
 it('ignores an old rejected read after a newer read succeeded', async () => {
  const old=deferred<TaskLabels[]>()
  vi.mocked(fetchTaskLabels).mockReturnValueOnce(old.promise).mockResolvedValueOnce([row(4,['current'])])
  const store=useTaskLabelsStore(), stale=store.load()
  await store.load(); old.reject(new Error('obsolete timeout')); await stale
  expect(store.get('t1')).toEqual(row(4,['current'])); expect(store.error).toBe(''); expect(store.ready).toBe(true)
 })
 it('keeps existing labels when a save is rejected' , async () => {
  vi.mocked(fetchTaskLabels).mockResolvedValueOnce([row(3)])
  const store=useTaskLabelsStore(); await store.load()
  vi.mocked(saveTaskLabels).mockRejectedValueOnce(new Error('conflict'))
  await expect(store.save(row(2,['overwrite']))).rejects.toThrow('conflict')
  expect(store.get('t1')).toEqual(row(3))
 })
 it('isolates account changes from a delayed read and save', async () => {
  const read=deferred<TaskLabels[]>(), write=deferred<TaskLabels>()
  vi.mocked(fetchTaskLabels).mockReturnValueOnce(read.promise); vi.mocked(saveTaskLabels).mockReturnValueOnce(write.promise)
  const store=useTaskLabelsStore(), loading=store.load(), saving=store.save(row(0))
  const rejected=expect(saving).rejects.toThrow('账号已切换')
  identity.session.userId='other'; read.resolve([row(1)]); write.resolve(row(1))
  await loading; await rejected
  expect(store.rows).toEqual({}); expect(store.ready).toBe(false)
 })
 it('keeps child and parent associations independent', async () => {
  vi.mocked(fetchTaskLabels).mockResolvedValueOnce([row(1,['parent']),{...row(2,['child']),subId:'s1'}])
  const store=useTaskLabelsStore(); await store.load()
  expect(store.get('t1').tags).toEqual(['parent']); expect(store.get('t1','s1').tags).toEqual(['child'])
 })
})
