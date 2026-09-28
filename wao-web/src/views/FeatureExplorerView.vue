<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { MODULES } from '@/config/nav'
import { CURRENT_RELEASE, FEATURE_CATALOG } from '@/data/featureCatalog'
import {
  FEATURE_CATEGORIES,
  type FeatureCatalogEntry,
  type FeatureCategory,
} from '@/types/featureExplorer'
import { openReleaseHighlights } from '@/utils/versionUpdate'

const router = useRouter()
const category = ref<FeatureCategory | 'all'>('all')
const expanded = ref(false)
const selected = ref<FeatureCatalogEntry | null>(null)

const catalogById = new Map(FEATURE_CATALOG.map((item) => [item.id, item]))
const latest = CURRENT_RELEASE.highlights.flatMap((highlight) => {
  const feature = catalogById.get(highlight.featureId)
  return feature ? [{ highlight, feature }] : []
})
const filtered = computed(() =>
  FEATURE_CATALOG.filter((item) => category.value === 'all' || item.category === category.value),
)
const visible = computed(() =>
  category.value === 'all' && !expanded.value ? filtered.value.slice(0, 12) : filtered.value,
)
const recommended = FEATURE_CATALOG.filter((item) => item.recommended).slice(0, 3)
const categoryLabel = (id: FeatureCategory) =>
  FEATURE_CATEGORIES.find((item) => item.id === id)?.label ?? '功能'

function selectCategory(next: FeatureCategory | 'all') {
  category.value = next
  expanded.value = next !== 'all'
}

function openDetail(item: FeatureCatalogEntry) {
  selected.value = item
}

async function openFeature(item: FeatureCatalogEntry) {
  selected.value = null
  if (item.id === 'changelog') return
  await router.push(MODULES[item.id].path)
}
</script>

