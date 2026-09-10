<script setup lang="ts">
import { CHANGELOG_CATEGORIES, type ChangelogEntry } from '@/types/changelog'
defineProps<{ entry: ChangelogEntry }>()
</script>

<template>
  <article class="change-card">
    <div class="card-meta">
      <span
        v-for="category in CHANGELOG_CATEGORIES.filter((item) =>
          entry.categories.includes(item.id),
        )"
        :key="category.id"
        class="category"
        :class="category.id"
      >
        <i :class="category.icon" aria-hidden="true"></i>{{ category.label }}
      </span>
      <span class="version">{{ entry.version }}</span>
    </div>
    <h3>{{ entry.title }}</h3>
    <p class="summary">{{ entry.summary }}</p>
    <ul>
      <li v-for="highlight in entry.highlights" :key="highlight">{{ highlight }}</li>
    </ul>
    <div class="card-footer">
      <div class="tags">
        <span v-for="tag in entry.tags" :key="tag"># {{ tag }}</span>
      </div>
      <span class="source"
        ><i
          :class="entry.source.aiGenerated ? 'ri-sparkling-2-line' : 'ri-edit-line'"
          aria-hidden="true"
        ></i>
        {{ entry.source.aiGenerated ? 'AI 整理 · 示例' : '人工编辑 · 示例' }}
      </span>
    </div>
    <details>
      <summary>查看详情 <i class="ri-arrow-down-s-line" aria-hidden="true"></i></summary>
      <p>{{ entry.details }}</p>
    </details>
  </article>
</template>

<style scoped>
.change-card {
  padding: 22px 24px 16px;
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-card);
  background: var(--wao-bg-card);
}
.card-meta,
.category,
.card-footer,
.tags,
.source {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
}
.card-meta {
  gap: 8px;
}
.category {
  padding: 3px 8px;
  border-radius: 6px;
  font-size: 11px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.category.improvement {
  background: var(--wao-success-bg);
  color: var(--wao-success-strong);
}
.category.fix {
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
}
.category.ai {
  color: var(--wao-area-finance);
  background: color-mix(in srgb, var(--wao-area-finance) 12%, var(--wao-bg-card));
}
.category.system {
  background: var(--wao-bg-hover-2);
  color: var(--wao-text-3);
}
.version {
  margin-left: auto;
  color: var(--wao-text-3);
  font: 11px var(--wao-font-mono);
}
h3 {
  margin: 15px 0 8px;
  color: var(--wao-text);
  font-size: 18px;
  line-height: 1.5;
}
.summary,
details p {
  margin: 0;
  font-size: 13px;
  line-height: 1.85;
  color: var(--wao-text-3);
}
ul {
  margin: 14px 0 18px;
  padding-left: 18px;
  color: var(--wao-text-2);
  font-size: 12px;
  line-height: 2;
}
li::marker {
  color: var(--wao-primary);
}
.card-footer {
  justify-content: space-between;
  gap: 12px;
  font-size: 11px;
  color: var(--wao-text-3);
}
.tags {
  gap: 12px;
}
.source {
  font-size: 10px;
  color: var(--wao-text-3);
}
details {
  margin-top: 15px;
  padding-top: 12px;
  border-top: 1px solid var(--wao-divider);
}
summary {
  display: flex;
  align-items: center;
  gap: 5px;
  width: fit-content;
  list-style: none;
  cursor: pointer;
  color: var(--wao-primary-text);
  font-size: 12px;
}
summary::-webkit-details-marker {
  display: none;
}
details[open] summary i {
  transform: rotate(180deg);
}
details p {
  padding-top: 12px;
}
@media (max-width: 600px) {
  .change-card {
    padding: 18px 16px 14px;
  }
  h3 {
    font-size: 16px;
  }
}
</style>
