<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { deleteSkillDraft, fetchMySkills, fetchPublisher, savePublisher, setSkillListed, submitSkill, type MySkill, type SkillPublisher } from '@/api/skills'
import { useSkillMarketRoutes } from '@/composables/skillMarketRoutes'
import { useSessionStore } from '@/stores/session'

const marketRoutes=useSkillMarketRoutes()
const session = useSessionStore(), notice = ref(''), draftError = ref('')
const draftKey = computed(() => `wao:capabilities:publisher-name:${session.userId}`)
const props = defineProps<{ selectedId?: string }>()
const emit = defineEmits<{ changed: [] }>()
const publisher=ref<SkillPublisher|null>(null),items=ref<MySkill[]>([]),loading=ref(true),busy=ref(''),error=ref('')
const form=reactive({handle:'',displayName:''})
const displayItems = computed(() => props.selectedId ? items.value.filter(item => item.id === props.selectedId) : items.value)
const stateName:Record<string,string>={DRAFT:'草稿',PENDING_REVIEW:'待审核',PUBLISHED:'已发布',REJECTED:'已驳回',OFFLINE:'已下架'}
function msg(e:unknown){return e instanceof Error?e.message:'操作失败'}
async function load(){loading.value=true;error.value='';try{publisher.value=await fetchPublisher();if(publisher.value){Object.assign(form,publisher.value);try{const saved=JSON.parse(localStorage.getItem(draftKey.value)||'null');if(saved?.handle===publisher.value.handle && typeof saved.displayName==='string')form.displayName=saved.displayName}catch{draftError.value='本机昵称草稿无法读取，原缓存未删除。'}}items.value=await fetchMySkills()}catch(e){error.value=msg(e)}finally{loading.value=false}}
async function updatePublisher(){busy.value='publisher';error.value='';notice.value='';try{publisher.value=await savePublisher(form);notice.value='昵称已保存';try{localStorage.removeItem(draftKey.value)}catch{draftError.value='昵称已保存，本机草稿清理失败。'}}catch(e){error.value=msg(e)}finally{busy.value=''}}
watch(() => form.displayName, value => {if(loading.value||!publisher.value)return;notice.value='';try{localStorage.setItem(draftKey.value,JSON.stringify({handle:publisher.value.handle,displayName:value}));draftError.value=''}catch{draftError.value='昵称草稿未能保存到本机，请保持页面打开并重试。'}},{flush:'sync'})
async function act(key:string,work:()=>Promise<unknown>){busy.value=key;error.value='';try{await work();await load();emit('changed')}catch(e){error.value=msg(e)}finally{busy.value=''}}
onMounted(load)
</script>
<template>
  <header class="page-head"><div><h1 class="market-title">我的发布</h1><p class="market-lead">已提交版本不可覆盖；新版本审核期间，旧版本仍可下载。</p></div><RouterLink class="market-button primary" :to="marketRoutes.path('/publish')">发布 Skill</RouterLink></header>
  <div v-if="error" class="market-error">{{ error }} <button @click="load">重试</button></div>
  <div v-if="draftError" class="market-error" role="alert">{{ draftError }}</div><p v-if="notice" role="status">{{ notice }}</p>
  <div v-if="loading" class="market-card market-empty">正在读取发布记录…</div>
  <template v-else>
    <section v-if="publisher" class="market-card identity"><div><b>@{{ publisher.handle }}</b><p class="market-muted">公开 handle 已锁定，可修改昵称。</p></div><label class="market-label">公开昵称<input v-model.trim="form.displayName" class="market-input"></label><button class="market-button" :disabled="busy==='publisher'" @click="updatePublisher">保存昵称</button></section>
    <section v-if="!items.length" class="market-card market-empty"><h2>还没有发布记录</h2><p>从 ZIP 或文件夹创建你的第一个 Skill。</p></section>
    <div v-else class="skill-list"><article v-for="skill in displayItems" :key="skill.id" class="market-card skill-item"><header><div><span class="status">{{ stateName[skill.status]||skill.status }}</span><h2>{{ skill.displayName }}</h2><p>{{ skill.summary }}</p></div><div class="stats">{{ skill.downloads }} 下载 · {{ skill.favorites }} 收藏</div></header><div class="versions"><div v-for="version in skill.versions" :key="version.id" class="version"><div><b>v{{ version.version }}</b><span :class="`state state-${version.state.toLowerCase()}`">{{ stateName[version.state]||version.state }}</span><small>{{ version.license }} · {{ version.fileCount }} 个文件</small></div><p v-if="version.reviewNote" class="note">审核意见：{{ version.reviewNote }}</p><div class="row-actions"><button v-if="version.state==='DRAFT'" class="market-button primary" :disabled="!!busy" @click="act(version.id,()=>submitSkill(skill.id,version.id))">提交审核</button><button v-if="version.state==='DRAFT'" class="market-button danger" :disabled="!!busy" @click="act(version.id,()=>deleteSkillDraft(skill.id,version.id))">删除草稿</button></div></div></div><footer><RouterLink class="market-button" :to="{path:marketRoutes.path('/publish'),query:{skillId:skill.id}}">上传新版本</RouterLink><button v-if="skill.status==='PUBLISHED'" class="market-button" :disabled="!!busy" @click="act(skill.id,()=>setSkillListed(skill.id,false))">下架</button><button v-if="skill.status==='OFFLINE'" class="market-button primary" :disabled="!!busy" @click="act(skill.id,()=>setSkillListed(skill.id,true))">重新上架</button><RouterLink v-if="skill.status==='PUBLISHED' && publisher" class="market-button" :to="marketRoutes.path(`/@${publisher.handle}/${skill.slug}`)">查看公开页</RouterLink></footer></article></div>
  </template>
</template>
<style scoped>
.page-head{display:flex;justify-content:space-between;align-items:flex-end;margin-bottom:26px}.market-error{margin-bottom:16px}.market-error button{border:0;background:none;text-decoration:underline;color:inherit}.identity{padding:18px 22px;display:grid;grid-template-columns:1fr minmax(240px,400px) auto;gap:16px;align-items:end;margin-bottom:18px}.identity p{margin:6px 0 0}.skill-list{display:grid;gap:16px}.skill-item{padding:24px}.skill-item>header{display:flex;justify-content:space-between;gap:20px}.skill-item h2{margin:9px 0 5px}.skill-item header p{color:var(--market-sub);margin:0}.status,.state{display:inline-block;font-size:10px;border-radius:6px;padding:4px 7px;background:var(--wao-primary-soft);color:var(--wao-primary-text)}.stats{color:var(--market-sub);font-size:12px}.versions{margin:20px 0;display:grid;gap:8px}.version{border:1px solid var(--market-line);border-radius:12px;padding:13px 15px;display:flex;align-items:center;justify-content:space-between;gap:15px}.version>div:first-child{display:flex;align-items:center;gap:9px;flex-wrap:wrap}.version small{color:var(--market-sub)}.state-rejected{background:var(--wao-danger-bg);color:var(--wao-danger-strong)}.state-published{background:var(--wao-success-bg);color:var(--wao-success-strong)}.note{color:var(--wao-danger-strong);font-size:12px;margin:0}.row-actions,.skill-item footer{display:flex;gap:8px;flex-wrap:wrap}.market-button{height:36px;font-size:12px}@media(max-width:760px){.page-head{align-items:flex-start;gap:16px}.identity{grid-template-columns:1fr}.skill-item>header,.version{align-items:flex-start;flex-direction:column}}
</style>
