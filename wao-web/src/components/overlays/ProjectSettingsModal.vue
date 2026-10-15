<script setup lang="ts">
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import ProjectSettingsLayout, { type ProjectSettingsTab } from '@/components/ui/ProjectSettingsLayout.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoField from '@/components/ui/WaoField.vue'
import { readProjectSettingsDraft, writeProjectSettingsDraft, type ProjectSettingsValues } from '@/utils/projectSettingsDraft'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import { fetchMembers, apiGetProject } from '@/api/board'
import { useDataStore } from '@/stores/data'
import { toast } from '@/composables/toast'
import {
  createAiPairCode,
  fetchAvailableAiSkills,
  fetchWaoApplicationSkills,
  fetchAiProjectConfig,
  fetchAiProjectSkills,
  fetchAiRunners,
  saveAiProjectConfig,
  saveAiProjectSkills,
} from '@/api/ai'
import { AI_ENGINES, engineLabel } from '@/composables/aiEngines'
import { AI_RUN_ENABLED } from '@/config/features'
import type { AiEngine, AiRunner, AiSkillCapability, Project, Member } from '@/types'

const props = defineProps<{ project: Project }>()
const emit = defineEmits<{ close: []; deleted: [] }>()

const data = useDataStore()
const session = useSessionStore()
const draftMember = session.userId
const projectId = props.project.id
const activeTab = ref<ProjectSettingsTab>('basic')
const iconPicker = ref<HTMLDetailsElement>()
const nameError = ref('')
const draftWarning = ref('')
const draftRestored = ref(false)
const draftConflict = ref(false)
const canManageMembers = computed(() => session.userId === props.project.owner)
const originalMembers = ref([...props.project.members])
const selectedMembers = ref([...new Set([props.project.owner, ...originalMembers.value])])
const memberConflict = ref(false)
const memberReview = ref('')
const memberDirectory = ref<Member[]>([])
const membersLoading = ref(false)
const membersError = ref('')
const memberToAdd = ref('')
const membersChanged = computed(() => JSON.stringify([...selectedMembers.value].sort()) !== JSON.stringify([...new Set([props.project.owner, ...originalMembers.value])].sort()))
const availableMembers = computed(() => memberDirectory.value.filter(member => !selectedMembers.value.includes(member.id)))
async function loadMembers() {
  membersLoading.value = true
  membersError.value = ''
  try { memberDirectory.value = await fetchMembers() }
  catch { membersError.value = '成员列表读取失败，请重试；现有选择已保留。' }
  finally { membersLoading.value = false }
}
function addMember() {
  if (!canManageMembers.value || !availableMembers.value.some(member => member.id === memberToAdd.value)) return
  selectedMembers.value.push(memberToAdd.value)
  memberToAdd.value = ''
}
function removeMember(id: string) {
  if (!canManageMembers.value || id === props.project.owner) return
  selectedMembers.value = selectedMembers.value.filter(member => member !== id)
}
function memberName(id: string) {
  return memberDirectory.value.find(member => member.id === id)?.name ?? session.memberById(id).name ?? id
}
async function refreshMemberSelection() {
  if (saving.value) return
  saving.value = true
  try {
    const latest = await apiGetProject(props.project.id)
    const additions = selectedMembers.value.filter(id => !originalMembers.value.includes(id))
    const removals = originalMembers.value.filter(id => !selectedMembers.value.includes(id))
    selectedMembers.value = [...new Set([latest.owner, ...latest.members.filter(id => !removals.includes(id)), ...additions])]
    originalMembers.value = [...latest.members]
    memberConflict.value = false
    saveError.value = ''
    memberReview.value = `最新已保存成员：${latest.members.map(memberName).join('、')}。已保留本次添加和移除，请确认列表后保存。`
    rememberDraft()
  } catch (error) { saveError.value = error instanceof Error ? error.message : '读取最新成员失败，选择已保留，请重试。' }
  finally { saving.value = false }
}

const name = ref(props.project.name)
const area = ref(props.project.area)
const goal = ref(
  props.project.goal === '尚未填写目标——打开项目后可补充。' ? '' : props.project.goal,
)
const icon = ref(props.project.icon || 'ri-stack-line')
const due = ref(props.project.due || '')
const confirming = ref(false)
const aiLoading = ref(true)
const aiEnabled = ref(false)
const repoUrl = ref('')
const defaultBranch = ref('main')
const workspaceDir = ref('')
const defaultEngine = ref<AiEngine>('codex')
const preferredRunnerId = ref('')
const maxParallelRuns = ref(2)
const failureRetryCount = ref(2)
const executionPrompt = ref('')
const runners = ref<AiRunner[]>([])
const pairCode = ref('')
const pairExpiresAt = ref('')
const availableSkills = ref<AiSkillCapability[]>([])
const waoApplicationSkills = ref<AiSkillCapability[]>([])
const selectedSkillVersions = ref<string[]>([])

