<script setup lang="ts">
import { ref } from 'vue'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import HomeInspirationPaper from '@/components/home/HomeInspirationPaper.vue'
import { companionQuiet } from '@/composables/quietCompanions'
import { RouterLink } from 'vue-router'
import WeatherPanel from '@/components/calendar/WeatherPanel.vue'
import HomeOverviewCard from '@/components/home/HomeOverviewCard.vue'
import HomeCompanions from '@/components/home/HomeCompanions.vue'
import { useDataStore } from '@/stores/data'
const data = useDataStore()
const companions = ref<InstanceType<typeof HomeCompanions>>()
const menu = ref<InstanceType<typeof ContextActionMenu>>()
const paper = ref<InstanceType<typeof HomeInspirationPaper>>()
function actions(event: MouseEvent | KeyboardEvent, index?: number) {
  void menu.value?.openAt(event, index === undefined ? '此刻，想做点什么？' : ['小画家的画外音', '小书虫的旧纸条', '小开心的即兴舞', '小青蛙的慢时光'][index]!, [
    { id: 'inspire', label: index === 1 ? '翻翻自己的旧小记' : '给我一个新灵感', run: () => paper.value?.show(index === 1 ? 'memory' : 'question') },
    { id: 'write', label: '记下一个念头', run: () => paper.value?.show('write') },
    { id: 'dance', label: index === undefined ? '大家，跳支舞吧！' : '跳支舞给我看', run: () => companions.value?.dance(index) },
    { id: 'quiet', label: companionQuiet.value ? '唤醒伙伴' : '安静陪伴一会儿', run: () => companions.value?.toggleQuiet() },
  ])
}
function blankMenu(event: MouseEvent) {
  if (!(event.target instanceof Element) || event.target.closest('a,button,input,textarea,[contenteditable],select,[role="dialog"]') || window.getSelection()?.toString()) return
  actions(event)
}
</script>

<template>
  <main class="home" @contextmenu="blankMenu">
    <section class="home-rest-welcome">
      <header class="home-day">
        <HomeOverviewCard />
        <WeatherPanel surface="calendar" :date="data.today" toolbar glance />
      </header>
      <div class="home-rest-copy">
        <p>WAO · 一呼一吸</p>
        <h1>留一点空白，再开始。</h1>
        <p>随时暂停手边的事，回到呼吸与静坐的空间。</p>
        <div class="home-actions">
          <RouterLink class="rest-entrance" to="/breathe">休息一下 <span aria-hidden="true">↗</span></RouterLink>
          <RouterLink class="rest-entrance" to="/features">功能探索 <span aria-hidden="true">↗</span></RouterLink>
        <button class="rest-entrance play-entrance" type="button" @click="actions($event)" @keydown.enter.prevent="actions($event)" @keydown.space.prevent="actions($event)"><i class="ri-sparkling-line" aria-hidden="true"></i> 和伙伴玩</button>
          <button class="rest-entrance" type="button" @click="paper?.show('write')"><i class="ri-quill-pen-line" aria-hidden="true"></i> 记个念头</button>
        </div>
        <small class="play-hint">也可以在空白处或伙伴身上右键，发现一点小惊喜。</small>
      </div>
      <div class="home-companion-space"><HomeCompanions ref="companions" @menu="actions" /></div>
    </section>
    <ContextActionMenu ref="menu" />
    <HomeInspirationPaper ref="paper" />
  </main>
</template>

<style scoped>
.home { min-height: 100%; }
.home-day { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.home-rest-welcome { min-height: max(650px, calc(100dvh - 145px)); padding: 32px 32px 0; border: 1px solid var(--wao-line); border-radius: 16px; background: var(--wao-bg-card); display: flex; flex-direction: column; gap: 30px; overflow: hidden; }
.home-companion-space { position: relative; flex: 1; min-height: 250px; margin-inline: -32px; }
.home-rest-copy p { color: var(--wao-text-weak); font-size: 13px; line-height: 1.8; }
.home-rest-copy h1 { font-size: clamp(20px,3vw,30px); font-weight: 500; }
.home-actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 12px; }
.play-hint { display:block; margin-top:12px; color:var(--wao-text-weak); font-size:12px; }
.play-entrance { background:var(--wao-primary-soft) !important; color:var(--wao-primary-text) !important; }
.rest-entrance { background:var(--wao-bg-card); cursor:pointer; font:inherit; display: inline-block; flex: none; white-space: nowrap; padding: 10px 16px; border: 1px solid var(--wao-line); border-radius: 24px; color: var(--wao-text); text-decoration: none; }
.rest-entrance:focus-visible { outline: 2px solid var(--wao-primary-text); outline-offset: 4px; }
@media (max-width: 600px) { .home-rest-welcome { padding: 20px 20px 0; } .home-companion-space { margin-inline: -20px; } }
</style>
