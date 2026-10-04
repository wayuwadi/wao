<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
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
import '@/components/relax/relax.css'
import { restArrivalSerial, restSelectionSerial } from '@/composables/restJourney'
const GameHost = defineAsyncComponent(() => import('../../../wao-game/host/GameHost.vue'))
const gameImmersive = ref(false), gameMemoryOpen = ref(false)
const moreMenu = ref<HTMLDetailsElement>(), styleMenu = ref<HTMLDetailsElement>()
function closeMenus() {
  if (moreMenu.value) moreMenu.value.open = false
  if (styleMenu.value) styleMenu.value.open = false
}
const selectedPage = ref('home'),
  pageStyle = ref<WorldId>('theater')
const pages = [
  { id: 'dino', name: '恐龙荒野', color: '#ec6660' },
  { id: 'rift', name: '裂隙远征', color: '#eb973e' },
  { id: 'starfield', name: '星野奇旅', color: '#c8a126' },
  { id: 'beasts', name: '幻兽领地', color: '#62a873' },
  { id: 'camp', name: '最后一座营地', color: '#41a9b3' },
]
const isolatedGame = computed(() => selectedPage.value === 'dino' || selectedPage.value === 'rift' || selectedPage.value === 'starfield' || selectedPage.value === 'beasts' || selectedPage.value === 'camp' ? selectedPage.value : undefined)
const gameSource = computed(() => import.meta.env.DEV && isolatedGame.value ? `/play-assets/dev/${isolatedGame.value}/index.html` : undefined)
const session = useSessionStore(),
  route = useRoute(),
  router = useRouter()
