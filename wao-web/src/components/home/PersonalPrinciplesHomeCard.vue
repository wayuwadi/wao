<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { fetchPersonalPrinciples, type PersonalPrinciple } from '@/api/personalPrinciples'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const items = ref<PersonalPrinciple[]>([])
const loading = ref(true)
const error = ref('')
let timer: ReturnType<typeof setInterval> | undefined
let requestVersion = 0
const focus = computed(() => {
  const order: Record<string, number> = {
    NEEDS_ATTENTION: 0,
    RECORD_DUE: 1,
    WAITING_DATA: 2,
    PENDING_CONSENT: 3,
    ADVICE: 4,
    PENDING: 5,
    COMPLETED: 6,
    EXCEPTION: 7,
    PAUSED: 8,
    MANAGED_ONLY: 9,
  }
  return (
    [...items.value].sort((a, b) => (order[a.judgment] ?? 99) - (order[b.judgment] ?? 99))[0] ??
    null
  )
})
const headline = computed(() => {
  const item = focus.value
  if (!item) return '给重要的事，留下一条清楚的约定。'
  switch (item.judgment) {
    case 'RECORD_DUE': return `到时间了，记下「${item.title}」的实际情况`
    case 'WAITING_DATA': return `「${item.title}」还没有可用数据`
    case 'PENDING_CONSENT': return `「${item.title}」等你确认参与`
    case 'NEEDS_ATTENTION': return `你记下「${item.title}」还需调整，下次试试新的做法`
    case 'COMPLETED': return `今天「${item.title}」已经做到了`
    default: return item.title
  }
})
async function load() {
  if (!session.userId) {
    loading.value = false
    items.value = []
    return
  }
  const version = ++requestVersion
  try {
    const result = await fetchPersonalPrinciples()
    if (version === requestVersion) {
      items.value = result
      error.value = ''
    }
  } catch (problem) {
    if (version === requestVersion)
      error.value = problem instanceof Error ? problem.message : '读取失败'
  } finally {
    if (version === requestVersion) loading.value = false
  }
}
watch(() => session.userId, load)
onMounted(() => {
  void load()
  timer = setInterval(() => {
    if (!document.hidden) void load()
  }, 60_000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
  requestVersion++
})
</script>

<template>
  <section class="principle-home-card" aria-label="准则自留地">
    <div class="mini-scene" aria-hidden="true">
      <span class="scene-halo" /><span class="scene-sheet back" /><span
        class="scene-sheet middle"
      /><span class="scene-sheet front"><i class="ri-shield-check-line" /></span>
    </div>
    <div class="mini-copy">
      <div class="mini-kicker">
        <span class="live-dot" /> 准则自留地 <span class="separator">/</span> 给此刻的一句话
      </div>
      <strong v-if="loading">正在看看今天的约定…</strong>
      <strong v-else-if="error">准则暂时没有更新</strong>
      <strong v-else>{{ headline }}</strong>
      <p v-if="error">{{ error }} · <button type="button" @click="load">重试</button></p>
      <p v-else-if="focus">{{ focus.judgmentReason }}</p>
      <p v-else>可以从散步、睡前不玩手机，或一条仅供参考的建议开始。</p>
    </div>
    <a class="mini-open" href="/personal-principles">
      {{ focus ? '查看准则' : '制定准则' }} <i class="ri-arrow-right-line" />
    </a>
  </section>
</template>

<style scoped>
.principle-home-card {
  grid-column: 1/-1;
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr) auto;
  align-items: center;
  gap: 19px;
  min-height: 136px;
  margin: 18px 0;
  background: radial-gradient(circle at 7% 35%, #eff0ff 0, #f9faff 37%, #fff 68%);
  border: 1px solid #dfe4f9;
  border-radius: 16px;
  padding: 16px 24px 16px 14px;
  position: relative;
  isolation: isolate;
  overflow: hidden;
  color: #29344d;
}
.principle-home-card:before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 4px;
  background: linear-gradient(#6978e2, #93c8be);
}
.mini-scene {
  position: relative;
  width: 106px;
  height: 98px;
  display: grid;
  place-items: center;
}
.scene-halo {
  position: absolute;
  width: 96px;
  height: 96px;
  border: 1px solid #cbd2f4;
  border-radius: 50%;
  background: #edf0ff77;
}
.scene-sheet {
  position: absolute;
  width: 52px;
  height: 66px;
  border-radius: 11px;
  border: 1px solid #bdc9ef;
  box-shadow: 0 8px 15px #263b8d20;
}
.scene-sheet.back {
  background: #bec9ee;
  transform: translate(14px, -6px) rotate(18deg);
}
.scene-sheet.middle {
  background: #dae3fb;
  transform: translate(-10px, 2px) rotate(-15deg);
}
.scene-sheet.front {
  background: linear-gradient(160deg, #fff, #f4f6ff);
  display: grid;
  place-items: center;
  color: #4257bf;
  transform: translateY(2px) rotate(-2deg);
  font-size: 25px;
  box-shadow: 0 12px 24px #263b8d2c;
}
.mini-copy {
  min-width: 0;
}
.mini-kicker {
  display: flex;
  align-items: center;
  gap: 7px;
  color: #5364a7;
  font-size: 11px;
  font-weight: 750;
}
.live-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #60b9a2;
  box-shadow: 0 0 0 4px #d7f2ea;
}
.separator {
  color: #b5bdd2;
}
.mini-copy strong {
  display: block;
  font-size: 18px;
  line-height: 1.35;
  margin-top: 10px;
  letter-spacing: -0.3px;
}
.mini-copy p {
  font-size: 12px;
  line-height: 1.6;
  margin: 6px 0 0;
  color: #788398;
}
.mini-copy p button {
  border: 0;
  background: transparent;
  color: #4e64c7;
  font-weight: 700;
}
.mini-open {
  text-decoration: none;
  border: 1px solid #ccd4f3;
  border-radius: 10px;
  padding: 11px 12px;
  background: #fff;
  color: #4054bd;
  font-size: 12px;
  font-weight: 750;
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
  transition:
    background 0.18s,
    transform 0.18s;
}
.mini-open:hover {
  background: #f1f4ff;
  transform: translateX(2px);
}
@media (max-width: 760px) {
  .principle-home-card {
    grid-template-columns: 62px minmax(0, 1fr);
    gap: 10px;
    min-height: 0;
    padding: 14px;
    margin: 13px 0;
  }
  .mini-scene {
    width: 60px;
    height: 65px;
  }
  .scene-halo {
    width: 58px;
    height: 58px;
  }
  .scene-sheet {
    width: 34px;
    height: 44px;
    border-radius: 7px;
  }
  .scene-sheet.front {
    font-size: 17px;
  }
  .mini-copy strong {
    font-size: 15px;
    margin-top: 7px;
  }
  .mini-copy p {
    font-size: 11px;
  }
  .mini-open {
    grid-column: 2;
    justify-self: start;
    padding: 7px 10px;
    font-size: 11px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .mini-open {
    transition: none;
  }
  .mini-open:hover {
    transform: none;
  }
}
</style>
