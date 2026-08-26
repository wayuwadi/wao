<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { toast } from '@/composables/toast'
import {
  createAiPairCode,
  fetchAvailableAiSkills,
  fetchAiProjectConfig,
  fetchAiProjectSkills,
  fetchAiRunners,
  saveAiProjectConfig,
  saveAiProjectSkills,
} from '@/api/ai'
import { AI_ENGINES, engineLabel } from '@/composables/aiEngines'
import type { AiEngine, AiRunner, AiSkillCapability, Project } from '@/types'

const props = defineProps<{ project: Project }>()
const emit = defineEmits<{ close: []; deleted: [] }>()

const data = useDataStore()

const name = ref(props.project.name)
const area = ref(props.project.area)
const status = ref(props.project.status)
const goal = ref(props.project.goal === '尚未填写目标——打开项目后可补充。' ? '' : props.project.goal)
const icon = ref(props.project.icon || 'ri-stack-line')
const confirming = ref(false)
const aiLoading = ref(true)
const aiEnabled = ref(false)
const repoUrl = ref('')
const defaultBranch = ref('main')
const workspaceDir = ref('')
const defaultEngine = ref<AiEngine>('codex')
const preferredRunnerId = ref('')
const maxParallelRuns = ref(2)
const runners = ref<AiRunner[]>([])
const pairCode = ref('')
const pairExpiresAt = ref('')
const availableSkills = ref<AiSkillCapability[]>([])
const selectedSkillVersions = ref<string[]>([])

function toggleSkill(skill: AiSkillCapability, checked: boolean) {
  if (!checked) {
    selectedSkillVersions.value = selectedSkillVersions.value.filter((versionId) => versionId !== skill.versionId)
    return
  }
  const sameSkillVersions = new Set(availableSkills.value.filter((item) => item.skillId === skill.skillId).map((item) => item.versionId))
  selectedSkillVersions.value = selectedSkillVersions.value.filter((versionId) => !sameSkillVersions.has(versionId))
  selectedSkillVersions.value.push(skill.versionId)
}

/** 可选项目图标（remixicon）。 */
const ICONS = [
  'ri-stack-line', 'ri-rocket-line', 'ri-plant-line', 'ri-home-heart-line',
  'ri-briefcase-line', 'ri-book-2-line', 'ri-heart-pulse-line', 'ri-palette-line',
  'ri-code-box-line', 'ri-camera-line', 'ri-flight-takeoff-line', 'ri-cup-line',
  'ri-money-cny-circle-line', 'ri-run-line', 'ri-music-2-line', 'ri-lightbulb-line',
]

async function save() {
  if (!name.value.trim()) return
  try {
    const projectSaved = await data.updateProject(props.project.id, {
      name: name.value.trim(),
      area: area.value,
      status: status.value,
      goal: goal.value.trim(),
      icon: icon.value,
    })
    if (!projectSaved) throw new Error('项目设置保存失败，请重试')
    await saveAiProjectConfig(props.project.id, {
      enabled: aiEnabled.value,
      repoUrl: repoUrl.value.trim(),
      defaultBranch: defaultBranch.value.trim() || 'main',
      workspaceDir: workspaceDir.value.trim(),
      defaultEngine: defaultEngine.value,
      preferredRunnerId: preferredRunnerId.value || null,
      maxParallelRuns: maxParallelRuns.value,
    })
    await saveAiProjectSkills(props.project.id, selectedSkillVersions.value)
    toast('项目设置与 AI Run 配置已保存', 'ri-check-line')
    emit('close')
  } catch (error) {
    toast(error instanceof Error ? error.message : '项目设置保存失败', 'ri-error-warning-line')
  }
}

async function remove() {
  await data.deleteProject(props.project.id)
  emit('deleted')
}

