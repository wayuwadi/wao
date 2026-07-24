import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { Member } from '@/types'
import { MEMBERS, ORG } from '@/data/fixtures'

/**
 * 会话 store：当前登录成员、组织与成员列表。
 * 登录态存 localStorage、成员为静态演示数据（对齐原型「不接真实 OAuth」）；
 * 后端就绪后成员 / 组织改由 /api 提供。
 */

const AUTH_KEY = 'wao-session-v1'

interface Persisted {
  authed: boolean
  userId: string
}

function load(): Persisted {
  try {
    const raw = localStorage.getItem(AUTH_KEY)
    if (raw) return JSON.parse(raw) as Persisted
  } catch {
    /* ignore corrupted storage */
  }
  return { authed: false, userId: 'wg' }
}

export const useSessionStore = defineStore('session', () => {
  const persisted = load()
  const authed = ref(persisted.authed)
  const userId = ref(persisted.userId)

  const members = ref<Member[]>(MEMBERS)
  const org = ref(ORG)

  const currentUser = computed<Member>(
    () => members.value.find((m) => m.id === userId.value) ?? MEMBERS[0]!,
  )

  /** 按 id 查成员（找不到返回一个安全占位）。 */
  function memberById(id: string): Member {
    return members.value.find((m) => m.id === id) ?? { id, name: id, initial: '?', color: '#9A9DA9', role: '' }
  }

  function persist() {
    localStorage.setItem(AUTH_KEY, JSON.stringify({ authed: authed.value, userId: userId.value }))
  }

  /** 模拟扫码登录成功，进入系统。 */
  function login(id: string = userId.value) {
    userId.value = id
    authed.value = true
    persist()
  }

  function logout() {
    authed.value = false
    persist()
  }

  /** 切换演示身份（娃哥 / 娃弟）。 */
  function switchUser(id: string) {
    userId.value = id
    persist()
  }

  return { authed, userId, members, org, currentUser, memberById, login, logout, switchUser }
})
