<script setup lang="ts">
import WeatherPanel from '@/components/calendar/WeatherPanel.vue'
import HomeBreathingStage from '@/components/home/HomeBreathingStage.vue'
import HomeTianrenGlance from '@/components/home/HomeTianrenGlance.vue'
import { useDataStore } from '@/stores/data'
const data = useDataStore()
</script>

<template>
  <main class="home">
    <HomeBreathingStage>
      <template #information>
        <header class="home-day">
          <HomeTianrenGlance />
          <WeatherPanel surface="calendar" :date="data.today" toolbar glance />
        </header>
      </template>
    </HomeBreathingStage>
  </main>
</template>

<style scoped>
.home { min-height: 100%; }
.home-day { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
</style>
