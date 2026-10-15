<script setup lang="ts">
import {
  computed,
  defineAsyncComponent,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import {
  filterSystemAiFeatures,
  systemAiFeatures,
  localAiFeatureIds,
  apiAiFeatureIds,
  type SystemAiType,
} from '@/utils/systemAiCatalog'
import {
  fetchSystemAiResources,
  fetchSystemAiActivity,
  type SystemAiActivity,
} from '@/api/systemAi'
import NoteAiSettingsForm from '@/components/notes/NoteAiSettingsForm.vue'
import TianrenAiSettingsDialog from '@/components/tianren/TianrenAiSettingsDialog.vue'
import NoteDiscoveryPreference from '@/components/notes/NoteDiscoveryPreference.vue'
const TextPolishSettings = defineAsyncComponent(() => import('@/views/TextPolishView.vue'))
const AiAccountPanel = defineAsyncComponent(
  () => import('@/components/settings/AiAccountPanel.vue'),
)
const route = useRoute(),
  router = useRouter(),
  session = useSessionStore()
const owner = session.userId
const catalogElement = ref<HTMLElement>()
async function revealSelection() {
  await nextTick()
  const catalog = catalogElement.value
  if (!catalog || window.innerWidth > 900) return
  const card = catalog.querySelector<HTMLElement>('[aria-pressed=true]')
  if (!card) return
  const parent = catalog.getBoundingClientRect(),
    box = card.getBoundingClientRect()
  if (box.left < parent.left || box.right > parent.right)
    catalog.scrollLeft += box.left - parent.left
}
const keyword = ref(''),
  type = ref<'全部' | SystemAiType>('全部')
const view = ref<'catalog' | 'runtime'>('catalog'),
  pane = ref<'intro' | 'settings' | 'records'>('intro')
const selected = ref('note-polish'),
  resource = ref<'local' | 'api' | 'python'>('local'),
  accountsOpen = ref(false)
const items = computed(() => filterSystemAiFeatures(keyword.value, type.value))
const feature = computed(() => items.value.find((item) => item.id === selected.value))
const resources = ref<Awaited<ReturnType<typeof fetchSystemAiResources>> | null>(null),
  resourcesLoading = ref(false)
const activity = ref<SystemAiActivity | null>(null),
  activityLoading = ref(false),
  activityError = ref('')
let resourceGeneration = 0,
  activityGeneration = 0
const local = computed(() =>
  resources.value?.capabilities.value?.find((item) => item.executionMode === 'LOCAL_CODEX'),
)
const apis = computed(
  () =>
    resources.value?.capabilities.value?.filter((item) => item.executionMode === 'SERVER_API') ||
    [],
)
const impacts = computed(() =>
  systemAiFeatures.filter((item) =>
    (resource.value === 'local'
      ? localAiFeatureIds
      : resource.value === 'api'
        ? apiAiFeatureIds
        : []
    ).includes(item.id),
  ),
)
const models = computed(() => resources.value?.models.value)
const noteSection = computed(() =>
  feature.value?.id.endsWith('image') ? ('imagePrompt' as const) : ('capabilities' as const),
)
const tianrenSection = computed(() =>
  feature.value?.id === 'tianren-image' ? ('imagePrompt' as const) : ('capabilities' as const),
)
async function loadResources() {
  const ticket = ++resourceGeneration
  resourcesLoading.value = true
  const value = await fetchSystemAiResources()
  if (ticket !== resourceGeneration || owner !== session.userId) return
  resources.value = value
  resourcesLoading.value = false
}
async function loadActivity() {
  const ticket = ++activityGeneration,
    current = feature.value
  activity.value = null
  activityError.value = ''
  activityLoading.value = true
  if (!current) {
    activityLoading.value = false
    return
  }
  try {
    const value = await fetchSystemAiActivity(current)
    if (ticket === activityGeneration && owner === session.userId) activity.value = value
  } catch {
    if (ticket === activityGeneration)
      activityError.value = '记录读取失败，请重试；未触发任何新任务。'
  } finally {
    if (ticket === activityGeneration) activityLoading.value = false
  }
}
function choose(id: string) {
  selected.value = id
  void router.replace({ path: route.path, query: { ...route.query, feature: id } })
}
function openImpact(id: string) {
  keyword.value = ''
  type.value = '全部'
  view.value = 'catalog'
  pane.value = 'settings'
  choose(id)
}
function selectResource(id: string) {
  resource.value = id as typeof resource.value
  accountsOpen.value = false
}
function openResource(id: string) {
  selectResource(id)
  view.value = 'runtime'
}
function date(value?: string) {
  if (!value) return ''
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN', { hour12: false })
}
watch(
  () => route.query.feature,
  (value) => {
    if (systemAiFeatures.some((item) => item.id === value)) selected.value = String(value)
  },
  { immediate: true },
)
watch(items, (value) => {
  if (!value.some((item) => item.id === selected.value)) selected.value = value[0]?.id || ''
})
watch([selected, pane], () => {
  activityGeneration++
  activity.value = null
  activityError.value = ''
  if (pane.value === 'records') void loadActivity()
})
void loadResources()
watch([selected, view, resource], revealSelection)
onMounted(() => {
  void revealSelection()
  window.addEventListener('resize', revealSelection)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', revealSelection)
  resourceGeneration++
  activityGeneration++
})
</script>
<template>
  <section class="system-ai" aria-label="系统 AI 能力">
    <header class="ai-heading">
      <div>
        <span class="eyebrow">系统 AI · 按功能管理</span>
        <h2>知道哪里用到 AI，也知道改动会影响哪里</h2>
        <p>原来的功能入口继续保留，这里管理同一份账号配置。</p>
      </div>
      <nav aria-label="系统 AI 视图">
        <button :aria-pressed="view === 'catalog'" @click="view = 'catalog'">功能清单</button
        ><button :aria-pressed="view === 'runtime'" @click="view = 'runtime'">模型与执行</button>
      </nav>
    </header>
    <div v-if="view === 'catalog'" class="ai-toolbar">
      <nav aria-label="按执行类型筛选">
        <button
          v-for="value in ['全部', 'AI', 'Python', 'AI + 工具'] as const"
          :key="value"
          :aria-pressed="type === value"
          @click="type = value"
        >
          {{ value }}
        </button>
      </nav>
      <label
        ><i class="ri-search-line" aria-hidden="true" /><input
          v-model="keyword"
          type="search"
          aria-label="搜索系统 AI 能力"
          placeholder="搜索功能、来源或处理步骤"
          maxlength="100"
      /></label>
    </div>
    <div class="ai-columns">
      <aside
        v-if="view === 'catalog'"
        ref="catalogElement"
        class="ai-catalog"
        aria-label="系统功能清单"
      >
        <div class="list-label">
          功能清单 <span>{{ items.length }} 项</span>
        </div>
        <button
          v-for="item in items"
          :key="item.id"
          class="ai-card"
          :aria-pressed="selected === item.id"
          @click="choose(item.id)"
        >
          <i :class="item.icon" aria-hidden="true" /><span
            ><strong>{{ item.name }}</strong
            ><small>{{ item.summary }}</small
            ><em>{{ item.type }} · {{ item.source }}</em></span
          >
        </button>
        <p v-if="!items.length" class="empty">
          {{
            type === 'Python' || type === 'AI + 工具'
              ? '该类型尚未登记在用能力。后续接入后会显示在这里。'
              : '没有匹配的能力，试试其他关键词。'
          }}
        </p>
      </aside>
      <aside v-else ref="catalogElement" class="ai-catalog" aria-label="执行资源清单">
        <div class="list-label">执行资源 <span>3 类</span></div>
        <button
          v-for="item in [
            {
              id: 'local',
              name: '系统 AI 执行器',
              icon: 'ri-cpu-line',
              text: 'WAO 服务主机的本地执行',
            },
            { id: 'api', name: '模型 API', icon: 'ri-cloud-line', text: '可用于页面问答的服务' },
            {
              id: 'python',
              name: 'Python 工具',
              icon: 'ri-code-box-line',
              text: '执行环境与确定性工具',
            },
          ]"
          :key="item.id"
          class="ai-card"
          :aria-pressed="resource === item.id"
          @click="selectResource(item.id)"
        >
          <i :class="item.icon" aria-hidden="true" /><span
            ><strong>{{ item.name }}</strong
            ><small>{{ item.text }}</small></span
          >
        </button>
      </aside>
      <section class="ai-detail" :aria-label="view === 'catalog' ? '系统能力详情' : '执行资源详情'">
        <template v-if="view === 'catalog' && feature">
          <header class="detail-heading">
            <div>
              <span class="eyebrow">{{ feature.source }} · {{ feature.type }}</span>
              <h2>{{ feature.name }}</h2>
              <p>{{ feature.summary }}</p>
            </div>
            <RouterLink :to="feature.path"
              >打开原功能 <i class="ri-arrow-right-up-line" aria-hidden="true"
            /></RouterLink>
          </header>
          <nav class="detail-tabs" aria-label="系统能力管理项目">
            <button
              v-for="item in [
                { id: 'intro', label: '能力介绍' },
                { id: 'settings', label: '功能设置' },
                { id: 'records', label: '运行记录' },
              ]"
              :key="item.id"
              :aria-pressed="pane === item.id"
              @click="pane = item.id as typeof pane"
            >
              {{ item.label }}
            </button>
          </nav>
          <div v-if="pane === 'intro'" class="detail-content">
            <h3>如何工作</h3>
            <ol class="ai-steps">
              <li v-for="(step, index) in feature.steps" :key="step">
                <span>{{ index + 1 }}</span
                >{{ step }}
              </li>
            </ol>
            <div class="info-card">
              <i class="ri-shield-check-line" aria-hidden="true" />
              <div>
                <strong>数据与影响范围</strong>
                <p>{{ feature.scope }}</p>
              </div>
            </div>
            <div class="info-card">
              <i class="ri-cpu-line" aria-hidden="true" />
              <div>
                <strong>执行依赖</strong>
                <p>
                  {{
                    feature.group === 'assistant'
                      ? '可选择系统执行器或获准的模型 API。'
                      : 'WAO 服务主机的本地 AI 执行器。'
                  }}查看配置不会发起生成。
                </p>
                <button class="light" @click="openResource('local')">查看模型与执行</button>
              </div>
            </div>
          </div>
          <div v-if="pane === 'settings'" class="settings-content">
            <p v-if="feature.group === 'companion'" class="scope-note">
              小记润色完成后直接生效。模型、提示词与自动开关在小记整理设置中管理。
            </p>
            <p v-if="feature.group === 'notes'" class="scope-note">
              这里保存同一组小记 AI 设置。配图提示词也用于文档配图；文档仍需手动生成。
            </p>
            <p v-if="feature.group === 'tianren'" class="scope-note">
              仅保存本人卦象设置，解读与配图共用本组模型；不会修改小记。
            </p>
            <KeepAlive
              ><NoteAiSettingsForm
                v-if="feature.group === 'notes'"
                :key="`notes:${owner}`"
                :section="noteSection"
                @saved="loadResources" /><TianrenAiSettingsDialog
                v-else-if="feature.group === 'tianren'"
                :key="`tianren:${owner}`"
                embedded
                :section="tianrenSection"
                @saved="loadResources" /><NoteDiscoveryPreference
                v-else-if="feature.group === 'discovery'"
                :key="`discovery:${owner}`"
            /></KeepAlive>
            <div v-if="feature.group === 'polish'" class="detail-content">
              <TextPolishSettings :key="owner" settings-only />
            </div>
            <div v-if="feature.group === 'assistant'" class="detail-content">
              <h3>每次对话单独选择执行能力</h3>
              <p>在页面 AI 助手选择本次模型能力与来源。这里查看实际可选资源和管理 AI 账号。</p>
              <button class="primary" @click="openResource('api')">管理模型 API</button>
            </div>
          </div>
          <div v-if="pane === 'records'" class="detail-content">
            <div class="record-heading">
              <h3>运行记录</h3>
              <button class="light" :disabled="activityLoading" @click="loadActivity">
                刷新记录
              </button>
            </div>
            <p v-if="activityLoading" role="status">正在读取本人记录…</p>
            <p v-else-if="activityError" role="alert">
              {{ activityError }} <button class="light" @click="loadActivity">重试</button>
            </p>
            <template v-else-if="activity"
              ><p>{{ activity.summary }}</p>
              <div v-for="(item, index) in activity.items" :key="index" class="record">
                <strong>{{ item.title }}</strong>
                <p>{{ item.detail }}</p>
                <time v-if="item.at">{{ date(item.at) }}</time>
              </div>
              <p v-if="!activity.items.length" class="empty">暂无可汇总的记录</p>
              <p class="scope-note">{{ activity.note }}</p></template
            >
          </div>
        </template>
        <div v-else-if="view === 'catalog'" class="detail-content empty">
          <i class="ri-search-line" aria-hidden="true" />
          <h3>选择一个在用能力</h3>
          <p>可清空搜索或切换类型查看。</p>
        </div>
        <template v-else>
          <header class="detail-heading">
            <div>
              <span class="eyebrow">模型与执行 · 依赖总览</span>
              <h2>
                {{
                  resource === 'local'
                    ? '系统 AI 执行器'
                    : resource === 'api'
                      ? '模型 API'
                      : 'Python 工具'
                }}
              </h2>
              <p>
                {{
                  resource === 'local'
                    ? '执行环境属于 WAO 服务主机，与你电脑上的 WAO CLI 连接分别管理。'
                    : resource === 'api'
                      ? '现有可选能力与账号管理，保留原有权限。'
                      : '后续接入的确定性工具在这里统一登记。'
                }}
              </p>
            </div>
            <button class="light" :disabled="resourcesLoading" @click="loadResources">
              {{ resourcesLoading ? '读取中…' : '刷新资源' }}
            </button>
          </header>
          <div class="detail-content">
            <p v-if="resourcesLoading" role="status">正在读取真实执行资源…</p>
            <template v-if="resource === 'local'"
              ><div class="info-card">
                <i class="ri-cpu-line" aria-hidden="true" />
                <div>
                  <strong>{{
                    local ? (local.available ? '配置检查通过' : '当前不可用') : '状态尚未确认'
                  }}</strong>
                  <p>{{ resources?.capabilities.error || local?.message }}</p>
                  <small>配置检查不验证上游登录、网络或额度，实际调用结果见运行记录。</small>
                </div>
              </div>
              <h3>可用模型目录</h3>
              <p v-if="resources?.models.error" role="alert">{{ resources.models.error }}</p>
              <template v-else-if="models"
                ><p>
                  {{ models.stale ? '目录已过期，已有配置保留' : '已读取模型目录' }} ·
                  {{ models.updatedAt ? date(models.updatedAt) : '无更新时间' }}
                </p>
                <p>{{ models.message }}</p>
                <div class="model-grid">
                  <div v-for="model in models.models" :key="model.id">
                    <strong>{{ model.label }}</strong
                    ><small>{{ model.reasoningEfforts.join(' / ') }}</small>
                  </div>
                </div></template
              ></template
            >
            <template v-else-if="resource === 'api'"
              ><p v-if="resources?.capabilities.error" role="alert">
                {{ resources.capabilities.error }}
              </p>
              <div v-for="item in apis" :key="item.id" class="record">
                <strong>{{ item.label }}</strong>
                <p>{{ item.model }} · {{ item.available ? '允许选择' : '不可用' }}</p>
                <small>{{ item.message }}</small>
              </div>
              <p
                v-if="!resourcesLoading && !resources?.capabilities.error && !apis.length"
                class="empty"
              >
                暂无可供页面问答选择的模型 API。
              </p>
              <p class="scope-note">
                不同功能的用途授权独立。页面问答可选，不表示小记或卦象会自动切换到 API。
              </p>
              <button
                class="primary"
                :aria-expanded="accountsOpen"
                @click="accountsOpen = !accountsOpen"
              >
                {{ accountsOpen ? '收起账号管理' : '管理 AI 账号' }}
              </button>
              <div v-if="accountsOpen" class="accounts"><AiAccountPanel :key="owner" /></div
            ></template>
            <div v-else class="empty">
              <i class="ri-code-box-line" aria-hidden="true" />
              <h3>尚未登记 Python 执行器</h3>
              <p>系统目前没有统一的 Python 工具健康与配置接口，因此没有可操作的工具开关。</p>
            </div>
            <template v-if="impacts.length"
              ><h3>
                影响的功能 <small>{{ impacts.length }} 项</small>
              </h3>
              <p class="scope-note">
                {{
                  resource === 'local'
                    ? '这些功能依赖或可选择此执行器；状态需按各功能配置检查。'
                    : '以下功能支持从此资源目录选择执行能力，实际选用以对话为准。'
                }}
              </p>
              <div class="impact-list">
                <button v-for="item in impacts" :key="item.id" @click="openImpact(item.id)">
                  <i :class="item.icon" aria-hidden="true" />{{ item.name
                  }}<i class="ri-arrow-right-s-line" aria-hidden="true" />
                </button></div
            ></template>
          </div>
        </template>
      </section>
    </div>
  </section>
