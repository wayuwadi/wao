<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchAiProjectConfig, fetchAiRunners } from '@/api/ai'
import { AI_ENGINES, engineColor } from '@/composables/aiEngines'
import type { AiEngine, AiRunner } from '@/types'

const props = defineProps<{ projectId: string; starting?: boolean; scope?: string }>()
const emit = defineEmits<{ close: []; start: [engine: AiEngine, runnerId: string | null] }>()

const loading = ref(true)
const runners = ref<AiRunner[]>([])
const engine = ref<AiEngine>('codex')
const runnerId = ref('')

/** 装了这种 AI 工具的电脑；离线的也列出来，选中后任务会排队等它上线。 */
const candidates = computed(() => runners.value.filter((runner) => runner.engines.includes(engine.value)))
const availability = computed(() =>
  AI_ENGINES.map((item) => ({
    ...item,
    online: runners.value.filter((runner) => runner.online && runner.engines.includes(item.id)).length,
    total: runners.value.filter((runner) => runner.engines.includes(item.id)).length,
  })),
)
const hint = computed(() => {
  const picked = candidates.value.find((runner) => runner.id === runnerId.value)
  if (picked) return picked.online ? `将由 ${picked.name} 领取` : `${picked.name} 当前离线，任务会排队等它上线`
  const online = candidates.value.filter((runner) => runner.online).length
  return online ? `将由 ${online} 台在线电脑中最先空闲的一台领取` : '当前没有在线电脑，任务会先排队'
})

function pickEngine(next: AiEngine) {
  engine.value = next
  if (!candidates.value.some((runner) => runner.id === runnerId.value)) runnerId.value = ''
}

onMounted(async () => {
  try {
    const [nodes, config] = await Promise.all([fetchAiRunners(), fetchAiProjectConfig(props.projectId)])
    runners.value = nodes
    // 项目默认工具一台电脑都没装时，直接落到已经接入的那种，省得用户先撞一次 400。
    const supported = AI_ENGINES.map((item) => item.id)
      .filter((id) => nodes.some((runner) => runner.engines.includes(id)))
    engine.value = supported.includes(config.defaultEngine) || !supported.length
      ? config.defaultEngine
      : supported[0]!
    runnerId.value = candidates.value.some((runner) => runner.id === config.preferredRunnerId)
      ? config.preferredRunnerId ?? ''
      : ''
  } catch {
    // 读取失败时保留默认选择，用户仍然可以直接开跑。
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="launcher-mask" @click.self="emit('close')"></div>
  <section class="launcher" role="dialog" aria-label="选择执行方式">
    <header>
      <b>交给谁来跑？</b>
      <button class="close" aria-label="关闭" @click="emit('close')"><i class="ri-close-line"></i></button>
    </header>

    <p v-if="scope" class="scope"><i class="ri-focus-3-line"></i>本次范围：{{ scope }}</p>

    <div v-if="loading" class="loading"><i class="ri-loader-4-line spin"></i>正在读取可用的电脑…</div>
    <template v-else>
      <div class="label">AI 工具</div>
      <div class="engine-picker">
        <button
          v-for="item in availability"
          :key="item.id"
          class="engine-option"
          :class="{ on: engine === item.id }"
          :style="engine === item.id ? { borderColor: engineColor(item.id), color: engineColor(item.id) } : {}"
          @click="pickEngine(item.id)"
        >
          <i :class="item.icon"></i>
          <b>{{ item.label }}</b>
          <em>{{ item.online ? `${item.online} 台在线` : item.total ? '全部离线' : '未接入' }}</em>
        </button>
      </div>

      <div class="label">执行电脑</div>
      <select v-model="runnerId" class="runner-select">
        <option value="">自动分配（任意在线电脑）</option>
        <option v-for="runner in candidates" :key="runner.id" :value="runner.id">
          {{ runner.name }}{{ runner.online ? ` · 在线 ${runner.activeRuns}/${runner.maxConcurrency}` : ' · 离线' }}
        </option>
      </select>
      <p class="hint"><i class="ri-information-line"></i>{{ hint }}</p>

      <footer>
        <button class="secondary" @click="emit('close')">取消</button>
        <button class="primary" :disabled="starting" @click="emit('start', engine, runnerId || null)">
          <i :class="starting ? 'ri-loader-4-line spin' : 'ri-play-circle-line'"></i>
          {{ starting ? '正在入队…' : '开始运行' }}
        </button>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.launcher-mask { position: fixed; inset: 0; z-index: 40; }
.launcher {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 41;
  width: 300px;
  padding: 14px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 16px 40px rgba(26, 31, 61, .18);
  text-align: left;
}
.launcher header { display: flex; align-items: center; }
.launcher header b { flex: 1; font-size: 13px; }
.close { width: 26px; height: 26px; border: 0; border-radius: 7px; background: #f4f5f8; cursor: pointer; }
.scope { margin: 9px 0 0; padding: 6px 8px; border-radius: 7px; background: #f4f6ff; color: var(--wao-text-2); font-size: 11px; line-height: 1.5; }
.scope i { margin-right: 4px; }
.loading { padding: 18px 0; color: var(--wao-text-weak); font-size: 12px; display: flex; align-items: center; gap: 7px; justify-content: center; }
.label { margin: 13px 0 7px; color: var(--wao-text-weak); font-size: 10.5px; font-weight: 650; letter-spacing: .5px; }
.engine-picker { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.engine-option {
  padding: 10px 9px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  background: #fff;
  color: var(--wao-text-2);
  cursor: pointer;
  text-align: left;
}
.engine-option:hover { border-color: #bec7ef; }
.engine-option.on { background: #f7f8ff; }
.engine-option i { font-size: 15px; }
.engine-option b { display: block; margin-top: 4px; font-size: 12px; }
.engine-option em { display: block; margin-top: 2px; color: var(--wao-text-weak); font-size: 10px; font-style: normal; }
.runner-select {
  width: 100%;
  height: 34px;
  padding: 0 9px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  background: #fff;
  color: var(--wao-text-1);
  font-size: 12px;
}
.hint { margin: 9px 0 0; color: var(--wao-text-weak); font-size: 11px; line-height: 1.5; }
.hint i { margin-right: 4px; }
.launcher footer { margin-top: 14px; display: flex; justify-content: flex-end; gap: 8px; }
.launcher footer button { padding: 7px 13px; border-radius: 8px; cursor: pointer; font-size: 12px; display: inline-flex; align-items: center; gap: 5px; }
.launcher footer .secondary { border: 1px solid var(--wao-line); background: #fff; }
.launcher footer .primary { border: 1px solid #4358d6; background: #4358d6; color: #fff; }
.launcher footer .primary:disabled { opacity: .65; cursor: not-allowed; }
.spin { display: inline-block; animation: spin .9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
