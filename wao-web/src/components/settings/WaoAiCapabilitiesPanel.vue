<script setup lang="ts">
import { useSaveBeforeRefresh } from '@/composables/saveBeforeRefresh'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { fetchAvailableAiSkills, fetchWaoApplicationSkills, saveWaoApplicationSkills } from '@/api/ai'
import { toast } from '@/composables/toast'
import { useSessionStore } from '@/stores/session'
import type { AiSkillCapability } from '@/types'

const session = useSessionStore()
const loading = ref(true)
const loadError = ref('')
const saving = ref(false)
const saveError = ref('')
const available = ref<AiSkillCapability[]>([])
const selected = ref<string[]>([])
const saved = ref<string[]>([])
const canManage = computed(() => ['管理员', '超级管理员'].includes(session.currentUser.role))
const dirty = computed(() => selected.value.join('|') !== saved.value.join('|'))

function toggle(skill: AiSkillCapability, checked: boolean) {
  if (!checked) {
    selected.value = selected.value.filter((versionId) => versionId !== skill.versionId)
    return
  }
  const sameSkill = new Set(available.value.filter((item) => item.skillId === skill.skillId).map((item) => item.versionId))
  selected.value = selected.value.filter((versionId) => !sameSkill.has(versionId))
  selected.value.push(skill.versionId)
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [market, enabled] = await Promise.all([fetchAvailableAiSkills(), fetchWaoApplicationSkills()])
    const marketIds = new Set(market.map((item) => item.versionId))
    available.value = [...market, ...enabled.filter((item) => !marketIds.has(item.versionId))]
    selected.value = enabled.map((item) => item.versionId)
    saved.value = [...selected.value]
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : 'WAO AI 能力加载失败'
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!canManage.value || saving.value || !dirty.value) return
  saving.value = true
  saveError.value = ''
  try {
    const result = await saveWaoApplicationSkills(selected.value)
    selected.value = result.map((item) => item.versionId)
    saved.value = [...selected.value]
    toast('WAO 默认 Skill 已保存，只影响之后新建的运行', 'ri-check-line')
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : '保存失败，请重试'
  } finally {
    saving.value = false
  }
}

function confirmLeave() {
  return !dirty.value || window.confirm('WAO 默认 Skill 还有未保存的修改，确定离开吗？')
}

function beforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

defineExpose({ confirmLeave })
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
useSaveBeforeRefresh(async () => { if (dirty.value) await save() })
</script>

<template>
  <section class="capability-panel">
    <header>
      <div>
        <h2>WAO AI 能力</h2>
        <p>这里启用的审核 Skill 会自动进入之后新建的 WAO Codex Run；项目设置仍可覆盖同一 Skill 的固定版本。</p>
      </div>
      <a href="/wao/skills">打开 Skill 市场</a>
    </header>

    <div v-if="loading" class="state"><i class="ri-loader-4-line spin"></i>正在读取真实能力配置…</div>
    <div v-else-if="loadError" class="state error" role="alert">
      <i class="ri-error-warning-line"></i><span>{{ loadError }}</span><button @click="load">重试</button>
    </div>
    <template v-else>
      <div class="scope-note">
        <i class="ri-shield-check-line"></i>
        <span><b>WAO 应用默认</b><small>固定审核版本、运行级隔离装配；设置变化不改写历史 Run。</small></span>
        <em>{{ selected.length }} / 10</em>
      </div>
      <div v-if="!available.length" class="empty">
        <i class="ri-sparkling-2-line"></i><b>市场暂无可启用的 Codex Skill</b><span>先发布并通过审核后，真实版本才会出现在这里。</span>
      </div>
      <div v-else class="skill-list">
        <label v-for="skill in available" :key="skill.versionId" class="skill-option">
          <input type="checkbox" :checked="selected.includes(skill.versionId)" :disabled="!canManage || saving || (selected.length >= 10 && !selected.includes(skill.versionId) && !available.some((item) => item.skillId === skill.skillId && selected.includes(item.versionId)))" @change="toggle(skill, ($event.target as HTMLInputElement).checked)" />
          <span><b>{{ skill.displayName }} <small>v{{ skill.version }}</small></b><em>{{ skill.summary }}</em></span>
          <code>{{ skill.sha256.slice(0, 12) }}</code>
        </label>
      </div>
      <p v-if="!canManage" class="permission-note">只有管理员可以修改 WAO 默认能力；当前配置仍可核对。</p>
      <p v-if="saveError" class="save-error" role="alert">{{ saveError }} <button @click="save">重试</button></p>
      <footer>
        <span>{{ dirty ? '有未保存的修改' : '当前选择已保存' }}</span>
        <button class="save" :disabled="!canManage || !dirty || saving" @click="save">
          <i :class="saving ? 'ri-loader-4-line spin' : 'ri-save-line'"></i>{{ saving ? '保存中…' : '保存默认能力' }}
        </button>
      </footer>
    </template>

    <aside>
      <i class="ri-node-tree"></i>
      <div><b>MCP 运行接入尚未开放</b><p>当前没有通用 MCP 注册表、短期运行凭据和跨 KAI 适配器，因此不提供无效开关；个人记忆 MCP 继续保持成员隔离。</p></div>
    </aside>
  </section>
