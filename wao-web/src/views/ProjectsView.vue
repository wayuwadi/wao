<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { useDeco } from '@/composables/deco'
import { statusMeta } from '@/utils/domain'
import type { Project, ProjectStatus } from '@/types'

const data = useDataStore()
const ui = useUiStore()
const route = useRoute()
const router = useRouter()
const { decoProj } = useDeco()

// 「我的任务」已迁到左侧「个人 · TODO」；老地址平滑跳转，书签不失效。
if (route.query.view === 'mytasks') router.replace('/todo')

// ---------- 项目视图 ----------
const q = ref('')
const groupBy = ref<'不分组' | '按领域' | '按状态'>('不分组')
const sortBy = ref<'按截止日期' | '按进度' | '按名称'>('按截止日期')
const statusF = ref<'全部' | ProjectStatus>('全部')
const openDrop = ref<'group' | 'sort' | null>(null)

const filteredProjects = computed(() => {
  const kw = q.value.trim().toLowerCase()
  let list = data.projects.filter(
    (p) => !kw || p.name.toLowerCase().includes(kw) || p.goal.toLowerCase().includes(kw),
  )
  if (statusF.value !== '全部') list = list.filter((p) => p.status === statusF.value)
  const sorts: Record<string, (a: Project, b: Project) => number> = {
    按截止日期: (a, b) => (a.due || '9').localeCompare(b.due || '9'),
    按进度: (a, b) => b.progress - a.progress,
    按名称: (a, b) => a.name.localeCompare(b.name, 'zh'),
  }
  return list.slice().sort(sorts[sortBy.value]!)
})

const projGroups = computed(() => {
  const list = filteredProjects.value
  let groups: { label: string; dot: string; items: Project[]; hide?: boolean }[]
  if (groupBy.value === '按领域') {
    groups = data.areas
      .map((a) => ({ label: a.name, dot: a.color, items: list.filter((p) => p.area === a.id) }))
      .filter((g) => g.items.length)
  } else if (groupBy.value === '按状态') {
    groups = (['进行中', '有风险', '规划中', '已完成'] as ProjectStatus[])
      .map((st) => ({ label: st, dot: statusMeta(st).color, items: list.filter((p) => p.status === st) }))
      .filter((g) => g.items.length)
  } else {
    groups = [{ label: '全部项目', dot: '#9A9DA9', items: list, hide: true }]
  }
  return groups.map((g) => ({
    label: g.label, dot: g.dot, count: g.items.length, showHead: !g.hide,
    items: g.items.map(decoProj),
  }))
})

const statusChips = computed(() =>
  (['全部', '进行中', '有风险', '规划中', '已完成'] as const).map((st) => ({
    label: st,
    count: data.projects.filter((p) => (st === '全部' ? true : p.status === st)).length,
    on: statusF.value === st,
  })),
)

// ---------- 交互 ----------
function openProject(id: string) {
  router.push(`/projects/${id}`)
}
</script>

