<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createSkillDraft, fetchMySkills, fetchPublisher, fetchSkillCategories, inspectSkillPackage, savePublisher, submitSkill, type DraftMetadata, type MySkill, type SkillCategory, type SkillPackageInspection, type SkillPublisher } from '@/api/skills'
import { useSkillMarketRoutes } from '@/composables/skillMarketRoutes'

const route = useRoute(), router = useRouter()
const marketRoutes = useSkillMarketRoutes()
const categories = ref<SkillCategory[]>([]), publisher = ref<SkillPublisher | null>(null), result = ref<MySkill | null>(null)
const loading = ref(true), savingPublisher = ref(false), inspecting = ref(false), uploading = ref(false), submitting = ref(false), progress = ref(0), error = ref('')
const archive = ref<File | null>(null), folderFiles = ref<File[]>([]), inspection = ref<SkillPackageInspection | null>(null)
let inspectionSequence = 0
const publisherForm = reactive({ handle: '', displayName: '' })
const form = reactive<DraftMetadata>({ skillId: undefined, slug: '', displayName: '', summary: '', category: '', tags: [], version: '1.0.0', changelog: '', license: 'MIT', testedPlatforms: ['codex'], rightsConfirmed: false, scanOverrideConfirmed: false })
const tagsText = ref(''), storageKey = 'wao-skill-publish-draft-v1'
const draftVersion = computed(() => result.value?.versions.find(v => v.state === 'DRAFT'))
const selectedName = computed(() => archive.value?.name || (folderFiles.value.length ? `${folderFiles.value.length} 个文件` : '尚未选择'))
const canContinue = computed(() => !!inspection.value && (!inspection.value.findings.length || form.scanOverrideConfirmed))

watch([() => ({ ...form, tags: tagsText.value }), tagsText], () => {
  if (!loading.value) localStorage.setItem(storageKey, JSON.stringify({ ...form, tags: tagsText.value }))
}, { deep: true })

function message(value: unknown) { return value instanceof Error ? value.message : '操作失败，请稍后重试' }
async function inspectSelectedPackage() {
  const sequence = ++inspectionSequence
  error.value = ''; inspection.value = null; inspecting.value = true
  try {
    const value = await inspectSkillPackage(archive.value, folderFiles.value)
    if (sequence !== inspectionSequence) return
    inspection.value = value; form.scanOverrideConfirmed = false
    form.displayName = value.name.slice(0, 80); form.summary = value.description.slice(0, 500)
    if (!form.skillId) form.slug = value.suggestedSlug
  } catch (e) {
    if (sequence === inspectionSequence) error.value = message(e)
  } finally {
    if (sequence === inspectionSequence) inspecting.value = false
  }
}
function passInspection() { form.scanOverrideConfirmed = true }
async function chooseArchive(event: Event) {
  const input = event.target as HTMLInputElement
  archive.value = input.files?.[0] || null; folderFiles.value = []; input.value = ''
  if (archive.value) await inspectSelectedPackage()
}
async function chooseFolder(event: Event) {
  const input = event.target as HTMLInputElement
  folderFiles.value = [...(input.files || [])]; archive.value = null; input.value = ''
  if (folderFiles.value.length) await inspectSelectedPackage()
}
async function persistPublisher() {
  error.value = ''; savingPublisher.value = true
  try { publisher.value = await savePublisher(publisherForm) } catch (e) { error.value = message(e) } finally { savingPublisher.value = false }
}
async function upload() {
  error.value = ''
  if (!archive.value && !folderFiles.value.length) { error.value = '请先选择 ZIP 或 Skill 文件夹'; return }
  form.tags = tagsText.value.split(/[,，]/).map(v => v.trim()).filter(Boolean)
  uploading.value = true; progress.value = 0
  try { result.value = await createSkillDraft(form, archive.value, folderFiles.value, value => { progress.value = value }); localStorage.removeItem(storageKey) }
  catch (e) { error.value = message(e) } finally { uploading.value = false }
}
async function submit() {
  if (!result.value || !draftVersion.value) return
  submitting.value = true; error.value = ''
  try { await submitSkill(result.value.id, draftVersion.value.id); await router.push(marketRoutes.path('/manage')) }
  catch (e) { error.value = message(e) } finally { submitting.value = false }
}

onMounted(async () => {
  try {
    categories.value = await fetchSkillCategories(); publisher.value = await fetchPublisher()
    if (publisher.value) Object.assign(publisherForm, publisher.value)
    const saved = localStorage.getItem(storageKey)
    if (saved) { const value = JSON.parse(saved); Object.assign(form, value); tagsText.value = value.tags || '' }
    if (route.query.skillId) {
      const existing = (await fetchMySkills()).find(item => item.id === route.query.skillId)
      if (existing) Object.assign(form, { skillId: existing.id, slug: existing.slug, displayName: existing.displayName, summary: existing.summary, category: existing.category })
    }
  } catch (e) { error.value = message(e) } finally { loading.value = false }
})
</script>

