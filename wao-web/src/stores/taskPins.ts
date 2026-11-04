import { defineStore } from 'pinia'
import { useMemberPreference } from '@/composables/memberPreference'
export const useTaskPinsStore = defineStore('task-pins', () => {
  const preference = useMemberPreference('task-pins-v1', raw => {
    try { const value:unknown=JSON.parse(raw || '[]'); return Array.isArray(value) ? value.filter((id):id is string=>typeof id==='string') : [] } catch { return [] }
  }, JSON.stringify)
  const has=(id:string)=>preference.value.value.includes(id)
  const sort=<T extends {id:string}>(items:T[])=>[...items].sort((a,b)=>Number(has(b.id))-Number(has(a.id)))
  async function toggle(id:string) {
    if(preference.state.value==='loading'||preference.state.value==='idle')throw new Error('置顶偏好正在读取，请稍后重试')
    if(preference.error.value)throw new Error(preference.error.value)
    await preference.set(has(id)?preference.value.value.filter(key=>key!==id):[id,...preference.value.value])
    if(preference.error.value)throw new Error(preference.error.value)
  }
  return {has,sort,toggle,state:preference.state,error:preference.error,retry:preference.retry}
})
