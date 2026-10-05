<script setup lang="ts">
import { ref } from 'vue'
import WaoLogo from '@/components/WaoLogo.vue'
import { requestRestJourney, restJourneyRequested } from '@/composables/restJourney'
import { requestWritingJourney, writingJourneyRequested } from '@/composables/writingJourney'
import WeatherPanel from '@/components/calendar/WeatherPanel.vue'
import HomeOverviewCard from '@/components/home/HomeOverviewCard.vue'
import HomeCompanions from '@/components/home/HomeCompanions.vue'
import HomeSurprises from '@/components/home/HomeSurprises.vue'
import HomeHiddenWorld from '@/components/home/HomeHiddenWorld.vue'
import HomePointerPlay from '@/components/home/HomePointerPlay.vue'
import { createWorldChoices } from '@/components/home/companionWorld'
import type { SurpriseStyle } from '@/components/home/companionSurprises'
import { useDataStore } from '@/stores/data'
const data = useDataStore()
const companions = ref<InstanceType<typeof HomeCompanions>>()
const surprises = ref<InstanceType<typeof HomeSurprises>>()
const world = ref<InstanceType<typeof HomeHiddenWorld>>()
const pointerPlay = ref<InstanceType<typeof HomePointerPlay>>()
const choices = createWorldChoices()
const anchors = () => companions.value?.anchors() ?? []
function surprise(style: SurpriseStyle) { surprises.value?.celebrate(style); world.value?.celebrated() }
function clearSurprises() { surprises.value?.clear(); world.value?.clear(); pointerPlay.value?.clear() }
function tap(event: MouseEvent) {
  if (pointerPlay.value?.consumeTap(event)) return
  surprises.value?.tap(event); world.value?.tap(event)
}
function beginRandom() { companions.value?.settle(); surprises.value?.clear(); world.value?.clear() }
function down(event: PointerEvent) { pointerPlay.value?.down(event); world.value?.down(event) }
function move(event: PointerEvent) { pointerPlay.value?.move(event); surprises.value?.sweep(event); world.value?.move(event) }
function up(event: PointerEvent) { pointerPlay.value?.up(event); world.value?.up(event) }
function actions(event: MouseEvent | KeyboardEvent, index?: number) {
  pointerPlay.value?.trigger(event, index)
}
function rest() { if (writingJourneyRequested.value) return; clearSurprises(); companions.value?.settle(); requestRestJourney() }
function write() { clearSurprises(); companions.value?.settle(); requestWritingJourney() }
</script>

<template>
  <main class="home wao-page-frame" @contextmenu="actions" @click="tap" @pointerdown="down" @pointermove="move" @pointerup="up" @pointerleave="pointerPlay?.release()" @pointercancel="clearSurprises">
    <section class="home-rest-welcome">
      <header class="home-day">
        <HomeOverviewCard />
        <WeatherPanel surface="calendar" :date="data.today" toolbar glance />
      </header>
      <div class="home-rest-copy">
        <p class="home-brand-signature"><WaoLogo wordmark /><span>· 一呼一吸</span></p>
        <h1>留一点空白，再开始。</h1>
        <p>随时暂停手边的事，回到呼吸与静坐的空间。</p>
        <div class="home-actions">
          <button class="rest-entrance" :disabled="restJourneyRequested || !!writingJourneyRequested" @click.stop="rest">休息一下 <span aria-hidden="true">↗</span></button>
          <button class="rest-entrance writing-entrance" :disabled="restJourneyRequested || !!writingJourneyRequested" @click.stop="write">写点东西 <i class="ri-quill-pen-line" aria-hidden="true"></i></button>
        </div>
        <small class="play-hint">点一下伙伴，让它变成泡泡。按住留白或右键，随机遇见一点惊喜。</small>
      </div>
      <div class="home-companion-space"><HomeCompanions ref="companions" :choices="choices" @random="actions" @surprise="surprise" @cancel-surprises="clearSurprises" @world="world?.story($event)" /></div>
    </section>
    <HomeSurprises ref="surprises" />
    <HomeHiddenWorld ref="world" :anchors="anchors" :choices="choices" @story="companions?.story($event)" />
    <HomePointerPlay ref="pointerPlay" :anchors="anchors" :available="() => !!companions?.ready" @begin="beginRandom" @activity="companions?.perform($event)" @story="companions?.story($event)" />

  </main>
