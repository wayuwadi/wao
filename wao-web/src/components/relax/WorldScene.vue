<script setup lang="ts">
import { ref, watch } from 'vue'
import { buddies, type WorldId } from './worlds'
const props = defineProps<{ world: WorldId; story?: string }>()
const active = ref(-1),
  greeting = ref('今天的待办：先对自己好一点。')
const words = [
  '脑袋放空，灵感才有地方蹦进来。',
  '这一页，允许空白。',
  '电量不足？一起充个电。',
  '我宣布：现在可以不行动！',
  '先把烦恼，存到明天。',
]
function greet(i: number) {
  active.value = i
  greeting.value = words[i]!
}
watch(
  () => [props.world, props.story],
  () => {
    active.value = -1
  },
)
</script>
<template>
  <div class="world-scene" :class="`scene-${world}`">
    <div class="world-floor"></div>
    <div class="world-decoration" aria-hidden="true">
      <span v-if="world === 'club'">TAKE<br />IT EASY.</span
      ><span v-else-if="world === 'arcade'">SAFE ZONE</span
      ><span v-else-if="world === 'journal'">这一页，允许不完美。</span
      ><span v-else-if="world === 'gallery'">THE ART OF DOING NOTHING.</span><span v-else>✳</span>
    </div>
    <p class="world-greeting" role="status">{{ active >= 0 ? greeting : story || greeting }}</p>
    <div class="world-crew">
      <button
        v-for="(b, i) in buddies"
        :key="i"
        :class="{ hello: active === i }"
        :aria-label="`${b.name}，打个招呼`"
        @focus="greet(i)"
        @pointerenter="$event.pointerType === 'mouse' && greet(i)"
        @click="greet(i)"
      >
        <img :src="b.image" :alt="b.name" /><span v-if="world === 'gallery'" class="plinth"></span>
      </button>
    </div>
  </div>
</template>