<template>
  <header class="page-head"><div><h1 class="market-title">发布 Skill</h1><p class="market-lead">上传后只解析与安全扫描，不会执行包内任何脚本。</p></div><RouterLink class="market-button" :to="marketRoutes.path('/manage')">查看草稿</RouterLink></header>
  <div v-if="loading" class="market-card market-empty">正在加载发布工作台…</div>
  <template v-else>
    <div v-if="error" class="market-error">{{ error }}</div>
    <section v-if="!publisher" class="market-card publisher-card">
      <div><h2>先创建公开发布者身份</h2><p class="market-muted">handle 会出现在分享链接中，创建后不可修改，且不会暴露 WAO 成员 ID。</p></div>
      <div class="form-grid"><label class="market-label">handle<input v-model.trim="publisherForm.handle" class="market-input" placeholder="lowercase-name"></label><label class="market-label">公开昵称<input v-model.trim="publisherForm.displayName" class="market-input" placeholder="例如：娃有多大胆"></label></div>
      <button class="market-button primary" :disabled="savingPublisher" @click="persistPublisher">{{ savingPublisher ? '创建中…' : '创建发布者身份' }}</button>
    </section>
    <section v-else-if="!result" class="market-card publish-card">
      <div class="upload-zone first-step"><strong>第一步：选择 Skill 文件夹</strong><span>{{ selectedName }}</span><div><label class="market-button primary">选择文件夹<input hidden type="file" webkitdirectory multiple @change="chooseFolder"></label><label class="market-button">也可选择 ZIP<input hidden type="file" accept=".zip,application/zip" @change="chooseArchive"></label></div><small>选择后立即只读解析与安全扫描，不执行脚本、不创建草稿。</small></div>
      <div v-if="inspecting" class="inspection-state">正在解析 SKILL.md 并检查敏感信息…</div>
      <div v-else-if="inspection?.findings.length && !form.scanOverrideConfirmed" class="market-error inspection-result"><b>安全扫描拦截了以下位置：</b><div v-for="item in inspection.findings" :key="item">{{ item }}</div><p>文件数据仍保留在当前页面。确认这些内容可以公开时，可直接 Pass 继续；无需重新选择文件夹。</p><button class="market-button warning-pass" type="button" @click="passInspection">Pass，继续发布</button></div>
      <div v-else-if="inspection?.findings.length" class="scan-overridden"><b>已 Pass 安全扫描警告</b><span>以下拦截点会随版本保留并交给管理员复核，无需重新上传。</span><div v-for="item in inspection.findings" :key="item">{{ item }}</div></div>
      <div v-else-if="inspection" class="scan-passed"><b>安全检查通过</b><span>已解析 {{ inspection.fileCount }} 个文件，并从 SKILL.md 自动预填以下信息。</span></div>
      <div v-if="canContinue" class="form-grid">
        <label class="market-label">Skill 标题<input v-model.trim="form.displayName" class="market-input" maxlength="80"></label>
        <label class="market-label">slug<input v-model.trim="form.slug" class="market-input" :disabled="!!form.skillId" placeholder="meeting-summary"></label>
        <label class="market-label wide">一句话简介<textarea v-model.trim="form.summary" class="market-textarea" maxlength="500"></textarea></label>
        <label class="market-label">分类<select v-model="form.category" class="market-select"><option value="">请选择</option><option v-for="item in categories" :key="item.key" :value="item.key">{{ item.name }}</option></select></label>
        <label class="market-label">标签（逗号分隔）<input v-model="tagsText" class="market-input" placeholder="会议,总结"></label>
        <label class="market-label">语义版本<input v-model.trim="form.version" class="market-input" placeholder="1.0.0"></label>
        <label class="market-label">许可证<select v-model="form.license" class="market-select"><option>MIT</option><option>Apache-2.0</option><option>GPL-3.0</option><option>CC-BY-4.0</option><option value="ALL-RIGHTS-RESERVED">保留所有权利</option></select></label>
        <label class="market-label wide">版本说明<textarea v-model.trim="form.changelog" class="market-textarea" placeholder="这个版本改了什么"></textarea></label>
      </div>
      <template v-if="canContinue">
        <fieldset><legend>作者已测试平台</legend><label v-for="item in [{k:'agent-skills',n:'Agent Skills'},{k:'codex',n:'Codex'},{k:'claude-code',n:'Claude Code'},{k:'openclaw',n:'OpenClaw'}]" :key="item.k"><input v-model="form.testedPlatforms" type="checkbox" :value="item.k">{{ item.n }}</label></fieldset>
        <label class="rights"><input v-model="form.rightsConfirmed" type="checkbox">我确认拥有该内容的公开发布权，并同意按所选许可证分享。</label>
        <div class="actions"><button class="market-button primary" :disabled="uploading" @click="upload">{{ uploading ? `上传中 ${progress}%` : '确认信息并创建草稿' }}</button><span class="market-muted">创建前会再次安全检查；表单已自动保存在本机</span></div>
      </template>
    </section>
    <section v-else class="market-card preview-card"><div class="preview-head"><div><span class="status">DRAFT</span><h2>{{ result.displayName }} <small>v{{ draftVersion?.version }}</small></h2><p>{{ result.summary }}</p></div><b>{{ draftVersion?.fileCount }} 个文件</b></div><div v-if="draftVersion?.findings.length" class="scan-overridden"><b>{{ draftVersion.scanOverrideConfirmed ? '已由作者 Pass，等待管理员复核' : '存在未确认的安全拦截点' }}</b><div v-for="item in draftVersion.findings" :key="item">{{ item }}</div></div><div class="preview-grid"><div><h3>SKILL.md</h3><pre>{{ draftVersion?.skillMd }}</pre></div><div><h3>文件清单</h3><ul><li v-for="file in draftVersion?.files" :key="file.path"><span>{{ file.path }}</span><small>{{ file.size }} B</small></li></ul></div></div><div class="hash">SHA-256 <code>{{ draftVersion?.sha256 }}</code></div><div class="actions"><button class="market-button primary" :disabled="submitting || (!!draftVersion?.findings.length && !draftVersion.scanOverrideConfirmed)" @click="submit">{{ submitting ? '提交中…' : '提交管理员审核' }}</button><RouterLink class="market-button" :to="marketRoutes.path('/manage')">保留草稿</RouterLink></div></section>
  </template>
