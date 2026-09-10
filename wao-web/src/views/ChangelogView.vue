<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Button as AButton, Input as AInput } from 'ant-design-vue'
import ChangelogCard from '@/components/changelog/ChangelogCard.vue'
import { fetchChangelog } from '@/api/changelog'
import {
  CHANGELOG_CATEGORIES,
  type ChangelogCategory,
  type ChangelogEntry,
} from '@/types/changelog'

const entries = ref<ChangelogEntry[]>([])
const loading = ref(false)
const error = ref('')
const category = ref<ChangelogCategory | 'all'>('all')
const query = ref('')
const version = ref('')
const subscribed = ref(false)
const filters = [{ id: 'all' as const, label: '全部' }, ...CHANGELOG_CATEGORIES]
const sorted = computed(() =>
  [...entries.value].sort((a, b) => b.publishedOn.localeCompare(a.publishedOn)),
)
const versions = computed(() => [...new Set(sorted.value.map((entry) => entry.version))])
const filtered = computed(() => {
  const search = query.value.trim().toLocaleLowerCase()
  return sorted.value.filter(
    (entry) =>
      (category.value === 'all' || entry.categories.includes(category.value)) &&
      (!version.value || entry.version === version.value) &&
      (!search ||
        [entry.title, entry.summary, ...entry.highlights, ...entry.tags, entry.version]
          .join(' ')
          .toLocaleLowerCase()
          .includes(search)),
  )
})
const months = computed(() => {
  const groups = new Map<string, ChangelogEntry[]>()
  for (const entry of filtered.value) {
    const key = entry.publishedOn.slice(0, 7)
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key)!.push(entry)
  }
  return [...groups].map(([month, items]) => ({ month, items }))
})
const hasFilter = computed(() => category.value !== 'all' || !!version.value || !!query.value)