async function loadAiConfig() {
  aiLoading.value = true
  try {
    const [config, nodes, skills, enabledSkills] = await Promise.all([
      fetchAiProjectConfig(props.project.id),
      fetchAiRunners(),
      fetchAvailableAiSkills(),
      fetchAiProjectSkills(props.project.id),
    ])
    aiEnabled.value = config.enabled
    repoUrl.value = config.repoUrl
    defaultBranch.value = config.defaultBranch
    workspaceDir.value = config.workspaceDir
    defaultEngine.value = config.defaultEngine
    preferredRunnerId.value = config.preferredRunnerId ?? ''
    maxParallelRuns.value = config.maxParallelRuns
    runners.value = nodes
    const availableVersionIds = new Set(skills.map((item) => item.versionId))
    availableSkills.value = [...skills, ...enabledSkills.filter((item) => !availableVersionIds.has(item.versionId))]
    selectedSkillVersions.value = enabledSkills.map((item) => item.versionId)
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

onMounted(loadAiConfig)
</script>

<template>
  <div class="mask" @click="emit('close')"></div>
  <div class="modal">
    <div class="head">
      <div class="title">项目设置</div>
      <span class="head-tip">自定义图标、名称、描述，或删除项目</span>
      <div class="spacer"></div>
      <button class="close" @click="emit('close')"><i class="ri-close-line"></i></button>
    </div>

    <div class="label">项目图标</div>
    <div class="icon-grid">
      <button
        v-for="ic in ICONS"
        :key="ic"
        class="icon-cell"
        :class="{ on: icon === ic }"
        :style="icon === ic ? { background: data.areaColor(area) } : {}"
        @click="icon = ic"
      >
        <i :class="ic"></i>
      </button>
    </div>

    <div class="label">项目名称</div>
    <input v-model="name" class="title-input" placeholder="项目名称" @keydown.enter="save" />

    <div class="label">所属领域</div>
    <div class="pills">
      <button v-for="a in data.areas" :key="a.id" class="pill" :class="{ on: area === a.id }" @click="area = a.id">
        <span class="dot" :style="{ background: a.color }"></span>{{ a.name }}
      </button>
    </div>

    <div class="label">项目状态</div>
    <select v-model="status" class="status-select" aria-label="项目状态">
      <option value="规划中">规划中</option>
      <option value="进行中">进行中</option>
      <option value="有风险">有风险</option>
      <option value="已完成">已完成</option>
      <option value="已搁置">已搁置</option>
    </select>

    <div class="label">项目描述 / 目标</div>
    <textarea v-model="goal" class="goal" rows="3" placeholder="这个项目做成了，会是什么样？"></textarea>

    <section class="ai-section">
      <div class="ai-head">
        <div>
          <div class="ai-title"><i class="ri-robot-2-line"></i>AI Run</div>
          <div class="ai-sub">把任务派给在线电脑上的 Codex 或 Claude Code，自动开发、测试、提交和部署。</div>
        </div>
        <label class="switch">
          <input v-model="aiEnabled" type="checkbox" :disabled="aiLoading" />
          <span></span>
        </label>
      </div>

      <div v-if="aiLoading" class="ai-loading"><i class="ri-loader-4-line spin"></i>正在读取执行配置…</div>
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
            <option v-for="engine in AI_ENGINES" :key="engine.id" :value="engine.id">{{ engine.label }}</option>
          </select>
        </label>
        <label class="form-field">
          <span>同项目最大并发</span>
          <input v-model.number="maxParallelRuns" type="number" min="1" max="10" />
        </label>
        <label class="form-field">
          <span>优先执行电脑</span>
          <select v-model="preferredRunnerId">
            <option value="">自动分配在线电脑</option>
            <option v-for="runner in runners" :key="runner.id" :value="runner.id">
              {{ runner.name }} · {{ runner.engines.map(engineLabel).join(' / ') }}{{ runner.online ? ` · 在线 ${runner.activeRuns}/${runner.maxConcurrency}` : ' · 离线' }}
            </option>
          </select>
        </label>
      </div>

      <div v-if="!aiLoading" class="skill-config" :class="{ muted: !aiEnabled }">
        <div class="skill-config-head">
          <div><b>项目 Skill</b><span>固定审核版本，只在隔离的 Codex 运行中加载；升级不会改写历史运行。</span></div>
          <RouterLink to="/wao/skills" target="_blank">打开 Skill 市场</RouterLink>
        </div>
        <div v-if="defaultEngine !== 'codex'" class="skill-engine-warning">项目 Skill 当前只支持 Codex；改用其它默认工具前请先取消全部绑定。</div>
        <div v-if="!availableSkills.length" class="skill-empty">市场暂无可启用的 Codex Skill，可先发布并通过审核。</div>
        <label v-for="skill in availableSkills" v-else :key="skill.versionId" class="skill-option">
          <input type="checkbox" :value="skill.versionId" :checked="selectedSkillVersions.includes(skill.versionId)" :disabled="defaultEngine !== 'codex' || (selectedSkillVersions.length >= 10 && !selectedSkillVersions.includes(skill.versionId) && !availableSkills.some((item) => item.skillId === skill.skillId && selectedSkillVersions.includes(item.versionId)))" @change="toggleSkill(skill, ($event.target as HTMLInputElement).checked)" />
          <span><b>{{ skill.displayName }} <small>v{{ skill.version }}</small></b><em>{{ skill.summary }}</em></span>
        </label>
      </div>

      <div class="runner-box">
        <div>
          <b>接入一台新电脑</b>
          <span>电脑只需配对一次；Codex / Claude Code 与 GitHub 凭据不会上传到 WAO。更完整的电脑与工作流管理在首页 AI 控制台。</span>
        </div>
        <button class="pair-btn" @click="generatePairCode"><i class="ri-link"></i>生成电脑配对码</button>
      </div>
      <div v-if="pairCode" class="pair-code">
        <span>{{ pairCode }}</span>
        <small>十分钟内有效 · 到期时间 {{ pairExpiresAt.replace('T', ' ') }}</small>
      </div>
    </section>

    <div class="danger-zone">
      <template v-if="!confirming">
        <div class="dz-text">
          <div class="dz-title">删除项目</div>
          <div class="dz-sub">将同时移除该项目下的任务与关联知识库，不可恢复。</div>
        </div>
        <button class="btn-danger" @click="confirming = true"><i class="ri-delete-bin-line"></i>删除</button>
      </template>
      <template v-else>
        <div class="dz-text">
          <div class="dz-title">确认删除「{{ project.name }}」？</div>
          <div class="dz-sub">此操作不可撤销。</div>
        </div>
        <button class="btn-ghost sm" @click="confirming = false">取消</button>
        <button class="btn-danger" @click="remove"><i class="ri-delete-bin-line"></i>确认删除</button>
      </template>
    </div>

    <div class="foot">
      <div class="spacer"></div>
      <button class="btn-ghost" @click="emit('close')">取消</button>
      <button class="btn-primary" :disabled="!name.trim()" @click="save">保存</button>
    </div>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(23, 25, 35, 0.32);
  z-index: 100;
  animation: waoFade 0.15s ease;
}
.modal {
  position: fixed;
  top: 8vh;
  /* 用 margin 居中而不是 transform：waoPop 动画也动 transform，
     两者叠在一起时居中位移会被覆盖，卡片先偏右出现再跳回中间。 */
  left: 0;
  right: 0;
  margin-inline: auto;
  width: min(560px, calc(100vw - 40px));
  max-height: 84vh;
  overflow-y: auto;
  background: var(--wao-bg-card);
  border-radius: 14px;
  z-index: 101;
  box-shadow: var(--wao-shadow-modal);
  animation: waoPop 0.16s ease;
  padding: 20px 22px;
}
.spacer {
  flex: 1;
}
.head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.title {
  font-size: 16px;
  font-weight: 700;
}
.head-tip {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.close {
  width: 28px;
  height: 28px;
  border: none;
  background: none;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
}
.close:hover {
  background: var(--wao-bg-hover-2);
}
.close i {
  font-size: 16px;
}
.label {
  margin-top: 14px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.icon-grid {
  margin-top: 8px;
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 8px;
}
.icon-cell {
  aspect-ratio: 1;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  border-radius: 9px;
  color: var(--wao-text-3);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.icon-cell i {
  font-size: 17px;
}
.icon-cell:hover {
  border-color: #c7d0f1;
}
.icon-cell.on {
  color: #fff;
  border-color: transparent;
}
.title-input {
  margin-top: 6px;
  width: 100%;
  height: 40px;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  padding: 0 12px;
  font-size: 14px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  outline: none;
}
.title-input:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.pills {
  margin-top: 6px;
  display: flex;
  gap: 7px;
  flex-wrap: wrap;
}
.pill {
  height: 28px;
  padding: 0 11px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  border-radius: 999px;
  font-size: 12px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.pill.on {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  border-color: #c7d0f1;
}
.pill .dot {
  width: 6px;
  height: 6px;
  border-radius: 2px;
}
.goal {
  margin-top: 6px;
  width: 100%;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 1.7;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  outline: none;
  resize: none;
}
.status-select {
  margin-top: 6px;
  width: 100%;
  height: 40px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font-size: 13px;
  outline: none;
}
.status-select:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.goal:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.ai-section {
  margin-top: 18px;
  padding: 15px;
  border: 1px solid #dce1f8;
  border-radius: 12px;
  background: linear-gradient(145deg, var(--wao-primary-tint), var(--wao-bg-card));
}
.ai-head,
.runner-box {
  display: flex;
  align-items: center;
  gap: 14px;
}
.skill-config { margin-top: 14px; border-top: 1px solid var(--wao-line); padding-top: 13px; }
.skill-config-head { display: flex; gap: 12px; align-items: flex-start; justify-content: space-between; }
.skill-config-head > div { display: grid; gap: 3px; }
.skill-config-head b { font-size: 12px; }
.skill-config-head span,.skill-empty { font-size: 11px; color: var(--wao-text-weak); line-height: 1.5; }
.skill-config-head a { flex: none; font-size: 11px; color: var(--wao-primary-text); text-decoration: none; }
.skill-option { margin-top: 8px; display: flex; gap: 9px; align-items: flex-start; padding: 9px 10px; border: 1px solid var(--wao-line-control); border-radius: 9px; background: var(--wao-bg-card); cursor: pointer; }
.skill-option input { margin-top: 3px; accent-color: var(--wao-primary); }
.skill-option span { min-width: 0; display: grid; gap: 3px; }
.skill-option b { font-size: 12px; color: var(--wao-text); }
.skill-option small { color: var(--wao-text-weak); font-weight: 500; }
.skill-option em { font-size: 11px; color: var(--wao-text-weak); font-style: normal; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.skill-empty { padding: 12px 0 2px; }
.skill-engine-warning { margin-top: 8px; color: var(--wao-warning-strong); font-size: 11px; }
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
  border-radius: 999px;
  background: #cfd3df;
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
  border-radius: 50%;
  background: var(--wao-bg-card);
  box-shadow: 0 1px 3px #0002;
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
  to { transform: rotate(360deg); }
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
.form-field select {
  width: 100%;
  height: 34px;
  padding: 0 9px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font-size: 12px;
  outline: none;
}
.form-field input:focus,
.form-field select:focus {
  border-color: var(--wao-primary);
}
.runner-box {
  margin-top: 13px;
  padding-top: 12px;
  border-top: 1px solid #e5e8f5;
}
.runner-box b {
  font-size: 12.5px;
}
.pair-btn {
  height: 32px;
  padding: 0 11px;
  border: 1px solid #cbd2f3;
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-primary-text);
  font-size: 12px;
  cursor: pointer;
}
.pair-code {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px dashed #aeb9ee;
  border-radius: 9px;
  background: var(--wao-primary-tint);
}
.pair-code span {
  display: block;
  color: #273ba3;
  font: 700 21px/1.2 ui-monospace, SFMono-Regular, Consolas, monospace;
  letter-spacing: 2px;
}
.pair-code small {
  color: var(--wao-text-weak);
  font-size: 10.5px;
}
.danger-zone {
  margin-top: 18px;
  border: 1px solid #f0d9d9;
  background: var(--wao-danger-bg);
  border-radius: 10px;
  padding: 12px 14px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.dz-text {
  flex: 1;
  min-width: 0;
}
.dz-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--wao-danger-strong);
}
.dz-sub {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.btn-danger {
  height: 32px;
  padding: 0 12px;
  border: none;
  background: var(--wao-danger);
  color: #fff;
  border-radius: 8px;
  font-size: 12.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.btn-danger:hover {
  filter: brightness(0.95);
}
.btn-danger i {
  font-size: 14px;
}
.foot {
  margin-top: 20px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.btn-ghost {
  height: 34px;
  padding: 0 14px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
}
.btn-ghost.sm {
  height: 32px;
  padding: 0 12px;
  font-size: 12.5px;
  flex-shrink: 0;
}
.btn-ghost:hover {
  background: var(--wao-bg-hover);
}
.btn-primary {
  height: 34px;
  padding: 0 16px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}
.btn-primary:hover:not(:disabled) {
  background: var(--wao-primary-hover);
}
.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