</template>

<style scoped>
.page-head{display:flex;align-items:flex-end;justify-content:space-between;margin-bottom:26px}.publisher-card,.publish-card,.preview-card{padding:26px;display:grid;gap:22px}.publisher-card h2{margin:0}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:18px}.wide{grid-column:1/-1}fieldset{border:1px solid var(--market-line);border-radius:12px;padding:14px;display:flex;gap:20px;flex-wrap:wrap}fieldset legend{font-size:13px;font-weight:750;padding:0 6px}fieldset label,.rights{display:flex;gap:7px;align-items:flex-start;font-size:13px}.upload-zone{border:1.5px dashed var(--wao-primary-line);background:var(--wao-primary-tint);border-radius:14px;padding:25px;display:grid;place-items:center;gap:10px;text-align:center}.upload-zone span,.upload-zone small{color:var(--market-sub)}.upload-zone div{display:flex;gap:8px;flex-wrap:wrap;justify-content:center}.first-step{padding:34px}.inspection-state,.scan-passed,.scan-overridden{border-radius:10px;padding:13px 15px}.inspection-state{background:var(--wao-bg-sub);color:var(--market-sub)}.scan-passed,.scan-overridden{display:flex;gap:10px;align-items:flex-start;background:var(--wao-primary-tint);color:var(--wao-primary-text)}.scan-overridden{flex-direction:column;background:var(--wao-warning-bg);color:var(--wao-warning-strong)}.scan-passed span,.scan-overridden span{color:var(--market-sub)}.inspection-result{margin:0}.inspection-result p{margin:10px 0;line-height:1.6}.warning-pass{border-color:currentColor;background:transparent;color:inherit}.actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.preview-head{display:flex;justify-content:space-between}.preview-head h2{margin:8px 0}.preview-head p{color:var(--market-sub)}.status{font-size:10px;background:var(--wao-primary-soft);color:var(--wao-primary-text);padding:5px 8px;border-radius:6px}.preview-grid{display:grid;grid-template-columns:1fr 1fr;gap:15px;min-width:0}.preview-grid>div{min-width:0}.preview-grid pre,.preview-grid ul{height:340px;overflow:auto;background:#151a27;color:#e8ebf5;border-radius:12px;padding:16px;font-size:12px;line-height:1.65;white-space:pre-wrap}.preview-grid ul{list-style:none;margin:0}.preview-grid li{display:flex;justify-content:space-between;gap:12px;border-bottom:1px solid #2a3040;padding:6px 0}.hash{overflow-wrap:anywhere;background:var(--wao-bg-sub);padding:12px;border-radius:9px;font-size:12px}.hash code{margin-left:8px}@media(max-width:700px){.page-head{align-items:flex-start;gap:18px}.form-grid,.preview-grid{grid-template-columns:1fr}.wide{grid-column:auto}.publisher-card,.publish-card,.preview-card{padding:18px}.scan-passed{align-items:flex-start;flex-direction:column}}
</style>
