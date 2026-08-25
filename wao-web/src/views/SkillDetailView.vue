<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { fetchSkill, setSkillFavorite, skillDownloadUrl, type SkillDetail } from '@/api/skills'
import { useSessionStore } from '@/stores/session'
import { toast } from '@/composables/toast'

const route=useRoute(),session=useSessionStore(),detail=ref<SkillDetail|null>(null),loading=ref(true),error=ref('')
const handle=computed(()=>String(route.params.handle||'').replace(/^@/,'')),slug=computed(()=>String(route.params.slug||''))
const platformName:Record<string,string>={'agent-skills':'Agent Skills',codex:'Codex','claude-code':'Claude Code',openclaw:'OpenClaw'}
const installs=[
  {name:'Codex',path:'$CODEX_HOME/skills/<skill-name>',tip:'解压后重启 Codex，使新 Skill 被重新发现。'},
  {name:'Claude Code',path:'~/.claude/skills/<skill-name>',tip:'放入个人目录；项目专用可放在 .claude/skills。'},
  {name:'OpenClaw',path:'~/.agents/skills/<skill-name>',tip:'这是多个 Agent 可共享的个人 Skill 根目录。'},
]
async function load(){loading.value=true;error.value='';try{detail.value=await fetchSkill(handle.value,slug.value);document.title=`${detail.value.skill.displayName} · WAO Skill 市场`}catch(e){error.value=e instanceof Error?e.message:'Skill 加载失败'}finally{loading.value=false}}
async function toggleFavorite(){if(!detail.value)return;if(!session.authed){toast('请先登录 WAO 再收藏');return}const next=!detail.value.favorite;try{await setSkillFavorite(detail.value.skill.id,next);detail.value.favorite=next;detail.value.skill.favorites+=next?1:-1}catch(e){toast(e instanceof Error?e.message:'操作失败')}}
async function copyLink(){const publicPath=`/skills/@${handle.value}/${slug.value}`;await navigator.clipboard.writeText(new URL(publicPath,window.location.origin).href);toast('公开分享链接已复制')}
watch(()=>route.fullPath,load);onMounted(load)
</script>

<template>
  <div v-if="loading" class="market-card market-empty">正在读取 Skill…</div>
  <div v-else-if="error" class="market-error">{{ error }}</div>
  <template v-else-if="detail">
    <section class="detail-hero market-card">
      <div class="detail-icon">{{ detail.skill.displayName.slice(0,1) }}</div>
      <div class="detail-main"><div class="detail-title"><h1>{{ detail.skill.displayName }}</h1><span>v{{ detail.publishedVersion.version }}</span></div><p>{{ detail.skill.summary }}</p><div class="badges"><span v-for="p in detail.publishedVersion.compatiblePlatforms" :key="p">{{ platformName[p]||p }}</span><span class="license">{{ detail.publishedVersion.license }}</span></div><small>由 <b>@{{ detail.skill.publisher.handle }}</b> · {{ detail.skill.publisher.displayName }} 发布</small></div>
      <div class="detail-actions"><a class="market-button primary" :href="skillDownloadUrl(handle,slug)"><i class="ri-download-2-line"></i>下载 ZIP</a><button class="market-button" @click="toggleFavorite"><i :class="detail.favorite?'ri-star-fill':'ri-star-line'"></i>{{ detail.favorite?'已收藏':'收藏' }} {{ detail.skill.favorites }}</button><button class="market-button" @click="copyLink"><i class="ri-links-line"></i>分享</button></div>
    </section>
    <div class="detail-grid">
      <article class="market-card content-card"><h2>SKILL.md</h2><pre>{{ detail.publishedVersion.skillMd }}</pre></article>
      <aside class="detail-side">
        <section class="market-card side-card"><h3>版本凭据</h3><dl><div><dt>版本</dt><dd>{{ detail.publishedVersion.version }}</dd></div><div><dt>文件</dt><dd>{{ detail.publishedVersion.fileCount }} 个</dd></div><div><dt>下载</dt><dd>{{ detail.skill.downloads }} 次</dd></div><div class="hash"><dt>SHA-256</dt><dd>{{ detail.publishedVersion.sha256 }}</dd></div></dl></section>
        <section class="market-card side-card"><h3>作者已测试</h3><div class="tested"><span v-for="p in detail.publishedVersion.testedPlatforms" :key="p"><i class="ri-checkbox-circle-fill"></i>{{ platformName[p]||p }}</span></div></section>
        <section class="market-card side-card"><h3>文件清单</h3><ul class="files"><li v-for="file in detail.publishedVersion.files" :key="file.path"><i class="ri-file-code-line"></i><span>{{ file.path }}</span><small>{{ file.size }} B</small></li></ul></section>
      </aside>
    </div>
    <section class="install-section"><div><h2>手动安装</h2><p>WAO 只提供已审核的原始包，不会远程操作你的电脑。</p></div><div class="install-grid"><article v-for="item in installs" :key="item.name" class="market-card"><h3>{{ item.name }}</h3><code>{{ item.path }}</code><p>{{ item.tip }}</p></article></div></section>
    <section v-if="detail.versions.length>1" class="market-card versions"><h2>历史公开版本</h2><a v-for="version in detail.versions" :key="version.id" :href="skillDownloadUrl(handle,slug,version.version)"><b>v{{ version.version }}</b><span>{{ version.changelog||'无版本说明' }}</span><code>{{ version.sha256.slice(0,12) }}…</code></a></section>
  </template>
