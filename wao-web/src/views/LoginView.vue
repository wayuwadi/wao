<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { useTheme } from '@/composables/theme'
import HomeBreathingStage from '@/components/home/HomeBreathingStage.vue'
import AuthDialog from '@/components/login/AuthDialog.vue'
import SiteFooter from '@/components/SiteFooter.vue'
import {
  consumeAuthPractice,
  safeAuthTarget,
  saveAuthHandoff,
  setInAppAuthTarget,
} from '@/composables/breathingAuth'
import { worlds, type WorldId } from '@/components/relax/worlds'
import { useRelaxProgress } from '@/components/relax/useRelaxProgress'
import RelaxGames from '@/components/relax/RelaxGames.vue'
import '@/components/relax/relax.css'
const session = useSessionStore(),
  route = useRoute(),
  router = useRouter()
const theme = useTheme()
const {
  progress,
  status: saveStatus,
  message: saveMessage,
  revision,
  save,
  reload,
} = useRelaxProgress(computed(() => session.userId))
const gameAuthOpen = ref(false)
const visible = ref(!document.hidden)
const gamesActive = computed(
  () => progress.value.place === 'play' && !gameAuthOpen.value && visible.value,
)
function visibility() {
  visible.value = !document.hidden
}
function chooseWorld(id: WorldId) {
  progress.value.world = id
}
function choosePlace(place: 'rest' | 'play') {
  if (place === 'play') stage.value?.pauseForPlay()
  progress.value.place = place
}
function openedAuth() {
  gameAuthOpen.value = true
  stage.value?.pauseForAuth()
}
function closedAuth() {
  gameAuthOpen.value = false
  stage.value?.cancelAuthRetention()
}
const stage = ref<InstanceType<typeof HomeBreathingStage>>()
const auth = ref<InstanceType<typeof AuthDialog>>()
const target = ref('/breathe')
const protectedTarget = computed(() => safeAuthTarget(route.query.redirect))
async function openAuth(workspace = false) {
  if (document.fullscreenElement) await document.exitFullscreen().catch(() => {})
  target.value =
    protectedTarget.value !== '/breathe' ? protectedTarget.value : workspace ? '/' : '/breathe'
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
  document.removeEventListener('visibilitychange', visibility)
})
onMounted(async () => {
  document.title = 'WAO · 一呼一吸'
  document.addEventListener('visibilitychange', visibility)
  const practice = consumeAuthPractice()
  if (practice) stage.value?.restore(practice)
  if (route.query.redirect && !session.authed) {
    await nextTick()
    openAuth()
  }
})
</script>
<template>
  <main
    class="login-stage relax-root"
    :class="[`world-${progress.world}`, { 'relax-light': theme.mode.value === 'light' }]"
    :data-entry-theme="theme.mode.value === 'light' ? 'light' : 'dark'"
  >
    <nav class="world-switcher" aria-label="六种休息风格">
      <span>换个心情 ↘</span>
      <div>
        <button
          v-for="w in worlds"
          :key="w.id"
          :aria-pressed="progress.world === w.id"
          @click="chooseWorld(w.id)"
        >
          <i :style="{ background: w.color }"></i>{{ w.name }}
        </button>
      </div>
    </nav>
    <div class="place-switcher" role="group" aria-label="休息或游戏">
      <button :aria-pressed="progress.place === 'rest'" @click="choosePlace('rest')">
        歇一会儿</button
      ><button :aria-pressed="progress.place === 'play'" @click="choosePlace('play')">
        玩一小会儿 ↗</button
      ><span>六个世界，同一支小队</span>
    </div>
    <HomeBreathingStage
      v-show="progress.place === 'rest'"
      ref="stage"
      :world="progress.world"
      :story="progress.anecdote"
      @workspace="enterWorkspace"
    >
      <template #account
        ><button class="header-login" @click="session.authed ? enterWorkspace() : openAuth()">
          {{ session.authed ? '进入工作空间' : '登录 WAO' }} <span aria-hidden="true">↗</span>
        </button></template
      >
      <template #footer><SiteFooter class="entry-footer" /></template>
    </HomeBreathingStage>
    <section v-if="progress.place === 'play'" class="play-world">
      <header class="play-header">
        <strong>wao <small>· 玩一小会儿</small></strong>
        <div>
          <button @click="theme.toggleTheme">切换明暗</button
          ><button class="header-login" @click="session.authed ? enterWorkspace() : openAuth()">
            {{ session.authed ? '进入工作空间' : '登录 WAO' }} ↗
          </button>
        </div>
      </header>
      <RelaxGames
        :key="`${session.userId}:${revision}:${progress.world}`"
        :progress="progress"
        :active="gamesActive"
        @story="progress.anecdote = $event"
      /><SiteFooter class="entry-footer" />
    </section>
    <div class="relax-save-status" role="status">
      <span v-if="saveStatus === 'loading'">读取本机进度…</span
      ><span v-else-if="saveStatus === 'saving'">保存中…</span
      ><span v-else-if="saveStatus === 'saved'"
        >进度已保存在本机 · {{ session.authed ? '当前账号' : '访客' }}</span
      ><template v-else
        ><span>{{ saveMessage }}</span
        ><button @click="save">保存本页进度</button
        ><button @click="reload">
          {{ saveStatus === 'conflict' ? '读取另一个页面的进度' : '重试读取' }}
        </button></template
      >
    </div>
    <AuthDialog
      ref="auth"
      :target="target"
      :before-navigate="beforeNavigate"
      @opened="openedAuth"
      @closed="closedAuth"
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
  .entry-footer {
    font-size: 9px;
  }
}
</style>
