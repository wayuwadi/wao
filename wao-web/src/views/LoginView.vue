<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { useTheme } from '@/composables/theme'
import HomeBreathingStage from '@/components/home/HomeBreathingStage.vue'
import AuthDialog from '@/components/login/AuthDialog.vue'
import LoginCrew from '@/components/login/LoginCrew.vue'
import SiteFooter from '@/components/SiteFooter.vue'
import { useLoginGreetings } from '@/composables/useLoginGreetings'
import { consumeAuthPractice, safeAuthTarget, saveAuthHandoff, setInAppAuthTarget } from '@/composables/breathingAuth'
const session = useSessionStore(),
  route = useRoute(),
  router = useRouter()
const theme = useTheme()
const stage = ref<InstanceType<typeof HomeBreathingStage>>()
const auth = ref<InstanceType<typeof AuthDialog>>()
const target = ref('/breathe')
const silent = ref(false)
const { active, greet, dismiss } = useLoginGreetings(silent, () => 0)
const greetings = [
  '此刻，不用急着有答案。',
  '给自己留一点空白。',
  '准备好时，我们再出发。',
  '慢慢来，也是一种前进。',
  '休息，也是日常的一部分。',
]
const protectedTarget = computed(() => safeAuthTarget(route.query.redirect))
async function openAuth(workspace = false) {
  if (document.fullscreenElement) await document.exitFullscreen().catch(() => {})
  target.value =
    protectedTarget.value !== '/breathe' ? protectedTarget.value : workspace ? '/' : '/breathe'
  dismiss()
  void auth.value?.open()
}
function enterWorkspace() {
  if (session.authed)
    void router.push(protectedTarget.value === '/breathe' ? '/' : protectedTarget.value)
  else openAuth(true)
}
function beforeNavigate(url: string) {
  const practice = stage.value?.snapshot() ?? null
  if (!saveAuthHandoff(target.value, practice, url) && (practice || target.value !== '/breathe')) {
    throw new Error('浏览器无法暂存认证进度，请使用邮箱登录保留练习。')
  }
}
const previousTitle = document.title
onBeforeUnmount(() => {
  document.title = previousTitle
})
onMounted(async () => {
  document.title = 'WAO · 一呼一吸'
  const practice = consumeAuthPractice()
  if (practice) stage.value?.restore(practice)
  if (route.query.redirect && !session.authed) {
    await nextTick()
    openAuth()
  }
})
</script>
<template>
  <main class="login-stage" :data-entry-theme="theme.mode.value === 'light' ? 'light' : 'dark'">
    <HomeBreathingStage ref="stage" @workspace="enterWorkspace">
      <template #account
        ><button class="header-login" @click="session.authed ? enterWorkspace() : openAuth()">
          {{ session.authed ? '进入工作空间' : '登录 WAO' }} <span aria-hidden="true">↗</span>
        </button></template
      >
      <template #companions
        ><div class="quiet-crew">
          <p class="crew-greeting" role="status">
            {{ active === null ? '在这里，安静陪你一会儿。' : greetings[active] }}
          </p>
          <LoginCrew :active="active" @greet="greet" /></div
      ></template>
      <template #footer><SiteFooter class="entry-footer" /></template>
    </HomeBreathingStage>
    <AuthDialog
      ref="auth"
      :target="target"
      :before-navigate="beforeNavigate"
      @opened="stage?.pauseForAuth()"
      @closed="stage?.cancelAuthRetention()"
      @claim="setInAppAuthTarget(target, stage?.snapshot() ?? null)"
    />
  </main>
</template>
<style scoped>
.login-stage {
  min-height: 100dvh;
}
.header-login {
  border: 1px solid currentColor;
  background: transparent;
  color: inherit;
  border-radius: 24px;
  padding: 10px 18px;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  white-space: nowrap;
}
.header-login:focus-visible {
  outline: 2px solid currentColor;
  outline-offset: 3px;
}
.quiet-crew {
  opacity: 0.7;
  filter: saturate(0.35);
}
.quiet-crew :deep(svg) {
  width: 100%;
  display: block;
}
.quiet-crew :deep(.label) {
  display: none;
}
.quiet-crew :deep(.buddy) {
  animation: none;
}
.crew-greeting {
  font-size: 10px;
  margin: 0 0 -5px;
  text-align: center;
  line-height: 1.7;
  min-height: 17px;
}
.entry-footer {
  padding: 8px 0 0;
  font-size: 10px;
  opacity: 0.8;
}
@media (max-width: 600px) {
  .header-login {
    padding: 9px 12px;
    font-size: 11px;
  }
  .crew-greeting {
    font-size: 9px;
  }
  .entry-footer {
    font-size: 9px;
  }
}
</style>