function toggleSkill(skill: AiSkillCapability, checked: boolean) {
  if (!checked) {
    selectedSkillVersions.value = selectedSkillVersions.value.filter(
      (versionId) => versionId !== skill.versionId,
    )
    return
  }
  const sameSkillVersions = new Set(
    availableSkills.value
      .filter((item) => item.skillId === skill.skillId)
      .map((item) => item.versionId),
  )
  selectedSkillVersions.value = selectedSkillVersions.value.filter(
    (versionId) => !sameSkillVersions.has(versionId),
  )
  selectedSkillVersions.value.push(skill.versionId)
}

/** 可选项目图标（remixicon）。 */
const ICONS = [
  'ri-stack-line',
  'ri-rocket-line',
  'ri-plant-line',
  'ri-home-heart-line',
  'ri-briefcase-line',
  'ri-book-2-line',
  'ri-heart-pulse-line',
  'ri-palette-line',
  'ri-code-box-line',
  'ri-camera-line',
  'ri-flight-takeoff-line',
  'ri-cup-line',
  'ri-money-cny-circle-line',
  'ri-run-line',
  'ri-music-2-line',
  'ri-lightbulb-line',
]

const saving = ref(false),
  saveError = ref('')
const values = (): ProjectSettingsValues => ({ name: name.value, area: area.value, goal: goal.value, icon: icon.value, due: due.value, members: [...selectedMembers.value] })
const original = ref<ProjectSettingsValues>(values())
const fields = ['name', 'area', 'goal', 'icon', 'due'] as const
const dirty = computed(() => fields.some(field => values()[field] !== original.value[field]) || (canManageMembers.value && membersChanged.value) || (AI_RUN_ENABLED && aiLoaded.value && aiSnapshot() !== aiOriginal.value))
let draftRevision = ''
let saved = false
const restored = draftMember ? readProjectSettingsDraft(draftMember, projectId) : null
if (restored) {
  draftConflict.value = fields.some(field => restored.value[field] !== restored.base[field] && original.value[field] !== restored.base[field] && original.value[field] !== restored.value[field])
  for (const field of fields) if (restored.value[field] === restored.base[field]) { restored.value[field] = original.value[field]; restored.base[field] = original.value[field] }
  name.value = restored.value.name; area.value = restored.value.area; goal.value = restored.value.goal; icon.value = restored.value.icon; due.value = restored.value.due
  original.value = restored.base
  if (canManageMembers.value) { originalMembers.value = [...restored.base.members]; selectedMembers.value = [...new Set([props.project.owner, ...restored.value.members])] }
  draftRevision = restored.revision
  draftRestored.value = true
}
function rememberDraft() {
  if (!draftMember || saved || session.userId !== draftMember) return
  const previous = draftRevision
  const next = crypto.randomUUID()
  const ok = dirty.value
    ? writeProjectSettingsDraft(draftMember, projectId, { base: { ...original.value, members: [...originalMembers.value] }, value: values(), revision: next })
    : writeProjectSettingsDraft(draftMember, projectId, null, previous)
  if (ok && dirty.value) draftRevision = next
  draftWarning.value = ok ? '' : '本机草稿无法保存，请保持窗口打开并保存到账号。'
}
function clearDraft() {
  saved = true
  if (draftMember && !writeProjectSettingsDraft(draftMember, projectId, null, draftRevision)) draftWarning.value = '账号已保存，但本机草稿清理失败。'
}
function chooseIcon(value: string) {
  icon.value = value
  if (iconPicker.value) { iconPicker.value.open = false; iconPicker.value.querySelector('summary')?.focus() }
}
function dismissIcon(event: PointerEvent) {
  if (iconPicker.value?.open && event.target instanceof Node && !iconPicker.value.contains(event.target)) iconPicker.value.open = false
}
function reviewDraft(keep: boolean) {
  const previous = values()
  const edited = new Set(fields.filter(field => previous[field] !== original.value[field]))
  const latest = props.project
  const current: ProjectSettingsValues = { name: latest.name, area: latest.area, goal: latest.goal === '尚未填写目标——打开项目后可补充。' ? '' : latest.goal, icon: latest.icon || 'ri-stack-line', due: latest.due || '', members: [...latest.members] }
  const next = Object.fromEntries(fields.map(field => [field, keep && edited.has(field) ? previous[field] : current[field]])) as Pick<ProjectSettingsValues, typeof fields[number]>
  name.value = next.name; area.value = next.area; goal.value = next.goal; icon.value = next.icon; due.value = next.due
  if (!keep) { selectedMembers.value = [...new Set([latest.owner, ...latest.members])]; originalMembers.value = [...latest.members] }
  original.value = current
  draftConflict.value = false
  rememberDraft()
}
watch(values, rememberDraft, { deep: true, flush: 'sync' })
watch(() => session.userId, id => { if (id !== draftMember) emit('close') })
watch(activeTab, tab => {
  if (tab !== 'basic' && iconPicker.value) iconPicker.value.open = false
  if (tab !== 'more') confirming.value = false
})
const aiSnapshot = () =>
  JSON.stringify([
    aiEnabled.value,
    repoUrl.value,
    defaultBranch.value,
    workspaceDir.value,
    defaultEngine.value,
    preferredRunnerId.value,
    maxParallelRuns.value,
    failureRetryCount.value,
    executionPrompt.value,
    selectedSkillVersions.value,
  ])
