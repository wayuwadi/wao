import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h, ref, type Ref } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { fetchPreference, savePreference } from '@/api/favorites'
import { useTodoViewPreferences } from '@/composables/todoViewPreferences'
import { useSessionStore } from '@/stores/session'

vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))
const fetch = vi.mocked(fetchPreference), save = vi.mocked(savePreference)
const mounted: VueWrapper[] = []
function login(accountId = 'a', id = 'm') {
  useSessionStore().accept({ accountId, member: { id, name: id, initial: id, color: '', role: '' }, identities: [], createdAt: '' })
}
function setup(scope: Ref<string> = ref('all')) {
  let settings!: ReturnType<typeof useTodoViewPreferences>
  mounted.push(mount(defineComponent({ setup() { settings = useTodoViewPreferences(scope); return () => h('div') } })))
  return settings
}
const deferred = <T>() => { let resolve!: (value:T) => void; const promise = new Promise<T>(r => { resolve=r }); return { promise, resolve } }
beforeEach(() => {
  vi.restoreAllMocks(); vi.resetAllMocks(); localStorage.clear(); setActivePinia(createPinia()); login()
  fetch.mockResolvedValue(''); save.mockResolvedValue()
})
afterEach(() => { mounted.splice(0).forEach(wrapper => wrapper.unmount()); vi.restoreAllMocks() })

