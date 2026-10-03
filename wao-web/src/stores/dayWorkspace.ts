import { defineStore } from 'pinia'
import { reactive, watch } from 'vue'
import { useSessionStore } from './session'
import { fetchDayWorkspace, type DayWorkspace } from '@/api/dayWorkspace'

export const useDayWorkspaceStore = defineStore('day-workspace', () => {
  const session = useSessionStore()
  const days = reactive<Record<string, DayWorkspace>>({})
  const states = reactive<Record<string, 'loading' | 'loaded' | 'error'>>({})
  const errors = reactive<Record<string, string>>({})
  let generation = 0
  const requests = new Map<string, Promise<void>>()
  const sequences = new Map<string, number>()
  watch(() => `${session.account?.accountId}:${session.userId}`, () => {
    generation++; requests.clear(); sequences.clear()
    for (const map of [days, states, errors]) Object.keys(map).forEach(key => delete map[key])
  })
  async function load(date: string, force = false) {
    if (!force && requests.has(date)) return requests.get(date)
    const captured = generation
    const sequence = (sequences.get(date) ?? 0) + 1
    sequences.set(date, sequence)
    const active = () => captured === generation && sequences.get(date) === sequence
    states[date] = 'loading'; errors[date] = ''
    const request = fetchDayWorkspace(date).then(day => {
      if (!active()) return
      days[date] = day; states[date] = 'loaded'
    }).catch(cause => {
      if (!active()) return
      states[date] = 'error'; errors[date] = cause instanceof Error ? cause.message : '当天记录读取失败，请重试'
    }).finally(() => { if (active()) requests.delete(date) })
    requests.set(date, request)
    return request
  }
  function invalidate() { for (const key of Object.keys(states)) delete states[key] }
  return { days, states, errors, load, invalidate }
})