function clearFilters() {
  category.value = 'all'
  query.value = ''
  version.value = ''
}
function monthLabel(month: string) {
  const [year, number] = month.split('-')
  return `${year} 年 ${Number(number)} 月`
}
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    entries.value = await fetchChangelog()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '更新日志读取失败，请重试。'
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <main class="changelog-page">
    <header class="changelog-hero">
      <div class="hero-copy">
        <div class="eyebrow">
          <i class="ri-sparkling-2-line" aria-hidden="true"></i> WAO PRODUCT UPDATES
          <span>示例预览</span>
        </div>
        <h1>每一次更新，都让工作更顺手</h1>
        <p>了解 WAO 的新功能、体验优化与问题修复，和我们一起，把想法变成进展。</p>
        <div class="hero-meta">
          <span><i class="ri-history-line" aria-hidden="true"></i> 更新日志</span
          ><span>{{ entries.length }} 条示例更新</span><span>{{ versions.length }} 个示例版本</span>
        </div>
      </div>
      <div class="hero-art" aria-hidden="true">
        <i class="ri-rocket-2-line"></i><span>Keep moving forward.</span>
      </div>
    </header>
    <div class="preview-note">
      <i class="ri-information-line" aria-hidden="true"></i
      ><span
        >当前为前端示例，内容与版本号仅供预览。真实交付记录可在<RouterLink to="/updates"
          >更新日记</RouterLink
        >查看。</span
      >
    </div>

    <div class="changelog-layout">
      <section class="feed" aria-label="更新日志">
        <div class="filter-panel">
          <div class="filters" role="group" aria-label="更新分类">
            <button
              v-for="filter in filters"
              :key="filter.id"
              type="button"
              :aria-pressed="category === filter.id"
              :class="{ active: category === filter.id }"
              @click="category = filter.id"
            >
              {{ filter.label }}
            </button>
          </div>
          <AInput
            v-model:value="query"
            allow-clear
            placeholder="搜索更新、功能或标签"
            aria-label="搜索更新"
          >
            <template #prefix
              ><i class="ri-search-line search-icon" aria-hidden="true"></i
            ></template>
          </AInput>
        </div>
        <div class="results-meta" role="status" aria-live="polite">
          <span>{{ version || '所有版本' }} · {{ filtered.length }} 条更新</span>
          <button v-if="hasFilter" type="button" @click="clearFilters">
            清除筛选 <i class="ri-close-line" aria-hidden="true"></i>
          </button>
        </div>
        <div v-if="loading" class="state-panel" role="status">正在读取更新日志…</div>
        <div v-if="error" class="state-panel error" role="alert">
          <p>{{ error }}</p>
          <AButton @click="load">重新加载</AButton>
        </div>
        <div v-if="!loading && !error && !filtered.length" class="state-panel">
          <i class="ri-search-eye-line" aria-hidden="true"></i>
          <h2>{{ entries.length ? '没有找到匹配的更新' : '还没有可展示的更新' }}</h2>
          <p>
            {{
              entries.length
                ? '试试其他关键词，或清除筛选查看全部内容。'
                : '新的产品进展会在这里与你见面。'
            }}
          </p>
          <AButton v-if="hasFilter" @click="clearFilters">查看全部更新</AButton>
        </div>
        <section
          v-for="group in months"
          :key="group.month"
          class="month-group"
          :aria-label="monthLabel(group.month)"
        >
          <h2 class="month-heading">
            {{ monthLabel(group.month) }}<span>{{ group.items.length }} 条更新</span>
          </h2>
          <div class="timeline">
            <div v-for="entry in group.items" :key="entry.id" class="timeline-entry">
              <div class="date-column">
                <span class="date-dot"></span
                ><time :datetime="entry.publishedOn">{{
                  entry.publishedOn.slice(5).replace('-', ' / ')
                }}</time>
              </div>
              <ChangelogCard :entry="entry" />
            </div>
          </div>
        </section>
        <p v-if="filtered.length" class="feed-end">
          每一点进步，都值得记录 <i class="ri-sparkling-line" aria-hidden="true"></i>
        </p>
      </section>

      <aside class="sidebar" aria-label="版本与订阅">
        <section class="side-card subscription">
          <div class="side-icon"><i class="ri-notification-3-line" aria-hidden="true"></i></div>
          <h2>不错过每一次进步</h2>
          <p>订阅产品更新，让新功能和改进第一时间与你见面。</p>
          <AButton
            type="primary"
            block
            :aria-pressed="subscribed"
            @click="subscribed = !subscribed"
          >
            <i
              :class="subscribed ? 'ri-check-line' : 'ri-notification-3-line'"
              aria-hidden="true"
            ></i>
            {{ subscribed ? '取消演示订阅' : '体验订阅更新' }}
          </AButton>
          <p class="subscription-status" role="status">
            {{
              subscribed
                ? '演示订阅已开启，仅本次页面有效；不会发送通知。'
                : '订阅功能演示，不会发送邮件或站内通知。'
            }}
          </p>
        </section>
        <section class="side-card">
          <h2>
            <i class="ri-price-tag-3-line" aria-hidden="true"></i> 最近版本
            <span class="side-hint">示例</span>
          </h2>
          <div class="versions">
            <button
              v-for="(item, index) in versions.slice(0, 4)"
              :key="item"
              type="button"
              :aria-pressed="version === item"
              :class="{ selected: version === item }"
              @click="version = version === item ? '' : item"
            >
              <span class="version-name">{{ item }}<em v-if="index === 0">最新</em></span>
              <span
                >{{ sorted.find((entry) => entry.version === item)?.publishedOn
                }}<i class="ri-arrow-right-s-line" aria-hidden="true"></i
              ></span>
            </button>
          </div>
          <p v-if="!versions.length" class="side-hint">暂无版本</p>
        </section>
        <section class="side-card publishing">
          <h2><i class="ri-send-plane-line" aria-hidden="true"></i> 发布方式</h2>
          <div>
            <i class="ri-edit-line" aria-hidden="true"></i>
            <p><b>人工编辑</b><span>补充说明，让每条更新更易懂。</span></p>
          </div>
          <div>
            <i class="ri-sparkling-2-line" aria-hidden="true"></i>
            <p>
              <b>AI 辅助整理 <em>规划中</em></b
              ><span>从代码变更提炼用户关心的进展。</span>
            </p>
          </div>
          <div>
            <i class="ri-checkbox-circle-line" aria-hidden="true"></i>
            <p>
              <b>确认后发布 <em>规划中</em></b
              ><span>先审阅，再与大家分享。</span>
            </p>
          </div>
          <p class="publishing-note">当前展示预置示例，自动生成与编辑发布将在后续接入。</p>
        </section>
        <div class="side-signature">
          <span class="wao-mark">WAO</span
          ><span>Wa Allin One<br /><small>让想法与行动，在这里相遇。</small></span>
        </div>
      </aside>
    </div>
  </main>
</template>