</template>

<style scoped>
.capability-panel{max-width:820px}.capability-panel>header{display:flex;align-items:flex-start;justify-content:space-between;gap:20px}.capability-panel h2{margin:0;font-size:18px}.capability-panel header p{max-width:650px;margin:6px 0 0;color:var(--wao-text-weak);font-size:12px;line-height:1.7}.capability-panel header a{flex:none;color:var(--wao-primary-text);font-size:12px;text-decoration:none}.state,.empty{min-height:220px;margin-top:18px;border:1px solid var(--wao-line);border-radius:12px;background:var(--wao-bg-card);display:flex;align-items:center;justify-content:center;gap:8px;color:var(--wao-text-weak);font-size:12px}.state button,.save-error button{border:0;background:none;color:var(--wao-primary-text);cursor:pointer}.state.error,.save-error{color:var(--wao-danger-strong)}.scope-note{display:flex;align-items:center;gap:10px;margin-top:18px;padding:12px 13px;border:1px solid var(--wao-success-line);border-radius:11px;background:var(--wao-success-bg)}.scope-note>i{font-size:22px;color:var(--wao-success-strong)}.scope-note span{flex:1;display:grid;gap:2px}.scope-note b{font-size:12px}.scope-note small{color:var(--wao-text-weak);font-size:10px}.scope-note em{font-style:normal;font-size:11px}.empty{flex-direction:column}.empty>i{font-size:26px}.empty b{color:var(--wao-text)}.skill-list{display:grid;gap:8px;margin-top:12px}.skill-option{display:grid;grid-template-columns:18px minmax(0,1fr) auto;gap:9px;align-items:start;padding:11px 12px;border:1px solid var(--wao-line-control);border-radius:10px;background:var(--wao-bg-card);cursor:pointer}.skill-option input{margin-top:3px;accent-color:var(--wao-primary)}.skill-option>span{display:grid;gap:3px;min-width:0}.skill-option b{font-size:12px}.skill-option b small{color:var(--wao-text-weak);font-weight:500}.skill-option em{overflow:hidden;color:var(--wao-text-weak);font-size:11px;font-style:normal;text-overflow:ellipsis;white-space:nowrap}.skill-option code{color:var(--wao-text-weak);font-size:10px}.permission-note,.save-error{margin:10px 0 0;padding:9px 10px;border-radius:8px;background:var(--wao-warning-bg);font-size:11px}.save-error{background:var(--wao-danger-bg)}.capability-panel>footer{display:flex;align-items:center;justify-content:space-between;margin-top:13px}.capability-panel>footer span{color:var(--wao-text-weak);font-size:11px}.save{min-height:36px;padding:0 13px;border:0;border-radius:8px;background:var(--wao-primary);color:#fff}.save:disabled{opacity:.5}.capability-panel aside{display:flex;gap:10px;margin-top:20px;padding:12px 13px;border:1px dashed var(--wao-line-strong);border-radius:10px;background:var(--wao-bg-sub)}.capability-panel aside>i{font-size:20px;color:var(--wao-text-weak)}.capability-panel aside b{font-size:11px}.capability-panel aside p{margin:3px 0 0;color:var(--wao-text-weak);font-size:10px;line-height:1.6}.spin{display:inline-block;animation:spin .8s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}@media(max-width:700px){.capability-panel>header{display:grid}.skill-option{grid-template-columns:18px minmax(0,1fr)}.skill-option code{grid-column:2}.capability-panel>footer{align-items:stretch;flex-direction:column;gap:8px}}
</style>
