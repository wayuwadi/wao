import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { Member } from '@/types'
import { MEMBERS, ORG } from '@/data/fixtures'
import {
  claimMember,
  getSession,
  loginWithEmail,
  logoutSession,
  registerWithEmail,
  type AuthResolution,
  type AuthSession,
} from '@/api/auth'

export const useSessionStore = defineStore('session', () => {
  const account = ref<AuthSession | null>(null)
  const pendingClaim = ref<AuthResolution | null>(null)
  const initialized = ref(false)
  let initializePromise: Promise<void> | null = null

  const members = ref<Member[]>(MEMBERS)
  const org = ref(ORG)
  const authed = computed(() => account.value !== null)
  const userId = computed(() => account.value?.member.id ?? '')
  const currentUser = computed<Member>(() => account.value?.member ?? {
    id: '', name: '未登录', initial: '?', color: '#9A9DA9', role: '',
  })

  function memberById(id: string): Member {
    if (account.value?.member.id === id) return account.value.member
    return members.value.find((member) => member.id === id)
      ?? { id, name: id, initial: '?', color: '#9A9DA9', role: '' }
  }

  function accept(session: AuthSession) {
    account.value = session
    pendingClaim.value = null
    if (!members.value.some((member) => member.id === session.member.id)) members.value.push(session.member)
  }

  function acceptResolution(resolution: AuthResolution) {
    if (resolution.session) accept(resolution.session)
    else if (resolution.claimRequired) pendingClaim.value = resolution
  }

  async function initialize() {
    if (initialized.value) return
    if (!initializePromise) {
      initializePromise = getSession()
        .then(accept)
        .catch(() => { account.value = null })
        .finally(() => { initialized.value = true })
    }
    return initializePromise
  }

  async function emailLogin(email: string, password: string) {
    accept(await loginWithEmail(email, password))
  }

  async function emailRegister(name: string, email: string, password: string) {
    accept(await registerWithEmail(name, email, password))
  }

  async function claim(memberId: string) {
    accept(await claimMember(memberId))
  }

  async function logout() {
    try { await logoutSession() } finally {
      account.value = null
      pendingClaim.value = null
      initialized.value = true
    }
  }

  return {
    account, pendingClaim, initialized, authed, userId, members, org, currentUser,
    memberById, accept, acceptResolution, initialize, emailLogin, emailRegister, claim, logout,
  }
})