<style scoped>
.changelog-page {
  max-width: var(--wao-content-max);
  margin: 0 auto;
  padding: 24px 28px 48px;
  color: var(--wao-text);
}
.changelog-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 30px 32px;
  border-radius: var(--wao-radius-card);
  background: linear-gradient(115deg, #252b72 0%, #4358d6 72%, #7460d8 100%);
  color: white;
  overflow: hidden;
}
.hero-copy {
  position: relative;
  z-index: 1;
}
.eyebrow {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 1.4px;
  color: #dde2ff;
}
.eyebrow span {
  border: 1px solid #ffffff45;
  background: #ffffff12;
  padding: 2px 7px;
  border-radius: 5px;
  letter-spacing: 0;
}
h1 {
  font-size: clamp(23px, 2.5vw, 30px);
  margin: 16px 0 10px;
  line-height: 1.45;
  letter-spacing: 0.3px;
}
.hero-copy > p {
  margin: 0;
  font-size: 12px;
  line-height: 1.8;
  color: #e2e5ff;
}
.hero-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  margin-top: 25px;
  font-size: 11px;
  color: #e2e5ff;
}
.hero-meta i {
  margin-right: 4px;
}
.hero-art {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  flex-shrink: 0;
  color: #ffffffbb;
}
.hero-art > i {
  display: grid;
  place-items: center;
  width: 90px;
  height: 90px;
  border-radius: 24px;
  background: #ffffff12;
  border: 1px solid #ffffff30;
  font-size: 49px;
  transform: rotate(8deg);
}
.hero-art span {
  font-size: 10px;
  letter-spacing: 0.5px;
}
.preview-note {
  display: flex;
  align-items: baseline;
  gap: 7px;
  padding: 12px 0 20px;
  font-size: 11px;
  line-height: 1.7;
  color: var(--wao-text-3);
}
.preview-note a {
  margin: 0 4px;
}
.changelog-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 270px;
  gap: 24px;
  align-items: start;
}
.feed {
  min-width: 0;
}
.filter-panel {
  padding: 14px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-card);
}
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-bottom: 12px;
}
.filters button {
  border: 1px solid transparent;
  border-radius: var(--wao-radius-pill);
  background: transparent;
  color: var(--wao-text-3);
  padding: 6px 12px;
  cursor: pointer;
  font-size: 12px;
}
.filters button:hover {
  background: var(--wao-bg-hover);
}
.filters button.active {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  border-color: var(--wao-primary-line);
  font-weight: 600;
}
.search-icon {
  color: var(--wao-text-3);
  margin-right: 4px;
}
.changelog-page :deep(.ant-input-affix-wrapper),
.changelog-page :deep(.ant-input),
.changelog-page :deep(.ant-btn-default) {
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  border-color: var(--wao-line-control);
  border-radius: var(--wao-radius-control);
}
.changelog-page :deep(.ant-input::placeholder),
.changelog-page :deep(.ant-input-clear-icon) {
  color: var(--wao-text-3);
}
.changelog-page :deep(.ant-input-affix-wrapper:focus-within) {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 2px var(--wao-primary-soft);
}
.changelog-page :deep(.ant-btn-primary) {
  background: var(--wao-primary);
  border-color: var(--wao-primary);
  border-radius: var(--wao-radius-control);
  box-shadow: none;
}
.changelog-page :deep(.ant-btn-primary:hover) {
  background: var(--wao-primary-hover);
}
.results-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 44px;
  color: var(--wao-text-3);
  font-size: 11px;
}
.results-meta button {
  border: 0;
  background: none;
  color: var(--wao-primary-text);
  cursor: pointer;
  font-size: 11px;
}
.month-heading {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 15px;
  margin: 8px 0 18px;
}
.month-heading span {
  font-size: 10px;
  font-weight: 400;
  color: var(--wao-text-3);
}
.timeline {
  position: relative;
}
.timeline::before {
  content: '';
  position: absolute;
  top: 8px;
  bottom: 25px;
  left: 5px;
  width: 1px;
  background: var(--wao-primary-line);
}
.timeline-entry {
  position: relative;
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  gap: 12px;
  padding: 0 0 22px;
}
.date-column {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding-top: 23px;
  font: 11px var(--wao-font-mono);
  color: var(--wao-text-3);
}
.date-dot {
  position: relative;
  z-index: 1;
  width: 11px;
  height: 11px;
  flex-shrink: 0;
  background: var(--wao-bg-page);
  border: 3px solid var(--wao-primary);
  border-radius: 50%;
}
.date-column time {
  white-space: nowrap;
}
.feed-end {
  text-align: center;
  font-size: 11px;
  color: var(--wao-text-3);
  padding: 14px 0;
}
.sidebar {
  display: grid;
  gap: 16px;
}
.side-card {
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-card);
  padding: 20px;
  background: var(--wao-bg-card);
}
.side-card h2 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 12px;
  font-size: 14px;
}
.side-card h2 > i {
  color: var(--wao-primary-text);
  font-size: 17px;
}
.side-card > p {
  font-size: 12px;
  color: var(--wao-text-3);
  line-height: 1.8;
}
.subscription {
  background: linear-gradient(155deg, var(--wao-primary-tint), var(--wao-bg-card));
  border-color: var(--wao-primary-line);
}
.side-icon {
  display: grid;
  place-items: center;
  height: 38px;
  width: 38px;
  border-radius: 11px;
  color: var(--wao-primary-text);
  background: var(--wao-primary-soft);
  font-size: 20px;
  margin-bottom: 14px;
}
.subscription :deep(.ant-btn) {
  margin-top: 6px;
  font-size: 12px;
}
.subscription .subscription-status {
  margin: 10px 0 0;
  font-size: 10px;
}
.side-hint {
  color: var(--wao-text-3);
  font-size: 10px;
  font-weight: 400;
  margin-left: auto;
}
.versions {
  display: grid;
}
.versions button {
  display: grid;
  gap: 7px;
  border: none;
  border-bottom: 1px solid var(--wao-divider);
  background: transparent;
  border-radius: 6px;
  padding: 12px 7px;
  color: var(--wao-text-3);
  text-align: left;
  cursor: pointer;
  font-size: 10px;
}
.versions button:last-child {
  border-bottom: none;
}
.versions button:hover,
.versions button.selected {
  background: var(--wao-primary-soft);
}
.versions button > span {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.versions .version-name {
  color: var(--wao-text-2);
  font: 12px var(--wao-font-mono);
}
.version-name em,
.publishing em {
  font: normal 9px var(--wao-font);
  padding: 2px 5px;
  color: var(--wao-primary-text);
  background: var(--wao-primary-soft);
  border-radius: 4px;
}
.publishing > div {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 18px;
}
.publishing > div > i {
  color: var(--wao-primary-text);
  background: var(--wao-primary-soft);
  border-radius: 7px;
  padding: 5px;
  font-size: 14px;
}
.publishing div p {
  margin: 0;
  display: grid;
  gap: 6px;
  font-size: 11px;
  line-height: 1.6;
}
.publishing b {
  display: flex;
  align-items: center;
  gap: 7px;
  font-weight: 500;
}
.publishing div span {
  font-size: 10px;
  color: var(--wao-text-3);
}
.publishing .publishing-note {
  border-top: 1px solid var(--wao-divider);
  margin: 18px 0 0;
  padding-top: 12px;
  font-size: 10px;
}
.side-signature {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 9px;
  padding: 8px 0;
  color: var(--wao-text-3);
  font-size: 11px;
  line-height: 1.8;
}
.wao-mark {
  width: 31px;
  height: 31px;
  display: grid;
  place-items: center;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  border-radius: 9px;
  font-size: 10px;
  font-weight: 700;
}
.side-signature small {
  font-size: 9px;
}
.state-panel {
  text-align: center;
  padding: 36px 20px;
  background: var(--wao-bg-card);
  border: 1px dashed var(--wao-line-control);
  border-radius: var(--wao-radius-card);
  color: var(--wao-text-3);
  font-size: 12px;
}
.state-panel > i {
  font-size: 28px;
  color: var(--wao-primary-text);
}
.state-panel h2 {
  font-size: 16px;
  color: var(--wao-text);
}
.error {
  color: var(--wao-danger-strong);
  margin-bottom: 16px;
}
@media (max-width: 1100px) {
  .changelog-page {
    padding: 20px;
  }
  .changelog-layout {
    grid-template-columns: minmax(0, 1fr) 238px;
    gap: 18px;
  }
  .timeline-entry {
    grid-template-columns: 1fr;
    gap: 10px;
    padding-left: 22px;
  }
  .date-column {
    padding-top: 0;
  }
  .date-dot {
    position: absolute;
    left: 0;
    top: 1px;
  }
  .hero-art {
    display: none;
  }
}
@media (max-width: 800px) {
  .changelog-layout {
    grid-template-columns: 1fr;
  }
  .sidebar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .side-signature {
    display: none;
  }
}
@media (max-width: 600px) {
  .changelog-page {
    padding: 14px 12px 32px;
  }
  .changelog-hero {
    padding: 24px 20px;
  }
  .hero-meta {
    gap: 12px;
  }
  .sidebar {
    grid-template-columns: 1fr;
  }
  .filters button {
    padding: 7px 10px;
  }
}
</style>
