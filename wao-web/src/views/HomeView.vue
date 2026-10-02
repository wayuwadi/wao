<script setup lang="ts">
import { RouterLink } from 'vue-router'
import WeatherPanel from '@/components/calendar/WeatherPanel.vue'
import HomeTianrenGlance from '@/components/home/HomeTianrenGlance.vue'
import { useDataStore } from '@/stores/data'
const data = useDataStore()
</script>

<template>
  <main class="home">
    <section class="home-rest-welcome">
      <header class="home-day">
        <HomeTianrenGlance />
        <WeatherPanel surface="calendar" :date="data.today" toolbar glance />
      </header>
      <div class="home-rest-copy">
        <p>WAO · 一呼一吸</p>
        <h1>留一点空白，再开始。</h1>
        <p>随时暂停手边的事，回到呼吸与静坐的空间。</p>
        <RouterLink class="rest-entrance" to="/breathe">休息一下 <span aria-hidden="true">↗</span></RouterLink>
      </div>
    </section>
  </main>
</template>

<style scoped>
.home { min-height: 100%; }
.home-day { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.home-rest-welcome { min-height: 300px; padding: 32px; border: 1px solid var(--wao-line); border-radius: 16px; background: var(--wao-bg-card); display: flex; flex-direction: column; gap: 30px; }
.home-rest-copy p { color: var(--wao-text-weak); font-size: 13px; line-height: 1.8; }
.home-rest-copy h1 { font-size: clamp(20px,3vw,30px); font-weight: 500; }
.rest-entrance { display: inline-block; margin-top: 12px; padding: 10px 16px; border: 1px solid var(--wao-line); border-radius: 24px; color: var(--wao-text); text-decoration: none; }
.rest-entrance:focus-visible { outline: 2px solid var(--wao-primary-text); outline-offset: 4px; }
@media (max-width: 600px) { .home-rest-welcome { padding: 20px; } }
</style>
