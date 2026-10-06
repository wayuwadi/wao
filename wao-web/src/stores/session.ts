import { setDesktopCacheMember } from '@/api/desktopCache'
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  isSessionPaused,
  requireSessionRecovery,
  resumeSessionRequests,
} from '@/utils/sessionRecovery'
import type { Member } from '@/types'
import { fetchMembers, fetchOrg } from '@/api/board'
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
  const connectionError = ref('')
  const recoveryRequired = ref(false)
  let initializePromise: Promise<void> | null = null

  const members = ref<Member[]>([])
  const org = ref({ id: '', name: '', memberCount: 0 })
  const authed = computed(() => account.value !== null)
  const userId = computed(() => account.value?.member.id ?? '')
  const currentUser = computed<Member>(
    () =>
      account.value?.member ?? {
        id: '',
        name: '未登录',
        initial: '?',
        color: '#9A9DA9',
        role: '',
      },
  )

  function memberById(id: string): Member {
    if (account.value?.member.id === id) return account.value.member
    return (
      members.value.find((member) => member.id === id) ?? {
        id,
        name: id,
        initial: '?',
        color: '#9A9DA9',
        role: '',
      }
    )
  }

  function accept(session: AuthSession) {
    if (
      (recoveryRequired.value || isSessionPaused()) &&
      account.value &&
      (session.accountId !== account.value.accountId ||
        session.member.id !== account.value.member.id)
    ) {
      throw new Error('请使用原账号重新登录，当前页面和草稿已保留。')
    }
    setDesktopCacheMember(session.member.id)
    account.value = session
    connectionError.value = ''
    initialized.value = true
    pendingClaim.value = null
    recoveryRequired.value = false
    resumeSessionRequests()
    if (!members.value.some((member) => member.id === session.member.id))
      members.value.push(session.member)
  }

  async function refreshDirectory() {
    const [memberResult, orgResult] = await Promise.allSettled([fetchMembers(), fetchOrg()])
    if (memberResult.status === 'fulfilled') members.value = memberResult.value
    if (orgResult.status === 'fulfilled') org.value = orgResult.value
    if (account.value && !members.value.some((member) => member.id === account.value?.member.id)) {
      members.value.push(account.value.member)
    }
  }

  async function acceptResolution(resolution: AuthResolution) {
    if (resolution.session) {
      accept(resolution.session)
    } else if (resolution.claimRequired) {
      if (recoveryRequired.value) throw new Error('请使用已绑定的原账号重新登录。')
      pendingClaim.value = resolution
    }
  }

  function requireRecovery() {
    if (!account.value) {
      resumeSessionRequests()
      return
    }
    recoveryRequired.value = true
    requireSessionRecovery()
  }

  async function recover() {
    accept(await getSession())
  }

  async function initialize() {
    if (initialized.value) return
    if (!initializePromise) {
      connectionError.value = ''
      initializePromise = getSession()
        .then((value) => {
          accept(value)
        })
        .catch((error: unknown) => {
          const status =
            (error as { status?: number; response?: { status?: number } })?.status ??
            (error as { response?: { status?: number } })?.response?.status
          if (status === 401) {
            account.value = null
            initialized.value = true
          } else {
            connectionError.value = error instanceof Error ? error.message : '暂时无法连接服务器'
          }
        })
        .finally(() => {
          initializePromise = null
        })
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
    try {
      await logoutSession()
    } finally {
      setDesktopCacheMember('')
      account.value = null
      pendingClaim.value = null
      initialized.value = true
      recoveryRequired.value = false
      resumeSessionRequests()
    }
  }

  return {
    account,
    pendingClaim,
    initialized,
    connectionError,
    recoveryRequired,
    authed,
    userId,
    members,
    org,
    currentUser,
    requireRecovery,
    recover,
    memberById,
    accept,
    acceptResolution,
    initialize,
    refreshDirectory,
    emailLogin,
    emailRegister,
    claim,
    logout,
  }
})