const aiOriginal = ref(''),
  aiLoaded = ref(!AI_RUN_ENABLED)
async function requestClose() {
  if (saving.value) return
  if (iconPicker.value?.open) { iconPicker.value.open = false; return }
  if (confirming.value) {
    confirming.value = false
    return
  }
  if (dirty.value) await save()
  else emit('close')
}

async function save() {
  if (saving.value || memberConflict.value || draftConflict.value || session.userId !== draftMember) return
  if (!name.value.trim()) {
    nameError.value = '请填写项目名称，输入已保留。'
    activeTab.value = 'basic'
    return
  }
  nameError.value = ''
  if (!aiLoaded.value) {
    saveError.value = '请先成功加载配置，避免覆盖已有设置。'
    return
  }
  saving.value = true
  saveError.value = ''
  try {
    const projectSaved = await data.updateProject(props.project.id, {
      ...(name.value !== original.value.name ? { name: name.value.trim() } : {}),
      ...(area.value !== original.value.area ? { area: area.value } : {}),
      ...(goal.value !== original.value.goal ? { goal: goal.value.trim() } : {}),
      ...(icon.value !== original.value.icon ? { icon: icon.value } : {}),
      ...(due.value !== original.value.due ? { due: due.value } : {}),
      ...(canManageMembers.value && membersChanged.value ? { members: [...selectedMembers.value], expectedMembers: [...originalMembers.value] } : {}),
    })
    if (!projectSaved) throw new Error('项目设置保存失败，请重试')
    // AI Run 下线期间不展示、也不读取执行配置，保存时绝不能用空白默认值覆盖服务端已有配置。
    if (!AI_RUN_ENABLED) {
      toast('项目设置已保存', 'ri-check-line')
      clearDraft()
      if (session.userId === draftMember) emit('close')
      return
    }
    await saveAiProjectConfig(props.project.id, {
      enabled: aiEnabled.value,
      repoUrl: repoUrl.value.trim(),
      defaultBranch: defaultBranch.value.trim() || 'main',
      workspaceDir: workspaceDir.value.trim(),
      defaultEngine: defaultEngine.value,
      preferredRunnerId: preferredRunnerId.value || null,
      maxParallelRuns: maxParallelRuns.value,
      failureRetryCount: failureRetryCount.value,
      executionPrompt: executionPrompt.value.trim(),
    })
    await saveAiProjectSkills(props.project.id, selectedSkillVersions.value)
    toast('项目设置与 AI Run 配置已保存', 'ri-check-line')
    clearDraft()
    if (session.userId === draftMember) emit('close')
  } catch (error) {
    memberConflict.value = membersChanged.value && typeof error === 'object' && error !== null && 'status' in error && error.status === 409
    if (memberConflict.value) activeTab.value = 'members'
    saveError.value = error instanceof Error ? error.message : '项目设置保存失败'
    toast(saveError.value, 'ri-error-warning-line')
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (saving.value) return
  saving.value = true
  saveError.value = ''
  try { await data.deleteProject(projectId); clearDraft(); emit('deleted') }
  catch { saveError.value = '删除失败，项目与输入已保留，请重试。' }
  finally { saving.value = false }
}

async function loadAiConfig() {
  aiLoading.value = true
  try {
    const [config, nodes, skills, applicationSkills, enabledSkills] = await Promise.all([
      fetchAiProjectConfig(props.project.id),
      fetchAiRunners(),
      fetchAvailableAiSkills(),
      fetchWaoApplicationSkills(),
      fetchAiProjectSkills(props.project.id),
    ])
    aiEnabled.value = config.enabled
    repoUrl.value = config.repoUrl
    defaultBranch.value = config.defaultBranch
    workspaceDir.value = config.workspaceDir
    defaultEngine.value = config.defaultEngine
    preferredRunnerId.value = config.preferredRunnerId ?? ''
    maxParallelRuns.value = config.maxParallelRuns
    failureRetryCount.value = config.failureRetryCount
    executionPrompt.value = config.executionPrompt ?? ''
    runners.value = nodes
    waoApplicationSkills.value = applicationSkills
    const availableVersionIds = new Set(skills.map((item) => item.versionId))
    availableSkills.value = [
      ...skills,
      ...enabledSkills.filter((item) => !availableVersionIds.has(item.versionId)),
    ]
    selectedSkillVersions.value = enabledSkills.map((item) => item.versionId)
    aiOriginal.value = aiSnapshot()
    aiLoaded.value = true
  } catch (error) {
    toast(error instanceof Error ? error.message : 'AI Run 配置加载失败', 'ri-error-warning-line')
  } finally {
    aiLoading.value = false
  }
}

async function generatePairCode() {
  try {
    const result = await createAiPairCode()
    pairCode.value = result.code
    pairExpiresAt.value = result.expiresAt
    toast('已生成一次性配对码，十分钟内有效', 'ri-computer-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '配对码生成失败', 'ri-error-warning-line')
  }
}

onMounted(() => {
  document.addEventListener('pointerdown', dismissIcon)
  void loadMembers()
  if (AI_RUN_ENABLED) void loadAiConfig()
})
onBeforeUnmount(() => document.removeEventListener('pointerdown', dismissIcon))
</script>

<template>
  <SettingsDialogShell title="项目设置" :description="project.name" width="760px" height="auto" @close="requestClose">
    <div class="project-settings-body" :inert="saving || undefined">
      <div v-if="saveError || draftWarning || draftRestored || draftConflict" class="settings-feedback">
        <p v-if="saveError" class="save-error" role="alert">{{ saveError }}</p>
        <p v-if="draftWarning" class="save-error" role="alert">{{ draftWarning }}</p>
        <p v-if="draftRestored && !draftConflict" class="members-help" role="status">已恢复本机未保存的修改。</p>
        <div v-if="draftConflict" class="draft-conflict" role="alert">
          <p>项目已在其他窗口修改。本机输入已保留，请确认要使用的内容。</p>
          <p>账号当前名称：{{ project.name }}</p>
          <p>领域：{{ data.areaById(project.area)?.name || '当前领域已不可用' }} · 截止日期：{{ project.due || '无截止日期' }} · 图标：<i :class="project.icon || 'ri-stack-line'" role="img" aria-label="账号当前项目图标"></i></p>
          <p>目标：{{ project.goal }}</p>
          <WaoButton @click="reviewDraft(true)">保留我的修改</WaoButton>
          <WaoButton variant="ghost" @click="reviewDraft(false)">使用账号当前设置</WaoButton>
        </div>
        <WaoButton v-if="memberConflict" variant="secondary" @click="refreshMemberSelection">读取最新成员并保留我的修改</WaoButton>
      </div>
      <ProjectSettingsLayout v-model="activeTab" :member-count="selectedMembers.length">
        <template #basic>
          <div class="basic-form">
            <WaoField v-slot="field" label="项目名称" :error="nameError" required>
              <div class="name-editor">
                <details ref="iconPicker" class="icon-picker" @keydown.esc.stop.prevent="iconPicker!.open = false">
                  <summary aria-label="选择项目图标"><i :class="icon" aria-hidden="true"></i></summary>
                  <div class="icon-grid" aria-label="项目图标">
                    <button v-for="(ic, index) in ICONS" :key="ic" type="button" class="icon-cell" :class="{ on: icon === ic }"
                      :aria-label="`项目图标 ${index + 1}`" :aria-pressed="icon === ic" @click="chooseIcon(ic)">
                      <i :class="ic" aria-hidden="true"></i>
                    </button>
                  </div>
                </details>
                <input :id="field.id" v-model="name" class="title-input" :aria-invalid="field.invalid" :aria-describedby="field.describedBy" required @keydown.enter.prevent="requestClose" @input="nameError = ''" />
              </div>
            </WaoField>
            <div class="basic-fields">
              <WaoField v-slot="field" label="所属领域">
                <select :id="field.id" v-model="area"><option v-if="!data.areaById(area)" :value="area">当前领域（已不可用）</option><option v-for="a in data.areas" :key="a.id" :value="a.id">{{ a.name }}</option></select>
              </WaoField>
              <WaoField v-slot="field" label="截止日期 · 可选">
                <div class="due-editor">
                  <input :id="field.id" v-model="due" type="date" aria-label="项目截止日期" />
                  <WaoButton v-if="due" variant="ghost" @click="due = ''">清空日期</WaoButton>
                </div>
              </WaoField>
            </div>
            <WaoField v-slot="field" label="项目描述 / 目标">
              <textarea :id="field.id" v-model="goal" class="goal" rows="4" placeholder="这个项目做成了，会是什么样？"></textarea>
            </WaoField>
          </div>
        </template>
        <template #members>
          <section class="project-members" aria-label="项目成员">
            <p class="members-help">{{ canManageMembers ? '负责人始终保留。成员调整保存后生效。' : '只有项目负责人可以添加或移除成员。' }}</p>
            <p v-if="memberReview" class="members-help" role="status">{{ memberReview }}</p>
            <p v-if="membersLoading" role="status">正在读取组织成员…</p>
            <p v-if="membersError" class="save-error" role="alert">{{ membersError }} <WaoButton variant="ghost" @click="loadMembers">重新读取</WaoButton></p>
            <ul class="member-list">
              <li v-for="id in selectedMembers" :key="id">
                <span class="member-avatar" aria-hidden="true">{{ memberName(id).slice(0, 1) }}</span>
                <div class="member-info"><span>{{ memberName(id) }}</span><small>{{ id === project.owner ? '负责人' : '项目成员' }}</small></div>
                <WaoButton v-if="canManageMembers && id !== project.owner" variant="ghost" :aria-label="`移除成员：${memberName(id)}`" @click="removeMember(id)">移除</WaoButton>
              </li>
            </ul>
            <div v-if="canManageMembers" class="member-add">
              <label for="project-member-add">添加成员</label>
              <div class="member-add-controls">
                <select id="project-member-add" v-model="memberToAdd" :disabled="membersLoading || !!membersError">
                  <option value="">{{ availableMembers.length ? '选择组织成员' : '没有可添加的成员' }}</option>
                  <option v-for="member in availableMembers" :key="member.id" :value="member.id">{{ member.name }}</option>
                </select>
                <WaoButton :disabled="!memberToAdd || membersLoading || !!membersError" @click="addMember">添加</WaoButton>
              </div>
            </div>
          </section>
        </template>
        <template #more>
      <details v-if="AI_RUN_ENABLED" class="ai-section">
        <summary>AI 执行配置与 Skill</summary>
        <div class="ai-head">
          <div>
            <div class="ai-title"><i class="ri-robot-2-line"></i>AI Run</div>
            <div class="ai-sub">
              把任务派给在线电脑上的 Codex 或 Claude Code，自动开发、测试、提交和部署。
            </div>
          </div>
          <label class="switch">
            <input v-model="aiEnabled" type="checkbox" :disabled="aiLoading" />
            <span></span>
          </label>
        </div>

        <div v-if="aiLoading" class="ai-loading">
          <i class="ri-loader-4-line spin"></i>正在读取执行配置…
        </div>
        <div v-else class="ai-fields" :class="{ muted: !aiEnabled }">
          <label class="form-field wide">
            <span>GitHub 仓库</span>
            <input v-model="repoUrl" placeholder="https://github.com/组织/仓库.git" />
          </label>
          <label class="form-field">
            <span>默认分支</span>
            <input v-model="defaultBranch" placeholder="main" />
          </label>
          <label class="form-field">
            <span>本机工程文件夹</span>
            <input v-model="workspaceDir" placeholder="wao" />
          </label>
          <label class="form-field">
            <span>默认 AI 工具</span>
            <select v-model="defaultEngine">
              <option v-for="engine in AI_ENGINES" :key="engine.id" :value="engine.id">
                {{ engine.label }}
              </option>
            </select>
          </label>
          <label class="form-field">
            <span>同项目最大并发</span>
            <input v-model.number="maxParallelRuns" type="number" min="1" max="10" />
          </label>
          <label class="form-field">
            <span>失败自动重试次数</span>
            <input v-model.number="failureRetryCount" type="number" min="0" max="5" />
          </label>
          <label class="form-field wide">
            <span
              >AI 执行提示词 <small>{{ executionPrompt.length }}/4000</small></span
            >
            <textarea
              v-model="executionPrompt"
              rows="7"
              maxlength="4000"
              aria-label="AI 执行提示词"
              placeholder="先完整阅读任务原文，核验根因，再按可验证结果连续完成实现。"
            ></textarea>
            <em>每次 AI Run 领取任务时先读取这段方法；任务原文和系统交付约束仍会完整保留。</em>
          </label>
          <label class="form-field">
            <span>优先执行电脑</span>
            <select v-model="preferredRunnerId">
              <option value="">自动分配在线电脑</option>
              <option v-for="runner in runners" :key="runner.id" :value="runner.id">
                {{ runner.name }} · {{ runner.engines.map(engineLabel).join(' / ')
                }}{{
                  runner.online
                    ? ` · 在线 ${runner.activeRuns}/${runner.maxConcurrency}`
                    : ' · 离线'
                }}
              </option>
            </select>
          </label>
        </div>

        <div v-if="!aiLoading" class="skill-config" :class="{ muted: !aiEnabled }">
          <div class="skill-config-head">
            <div>
              <b>项目 Skill</b
              ><span>固定审核版本，只在隔离的 Codex 运行中加载；升级不会改写历史运行。</span>
            </div>
            <RouterLink to="/wao/skills" target="_blank">打开 Skill 市场</RouterLink>
          </div>
          <div v-if="waoApplicationSkills.length" class="application-skill-note">
            WAO 默认将加载：{{
              waoApplicationSkills.map((item) => `${item.displayName} v${item.version}`).join('、')
            }}。项目选择同一 Skill 时以项目版本为准。
          </div>
          <div
            v-if="
              defaultEngine !== 'codex' &&
              (selectedSkillVersions.length || waoApplicationSkills.length)
            "
            class="skill-engine-warning"
          >
            WAO 默认或项目 Skill 当前只支持 Codex；其它工具的运行会在入队前明确拒绝。
          </div>
          <div v-if="!availableSkills.length" class="skill-empty">
            市场暂无可启用的 Codex Skill，可先发布并通过审核。
          </div>
          <label
            v-for="skill in availableSkills"
            v-else
            :key="skill.versionId"
            class="skill-option"
          >
            <input
              type="checkbox"
              :value="skill.versionId"
              :checked="selectedSkillVersions.includes(skill.versionId)"
              :disabled="
                defaultEngine !== 'codex' ||
                (selectedSkillVersions.length >= 10 &&
                  !selectedSkillVersions.includes(skill.versionId) &&
                  !availableSkills.some(
                    (item) =>
                      item.skillId === skill.skillId &&
                      selectedSkillVersions.includes(item.versionId),
                  ))
              "
              @change="toggleSkill(skill, ($event.target as HTMLInputElement).checked)"
            />
            <span
              ><b
                >{{ skill.displayName }} <small>v{{ skill.version }}</small></b
              ><em>{{ skill.summary }}</em></span
            >
          </label>
        </div>

        <div class="runner-box">
          <div>
            <b>接入一台新电脑</b>
            <span
              >电脑只需配对一次；Codex / Claude Code 与 GitHub 凭据不会上传到
              WAO。更完整的电脑与工作流管理在首页 AI 控制台。</span
            >
          </div>
          <button class="pair-btn" @click="generatePairCode">
            <i class="ri-link"></i>生成电脑配对码
          </button>
        </div>
        <div v-if="pairCode" class="pair-code">
          <span>{{ pairCode }}</span>
          <small>十分钟内有效 · 到期时间 {{ pairExpiresAt.replace('T', ' ') }}</small>
        </div>
      </details>


          <div class="danger-zone">
            <div class="dz-text">
              <h3>{{ confirming ? `确认删除「${project.name}」？` : '删除项目' }}</h3>
              <p>{{ confirming ? '此操作不可撤销，请确认。' : '项目和项目下的任务将被删除；知识库与文档保留。' }}</p>
            </div>
            <div class="danger-actions">
              <WaoButton v-if="confirming" @click="confirming = false">取消删除</WaoButton>
              <WaoButton variant="danger" :loading="saving" @click="confirming ? remove() : confirming = true">{{ confirming ? '确认删除' : '删除项目' }}</WaoButton>
            </div>
          </div>
        </template>
      </ProjectSettingsLayout>
    </div>
    <template #footer>
      <div class="foot">
        <span class="save-status" role="status">{{ saving ? '正在保存…' : saveError ? '保存失败，修改已保留' : dirty ? '有待保存的修改 · 关闭时保存' : '没有待保存的修改' }}</span>
        <WaoButton variant="primary" class="btn-primary" :loading="saving" :disabled="memberConflict || draftConflict" @click="requestClose">{{ saving ? '保存中…' : saveError ? '重试保存' : dirty ? '保存并关闭' : '完成' }}</WaoButton>
      </div>
    </template>
  </SettingsDialogShell>
</template>
<style scoped>
.basic-form { display: grid; gap: 22px; }
.basic-fields { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 22px; }
.name-editor { display: flex; gap: 10px; min-width: 0; }
.icon-picker { position: relative; flex: 0 0 44px; }
.icon-picker summary { list-style: none; display: flex; align-items: center; justify-content: center; height: 44px; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-primary-soft); color: var(--wao-primary-text); cursor: pointer; }
.icon-picker summary::-webkit-details-marker { display: none; }
.icon-picker summary i { font-size: 22px; }
.icon-grid { position: absolute; left: 0; top: calc(100% + 6px); z-index: 1; width: 210px; display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 4px; padding: 8px; border: 1px solid var(--wao-line); border-radius: var(--wao-radius-card); box-shadow: var(--wao-shadow-pop); background: var(--wao-bg-card); }
.icon-cell { min-height: 44px; border: 1px solid transparent; border-radius: var(--wao-radius-control); color: var(--wao-text); background: transparent; cursor: pointer; font-size: 20px; }
.icon-cell:hover, .icon-cell.on { background: var(--wao-primary-soft); color: var(--wao-primary-text); }
.name-editor input { flex: 1; }
.due-editor { display: flex; align-items: center; gap: 4px; }
.due-editor input { flex: 1; }
.due-editor :deep(button) { padding: 8px; white-space: nowrap; }
.goal { min-height: 112px; }
.settings-feedback { padding: 16px 26px 0; }
.settings-feedback p { margin: 0 0 10px; overflow-wrap: anywhere; }
.save-error { color: var(--wao-danger-strong); font-size: 13px; }
.members-help { margin: 0; color: var(--wao-text-3); font-size: 13px; line-height: 1.7; }
.member-list { list-style: none; padding: 0; margin: 16px 0 24px; }
.member-list li { display: flex; align-items: center; gap: 12px; min-height: 68px; border-bottom: 1px solid var(--wao-line); }
.member-avatar { display: flex; align-items: center; justify-content: center; width: 36px; height: 36px; flex-shrink: 0; background: var(--wao-primary-soft); color: var(--wao-primary-text); border-radius: var(--wao-radius-control); }
.member-info { flex: 1; min-width: 0; display: grid; gap: 3px; overflow-wrap: anywhere; }
.member-info small { color: var(--wao-text-3); font-size: 12px; }
.member-add { display: grid; gap: 8px; }
.member-add-controls { display: flex; gap: 8px; }
.member-add select { flex: 1; min-width: 0; padding: 8px 12px; font: inherit; color: var(--wao-text); background: var(--wao-bg-card); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); }
.danger-zone { display: flex; flex-wrap: wrap; align-items: center; gap: 16px; }
.dz-text { flex: 1; min-width: 180px; }
.dz-text h3 { margin: 0 0 8px; font-size: 15px; color: var(--wao-danger-strong); overflow-wrap: anywhere; }
.dz-text p { margin: 0; color: var(--wao-text-3); font-size: 13px; line-height: 1.7; }
.danger-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.foot { display: flex; align-items: center; gap: 12px; }
.save-status { margin-right: auto; font-size: 12px; color: var(--wao-text-3); overflow-wrap: anywhere; }
.foot :deep(button) { flex-shrink: 0; }
@media (max-width: 650px) {
  .basic-fields { grid-template-columns: minmax(0, 1fr); gap: 20px; }
  .settings-feedback { padding: 16px 15px 0; }
  .foot { flex-wrap: wrap; }
  .save-status { flex-basis: 100%; }
  .foot :deep(button) { margin-left: auto; }
  .member-add select { font-size: 16px; }
}
.ai-section {
  margin-top: 18px;
  padding: 15px;
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: linear-gradient(145deg, var(--wao-primary-tint), var(--wao-bg-card));
}
.ai-head,
.runner-box {
  display: flex;
  align-items: center;
  gap: 14px;
}
.skill-config {
  margin-top: 14px;
  border-top: 1px solid var(--wao-line);
  padding-top: 13px;
}
.skill-config-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
}
.skill-config-head > div {
  display: grid;
  gap: 3px;
}
.skill-config-head b {
  font-size: 12px;
}
.skill-config-head span,
.skill-empty {
  font-size: 11px;
  color: var(--wao-text-weak);
  line-height: 1.5;
}
.skill-config-head a {
  flex: none;
  font-size: 11px;
  color: var(--wao-primary-text);
  text-decoration: none;
}
.skill-option {
  margin-top: 8px;
  display: flex;
  gap: 9px;
  align-items: flex-start;
  padding: 9px 10px;
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  cursor: pointer;
}
.skill-option input {
  margin-top: 3px;
  accent-color: var(--wao-primary);
}
.skill-option span {
  min-width: 0;
  display: grid;
  gap: 3px;
}
.skill-option b {
  font-size: 12px;
  color: var(--wao-text);
}
.skill-option small {
  color: var(--wao-text-weak);
  font-weight: 500;
}
.skill-option em {
  font-size: 11px;
  color: var(--wao-text-weak);
  font-style: normal;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.skill-empty {
  padding: 12px 0 2px;
}
.skill-engine-warning {
  margin-top: 8px;
  color: var(--wao-warning-strong);
  font-size: 11px;
}
.application-skill-note {
  margin-top: 8px;
  padding: 8px 9px;
  border-radius: var(--wao-radius-control);
  background: var(--wao-success-bg);
  color: var(--wao-success-strong);
  font-size: 11px;
  line-height: 1.5;
}
.ai-head > div,
.runner-box > div {
  flex: 1;
  min-width: 0;
}
.ai-title {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--wao-primary-text);
  font-size: 14px;
  font-weight: 700;
}
.ai-title i {
  font-size: 17px;
}
.ai-sub,
.runner-box span {
  display: block;
  margin-top: 3px;
  color: var(--wao-text-weak);
  font-size: 11.5px;
}
.switch input {
  position: absolute;
  opacity: 0;
}
.switch span {
  position: relative;
  display: block;
  width: 40px;
  height: 22px;
  border-radius: var(--wao-radius-pill);
  background: var(--wao-line-control);
  cursor: pointer;
  transition: 0.2s;
}
.switch span::after {
  content: '';
  position: absolute;
  top: 3px;
  left: 3px;
  width: 16px;
  height: 16px;
  border-radius: var(--wao-radius-pill);
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-pop);
  transition: 0.2s;
}
.switch input:checked + span {
  background: var(--wao-primary);
}
.switch input:checked + span::after {
  transform: translateX(18px);
}
.ai-loading {
  padding: 18px 0 4px;
  text-align: center;
  color: var(--wao-text-weak);
  font-size: 12px;
}
.spin {
  display: inline-block;
  margin-right: 6px;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.ai-fields {
  margin-top: 14px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.ai-fields.muted {
  opacity: 0.52;
}
.form-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  color: var(--wao-text-weak);
  font-size: 11.5px;
}
.form-field.wide {
  grid-column: 1 / -1;
}
.form-field input,
.form-field select,
.form-field textarea {
  width: 100%;
  height: 34px;
  padding: 0 9px;
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font-size: 12px;
  outline: none;
}
.form-field textarea {
  resize: vertical;
  min-height: 132px;
  line-height: 1.6;
}
.form-field span small {
  float: right;
  color: var(--wao-text-muted);
  font-weight: 500;
}
.form-field em {
  color: var(--wao-text-muted);
  font-size: 11px;
  font-style: normal;
  line-height: 1.5;
}
.form-field input:focus,
.form-field select:focus,
.form-field textarea:focus {
  border-color: var(--wao-primary);
}
.runner-box {
  margin-top: 13px;
  padding-top: 12px;
  border-top: 1px solid var(--wao-line-control);
}
.runner-box b {
  font-size: 12.5px;
}
.pair-btn {
  height: 32px;
  padding: 0 11px;
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-primary-text);
  font-size: 12px;
  cursor: pointer;
}
.pair-code {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px dashed var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: var(--wao-primary-tint);
}
.pair-code span {
  display: block;
  color: var(--wao-line-control);
  font:
    700 21px/1.2 ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
  letter-spacing: 2px;
}
.pair-code small {
  color: var(--wao-text-weak);
  font-size: 10.5px;
}
</style>