describe('todo view preference persistence', () => {
  // Value: protects=initial reads cannot lose saved unrelated fields; fails_when=default values are saved early;
  // why_new=controls could write before hydration; seam=none; no_claim=real network latency.
  it('首次读取完成前不能用默认值覆盖账号的视图和显示设置', async () => {
    const displayRead=deferred<string>(), viewRead=deferred<string>()
    fetch.mockImplementation(key => key==='todo-display-v1' ? displayRead.promise : viewRead.promise)
    const settings=setup()
    settings.changeDisplay({date:'time'}); settings.changeView({layout:'board'}); await flushPromises()
    expect(save).not.toHaveBeenCalled()
    displayRead.resolve('{"sync":true,"date":"countdown","detail":"popup"}'); await flushPromises()
    settings.changeView({layout:'board'}); await flushPromises()
    expect(save).not.toHaveBeenCalled()
    viewRead.resolve('{"layout":"list","group":"priority","sort":"modified","direction":"desc","showDone":true,"showDetails":true}'); await flushPromises()
    expect(settings.view.value).toMatchObject({group:'priority',sort:'modified',showDone:true,showDetails:true})
    settings.changeView({layout:'board'}); settings.changeDisplay({date:'time'}); await flushPromises()
    expect(JSON.parse(save.mock.calls.find(([key])=>key==='todo-view-v1:all')![1])).toMatchObject({layout:'board',group:'priority',sort:'modified',direction:'desc',showDone:true,showDetails:true})
    expect(JSON.parse(save.mock.calls.find(([key])=>key==='todo-display-v1')![1])).toMatchObject({sync:true,date:'time',detail:'popup'})
  })

  // Value: protects=failed initial reads remain protected until retry; fails_when=read errors permit defaults to overwrite remote;
  // why_new=successful-read tests miss initial failure; seam=none; no_claim=real offline transport.
  it('首次读取失败时保持禁止覆盖，重试成功后才允许保存', async () => {
    fetch.mockRejectedValue(new Error('offline'))
    const settings=setup(); await flushPromises()
    settings.changeDisplay({sync:false}); settings.changeView({layout:'board'}); await flushPromises()
    expect(save).not.toHaveBeenCalled()
    expect(settings.error.value).toContain('无法读取账号偏好')
    fetch.mockImplementation(async key=>key==='todo-display-v1' ? '{"sync":true}' : '{"sort":"priority","direction":"desc"}')
    await settings.retry(); await flushPromises()
    await settings.retry(); await flushPromises()
    settings.changeView({layout:'timeline'}); await flushPromises()
    expect(JSON.parse(save.mock.calls.find(([key])=>key==='todo-view-v1:all')![1])).toMatchObject({layout:'timeline',sort:'priority',direction:'desc'})
  })

  // Value: protects=acknowledged pending saves restore editing; fails_when=successful retry leaves controls unready;
  // why_new=combines restored pending data and failed first read; seam=none; no_claim=multi-device conflict resolution.
  it('恢复未同步草稿时，读取失败后成功重试保存即可恢复视图操作', async () => {
    localStorage.setItem('wao-member-preference-pending-v1:a:m:todo-view-v1:all','{"layout":"board","sort":"priority","direction":"desc"}')
    fetch.mockImplementation(key=>key==='todo-display-v1' ? Promise.resolve('{"sync":true}') : Promise.reject(new Error('offline')))
    const settings=setup(); await flushPromises()
    expect(settings.ready.value).toBe(false)
    expect(settings.error.value).toContain('无法读取账号偏好')
    await settings.retry(); await flushPromises()
    expect(settings.ready.value).toBe(true)
    expect(settings.error.value).toBe('')
    settings.changeView({layout:'timeline'}); await flushPromises()
    expect(JSON.parse(save.mock.calls[save.mock.calls.length-1]![1])).toMatchObject({layout:'timeline',sort:'priority',direction:'desc'})
    expect(localStorage.getItem('wao-member-preference-pending-v1:a:m:todo-view-v1:all')).toBeNull()
  })
  // Value: protects=local views stay scoped to list and account with sync disabled;
  // fails_when=local keys omit identity/scope or disabled changes write remote view;
  // why_new=workspace test only reloads one scope and one account; seam=none;
  // no_claim=real cross-device delivery (preference transport is mocked).
  it('关闭同步后按清单与成员独立保留，并在重新开启时发布当前本机视图', async () => {
    fetch.mockImplementation(async key => key === 'todo-display-v1' ? '{"sync":false}' : '')
    const scope = ref('all'), settings = setup(scope); await flushPromises()
    settings.changeView({ layout:'board' }); await flushPromises()
    scope.value='inbox'; await flushPromises()
    expect(settings.view.value.layout).toBe('list')
    settings.changeView({ layout:'timeline' }); await flushPromises()
    scope.value='all'; await flushPromises()
    expect(settings.view.value.layout).toBe('board')
    expect(save.mock.calls.filter(([key]) => key.startsWith('todo-view-v1:'))).toEqual([])
    login('b','n'); await flushPromises()
    expect(settings.view.value.layout).toBe('list')
    login(); await flushPromises()
    expect(settings.view.value.layout).toBe('board')
    settings.changeDisplay({sync:true}); await flushPromises()
    expect(save.mock.calls.find(([key]) => key==='todo-view-v1:all')?.[1]).toContain('"layout":"board"')
  })

  // Value: protects=local-write failure keeps current choice and exposes a working retry;
  // fails_when=write failure is swallowed or retry reloads defaults over unsaved choice;
  // why_new=no direct coverage of the local-only preference failure path; seam=none;
  // no_claim=browser disk quota behavior (Storage failure is injected).
  it('本机视图保存失败时保留选择，重试写入后清除错误', async () => {
    fetch.mockImplementation(async key => key === 'todo-display-v1' ? '{"sync":false}' : '')
    const settings=setup(); await flushPromises()
    const write=Storage.prototype.setItem
    const blocked=vi.spyOn(Storage.prototype,'setItem').mockImplementation(function(this:Storage,key,value) {
      if(key.startsWith('wao-todo-local-view-v1:')) throw new Error('quota')
      return write.call(this,key,value)
    })
    settings.changeView({layout:'timeline'}); await flushPromises()
    expect(settings.view.value.layout).toBe('timeline')
    expect(settings.error.value).toContain('本机视图未能保存')
    blocked.mockRestore()
    await settings.retry(); await flushPromises()
    expect(settings.error.value).toBe('')
    expect(JSON.parse(localStorage.getItem('wao-todo-local-view-v1:a:m:all')!).layout).toBe('timeline')
  })

  // Value: protects=failed local read is visible and retry restores saved local view;
  // fails_when=read failures are treated as clean defaults or retry cannot reread;
  // why_new=existing tests do not exercise disabled-sync read recovery; seam=none;
  // no_claim=OS storage accessibility (Storage failure is injected).
  it('读取本机视图失败时可重试恢复已保存选择', async () => {
    fetch.mockImplementation(async key => key === 'todo-display-v1' ? '{"sync":false}' : '')
    localStorage.setItem('wao-todo-local-view-v1:a:m:all','{"layout":"board"}')
    const read=Storage.prototype.getItem
    const blocked=vi.spyOn(Storage.prototype,'getItem').mockImplementation(function(this:Storage,key) {
      if(key.startsWith('wao-todo-local-view-v1:')) throw new Error('blocked')
      return read.call(this,key)
    })
    const settings=setup(); await flushPromises()
    expect(settings.error.value).toContain('无法读取本机视图')
    blocked.mockRestore()
    await settings.retry(); await flushPromises()
    expect(settings.view.value.layout).toBe('board')
    expect(settings.error.value).toBe('')
  })

  // Value: protects=remote-save failure survives remount and retries original selected view;
  // fails_when=remote reload overwrites pending local choice or retry omits it;
  // why_new=workspace success-path test does not fail preference transport; seam=none;
  // no_claim=real network outage recovery (API rejection is mocked).
  it('同步失败保留待同步视图，重新挂载和重试不退回账号旧值', async () => {
    const settings=setup(); await flushPromises()
    save.mockRejectedValueOnce(new Error('offline'))
    settings.changeView({layout:'board'}); await flushPromises()
    expect(settings.view.value.layout).toBe('board')
    expect(settings.error.value).toContain('尚未同步到账号')
    mounted.pop()!.unmount()
    fetch.mockImplementation(async key => key === 'todo-view-v1:all' ? '{"layout":"list"}' : '')
    const restored=setup(); await flushPromises()
    expect(restored.view.value.layout).toBe('board')
    expect(restored.error.value).toContain('尚未同步')
    await restored.retry(); await flushPromises()
    expect(restored.error.value).toBe('')
    expect(save.mock.calls[save.mock.calls.length-1]![1]).toContain('"layout":"board"')
  })

  // Value: protects=late old-scope refreshes and writes cannot replace the current scope;
  // fails_when=old callbacks publish across scope/account transitions;
  // why_new=generic task store tests use different preference implementation; seam=none;
  // no_claim=multi-process races (promise timing and transport are controlled).
  it('清单或成员切换后忽略旧读取和旧保存的迟到结果', async () => {
    const oldRead=deferred<string>(), oldWrite=deferred<void>()
    let reads=0
    fetch.mockImplementation(key => key === 'todo-view-v1:all' && ++reads>1 ? oldRead.promise : Promise.resolve(key === 'todo-view-v1:inbox' ? '{"layout":"timeline"}' : ''))
    const scope=ref('all'), settings=setup(scope); await flushPromises()
    window.dispatchEvent(new Event('focus'))
    save.mockReturnValueOnce(oldWrite.promise)
    settings.changeView({layout:'board'}); await flushPromises()
    scope.value='inbox'; await flushPromises()
    expect(settings.view.value.layout).toBe('timeline')
    oldRead.resolve('{"layout":"quadrant"}'); oldWrite.resolve(); await flushPromises()
    expect(settings.view.value.layout).toBe('timeline')
    fetch.mockResolvedValue('')
    login('b','n'); await flushPromises()
    expect(settings.view.value.layout).toBe('list')
  })
})
