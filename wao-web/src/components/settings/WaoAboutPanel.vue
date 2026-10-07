<script setup lang="ts">
import WaoLogo from '@/components/WaoLogo.vue'
import CompanionAvatar from '@/components/companions/CompanionAvatar.vue'
import { COMPANIONS, COMPANION_IDS } from '@/utils/companionIdentity'

const principles = [
  { icon: 'ri-quill-pen-line', title: '随手记录', description: '一个念头、一段日常，先留下来，再慢慢整理。' },
  { icon: 'ri-checkbox-circle-line', title: '清楚安排', description: '把待办和项目放在顺手的位置，知道下一步做什么。' },
  { icon: 'ri-leaf-line', title: '留一点空白', description: '事情可以一步步做，生活也可以停下来，喘口气。' },
]
// 已公开且核对过标题的真实产品记录；不在关于页加载成员的私人小记。
const stories = [
  { id: 'a92ad78fc67c4e27b480a7c261fa0830', title: '让首页记住重要日子，也留住空白', description: '在真实日期里，遇见一点日常的关心。' },
  { id: '8c5c81f9f4734e2a996d804c7a98d4f6', title: 'WAO 首页新增随机写作入场动画并上线', description: '从一个念头出发，走进可以写的地方。' },
]
</script>

<template>
  <article class="wao-about" aria-labelledby="wao-about-title">
    <h1 id="wao-about-title">关于 WAO</h1>
    <section class="brand-card" aria-labelledby="wao-brand-tagline">
      <div class="brand-signature"><WaoLogo wordmark /><span class="brand-descriptor">Life OS</span></div>
      <h2 id="wao-brand-tagline"><span>让事务有序，</span><span>让生活留白。</span></h2>
      <p>通过小记、待办、项目与 AI 协作，把日常事务放在顺手的位置，也给生活留一点空白。</p>
    </section>

    <section class="about-section" aria-labelledby="wao-principles-title">
      <h2 id="wao-principles-title">我们相信的小事</h2>
      <div class="principles">
        <div v-for="principle in principles" :key="principle.title" class="principle">
          <i :class="principle.icon" aria-hidden="true"></i>
          <h3>{{ principle.title }}</h3><p>{{ principle.description }}</p>
        </div>
      </div>
    </section>

    <section class="about-section" aria-labelledby="wao-companions-title">
      <h2 id="wao-companions-title">认识四位伙伴</h2>
      <p class="section-intro">记录一点，明白一点，开心一点，放松一点。</p>
      <div class="companions">
        <div v-for="id in COMPANION_IDS" :key="id" class="companion-card">
          <div class="portrait"><CompanionAvatar :identity="id" :size="80" /></div>
          <div class="companion-copy">
            <h3>{{ COMPANIONS[id].name }}<span>{{ COMPANIONS[id].role }}</span></h3>
            <p>{{ COMPANIONS[id].meaning }}</p>
            <blockquote>“{{ COMPANIONS[id].voice }}”</blockquote>
          </div>
        </div>
      </div>
    </section>

    <section class="about-section" aria-labelledby="wao-more-title">
      <h2 id="wao-more-title">了解更多</h2>
      <div class="resource-links">
        <a class="about-link" href="/design" target="_blank" rel="noopener noreferrer">
          <i class="ri-layout-grid-line" aria-hidden="true"></i>
          <span><strong>设计参考</strong><small>颜色、卡片、控件、弹窗与交互状态。</small></span>
          <i class="ri-external-link-line" aria-hidden="true"></i>
        </a>
        <a class="about-link brand-resource-link" href="/brand/guide.html" target="_blank" rel="noopener noreferrer">
          <i class="ri-palette-line" aria-hidden="true"></i>
          <span><strong>品牌与素材</strong><small>标识、配色、使用规范与完整资源包。</small></span>
          <i class="ri-external-link-line" aria-hidden="true"></i>
        </a>
        <a class="about-link" href="/download" target="_blank" rel="noopener noreferrer">
          <i class="ri-window-line" aria-hidden="true"></i>
          <span><strong>浏览器使用</strong><small>在电脑和手机上打开 WAO，让入口更顺手。</small></span>
          <i class="ri-external-link-line" aria-hidden="true"></i>
        </a>
      </div>
      <h3 class="stories-title">WAO 正在慢慢长大</h3>
      <div class="stories">
        <a v-for="story in stories" :key="story.id" class="about-link story-link" :href="`/s/d/${story.id}`" target="_blank" rel="noopener noreferrer">
          <span><strong>{{ story.title }}</strong><small>{{ story.description }}</small></span>
          <i class="ri-external-link-line" aria-hidden="true"></i>
        </a>
      </div>
    </section>
  </article>
