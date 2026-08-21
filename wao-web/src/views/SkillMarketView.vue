<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchSkillCategories, fetchSkills, type SkillCard, type SkillCategory } from '@/api/skills'

const categories=ref<SkillCategory[]>([]), items=ref<SkillCard[]>([])
const keyword=ref(''),category=ref(''),platform=ref(''),sort=ref('newest')
const loading=ref(true),error=ref(''),total=ref(0)
let timer:number|undefined
const categoryName=(key:string)=>categories.value.find(c=>c.key===key)?.name||key
const platformName:Record<string,string>={'agent-skills':'Agent Skills',codex:'Codex','claude-code':'Claude Code',openclaw:'OpenClaw'}

async function load(){loading.value=true;error.value='';try{const page=await fetchSkills({keyword:keyword.value||undefined,category:category.value||undefined,platform:platform.value||undefined,sort:sort.value,page:1,pageSize:48});items.value=page.items;total.value=page.total}catch(e){error.value=e instanceof Error?e.message:'市场加载失败'}finally{loading.value=false}}
watch([keyword,category,platform,sort],()=>{window.clearTimeout(timer);timer=window.setTimeout(load,250)})
onMounted(async()=>{try{categories.value=await fetchSkillCategories()}catch{/* 列表仍可使用 */}await load()})
</script>

<template>
  <section class="market-hero">
    <div><span class="hero-kicker">WAO SKILL MARKET</span><h1 class="market-title">把好用的 AI 工作流，交给更多人</h1><p class="market-lead">发现经过安全检查和人工审核的 Agent Skills。每一次下载都有明确版本、许可证与 SHA-256。</p></div>
    <RouterLink class="market-button primary" to="/skills/publish"><i class="ri-upload-cloud-2-line"></i>发布 Skill</RouterLink>
  </section>
  <section class="market-toolbar market-card">
    <label class="search-box"><i class="ri-search-line"></i><input v-model="keyword" placeholder="搜索 Skill 名称、简介或标签"></label>
    <select v-model="category" class="market-select"><option value="">全部分类</option><option v-for="item in categories" :key="item.key" :value="item.key">{{ item.name }}</option></select>
    <select v-model="platform" class="market-select"><option value="">全部平台</option><option v-for="(name,key) in platformName" :key="key" :value="key">{{ name }}</option></select>
    <select v-model="sort" class="market-select"><option value="newest">最近更新</option><option value="downloads">下载最多</option><option value="favorites">收藏最多</option></select>
  </section>
  <div class="result-head"><b>{{ total }} 个 Skill</b><span>公开浏览与下载无需登录</span></div>
  <div v-if="error" class="market-error">{{ error }} <button @click="load">重试</button></div>
  <div v-else-if="loading" class="skill-grid"><div v-for="n in 6" :key="n" class="skill-card skeleton"></div></div>
  <div v-else-if="!items.length" class="market-card market-empty"><i class="ri-inbox-2-line"></i><h3>还没有匹配的 Skill</h3><p>换个关键词或清空筛选试试。</p></div>
  <div v-else class="skill-grid">
    <RouterLink v-for="skill in items" :key="skill.id" class="skill-card market-card" :to="`/skills/@${skill.publisher.handle}/${skill.slug}`">
      <div class="skill-icon">{{ skill.displayName.slice(0,1).toUpperCase() }}</div>
      <div class="skill-copy"><div class="skill-name"><h2>{{ skill.displayName }}</h2><span>v{{ skill.version }}</span></div><p>{{ skill.summary }}</p></div>
      <div class="skill-tags"><span>{{ categoryName(skill.category) }}</span><span v-for="p in skill.compatiblePlatforms.slice(0,3)" :key="p">{{ platformName[p]||p }}</span></div>
      <footer><span>@{{ skill.publisher.handle }}</span><span><i class="ri-download-line"></i>{{ skill.downloads }} · <i class="ri-star-line"></i>{{ skill.favorites }}</span></footer>
    </RouterLink>
  </div>
</template>

<style scoped>
.market-hero{display:flex;align-items:flex-end;justify-content:space-between;gap:30px;padding:24px 4px 38px}.market-hero>div{max-width:760px}.hero-kicker{display:block;color:#5369dc;font-size:11px;font-weight:850;letter-spacing:2px;margin-bottom:12px}.market-toolbar{display:grid;grid-template-columns:minmax(240px,1fr) 170px 160px 140px;gap:10px;padding:12px}.search-box{height:42px;border:1px solid #dfe3ee;border-radius:10px;display:flex;align-items:center;gap:9px;padding:0 12px;background:white}.search-box i{color:#8c93a6}.search-box input{border:0;outline:0;width:100%;font:inherit}.result-head{display:flex;justify-content:space-between;align-items:center;margin:26px 3px 14px}.result-head span{font-size:12px;color:var(--market-sub)}.skill-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.skill-card{min-height:248px;padding:20px;display:flex;flex-direction:column;gap:14px;text-decoration:none;color:inherit;transition:.2s}.skill-card:not(.skeleton):hover{transform:translateY(-3px);border-color:#cbd2f5;box-shadow:0 16px 38px rgba(43,59,131,.11)}.skill-icon{width:46px;height:46px;border-radius:13px;display:grid;place-items:center;background:linear-gradient(135deg,#3e4fb7,#7788eb);color:white;font-size:18px;font-weight:850}.skill-copy{min-width:0}.skill-name{display:flex;align-items:center;gap:9px}.skill-name h2{font-size:17px;margin:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.skill-name span{font-size:10px;color:#7b8295;background:#f1f3f8;padding:3px 6px;border-radius:6px}.skill-copy p{font-size:13px;line-height:1.65;color:var(--market-sub);margin:8px 0 0;display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:3;overflow:hidden}.skill-tags{display:flex;gap:6px;flex-wrap:wrap}.skill-tags span{font-size:10px;background:#f4f6ff;color:#5265c7;padding:5px 7px;border-radius:7px}.skill-card footer{display:flex;justify-content:space-between;color:#858b9b;font-size:11px;margin-top:auto}.skeleton{background:linear-gradient(100deg,#f6f7fa 20%,#fff 45%,#f6f7fa 70%);background-size:220% 100%;animation:shimmer 1.4s infinite}@keyframes shimmer{to{background-position:-220% 0}}.market-error button{border:0;background:none;color:inherit;text-decoration:underline;cursor:pointer}
@media(max-width:900px){.market-toolbar{grid-template-columns:1fr 1fr}.search-box{grid-column:1/-1}.skill-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:600px){.market-hero{align-items:flex-start;flex-direction:column}.market-toolbar{grid-template-columns:1fr}.search-box{grid-column:auto}.skill-grid{grid-template-columns:1fr}.skill-card{min-height:220px}}
</style>