<template>
  <main class="features-page">
    <header class="feature-hero">
      <span class="hero-eyebrow"
        ><i class="ri-compass-discover-line"></i> WAO FEATURE EXPLORER</span
      >
      <h1>认识 WAO 的全部能力</h1>
      <p>
        先看看最近的新变化，也可以按你的目标探索已经存在的功能。这里讲清楚每项能力能帮你做什么，而不是只列一个菜单名称。
      </p>
      <div class="hero-actions">
        <a class="primary" href="#all-features">浏览全部功能</a>
        <button type="button" @click="openReleaseHighlights">只看本次更新</button>
      </div>
    </header>

    <section class="feature-section" aria-labelledby="latest-title">
      <div class="section-heading">
        <div>
          <small>JUST SHIPPED</small>
          <h2 id="latest-title">最新功能</h2>
        </div>
        <p>来自当前浏览器版本的精选亮点。只有真正进入当前前端制品的内容才会在这里出现。</p>
      </div>
      <div class="latest-grid">
        <article
          v-for="{ highlight, feature } in latest"
          :key="highlight.featureId"
          class="latest-card"
          tabindex="0"
          role="button"
          :aria-label="`了解${MODULES[feature.id].label}`"
          @click="openDetail(feature)"
          @keydown.enter="openDetail(feature)"
          @keydown.space.prevent="openDetail(feature)"
        >
          <div class="latest-visual" :data-tone="feature.tone">
            <span class="orbit"></span><i :class="MODULES[feature.id].icon"></i>
          </div>
          <div class="latest-copy">
            <span class="feature-tag new">NEW · {{ categoryLabel(feature.category) }}</span>
            <h3>{{ highlight.title }}</h3>
            <p>{{ highlight.summary }}</p>
            <span class="learn">了解更多 <i class="ri-arrow-right-line"></i></span>
          </div>
        </article>
      </div>
    </section>

    <div id="all-features" class="catalog-wrap">
      <section class="feature-section" aria-labelledby="catalog-title">
        <div class="section-heading">
          <div>
            <small>EXPLORE BY GOAL</small>
            <h2 id="catalog-title">全部功能</h2>
          </div>
          <p aria-live="polite">
            {{ filtered.length }} 项能力 ·
            {{ category === 'all' ? '全部分类' : categoryLabel(category) }}
          </p>
        </div>
        <div class="filter-bar" role="group" aria-label="功能分类">
          <button
            type="button"
            :class="{ active: category === 'all' }"
            :aria-pressed="category === 'all'"
            @click="selectCategory('all')"
          >
            所有功能
          </button>
          <button
            v-for="item in FEATURE_CATEGORIES"
            :key="item.id"
            type="button"
            :class="{ active: category === item.id }"
            :aria-pressed="category === item.id"
            @click="selectCategory(item.id)"
          >
            {{ item.label }}
          </button>
        </div>
        <div class="catalog-grid">
          <article
            v-for="item in visible"
            :key="item.id"
            class="catalog-card"
            tabindex="0"
            role="button"
            :aria-label="`了解${MODULES[item.id].label}`"
            @click="openDetail(item)"
            @keydown.enter="openDetail(item)"
            @keydown.space.prevent="openDetail(item)"
          >
            <div class="catalog-art" :data-tone="item.tone">
              <i :class="MODULES[item.id].icon"></i>
            </div>
            <div class="catalog-copy">
              <span class="feature-tag">{{ categoryLabel(item.category) }}</span>
              <h3>{{ MODULES[item.id].label }}</h3>
              <p>{{ item.summary }}</p>
            </div>
            <i class="ri-arrow-right-up-line catalog-arrow" aria-hidden="true"></i>
          </article>
        </div>
        <div v-if="category === 'all' && visible.length < filtered.length" class="more-wrap">
          <button type="button" @click="expanded = true">查看更多功能</button>
        </div>
      </section>
    </div>

    <section class="feature-section" aria-labelledby="recommended-title">
      <div class="section-heading">
        <div>
          <small>START HERE</small>
          <h2 id="recommended-title">推荐从这里开始</h2>
        </div>
        <p>不是虚构的全局热门排行，而是适合快速理解 WAO 工作方式的几个入口。</p>
      </div>
      <div class="recommended-grid">
        <article
          v-for="item in recommended"
          :key="item.id"
          tabindex="0"
          role="button"
          :aria-label="`了解${MODULES[item.id].label}`"
          @click="openDetail(item)"
          @keydown.enter="openDetail(item)"
          @keydown.space.prevent="openDetail(item)"
        >
          <span>{{ categoryLabel(item.category) }}</span
          ><i :class="MODULES[item.id].icon"></i>
          <h3>{{ MODULES[item.id].label }}</h3>
          <p>{{ item.summary }}</p>
        </article>
      </div>
    </section>

    <Teleport to="body">
      <div v-if="selected" class="detail-mask" @click.self="selected = null">
        <section
          class="detail-dialog"
          role="dialog"
          aria-modal="true"
          :aria-labelledby="`feature-${selected.id}`"
        >
          <button
            type="button"
            class="detail-close"
            aria-label="关闭功能详情"
            @click="selected = null"
          >
            <i class="ri-close-line"></i>
          </button>
          <div class="detail-visual" :data-tone="selected.tone">
            <i :class="MODULES[selected.id].icon"></i>
          </div>
          <div class="detail-copy">
            <span class="feature-tag">{{ categoryLabel(selected.category) }}</span>
            <h2 :id="`feature-${selected.id}`">{{ MODULES[selected.id].label }}</h2>
            <p>{{ selected.description }}</p>
            <div class="detail-actions">
              <button
                v-if="selected.id !== 'changelog'"
                type="button"
                class="primary"
                @click="openFeature(selected)"
              >
                打开功能
              </button>
              <button type="button" @click="selected = null">继续浏览</button>
            </div>
          </div>
        </section>
      </div>
    </Teleport>
  </main>
</template>