</template>
<style scoped>
.system-ai {
  min-width: 0;
}
.ai-heading,
.detail-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 18px;
}
.ai-heading {
  margin: 24px 0;
}
.eyebrow {
  font-size: 11px;
  font-weight: 600;
  color: var(--wao-primary-text);
}
h2 {
  font-size: 19px;
  margin: 7px 0;
}
h3 {
  font-size: 14px;
  margin: 20px 0 12px;
}
p {
  font-size: 13px;
  color: var(--wao-text-weak);
  line-height: 1.7;
  margin: 7px 0;
}
button,
a,
input {
  font: inherit;
}
button {
  cursor: pointer;
  color: var(--wao-text);
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  padding: 9px 12px;
  font-size: 12px;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
button:focus-visible,
a:focus-visible {
  outline: 2px solid var(--wao-primary);
  outline-offset: 3px;
}
nav {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
}
nav button[aria-pressed='true'] {
  color: var(--wao-primary-text);
  background: var(--wao-primary-soft);
  border-color: var(--wao-primary);
}
.ai-heading nav {
  flex-shrink: 0;
}
.ai-toolbar {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.ai-toolbar label {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  padding: 8px 12px;
  background: var(--wao-bg-card);
  color: var(--wao-text-weak);
  min-width: 0;
}
.ai-toolbar input {
  width: 240px;
  min-width: 0;
  border: 0;
  outline: none;
  background: none;
  color: var(--wao-text);
  font-size: 12px;
}
.ai-columns {
  display: grid;
  grid-template-columns: 246px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}
.ai-catalog {
  min-width: 0;
}
.list-label {
  display: flex;
  justify-content: space-between;
  padding: 4px 6px 12px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.ai-card {
  width: 100%;
  display: flex;
  gap: 11px;
  text-align: left;
  padding: 15px 12px;
  margin-bottom: 9px;
  border-radius: 12px;
}
.ai-card > i {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  font-size: 20px;
  color: var(--wao-primary-text);
  background: var(--wao-primary-soft);
  border-radius: 10px;
}
.ai-card[aria-pressed='true'] {
  background: var(--wao-primary-soft);
  border-color: var(--wao-primary);
}
.ai-card span {
  min-width: 0;
}
.ai-card strong {
  display: block;
  font-size: 13px;
}
.ai-card small,
.ai-card em {
  display: block;
  margin-top: 6px;
  font-size: 11px;
  color: var(--wao-text-weak);
  font-style: normal;
  line-height: 1.6;
}
.ai-detail {
  min-width: 0;
  border: 1px solid var(--wao-line);
  border-radius: 15px;
  background: var(--wao-bg-card);
  overflow: hidden;
  min-height: 480px;
}
.detail-heading {
  padding: 22px 24px;
}
.detail-heading a {
  flex-shrink: 0;
  margin-top: 6px;
  color: var(--wao-primary-text);
  font-size: 12px;
  text-decoration: none;
}
.detail-tabs {
  border-bottom: 1px solid var(--wao-line);
  padding: 0 24px 12px;
}
.detail-tabs button {
  border: 0;
  background: none;
}
.detail-content {
  padding: 6px 24px 24px;
}
.ai-steps {
  list-style: none;
  padding: 0;
  display: grid;
  gap: 10px;
}
.ai-steps li {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  padding: 12px;
  background: var(--wao-bg-sub);
  border-radius: 10px;
}
.ai-steps li span {
  display: grid;
  place-items: center;
  width: 25px;
  height: 25px;
  border-radius: 8px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-size: 12px;
}
.info-card {
  display: flex;
  gap: 12px;
  background: var(--wao-bg-sub);
  border-radius: 11px;
  padding: 16px;
  margin: 16px 0;
  font-size: 13px;
}
.info-card > i {
  font-size: 22px;
  color: var(--wao-primary-text);
}
small {
  font-size: 11px;
  color: var(--wao-text-weak);
  line-height: 1.7;
}
.scope-note {
  margin: 12px 24px;
  padding: 10px 12px;
  background: var(--wao-bg-sub);
  border-radius: 8px;
  font-size: 12px;
}
.detail-content > .scope-note {
  margin: 12px 0;
}
.settings-content {
  min-width: 0;
}
.settings-content :deep(.note-ai-settings-form) {
  height: 640px;
}
.empty {
  padding: 24px;
  color: var(--wao-text-weak);
  font-size: 13px;
  line-height: 1.8;
}
.empty > i {
  font-size: 28px;
  color: var(--wao-primary-text);
}
.record-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.record {
  padding: 14px 0;
  border-bottom: 1px solid var(--wao-line);
  font-size: 13px;
}
.record time {
  font-size: 11px;
  color: var(--wao-text-weak);
}
.light {
  border: 0;
  background: none;
  color: var(--wao-primary-text);
  padding: 6px 0;
}
.primary,
.primary-link {
  display: inline-block;
  background: var(--wao-primary);
  color: #fff;
  padding: 10px 14px;
  border-radius: 9px;
  font-size: 12px;
  text-decoration: none;
}
.model-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.model-grid > div {
  display: grid;
  gap: 5px;
  min-width: 0;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  padding: 12px;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.impact-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.impact-list button {
  display: flex;
  align-items: center;
  gap: 7px;
}
.accounts {
  margin-top: 20px;
}
.accounts :deep(.ai-account-panel) {
  max-width: 100%;
}
@media (max-width: 1100px) {
  .ai-columns {
    grid-template-columns: 215px minmax(0, 1fr);
    gap: 12px;
  }
  .ai-heading {
    flex-wrap: wrap;
  }
  .ai-heading h2 {
    font-size: 17px;
  }
  .settings-content :deep(.settings-body) {
    grid-template-columns: 145px minmax(0, 1fr);
  }
  .settings-content :deep(.settings-engine),
  .settings-content :deep(.settings-scroll) {
    padding-left: 16px;
    padding-right: 16px;
  }
}
@media (max-width: 900px) {
  .ai-columns {
    grid-template-columns: 1fr;
  }
  .ai-catalog {
    display: flex;
    gap: 8px;
    overflow: auto;
    align-items: stretch;
    padding: 3px;
  }
  .list-label {
    display: none;
  }
  .ai-card {
    flex: 0 0 190px;
    margin: 0;
  }
  .ai-toolbar {
    flex-wrap: wrap;
  }
  .ai-toolbar label {
    width: 100%;
    box-sizing: border-box;
  }
  .ai-toolbar input {
    width: 100%;
  }
  .settings-content :deep(.note-ai-settings-form) {
    height: auto;
    min-height: 640px;
  }
  .settings-content :deep(.settings-body) {
    grid-template-columns: 1fr;
  }
  .settings-content :deep(.settings-body > aside) {
    border-right: 0;
    border-bottom: 1px solid var(--wao-line);
    padding: 12px;
  }
  .settings-content :deep(.settings-body > aside nav) {
    display: flex;
    flex-wrap: wrap;
  }
  .settings-content :deep(.settings-body > aside .sidebar-foot),
  .settings-content :deep(.settings-body > aside .nav-label) {
    display: none;
  }
  .settings-content :deep(.settings-footer) {
    flex-wrap: wrap;
  }
  .detail-heading {
    padding: 18px 16px;
    flex-wrap: wrap;
  }
  .detail-tabs {
    padding-left: 16px;
    padding-right: 16px;
  }
  .detail-content {
    padding-left: 16px;
    padding-right: 16px;
  }
  .scope-note {
    margin-left: 16px;
    margin-right: 16px;
  }
  .ai-heading h2 {
    font-size: 16px;
  }
  .model-grid {
    grid-template-columns: 1fr;
  }
  .ai-detail {
    min-height: 400px;
  }
}
</style>
