<script setup lang="ts">
import { ref } from 'vue'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import { companionActivities } from '@/components/home/companionActivities'
import { companionQuiet } from '@/composables/quietCompanions'
import { RouterLink } from 'vue-router'
import WeatherPanel from '@/components/calendar/WeatherPanel.vue'
import HomeOverviewCard from '@/components/home/HomeOverviewCard.vue'
import HomeCompanions from '@/components/home/HomeCompanions.vue'
import { useDataStore } from '@/stores/data'
const data = useDataStore()
const companions = ref<InstanceType<typeof HomeCompanions>>()
const menu = ref<InstanceType<typeof ContextActionMenu>>()
function actions(event: MouseEvent | KeyboardEvent, _index?: number) {
  void menu.value?.openAt(event, '一起放松十五秒', [
    ...companionActivities.map(activity => ({ id: activity.id, label: activity.label, run: () => companions.value?.perform(activity.id) })),
    { id: 'quiet', label: companionQuiet.value ? '唤醒伙伴' : '现在安静下来', run: () => companions.value?.toggleQuiet() },
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
        </div>
        <small class="play-hint">点一下伙伴，让它变成泡泡。右键，一起放松十五秒。</small>
      </div>
      <div class="home-companion-space"><HomeCompanions ref="companions" @menu="actions" /></div>
    </section>
    <ContextActionMenu ref="menu" />

  </main>
</template>

<style scoped>
.home { height: 100%; min-width: 0; display: flex; }
.home-day { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.home-rest-welcome { flex: 1; min-width: 0; padding: 24px 28px 0; border: 1px solid var(--wao-line); border-radius: 16px; background: var(--wao-bg-card); display: grid; grid-template-columns: minmax(260px, 320px) minmax(0, 1fr); grid-template-rows: auto minmax(350px, 1fr); gap: 24px; overflow: hidden; }
.home-day { grid-column: 1 / -1; }
.home-rest-copy { align-self: center; min-width: 0; padding-bottom: 28px; }
.home-companion-space { position: relative; min-width: 0; min-height: 350px; margin-right: -28px; }
.home-rest-copy p { color: var(--wao-text-weak); font-size: 13px; line-height: 1.8; }
.home-rest-copy h1 { font-size: clamp(20px,3vw,30px); font-weight: 500; }
.home-actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 12px; }
.play-hint { display:block; margin-top:12px; color:var(--wao-text-weak); font-size:12px; }
.rest-entrance { background:var(--wao-bg-card); cursor:pointer; font:inherit; display: inline-block; flex: none; white-space: nowrap; padding: 10px 16px; border: 1px solid var(--wao-line); border-radius: 24px; color: var(--wao-text); text-decoration: none; }
.rest-entrance:focus-visible { outline: 2px solid var(--wao-primary-text); outline-offset: 4px; }
@media (max-width: 1100px) { .home-rest-welcome { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto auto minmax(280px, 1fr); } .home-rest-copy { padding-bottom: 0; } .home-companion-space { min-height: 280px; margin-inline: -28px; } }
@media (max-width: 600px) { .home-rest-welcome { padding: 20px 20px 0; } .home-companion-space { margin-inline: -20px; } }
</style>