</template>

<style scoped>
.detail-hero{padding:28px;display:grid;grid-template-columns:auto 1fr auto;gap:22px;align-items:start}.detail-icon{width:70px;height:70px;border-radius:19px;background:linear-gradient(140deg,#33449e,#7889ed);color:white;display:grid;place-items:center;font-size:30px;font-weight:900}.detail-title{display:flex;align-items:center;gap:10px}.detail-title h1{margin:0;font-size:29px}.detail-title span{background:var(--wao-primary-soft);color:var(--wao-primary-text);padding:4px 7px;border-radius:7px;font-size:11px}.detail-main p{margin:10px 0;color:var(--market-sub);line-height:1.7}.detail-main small{color:var(--market-sub)}.badges{display:flex;flex-wrap:wrap;gap:6px;margin:14px 0}.badges span{font-size:11px;background:var(--wao-bg-hover-2);padding:5px 8px;border-radius:7px}.badges .license{background:var(--wao-success-bg);color:var(--wao-success-strong)}.detail-actions{display:grid;gap:8px;min-width:145px}.detail-grid{display:grid;grid-template-columns:minmax(0,1fr) 340px;gap:18px;margin-top:18px}.content-card{padding:24px;min-width:0}.content-card h2,.versions h2{font-size:16px;margin:0 0 16px}.content-card pre{white-space:pre-wrap;word-break:break-word;background:#171b2b;color:#e8eaf3;border-radius:13px;padding:20px;line-height:1.65;font-size:12px;overflow:auto;min-height:420px}.detail-side{display:grid;gap:14px;align-content:start}.side-card{padding:19px}.side-card h3{margin:0 0 13px;font-size:14px}.side-card dl{margin:0;display:grid;gap:10px}.side-card dl div{display:flex;justify-content:space-between;gap:12px;font-size:12px}.side-card dt{color:var(--market-sub)}.side-card dd{margin:0;font-weight:650}.side-card .hash{display:block}.hash dd{font-family:monospace;word-break:break-all;background:var(--wao-bg-sub);padding:8px;margin-top:6px;border-radius:7px;font-size:10px}.tested{display:grid;gap:8px}.tested span{font-size:12px}.tested i{color:var(--wao-success);margin-right:6px}.files{list-style:none;margin:0;padding:0;max-height:260px;overflow:auto}.files li{display:flex;align-items:center;gap:7px;padding:7px 0;border-bottom:1px solid var(--wao-divider);font-size:11px}.files span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.files small{margin-left:auto;color:var(--market-sub);white-space:nowrap}.install-section{margin-top:42px}.install-section>div:first-child p{color:var(--market-sub);font-size:13px}.install-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-top:15px}.install-grid article{padding:19px}.install-grid h3{margin:0 0 12px}.install-grid code{display:block;background:var(--wao-bg-sub);border-radius:8px;padding:10px;font-size:11px;word-break:break-all}.install-grid p{color:var(--market-sub);font-size:11px;line-height:1.6}.versions{margin-top:22px;padding:22px}.versions a{display:grid;grid-template-columns:100px 1fr auto;gap:14px;padding:12px 0;border-top:1px solid var(--market-line);text-decoration:none;color:inherit;font-size:12px}.versions span{color:var(--market-sub)}
@media(max-width:880px){.detail-hero{grid-template-columns:auto 1fr}.detail-actions{grid-column:1/-1;display:flex;flex-wrap:wrap}.detail-grid{grid-template-columns:1fr}.install-grid{grid-template-columns:1fr}}@media(max-width:560px){.detail-hero{grid-template-columns:1fr;padding:19px}.detail-icon{width:56px;height:56px}.detail-title h1{font-size:23px}.detail-actions .market-button{flex:1}.content-card{padding:13px}.content-card pre{padding:14px}.versions a{grid-template-columns:70px 1fr}.versions code{display:none}}
</style>
