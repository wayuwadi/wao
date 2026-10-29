import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import { focusElapsed, focusTime, fetchFocus, startFocus, changeFocus, type FocusSession } from '@/api/focus'
import { useFocusStore } from '@/stores/focus'
import { useSessionStore } from '@/stores/session'
vi.mock('@/api/focus', async original => ({ ...await original<object>(), fetchFocus: vi.fn(), startFocus: vi.fn(), changeFocus: vi.fn() }))
vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))
const row = (): FocusSession => ({ id: 'a'.repeat(32), mode: 'pomodoro', status: 'running', revision: 1, taskId: '', subId: '', taskTitle: '', note: '', noteId: null, startedAt: 1000, runningSince: 1000, elapsedMs: 0, plannedMs: 60_000, endedAt: 0 })
function login(id = 'me') { useSessionStore().accept({ accountId: id, member: { id, name: id, initial: '测', color: '', role: '' }, identities: [], createdAt: '' }) }
function state(active: FocusSession | null = null) { return { serverNow: Date.now(), active, history: [], stats: { todayCount: 0, todayMs: 0, totalCount: 0, totalMs: 0 } } }
beforeEach(() => { vi.resetAllMocks(); localStorage.clear(); setActivePinia(createPinia()); vi.mocked(fetchFocus).mockResolvedValue(state()); login() })
afterEach(() => { vi.useRealTimers() })
describe('番茄专注', () => {
 it('后台延迟按时间戳计算，暂停不增加，倒计时封顶，正计时继续', () => {
  const s=row();expect(focusElapsed(s,121_000)).toBe(60_000);expect(focusElapsed({ ...s,status:'paused',elapsedMs:3000 },121_000)).toBe(3000)
  expect(focusElapsed({ ...s,mode:'stopwatch',plannedMs:0 },121_000)).toBe(120_000);expect(focusTime(59_001)).toBe('01:00')
 })
 it('失败开始复用同一个请求编号和笔记，不重复计时', async () => {
  const focus=useFocusStore();await flushPromises();focus.editNote('开始前记录')
  vi.mocked(startFocus).mockRejectedValue(new Error('断网'))
  const body={mode:'pomodoro' as const,minutes:25,taskId:'task',subId:''}
  await focus.start(body);const first=vi.mocked(startFocus).mock.calls[0]!
  await focus.start(body);expect(vi.mocked(startFocus).mock.calls[1]).toEqual(first);expect(focus.note).toBe('开始前记录')
  expect(JSON.parse(localStorage.getItem('wao-focus-draft:me:me')!).pending.id).toBe(first[0])
 })
 it('同步不覆盖脏笔记，冲突后可按新版本手动重试', async () => {
  vi.mocked(fetchFocus).mockResolvedValue(state(row()));const focus=useFocusStore();await flushPromises();focus.editNote('本机草稿')
  vi.mocked(fetchFocus).mockResolvedValue(state({ ...row(),revision:2,note:'其他页面的笔记' }));await focus.load()
  expect(focus.note).toBe('本机草稿');expect(focus.active?.revision).toBe(2)
  vi.mocked(changeFocus).mockRejectedValue(new Error('冲突'));await focus.act('save');expect(focus.note).toBe('本机草稿');expect(focus.error).toBe('冲突')
 })
 it('到期只发一次自动完成，失败保留笔记并等待重试', async () => {
  vi.mocked(fetchFocus).mockResolvedValue(state(row()));const focus=useFocusStore();await flushPromises();focus.editNote('最后想法')
  vi.mocked(changeFocus).mockRejectedValue(new Error('保存失败'));focus.tick();await flushPromises();focus.tick();await flushPromises()
  expect(changeFocus).toHaveBeenCalledTimes(1);expect(focus.note).toBe('最后想法');expect(focus.active).not.toBeNull()
 })
 it('切换成员丢弃在途响应并隔离本机草稿', async () => {
  let resolve!: (value: ReturnType<typeof state>)=>void
  vi.mocked(fetchFocus).mockImplementationOnce(()=>new Promise(r=>resolve=r));const focus=useFocusStore();focus.editNote('成员甲草稿')
  login('other');await flushPromises();resolve(state(row()));await flushPromises()
  expect(focus.note).toBe('');expect(focus.active).toBeNull();expect(localStorage.getItem('wao-focus-draft:me:me')).toContain('成员甲草稿')
 })
 it('刷新恢复未同步笔记时要求明确同步，不能通过暂停静默覆盖云端', async () => {
  localStorage.setItem('wao-focus-draft:me:me',JSON.stringify({note:'未同步草稿',session:row().id}))
  vi.mocked(fetchFocus).mockResolvedValue(state({ ...row(),note:'云端笔记',revision:3 }))
  const focus=useFocusStore();await flushPromises();expect(focus.note).toBe('未同步草稿');expect(focus.conflict).toBe(true)
  await focus.act('pause');expect(changeFocus).not.toHaveBeenCalled()
 })
})
