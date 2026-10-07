<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useDevelopmentOverview } from '@/composables/developmentOverview'
import WaoButton from '@/components/ui/WaoButton.vue'
import type { DevelopmentRun, DevelopmentStatus } from '@/api/development'

withDefaults(defineProps<{ variant?: 'dashboard' | 'companion'; blocked?: boolean }>(), {
  variant: 'dashboard',
  blocked: false,
})
const router = useRouter()
const { overview, loading, loadError, refresh } = useDevelopmentOverview()
const executingStatuses: DevelopmentStatus[] = [
  'ANALYZING',
  'IMPLEMENTING',
  'VERIFYING',
  'PR_READY',
  'MERGED',
  'DEPLOYING',
]
const groups = computed(() => [
  {
    status: 'EXECUTING',
    label: '执行中',
    runs: overview.value?.runs.filter((run) => executingStatuses.includes(run.status)) ?? [],
  },
  {
    status: 'WAITING_DECISION',
    label: '待确认',
    runs: overview.value?.runs.filter((run) => run.status === 'WAITING_DECISION') ?? [],
  },
  {
    status: 'FAILED',
    label: '失败',
    runs: overview.value?.runs.filter((run) => run.status === 'FAILED') ?? [],
  },
  {
    status: 'RELEASED',
    label: '已上线待验收',
    runs: overview.value?.runs.filter((run) => run.status === 'RELEASED') ?? [],
  },
])
const firstActionable = computed(
  () =>
    groups.value.find((group) => group.status !== 'EXECUTING' && group.runs.length) ??
    groups.value.find((group) => group.status === 'EXECUTING' && group.runs.length),
)
const showDashboard = computed(
  () => overview.value && (overview.value.policy.enabled || overview.value.runs.length > 0),
)
function open(run?: DevelopmentRun) {
  void router.push({
    path: '/automation',
    query: { tab: 'development', ...(run ? { run: run.id } : {}) },
  })
}
</script>
<template>
  <aside
    v-if="variant === 'companion' && firstActionable && !blocked && !loadError"
    class="companion-glance"
    aria-label="伙伴建设提示"
    @pointerdown.stop
    @click.stop
    @contextmenu.stop
  >
    <WaoButton variant="ghost" @click="open(firstActionable.runs[0])"
      ><i class="ri-sparkling-line" aria-hidden="true"></i
      ><span v-if="firstActionable.status === 'EXECUTING'"
        >正在推进：{{ firstActionable.runs[0]?.title }}</span
      ><span v-else>{{ firstActionable.runs.length }} 项建设{{ firstActionable.label }}</span>
      <i class="ri-arrow-right-up-line" aria-hidden="true"></i
    ></WaoButton>
  </aside>
  <section v-else-if="variant === 'dashboard'" class="development-glance" aria-label="自主建设摘要">
    <p v-if="loading && !overview" role="status">正在读取自主建设摘要…</p>
    <div v-if="loadError" class="glance-error" role="alert">
      <span>建设摘要读取失败：{{ loadError }}</span
      ><WaoButton :loading="loading" @click="refresh">重试读取</WaoButton>
    </div>
    <template v-if="showDashboard"
      ><header>
        <div>
          <h2>自主建设</h2>
          <p>执行进展、关键决定与交付结果，来自实际运行记录。</p>
        </div>
        <WaoButton variant="ghost" @click="open()"
          >打开工作台 <i class="ri-arrow-right-up-line" aria-hidden="true"></i
        ></WaoButton>
      </header>
      <div class="glance-counts">
        <button
          v-for="group in groups"
          :key="group.status"
          type="button"
          :disabled="!group.runs.length"
          @click="open(group.runs[0])"
        >
          <strong>{{ group.runs.length }}</strong
          ><span>{{ group.label }}</span>
        </button>
      </div></template
    >
  </section>
</template>
<style scoped>
.development-glance {
  margin: var(--wao-space-3) 0;
  min-width: 0;
  color: var(--wao-text);
}
.development-glance:empty {
  margin: 0;
}
header,
.glance-error {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--wao-space-2);
}
h2 {
  font-size: 18px;
  margin: 0;
}
p {
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
  line-height: 1.7;
}
.glance-error {
  color: var(--wao-danger-strong);
}
.glance-counts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-block: 1px solid var(--wao-line);
}
.glance-counts button {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--wao-space-2);
  padding: var(--wao-space-2);
  min-height: 64px;
  border: 0;
  background: transparent;
  color: var(--wao-text);
  text-align: left;
  cursor: pointer;
}
.glance-counts button:hover:not(:disabled) {
  background: var(--wao-primary-soft);
}
.glance-counts button:disabled {
  color: var(--wao-text-3);
  cursor: default;
}
.glance-counts strong {
  font-size: 24px;
  font-weight: 500;
}
.glance-counts span {
  font-size: var(--wao-font-caption);
}
.companion-glance {
  position: absolute;
  bottom: 52px;
  left: var(--wao-space-2);
  max-width: min(320px, calc(100% - 32px));
  z-index: 3;
}
.companion-glance :deep(button) {
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  font-size: var(--wao-font-caption);
  min-height: 44px;
  white-space: normal;
}
@media (max-width: 600px) {
  .glance-counts {
    grid-template-columns: 1fr;
  }
}
</style>
