import { beforeEach, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import { useTaskOrganizationPreference } from '@/composables/taskOrganizationPreference'
import { fetchPreference } from '@/api/favorites'
import { saveTaskOrganizationPreference } from '@/api/taskOrganization'
vi.mock('@/api/favorites',()=>({fetchPreference:vi.fn()}))
vi.mock('@/api/taskOrganization',()=>({saveTaskOrganizationPreference:vi.fn()}))
const login=(account='a',member='me')=>useSessionStore().accept({accountId:account,member:{id:member,name:member,initial:member,color:'',role:''},identities:[],createdAt:''})
const decode=(raw:string|null):string[]=>JSON.parse(raw||'[]')
function setup() { let pref!:ReturnType<typeof useTaskOrganizationPreference<string[]>>;const wrapper=mount(defineComponent({setup(){pref=useTaskOrganizationPreference('todo-named-filters-v1',decode);return()=>h('div')}}));return {pref,wrapper} }
beforeEach(()=>{vi.resetAllMocks();localStorage.clear();setActivePinia(createPinia());login();vi.mocked(fetchPreference).mockResolvedValue('[]');vi.mocked(saveTaskOrganizationPreference).mockResolvedValue()})

it('首次读取失败不允许写入默认值，恢复后保存带上精确原版本',async()=>{
  vi.mocked(fetchPreference).mockRejectedValueOnce(new Error('offline'))
  const {pref,wrapper}=setup();await flushPromises();await pref.set(['local'])
  expect(saveTaskOrganizationPreference).not.toHaveBeenCalled()
  vi.mocked(fetchPreference).mockResolvedValue('["existing"]');await pref.retry();await pref.set(['existing','new'])
  expect(saveTaskOrganizationPreference).toHaveBeenCalledWith('todo-named-filters-v1','["existing","new"]','["existing"]');wrapper.unmount()
})
it('并发冲突保留原草稿且不偷偷推进版本，用户明确选择保留本机后才重基保存',async()=>{
  const {pref,wrapper}=setup();await flushPromises()
  vi.mocked(saveTaskOrganizationPreference).mockRejectedValueOnce(Object.assign(new Error('conflict'),{status:409}))
  vi.mocked(fetchPreference).mockResolvedValue('["other-window"]')
  await pref.set(['my-draft'])
  expect(pref.conflict.value).toBe(true);expect(pref.value.value).toEqual(['my-draft']);expect(pref.remote.value).toEqual(['other-window'])
  const stored=JSON.parse(localStorage.getItem('wao-task-organization:a:me:todo-named-filters-v1')!)
  expect(stored.expected).toBe('[]');expect(stored.pending).toBe(true)
  await pref.retry();expect(saveTaskOrganizationPreference).toHaveBeenCalledTimes(1)
  await pref.keepLocal()
  expect(saveTaskOrganizationPreference).toHaveBeenLastCalledWith('todo-named-filters-v1','["my-draft"]','["other-window"]')
  expect(pref.conflict.value).toBe(false);wrapper.unmount()
})
it('失败刷新恢复时保留最初版本，成功回执不污染切换后的账号',async()=>{
  localStorage.setItem('wao-task-organization:a:me:todo-named-filters-v1',JSON.stringify({raw:'["draft"]',expected:'[]',pending:true}))
  let finish!:()=>void
  vi.mocked(saveTaskOrganizationPreference).mockImplementationOnce(()=>new Promise<void>(resolve=>{finish=resolve}))
  const {pref,wrapper}=setup();await flushPromises()
  const pending=pref.retry();await flushPromises()
  login('b','other');await flushPromises();finish();await pending
  expect(pref.value.value).toEqual([])
  expect(localStorage.getItem('wao-task-organization:a:me:todo-named-filters-v1')).toContain('draft')
  wrapper.unmount()
})
it('选择账号版本丢弃冲突草稿不写远端',async()=>{
  localStorage.setItem('wao-task-organization:a:me:todo-named-filters-v1',JSON.stringify({raw:'["draft"]',expected:'[]',pending:true}))
  vi.mocked(fetchPreference).mockResolvedValue('["remote"]')
  const {pref,wrapper}=setup();await flushPromises();pref.useRemote()
  expect(pref.value.value).toEqual(['remote']);expect(saveTaskOrganizationPreference).not.toHaveBeenCalled();expect(pref.error.value).toBe('');wrapper.unmount()
})
it('冲突后的读取失败不允许拿旧快照覆盖账号，重读成功后才可选择版本',async()=>{
  const {pref,wrapper}=setup();await flushPromises()
  vi.mocked(saveTaskOrganizationPreference).mockRejectedValueOnce(Object.assign(new Error('conflict'),{status:409}))
  vi.mocked(fetchPreference).mockRejectedValueOnce(new Error('offline'))
  await pref.set(['my-draft'])
  expect(pref.conflict.value).toBe(true);expect(pref.remoteReady.value).toBe(false)
  await pref.keepLocal();pref.useRemote()
  expect(pref.value.value).toEqual(['my-draft']);expect(saveTaskOrganizationPreference).toHaveBeenCalledTimes(1)
  vi.mocked(fetchPreference).mockResolvedValue('["remote-new"]');await pref.retry()
  expect(pref.remoteReady.value).toBe(true)
  pref.useRemote();expect(pref.value.value).toEqual(['remote-new']);wrapper.unmount()
})