<template>
  <div class="projects">
    <!-- header -->
    <div class="ptop">
      <div class="ptitle">项目</div>
      <span class="ptip">{{ filteredProjects.length }} 个项目 · 软件项目与人生大事都值得正式立项</span>
      <div class="spacer"></div>
      <button class="btn-primary" @click="ui.openModal('project')">
        <i class="ri-add-line"></i>新建项目
      </button>
    </div>

    <!-- 项目视图 -->
    <div class="toolbar">
        <div class="spacer"></div>
        <div v-if="openDrop" class="drop-mask" @click="openDrop = null"></div>
        <div class="search">
          <i class="ri-search-line"></i>
          <input v-model="q" placeholder="搜索项目名称或目标…" />
        </div>
        <div class="drop-wrap">
          <button class="tool-btn" @click="openDrop = openDrop === 'group' ? null : 'group'">
            <i class="ri-node-tree"></i>{{ groupBy }}<i class="ri-arrow-down-s-line chev"></i>
          </button>
          <div v-if="openDrop === 'group'" class="drop-menu">
            <button
              v-for="o in (['不分组', '按领域', '按状态'] as const)"
              :key="o"
              class="drop-item"
              @click="((groupBy = o), (openDrop = null))"
            >
              <span>{{ o }}</span><i v-if="o === groupBy" class="ri-check-line"></i>
            </button>
          </div>
        </div>
        <div class="drop-wrap">
          <button class="tool-btn" @click="openDrop = openDrop === 'sort' ? null : 'sort'">
            <i class="ri-arrow-up-down-line"></i>{{ sortBy }}<i class="ri-arrow-down-s-line chev"></i>
          </button>
          <div v-if="openDrop === 'sort'" class="drop-menu">
            <button
              v-for="o in (['按截止日期', '按进度', '按名称'] as const)"
              :key="o"
              class="drop-item"
              @click="((sortBy = o), (openDrop = null))"
            >
              <span>{{ o }}</span><i v-if="o === sortBy" class="ri-check-line"></i>
            </button>
          </div>
        </div>
      </div>

      <div class="chips">
        <button
          v-for="c in statusChips"
          :key="c.label"
          class="chip"
          :class="{ on: c.on }"
          @click="statusF = c.label"
        >
          {{ c.label }}<span class="chip-count wao-tabular">{{ c.count }}</span>
        </button>
      </div>

      <div class="groups">
        <div v-for="g in projGroups" :key="g.label">
          <div v-if="g.showHead" class="group-head">
            <span class="group-dot" :style="{ background: g.dot }"></span>{{ g.label }}
            <span class="group-count wao-tabular">{{ g.count }}</span>
          </div>
          <div class="card-grid">
            <div v-for="p in g.items" :key="p.id" class="proj-card" @click="openProject(p.id)">
              <div class="pc-top">
                <div class="pc-icon" :style="{ background: p.areaColor }">
                  <i v-if="p.icon" :class="p.icon"></i>
                  <template v-else>{{ p.initialChar }}</template>
                </div>
                <div class="pc-name-wrap">
                  <div class="pc-name">{{ p.name }}</div>
                  <div class="pc-sub">{{ p.areaName }} · 负责人 {{ p.ownerName }}</div>
                </div>
                <span class="pc-status" :style="{ background: p.stBg, color: p.stColor }">{{ p.status }}</span>
              </div>
              <div class="pc-goal">{{ p.goalShort }}</div>
              <div class="pc-meta">
                <span class="pc-m"><i class="ri-checkbox-circle-line"></i>{{ p.taskOpen }} / {{ p.taskTotal }} 项任务</span>
                <span class="pc-m" :style="{ color: p.dueColor }"><i class="ri-calendar-line"></i>{{ p.dueStr }}</span>
                <span class="spacer"></span>
                <span class="pc-avs">
                  <span v-for="(mv, i) in p.memberAvs" :key="i" class="pc-av" :style="{ background: mv.color }">{{ mv.initial }}</span>
                </span>
              </div>
              <div class="pc-prog">
                <span class="pc-bar"><span class="pc-fill" :style="{ width: p.pct + '%', background: p.progColor }"></span></span>
                <b class="wao-tabular">{{ p.pct }}%</b>
              </div>
            </div>
          </div>
        </div>
        <div v-if="filteredProjects.length === 0" class="empty">
          <i class="ri-filter-3-line"></i>
          <div>没有符合条件的项目——试试清除搜索或切换状态筛选</div>
        </div>
      </div>
  </div>
</template>

<style scoped>
.projects {
  padding: 16px 24px 40px;
  max-width: var(--wao-content-max);
  margin: 0 auto;
  animation: waoFadeUp 0.25s ease;
}
.spacer {
  flex: 1;
}