<style scoped>
.features-page {
  min-height: 100%;
  background: var(--wao-bg-page);
  color: var(--wao-text);
}
.feature-hero {
  position: relative;
  overflow: hidden;
  padding: 82px 24px 66px;
  text-align: center;
  background:
    radial-gradient(
      circle at 50% 8%,
      color-mix(in srgb, var(--wao-primary) 18%, transparent),
      transparent 35%
    ),
    linear-gradient(
      var(--wao-bg-page),
      color-mix(in srgb, var(--wao-primary-soft) 32%, var(--wao-bg-page))
    );
}
.feature-hero::before,
.feature-hero::after {
  content: '';
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}
.feature-hero::before {
  width: 340px;
  height: 340px;
  left: -190px;
  bottom: -230px;
  background: color-mix(in srgb, var(--wao-primary) 8%, transparent);
}
.feature-hero::after {
  width: 300px;
  height: 300px;
  right: -170px;
  top: 40px;
  background: rgb(80 199 139 / 9%);
}
.hero-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--wao-primary-text);
  font-size: 11px;
  font-weight: 850;
  letter-spacing: 0.14em;
}
.feature-hero h1 {
  max-width: 850px;
  margin: 17px auto 14px;
  font-size: clamp(42px, 6vw, 72px);
  line-height: 1.06;
  letter-spacing: -0.06em;
}
.feature-hero p {
  max-width: 700px;
  margin: 0 auto;
  color: var(--wao-text-3);
  font-size: 15px;
  line-height: 1.8;
}
.hero-actions {
  display: flex;
  justify-content: center;
  gap: 10px;
  margin-top: 25px;
}
.hero-actions a,
.hero-actions button {
  padding: 11px 16px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font-weight: 800;
  text-decoration: none;
  cursor: pointer;
}
.hero-actions .primary {
  border-color: var(--wao-text);
  background: var(--wao-text);
  color: var(--wao-bg-card);
}
.feature-section {
  width: min(1180px, calc(100% - 48px));
  margin: 0 auto;
  padding: 66px 0;
}
.section-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 25px;
}
.section-heading small {
  display: block;
  margin-bottom: 8px;
  color: var(--wao-text-weak);
  font-size: 10px;
  font-weight: 850;
  letter-spacing: 0.14em;
}
.section-heading h2 {
  margin: 0;
  font-size: 30px;
  letter-spacing: -0.04em;
}
.section-heading p {
  max-width: 420px;
  margin: 0;
  color: var(--wao-text-weak);
  font-size: 12px;
  line-height: 1.65;
  text-align: right;
}
.latest-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
.latest-card {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--wao-line);
  border-radius: 25px;
  background: var(--wao-bg-card);
  cursor: pointer;
  transition:
    transform 0.28s ease,
    box-shadow 0.28s ease;
}
.latest-card:hover {
  transform: translateY(-7px);
  box-shadow: 0 24px 55px rgb(37 47 42 / 13%);
}
.latest-visual {
  position: relative;
  display: grid;
  place-items: center;
  height: 248px;
  overflow: hidden;
  background: var(--tone);
}
.latest-visual .orbit {
  position: absolute;
  width: 210px;
  height: 210px;
  border: 1px solid color-mix(in srgb, var(--wao-primary) 22%, transparent);
  border-radius: 42% 58% 55% 45%;
  transform: rotate(18deg);
  transition: transform 1s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.latest-card:hover .orbit {
  transform: rotate(42deg) scale(1.05);
}
.latest-visual > i {
  position: relative;
  display: grid;
  place-items: center;
  width: 110px;
  height: 110px;
  border-radius: 32px;
  background: rgb(255 255 255 / 78%);
  box-shadow: 0 22px 42px rgb(46 48 80 / 16%);
  color: var(--tone-ink);
  font-size: 42px;
  transform: rotate(-4deg);
}
.latest-copy {
  padding: 23px 24px 26px;
}
.feature-tag {
  display: inline-flex;
  padding: 5px 8px;
  border-radius: 999px;
  background: var(--wao-bg-hover-2);
  color: var(--wao-text-3);
  font-size: 9px;
  font-weight: 850;
}
.feature-tag.new {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.latest-copy h3 {
  margin: 14px 0 8px;
  font-size: 22px;
  letter-spacing: -0.035em;
}
.latest-copy p {
  min-height: 62px;
  margin: 0;
  color: var(--wao-text-3);
  font-size: 12px;
  line-height: 1.7;
}
.learn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-top: 17px;
  color: var(--wao-primary-text);
  font-size: 11px;
  font-weight: 850;
}
[data-tone='violet'] {
  --tone: linear-gradient(145deg, #dedcff, #f4f2ff);
  --tone-ink: #5952dd;
}
[data-tone='mint'] {
  --tone: linear-gradient(145deg, #dff4e8, #f4fbf6);
  --tone-ink: #27805a;
}
[data-tone='amber'] {
  --tone: linear-gradient(145deg, #f7e5cb, #fff7e8);
  --tone-ink: #a86d18;
}
[data-tone='blue'] {
  --tone: linear-gradient(145deg, #dcecf9, #f2f8fd);
  --tone-ink: #2972a7;
}
[data-tone='rose'] {
  --tone: linear-gradient(145deg, #f4dfe9, #fcf3f7);
  --tone-ink: #a04f75;
}
[data-tone='slate'] {
  --tone: linear-gradient(145deg, #e1e6e3, #f5f7f5);
  --tone-ink: #53615a;
}
.catalog-wrap {
  background: color-mix(in srgb, var(--wao-bg-side) 82%, var(--wao-bg-page));
}
.filter-bar {
  position: sticky;
  top: 12px;
  z-index: 8;
  display: flex;
  gap: 5px;
  width: fit-content;
  max-width: 100%;
  margin: 0 auto 32px;
  padding: 7px;
  overflow-x: auto;
  border: 1px solid var(--wao-line);
  border-radius: 18px;
  background: color-mix(in srgb, var(--wao-bg-card) 92%, transparent);
  box-shadow: 0 13px 32px rgb(33 43 38 / 10%);
  backdrop-filter: blur(16px);
  scrollbar-width: none;
}
.filter-bar::-webkit-scrollbar {
  display: none;
}
.filter-bar button {
  flex: none;
  padding: 9px 13px;
  border: 0;
  border-radius: 11px;
  background: transparent;
  color: var(--wao-text-3);
  font-size: 11px;
  font-weight: 760;
  cursor: pointer;
}
.filter-bar button.active {
  background: var(--wao-text);
  color: var(--wao-bg-card);
  box-shadow: 0 6px 14px rgb(30 40 35 / 16%);
}
.catalog-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.catalog-card {
  position: relative;
  min-width: 0;
  min-height: 292px;
  overflow: hidden;
  padding: 18px;
  border: 1px solid var(--wao-line);
  border-radius: 21px;
  background: var(--wao-bg-card);
  cursor: pointer;
  transition:
    transform 0.25s ease,
    box-shadow 0.25s ease;
}
.catalog-card:hover {
  transform: translateY(-5px);
  box-shadow: 0 18px 36px rgb(41 51 46 / 10%);
}
.catalog-art {
  position: relative;
  display: grid;
  place-items: center;
  height: 150px;
  overflow: hidden;
  border-radius: 15px;
  background: var(--tone);
}
.catalog-art::before {
  content: '';
  position: absolute;
  width: 105px;
  height: 105px;
  border: 1px solid rgb(39 48 43 / 10%);
  border-radius: 36% 64% 55% 45%;
  transform: rotate(-20deg);
}
.catalog-art i {
  position: relative;
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  border-radius: 20px;
  background: rgb(255 255 255 / 78%);
  box-shadow: 0 14px 30px rgb(39 48 43 / 12%);
  color: var(--tone-ink);
  font-size: 27px;
  transition: transform 0.3s ease;
}
.catalog-card:hover .catalog-art i {
  transform: scale(1.08) rotate(4deg);
}
.catalog-copy {
  padding: 16px 3px 0;
}
.catalog-copy h3 {
  margin: 11px 0 7px;
  font-size: 17px;
}
.catalog-copy p {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: var(--wao-text-3);
  font-size: 10px;
  line-height: 1.62;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.catalog-arrow {
  position: absolute;
  right: 16px;
  bottom: 14px;
  color: var(--wao-text-3);
  font-size: 16px;
}
.more-wrap {
  display: flex;
  justify-content: center;
  padding-top: 28px;
}
.more-wrap button {
  padding: 11px 18px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font-weight: 800;
  cursor: pointer;
}
.recommended-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
.recommended-grid article {
  position: relative;
  min-height: 220px;
  padding: 25px;
  border-radius: 22px;
  background: #202925;
  color: #fff;
  cursor: pointer;
  transition: transform 0.25s ease;
}
.recommended-grid article:nth-child(2) {
  background: #504bd0;
}
.recommended-grid article:nth-child(3) {
  background: #dfeee5;
  color: #213029;
}
.recommended-grid article:hover {
  transform: translateY(-5px) rotate(-0.5deg);
}
.recommended-grid span {
  color: #9fe0bd;
  font-size: 10px;
  font-weight: 850;
  letter-spacing: 0.1em;
}
.recommended-grid article:nth-child(2) span {
  color: #d7d5ff;
}
.recommended-grid article:nth-child(3) span {
  color: #3a795a;
}
.recommended-grid i {
  position: absolute;
  right: 23px;
  top: 22px;
  font-size: 28px;
  opacity: 0.65;
}
.recommended-grid h3 {
  margin: 48px 0 10px;
  font-size: 23px;
}
.recommended-grid p {
  margin: 0;
  color: #bdc7c1;
  font-size: 11px;
  line-height: 1.65;
}
.recommended-grid article:nth-child(3) p {
  color: #65716a;
}
.detail-mask {
  position: fixed;
  inset: 0;
  z-index: 1250;
  display: grid;
  place-items: center;
  padding: 22px;
  background: rgb(15 20 17 / 58%);
  backdrop-filter: blur(12px);
}
.detail-dialog {
  position: relative;
  width: min(680px, 100%);
  overflow: hidden;
  border-radius: 27px;
  background: var(--wao-bg-card);
  box-shadow: 0 35px 90px rgb(0 0 0 / 32%);
}
.detail-close {
  position: absolute;
  right: 15px;
  top: 15px;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 12px;
  background: rgb(255 255 255 / 82%);
  color: #1d2622;
  font-size: 20px;
  cursor: pointer;
}
.detail-visual {
  display: grid;
  place-items: center;
  height: 230px;
  background: var(--tone);
}
.detail-visual i {
  color: var(--tone-ink);
  font-size: 64px;
}
.detail-copy {
  padding: 28px 30px 31px;
}
.detail-copy h2 {
  margin: 12px 0 10px;
  font-size: 29px;
}
.detail-copy p {
  margin: 0;
  color: var(--wao-text-3);
  font-size: 13px;
  line-height: 1.8;
}
.detail-actions {
  display: flex;
  gap: 9px;
  margin-top: 24px;
}
.detail-actions button {
  padding: 11px 15px;
  border: 1px solid var(--wao-line);
  border-radius: 11px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font-weight: 800;
  cursor: pointer;
}
.detail-actions .primary {
  border-color: var(--wao-text);
  background: var(--wao-text);
  color: var(--wao-bg-card);
}
[role='button']:focus-visible,
button:focus-visible,
a:focus-visible {
  outline: 3px solid color-mix(in srgb, var(--wao-primary) 45%, transparent);
  outline-offset: 3px;
}
@media (max-width: 900px) {
  .latest-grid,
  .recommended-grid {
    grid-template-columns: 1fr;
  }
  .latest-visual {
    height: 210px;
  }
  .catalog-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .feature-section {
    width: min(100% - 28px, 680px);
    padding: 48px 0;
  }
  .section-heading {
    align-items: start;
    flex-direction: column;
  }
  .section-heading p {
    text-align: left;
  }
  .feature-hero {
    padding-top: 62px;
  }
}
@media (max-width: 520px) {
  .feature-hero h1 {
    font-size: 42px;
  }
  .feature-hero p {
    font-size: 13px;
  }
  .hero-actions {
    flex-direction: column;
    align-items: center;
  }
  .catalog-grid {
    grid-template-columns: 1fr;
  }
  .catalog-card {
    min-height: 274px;
  }
  .filter-bar {
    top: 8px;
    width: 100%;
    justify-content: flex-start;
  }
  .section-heading h2 {
    font-size: 26px;
  }
  .detail-copy {
    padding: 23px 21px 25px;
  }
  .detail-visual {
    height: 190px;
  }
}
@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    scroll-behavior: auto !important;
    animation: none !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