</template>

<style scoped>
.home { height: 100%; min-width: 0; display: flex; }
.home-day { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.home-rest-welcome { flex: 1; min-width: 0; padding: 24px 28px 0; border: 1px solid var(--wao-line); border-radius: 16px; background: var(--wao-bg-card); display: grid; grid-template-columns: minmax(300px, 320px) minmax(0, 1fr); grid-template-rows: auto minmax(350px, 1fr); gap: 24px; overflow: hidden; }
.home-day { grid-column: 1 / -1; }
.home-rest-copy { align-self: center; min-width: 0; max-width: 380px; padding-bottom: 28px; }
.home-companion-space { position: relative; min-width: 0; min-height: 350px; height: calc(100% + 32px); margin: 0 -28px -32px -48px; transform: translateY(-32px); }
.home-rest-copy p { color: var(--wao-text-weak); font-size: 13px; line-height: 1.8; }
.home-rest-copy > p:not(.home-brand-signature) { color: var(--wao-text-2); }
.home-rest-copy .home-brand-signature { display: flex; align-items: center; gap: 9px; }
.home-brand-signature > span { color: var(--wao-text-3); font-size: 13px; }
.home-brand-signature :deep(.wao-logo) { gap: 7px; color: var(--wao-primary-text); }
.home-brand-signature :deep(.wao-logo-symbol) { width: 26px; height: 26px; }
.home-brand-signature :deep(.wao-logo-name) { font-size: 18px; font-weight: 600; letter-spacing: .08em; }
.home-rest-copy h1 { font-size: clamp(20px,3vw,30px); font-weight: 500; }
.home-actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 12px; }
.play-hint { display:block; margin-top:12px; color:var(--wao-text-3); font-size:12px; line-height:1.65; }
.rest-entrance { background:linear-gradient(135deg,var(--wao-primary-soft),var(--wao-bg-card));cursor:pointer;font:inherit;font-weight:550;display:inline-flex;align-items:center;gap:9px;flex:none;white-space:nowrap;padding:10px 16px;border:1px solid color-mix(in srgb,var(--wao-primary-text) 25%,var(--wao-line));border-radius:24px;color:var(--wao-primary-text);text-decoration:none;box-shadow:0 5px 18px color-mix(in srgb,var(--wao-primary-text) 9%,transparent);transition:transform .2s,background .2s,box-shadow .2s; }
.rest-entrance span { display:inline-grid;place-items:center;width:20px;height:20px;border-radius:50%;background:color-mix(in srgb,var(--wao-primary-soft) 68%,transparent);transition:transform .2s; }
.rest-entrance:hover { transform:translateY(-2px);background:var(--wao-primary-soft);box-shadow:0 8px 22px color-mix(in srgb,var(--wao-primary-text) 14%,transparent); }
.rest-entrance:hover span { transform: translate(2px,-2px); }
.rest-entrance:focus-visible { outline: 2px solid var(--wao-primary-text); outline-offset: 4px; }
@media (max-width: 1100px) { .home-rest-welcome { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto auto minmax(280px, 1fr); } .home-rest-copy { padding-bottom: 0; } .home-companion-space { height: auto; min-height: 280px; margin: 0 -28px; transform: none; } }
@media (max-width: 600px) { .home-rest-welcome { padding: 20px 20px 0; } .home-companion-space { margin-inline: -20px; } }
@media (prefers-reduced-motion: reduce) { .rest-entrance, .rest-entrance span { transition: none; transform: none !important; } }
</style>