.ptop {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.ptitle {
  font-size: 20px;
  font-weight: 700;
}
.ptip {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.btn-primary {
  height: 33px;
  padding: 0 14px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-primary:hover {
  background: var(--wao-primary-hover);
}
.btn-primary i {
  font-size: 15px;
}

.toolbar {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.drop-mask {
  position: fixed;
  inset: 0;
  z-index: 69;
}
.search {
  position: relative;
}
.search i {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--wao-text-weak);
  font-size: 14px;
}
.search input {
  height: 32px;
  width: 210px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  padding: 0 10px 0 32px;
  font-size: 13px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  outline: none;
}
.search input:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.drop-wrap {
  position: relative;
}
.tool-btn {
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.tool-btn:hover {
  background: var(--wao-bg-hover);
}
.tool-btn i {
  font-size: 14px;
  color: var(--wao-text-weak);
}
.tool-btn .chev {
  font-size: 13px;
}
.drop-menu {
  position: absolute;
  right: 0;
  top: 36px;
  z-index: 70;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  box-shadow: var(--wao-shadow-pop);
  padding: 5px;
  width: 130px;
  animation: waoPop 0.14s ease;
}
.drop-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  border: none;
  background: none;
  border-radius: 7px;
  padding: 7px 9px;
  font-size: 13px;
  color: var(--wao-text);
  cursor: pointer;
}
.drop-item span {
  flex: 1;
  text-align: left;
}
.drop-item i {
  font-size: 14px;
  color: var(--wao-primary);
}
.drop-item:hover {
  background: var(--wao-bg-hover-2);
}

.chips {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.chip {
  height: 28px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  border-radius: 999px;
  font-size: 12.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.chip.on {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  border-color: #c7d0f1;
  font-weight: 600;
}
.chip-count {
  opacity: 0.65;
}

.groups {
  margin-top: 2px;
}
.group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 2px 0;
  font-size: 12.5px;
  color: var(--wao-text-3);
  font-weight: 600;
}
.group-dot {
  width: 8px;
  height: 8px;
  border-radius: 3px;
}
.group-count {
  color: var(--wao-text-faint);
  font-weight: 400;
}
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
  margin-top: 12px;
}
.proj-card {
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 15px 16px 13px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.proj-card:hover {
  border-color: #c9cdda;
  box-shadow: 0 3px 12px rgba(16, 18, 28, 0.07);
}
.pc-top {
  display: flex;
  align-items: center;
  gap: 10px;
}
.pc-icon {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 16px;
  flex-shrink: 0;
}
.pc-icon i {
  font-size: 19px;
}
.pc-name-wrap {
  flex: 1;
  min-width: 0;
}
.pc-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.pc-sub {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.pc-status {
  font-size: 11px;
  border-radius: 999px;
  padding: 2px 9px;
  flex-shrink: 0;
}
.pc-goal {
  font-size: 12px;
  color: var(--wao-text-3);
  line-height: 1.7;
  min-height: 40px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.pc-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.pc-m {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.pc-m i {
  font-size: 13px;
}
.pc-avs {
  display: flex;
}
.pc-av {
  width: 19px;
  height: 19px;
  border-radius: 50%;
  color: #fff;
  font-size: 9.5px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #fff;
  margin-left: -5px;
}
.pc-prog {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pc-bar {
  flex: 1;
  height: 5px;
  border-radius: 99px;
  background: var(--wao-divider);
  overflow: hidden;
}
.pc-fill {
  display: block;
  height: 100%;
  border-radius: 99px;
}
.pc-prog b {
  font-size: 11.5px;
  color: var(--wao-text-3);
}

.empty {
  margin-top: 14px;
  padding: 44px;
  text-align: center;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  color: var(--wao-text-weak);
  font-size: 13px;
}
.empty i {
  font-size: 22px;
  color: var(--wao-text-faint-2);
}
.empty div {
  margin-top: 8px;
}

@media (max-width: 760px) {
  .projects { padding: 16px 12px 28px; }
  .toolbar { gap: 7px; }
  .search { flex: 1 1 100%; }
  .search input { width: 100%; height: 38px; }
  .tool-btn, .btn-primary { min-height: 36px; }
  .card-grid { grid-template-columns: 1fr; }
}
</style>
