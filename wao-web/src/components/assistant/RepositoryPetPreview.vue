<script setup lang="ts">
import { computed, ref, watch } from 'vue'
const props = defineProps<{ src: string; version: number }>()
const state = ref('idle'),
  failed = ref(false),
  loaded = ref(false)
const actions = [
  { id: 'idle', name: '待机', row: 0, frames: 6 },
  { id: 'right', name: '向右跑', row: 1, frames: 8 },
  { id: 'left', name: '向左跑', row: 2, frames: 8 },
  { id: 'wave', name: '挥手', row: 3, frames: 4 },
  { id: 'jump', name: '跳跃', row: 4, frames: 5 },
  { id: 'failed', name: '失败', row: 5, frames: 8 },
  { id: 'wait', name: '等待', row: 6, frames: 6 },
  { id: 'work', name: '工作', row: 7, frames: 6 },
  { id: 'review', name: '审阅', row: 8, frames: 6 },
]
const selected = computed(() => actions.find((a) => a.id === state.value)!)
const style = computed(() => ({
  width: '96px',
  height: '104px',
  backgroundImage: `url("${props.src}")`,
  backgroundSize: `768px ${props.version === 2 ? 1144 : 936}px`,
  backgroundPositionY: `-${selected.value.row * 104}px`,
  '--pet-end': `-${selected.value.frames * 96}px`,
  animation: `hub-pet-frames ${selected.value.frames * 0.15}s steps(${selected.value.frames}) infinite`,
}))
watch(
  () => props.src,
  () => {
    loaded.value = false
    failed.value = false
  },
)
</script>
<template>
  <div class="repo-pet-preview">
    <img
      :key="src"
      :src="src"
      alt=""
      class="repo-pet-loader"
      @load="loaded = true"
      @error="failed = true"
    />
    <div v-if="failed" role="alert">图集加载失败，请刷新重试。</div>
    <div v-else-if="!loaded" role="status">正在加载图集…</div>
    <div
      v-else
      :key="state"
      :style="style"
      role="img"
      :aria-label="`宠物${selected.name}动画`"
    ></div>
    <label
      >预览动作
      <select v-model="state" aria-label="预览动作">
        <option v-for="a in actions" :key="a.id" :value="a.id">{{ a.name }}</option>
      </select></label
    >
  </div>
</template>
<style>
.repo-pet-preview {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 20px;
  min-height: 130px;
  padding: 15px;
  background: var(--wao-bg-page, #f4f6fb);
  border-radius: 12px;
  flex-wrap: wrap;
}
.repo-pet-preview label {
  font-size: 12px;
  display: grid;
  gap: 7px;
}
.repo-pet-loader {
  display: none;
}
@keyframes hub-pet-frames {
  from {
    background-position-x: 0;
  }
  to {
    background-position-x: var(--pet-end);
  }
}
@media (prefers-reduced-motion: reduce) {
  .repo-pet-preview [role='img'] {
    animation: none !important;
  }
}
</style>
