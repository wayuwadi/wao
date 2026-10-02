<script setup lang="ts">
import { computed } from 'vue'
import type { RelaxProgress } from './progress'
import { worldById } from './worlds'
import MemoryGame from './MemoryGame.vue'
import LoungeGame from './LoungeGame.vue'
import OrbitGame from './OrbitGame.vue'
import PlatformGame from './PlatformGame.vue'
import JournalGame from './JournalGame.vue'
import ShadowGame from './ShadowGame.vue'
const props = defineProps<{ progress: RelaxProgress; active: boolean }>(),
  emit = defineEmits<{ story: [text: string] }>()
const world = computed(() => worldById(props.progress.world))
</script>
<template>
  <section class="relax-game-shell" :aria-label="world.game">
    <header class="game-heading">
      <div>
        <p>{{ world.genre }}</p>
        <h1>{{ world.game }}</h1>
      </div>
      <span>{{ world.name }}</span>
    </header>
    <MemoryGame
      v-if="world.id === 'theater'"
      :progress="progress.memory"
      :active="active"
      @story="emit('story', $event)"
    /><LoungeGame
      v-else-if="world.id === 'club'"
      :progress="progress.lounge"
      :active="active"
      @story="emit('story', $event)"
    /><OrbitGame
      v-else-if="world.id === 'orbit'"
      :progress="progress.orbit"
      :active="active"
      @story="emit('story', $event)"
    /><PlatformGame
      v-else-if="world.id === 'arcade'"
      :progress="progress.platform"
      :active="active"
      @story="emit('story', $event)"
    /><JournalGame
      v-else-if="world.id === 'journal'"
      :progress="progress.journal"
      :active="active"
      @story="emit('story', $event)"
    /><ShadowGame
      v-else
      :progress="progress.gallery"
      :active="active"
      @story="emit('story', $event)"
    />
    <p v-if="progress.anecdote" class="game-story">
      <span>伙伴小剧场 · 刚刚发生</span>{{ progress.anecdote }}
    </p>
  </section>
</template>