</template>

<style scoped>
.wao-about { max-width: 840px; color: var(--wao-text); }
h1 { margin: 0 0 22px; font-size: 22px; font-weight: 650; line-height: 1.4; }
h2, h3, p { margin: 0; }
p { font-size: 13px; line-height: 1.8; color: var(--wao-text-3); }
.brand-card { padding: 28px 30px; border: 1px solid var(--wao-line); border-radius: 18px; background: var(--wao-primary-soft); }
.brand-signature { display: flex; align-items: center; gap: 13px; color: var(--wao-primary-text); }
.brand-descriptor { padding-left: 13px; border-left: 1px solid var(--wao-line); font-size: 12px; letter-spacing: .06em; color: var(--wao-text-3); }
.brand-signature :deep(.wao-logo-symbol) { width: 44px; height: 44px; }
.brand-signature :deep(.wao-logo-name) { font-size: 24px; }
.brand-card h2 { margin: 24px 0 10px; font-size: clamp(22px, 2.2vw, 28px); line-height: 1.45; font-weight: 600; }
.brand-card h2 span { display: inline-block; }
.brand-card p { max-width: 540px; }
.about-section { margin-top: 30px; }
.about-section > h2 { font-size: 16px; font-weight: 600; line-height: 1.5; margin-bottom: 14px; }
.principles { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 22px; }
.principle > i { color: var(--wao-primary-text); font-size: 19px; }
.principle h3 { margin: 9px 0 6px; font-size: 14px; font-weight: 600; }
.section-intro { margin: -6px 0 17px; }
.companions { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.companion-card { min-width: 0; padding: 18px 16px; border: 1px solid var(--wao-line); border-radius: 15px; background: var(--wao-bg-card); }
.portrait { height: 80px; display: flex; align-items: center; justify-content: center; margin-bottom: 13px; }
.companion-copy h3 { display: flex; align-items: center; gap: 8px; font-size: 16px; font-weight: 600; line-height: 1.5; }
.companion-copy h3 span { font-size: 11px; font-weight: 400; color: var(--wao-primary-text); }
.companion-copy p { margin-top: 8px; }
blockquote { margin: 13px 0 0; color: var(--wao-primary-text); font-size: 12px; line-height: 1.7; }
.resource-links { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.about-link { box-sizing: border-box; display: flex; gap: 12px; align-items: center; padding: 16px; border: 1px solid var(--wao-line); border-radius: 12px; color: var(--wao-text); background: var(--wao-bg-card); text-decoration: none; }
.about-link:hover { border-color: var(--wao-primary-text); }
.about-link:focus-visible { outline: 2px solid var(--wao-primary-text); outline-offset: 3px; }
.about-link > i:first-child { color: var(--wao-primary-text); font-size: 20px; }
.about-link > i:last-child { font-size: 14px; color: var(--wao-text-weak); flex: none; }
.about-link > span { flex: 1; min-width: 0; }
.about-link strong { display: block; font-size: 13px; font-weight: 500; line-height: 1.6; overflow-wrap: anywhere; }
.about-link small { display: block; margin-top: 4px; color: var(--wao-text-3); font-size: 12px; line-height: 1.7; }
.stories-title { margin: 22px 0 8px; font-size: 13px; font-weight: 500; color: var(--wao-text-weak); }
.story-link { padding: 13px 0; border: 0; border-radius: 0; background: transparent; }
.story-link + .story-link { border-top: 1px solid var(--wao-line); }
.story-link:hover strong { color: var(--wao-primary-text); }
@media (max-width: 1300px) {
  .companions { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .companion-card { display: flex; align-items: center; gap: 14px; }
  .portrait { flex: none; margin-bottom: 0; }
}
@media (max-width: 650px) {
  .brand-card { padding: 22px 20px; }
  .brand-signature { gap: 9px; }
  .brand-descriptor { padding-left: 9px; }
  .principles { grid-template-columns: 1fr; gap: 17px; }
  .principle h3 { display: inline; margin-left: 8px; }
  .principle p { margin-top: 5px; }
  .companions, .resource-links { grid-template-columns: 1fr; }
  .companion-card { display: flex; gap: 14px; padding: 18px 14px; align-items: center; }
  .portrait { flex: none; width: 58px; margin-bottom: 0; }
  .portrait :deep(img) { max-width: 58px; max-height: 64px; }
  .companion-copy { min-width: 0; }
  blockquote { margin-top: 8px; }
}
</style>