const theme = useTheme()
const {
  progress,
  status: saveStatus,
  message: saveMessage,
  reload,
} = useRelaxProgress(computed(() => session.userId))
const gameAuthOpen = ref(false)
const visible = ref(!document.hidden)
const gamesActive = computed(
  () => selectedPage.value !== 'home' && !gameAuthOpen.value && visible.value,
)
function visibility() {
  visible.value = !document.hidden
}
function chooseWorld(id: WorldId) {
  pageStyle.value = id
  closeMenus()
}
function choosePage(id: string) {
  if (id !== 'home' && !pages.some((p) => p.id === id)) return
  closeMenus()
  if (id !== 'home') stage.value?.pauseForPlay()
  selectedPage.value = id
}
function meditate() {
  choosePage('home')
  void stage.value?.chooseMode('sitting')
}
function breatheHome() {
  choosePage('home')
  void stage.value?.chooseMode('breathing')
}
function exportLegacy() {
  const blob = new Blob(
    [
      JSON.stringify(
        {
          format: 'wao-legacy-relax-backup',
          exportedAt: new Date().toISOString(),
          progress: progress.value,
        },
        null,
        2,
      ),
    ],
    { type: 'application/json' },
  )
  const url = URL.createObjectURL(blob),
    a = document.createElement('a')
  a.href = url
  a.download = 'wao-legacy-relax-backup.json'
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
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
    class="login-stage relax-root rainbow-pages"
    :class="[
      `world-${pageStyle}`,
      {
        'relax-light': theme.effective.value === 'light',
        'breathing-home': selectedPage === 'home',
        'dino-page': !!isolatedGame,
        'rift-page': selectedPage === 'rift',
        'starfield-page': selectedPage === 'starfield',
        'beasts-page': selectedPage === 'beasts',
        'camp-page': selectedPage === 'camp',
      },
    ]"
    :data-entry-theme="theme.effective.value"
  >
    <header class="rainbow-header" :inert="gameImmersive || gameMemoryOpen">
      <button class="home-link" :aria-pressed="selectedPage === 'home'" @click="breatheHome">
        WAO <small>{{ selectedPage === 'home' ? '／ 片刻留白' : '呼吸' }}</small>
      </button>
      <nav v-show="selectedPage !== 'home'" class="rainbow-tabs" aria-label="游戏页面">
        <button
          v-for="page in pages"
          :key="page.id"
          :aria-pressed="selectedPage === page.id"
          @click="choosePage(page.id)"
        >
          <i :style="{ background: page.color }"></i>{{ page.name }}
        </button>
      </nav>
      <select
        v-show="selectedPage !== 'home'"
        class="rainbow-select"
        aria-label="选择呼吸或游戏页面"
        :value="selectedPage"
        @change="choosePage(($event.target as HTMLSelectElement).value)"
      >
        <option value="home">纯呼吸</option>
        <option v-for="page in pages" :key="page.id" :value="page.id">{{ page.name }}</option>
      </select>
      <div class="header-actions">
        <button class="header-login" type="button" @click="session.authed ? enterWorkspace() : openAuth()">
          {{ session.authed ? selectedPage === 'home' ? '返回首页' : '进入工作空间' : '登录 WAO' }} <span aria-hidden="true">↗</span>
        </button>
      <details ref="moreMenu" class="page-more">
        <summary>更多</summary>
        <div @click="closeMenus">
          <button @click="meditate">静坐</button><button :aria-label="`切换明暗，当前${theme.mode.value === 'system' ? '跟随系统' : theme.mode.value === 'dark' ? '夜间' : '日间'}`" @click="theme.toggleTheme">切换明暗</button
          ><button :disabled="saveStatus === 'loading' || saveStatus === 'error'" @click="exportLegacy">导出旧游戏历史档</button
          ><small>旧档保留在原账号空间，备份供历史恢复；不是新游戏记忆。</small>
          <template v-if="saveStatus === 'error' || saveStatus === 'conflict'"><small>{{saveMessage}}</small><button @click="reload">重试读取旧档</button></template>
          <template v-if="selectedPage === 'home'"><small>玩一小会儿</small><button v-for="page in pages" :key="page.id" @click="choosePage(page.id)">{{ page.name }}</button></template>
        </div>
      </details>
      </div>
    </header>
    <HomeBreathingStage
      v-show="selectedPage === 'home'"
      ref="stage"
      minimal
      restful
      :arrival="restArrivalSerial"
      :selection="restSelectionSerial"
      @workspace="enterWorkspace"
    ><template #footer><SiteFooter class="entry-footer" /></template></HomeBreathingStage>
    <section v-if="selectedPage !== 'home'" class="rainbow-play">
      <details ref="styleMenu" class="style-picker" :inert="gameImmersive || gameMemoryOpen">
        <summary>页面风格 · {{ worlds.find((w) => w.id === pageStyle)?.name }}</summary>
        <div>
          <button
            v-for="w in worlds"
            :key="w.id"
            :aria-pressed="pageStyle === w.id"
            @click="chooseWorld(w.id)"
          >
            {{ w.name }}
          </button>
        </div>
      </details>
      <GameHost
        v-if="isolatedGame"
        :key="isolatedGame"
        :game-id="isolatedGame"
        @immersion="gameImmersive = $event"
        @memory-panel="gameMemoryOpen = $event"
        :active="gamesActive"
        :theme="theme.effective.value"
        :source="gameSource"
        @leave="breatheHome"
      />
      <SiteFooter :inert="gameMemoryOpen" v-show="!gameImmersive" class="entry-footer" />
    </section>
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
.rift-page { --game-accent: #d97a35; background: #fff4df; }
.rift-page[data-entry-theme='dark'] { background: #382d34; }
.rift-page :deep(.game-host) { border-color: #e7c69c; background: #fff4df; color: #714b36; }
.starfield-page { --game-accent: #bc982c; background: #fff9e9; }
.starfield-page[data-entry-theme='dark'] { background: #343122; }
.starfield-page :deep(.game-host) { border-color: #e1d4a1; background: #fff9e9; color: #625735; }
</style>

<style scoped>
.rainbow-header {
  position: relative;
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 18px 28px;
  border-bottom: 1px solid #8882;
  z-index: 12;
  color: var(--world-fg);
}
.rainbow-header button {
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
  min-height: 42px;
}
.header-actions { display:flex; align-items:center; gap:18px; flex:none; }
.rainbow-header .header-login { border:1px solid var(--world-line); background:var(--world-panel); font-size:12px; min-height:44px; padding:9px 16px; }
.rainbow-header .header-login:hover { background:color-mix(in srgb,var(--world-fg) 8%,var(--world-panel)); }
.home-link {
  font-size: 18px !important;
  letter-spacing: 2px;
  white-space: nowrap;
  font-weight: 750 !important;
}
.home-link small {
  font-size: 11px;
  font-weight: 400;
  letter-spacing: 0;
}
.rainbow-tabs {
  display: flex;
  align-items: center;
  gap: 3px;
  flex: 1;
  justify-content: center;
}
.rainbow-tabs button {
  font-size: 11px;
  padding: 8px 10px;
  border-radius: 10px;
  white-space: nowrap;
}
.rainbow-tabs [aria-pressed='true'] {
  background: #8882;
}
.rainbow-tabs i {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-right: 7px;
}
.rainbow-select {
  display: none;
  flex: 1;
  max-width: 240px;
  background: transparent;
  border: 1px solid #8885;
  color: inherit;
  border-radius: 10px;
  padding: 10px;
  font-size: 12px;
  min-height: 44px;
}
.rainbow-select option {
  color: #563b42;
  background: #fff5ea;
}
.page-more {
  font-size: 12px;
}
.page-more summary {
  cursor: pointer;
  white-space: nowrap;
  min-height: 42px;
  display: flex;
  align-items: center;
}
.page-more > div {
  position: absolute;
  right: 24px;
  top: 65px;
  width: 225px;
  display: grid;
  padding: 15px;
  background: var(--world-panel);
  border: 1px solid var(--world-line);
  border-radius: 15px;
  box-shadow: 0 15px 40px #0002;
}
.page-more small {
  font-size: 10px;
  line-height: 1.7;
  opacity: 0.65;
  padding: 6px;
}
.rainbow-play {
  max-width: 1450px;
  margin: auto;
  padding: 18px 28px 25px;
}
.style-picker {
  margin-bottom: 14px;
  font-size: 11px;
}
.style-picker summary {
  cursor: pointer;
  text-align: right;
  min-height: 32px;
}
.style-picker > div {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 7px;
  margin: 7px 0;
}
.style-picker button {
  font: inherit;
  border: 1px solid #a0786a55;
  border-radius: 9px;
  background: transparent;
  color: inherit;
  padding: 9px 12px;
  min-height: 44px;
}
.style-picker [aria-pressed='true'] {
  background: #b5745820;
}
.legacy-status {
  font-size: 11px;
  text-align: center;
  margin: 14px;
}
.breathing-home {
  --world-bg: #f6f6ed;
  --world-fg: #273c3b;
  --world-panel: #fff;
  --world-line: #dedfd8;
  background: var(--world-bg);
}
.breathing-home[data-entry-theme='dark'] {
  --world-bg: #202b2a;
  --world-fg: #e6efea;
  --world-panel: #293934;
  --world-line: #52675a;
}
.breathing-home .rainbow-header { justify-content:space-between;border-bottom:0; }
.breathing-home .home-link { font-weight:500 !important; }
.breathing-home .header-login { border:0;background:transparent; }
.dino-page {
  --world-bg: #fff0e5;
  --world-fg: #633d44;
  --world-panel: #fff8ee;
  --world-line: #e5bdb0;
  --world-accent: #ed625b;
  background: var(--world-bg);
}
.dino-page[data-entry-theme='dark'] {
  --world-bg: #30232d;
  --world-fg: #f3d7c4;
  --world-panel: #44303b;
  --world-line: #79535b;
}
.dino-page.world-arcade :deep(.game-host) {
  border-radius: 4px;
  box-shadow: 6px 6px 0 #a6515422;
}
.dino-page.world-journal :deep(.game-host) {
  border: 2px dashed #d39a88;
  border-radius: 12px;
}
.dino-page.world-gallery .rainbow-play {
  max-width: 1160px;
}
.dino-page.world-club :deep(.game-host) {
  border-radius: 36px;
}
.dino-page.world-orbit :deep(.game-host) {
  box-shadow: 0 0 0 8px #e9928117;
}
.rainbow-header button:focus-visible,
summary:focus-visible {
  outline: 2px solid #c65056;
  outline-offset: 3px;
}
@media (max-width: 1100px) {
  .rainbow-tabs {
    display: none;
  }
  .rainbow-select {
    display: block;
  }
  .rainbow-header {
    justify-content: space-between;
  }
}
@media (max-width: 600px) {
  .rainbow-header {
    padding: 10px 14px;
    gap: 10px 12px;
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
  }
  .rainbow-play {
    padding: 10px 8px;
  }
  .home-link {
    font-size: 15px !important;
  }
  .page-more > div {
    right: 12px;
  }
  .header-actions { grid-column:2; grid-row:1; gap:12px; }
  .rainbow-header .header-login { padding:9px 12px; }
  .rainbow-select { grid-column:1 / -1; grid-row:2; width:100%; max-width:none; min-width:0; }
}
.rift-page { --game-accent: #d97a35; background: #fff4df; }
.rift-page[data-entry-theme='dark'] { background: #382d34; }
.rift-page :deep(.game-host) { border-color: #e7c69c; background: #fff4df; color: #714b36; }
.starfield-page { --game-accent: #bc982c; background: #fff9e9; }
.starfield-page[data-entry-theme='dark'] { background: #343122; }
.starfield-page :deep(.game-host) { border-color: #e1d4a1; background: #fff9e9; color: #625735; }
</style>

<style scoped>
.beasts-page { --game-accent: #659655; background: #f2f5e9; }
.beasts-page[data-entry-theme=dark] { background: #293729; }
.beasts-page :deep(.game-host) { border-color: #c6d5b3; background: #f2f5e9; color: #45613e; }
.camp-page { --game-accent: #4c929a; background: #eef5ef; }
.camp-page[data-entry-theme=dark] { background: #293e43; }
.camp-page :deep(.game-host) { border-color: #bdd3ca; background: #eef5ef; color: #315b63; }
</style>
