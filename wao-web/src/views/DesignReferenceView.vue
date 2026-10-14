<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import WaoLogo from '@/components/WaoLogo.vue'
import WaoCard from '@/components/ui/WaoCard.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoField from '@/components/ui/WaoField.vue'
import TodoDayLayout from '@/components/todo/TodoDayLayout.vue'
import TodoTaskChecklist from '@/components/todo/TodoTaskChecklist.vue'
import TaskDetailLayout from '@/components/task/TaskDetailLayout.vue'
import NoteReadingImages from '@/components/notes/NoteReadingImages.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import AiSettingsLayout from '@/components/ui/AiSettingsLayout.vue'
import AiSettingsToggleRow from '@/components/ui/AiSettingsToggleRow.vue'
import AiPromptEditor from '@/components/ui/AiPromptEditor.vue'
import AiSettingsSaveStatus from '@/components/ui/AiSettingsSaveStatus.vue'
import identity from '@/config/brandIdentity.json'
import { useTheme } from '@/composables/theme'

const { effective } = useTheme()
const previewTheme = ref<'system' | 'light' | 'dark'>('system')
const theme = computed(() =>
  previewTheme.value === 'system' ? effective.value : previewTheme.value,
)
const root = ref<HTMLElement>()
const sections = [
  { id: 'base', label: '基础样式', icon: 'ri-palette-line' },
  { id: 'components', label: '卡片与控件', icon: 'ri-layout-grid-line' },
  { id: 'dialogs', label: '弹窗与浮层', icon: 'ri-window-line' },
  { id: 'task-detail', label: '任务详情', icon: 'ri-list-check-3' },
  { id: 'states', label: '状态与反馈', icon: 'ri-checkbox-circle-line' },
  { id: 'engineering', label: '开发参考', icon: 'ri-code-s-slash-line' },
] as const
const active = ref<string>('base')
const todoExampleSubs = ref([{ id: 'read', text: '看清今天要做的事', done: false }, { id: 'align', text: '确认下一步安排', done: false }])
function toggleTodoExample(id: string) {
  const sub = todoExampleSubs.value.find(item => item.id === id)
  if (sub) sub.done = !sub.done
  feedback.value = '仅改变清单示例，没有保存到账号。'
}
const colors = [
  { name: '品牌识别', token: '', value: identity.primary, use: '标识与轻量签名' },
  { name: '主操作', token: '--wao-primary', use: '保存、选中与焦点' },
  { name: '轻强调', token: '--wao-primary-soft', use: '选中项与提示底色' },
  { name: '内容表面', token: '--wao-bg-card', use: '正文与卡片' },
  { name: '辅助分区', token: '--wao-bg-sub', use: '侧栏与弱分组' },
]
const tokenValues = ref<Record<string, string>>({})
async function readTokens() {
  await nextTick()
  if (!root.value) return
  const style = getComputedStyle(root.value)
  tokenValues.value = Object.fromEntries(
    colors
      .filter((item) => item.token)
      .map((item) => [item.token, style.getPropertyValue(item.token).trim()]),
  )
}
watch(theme, readTokens)
onMounted(readTokens)
const sampleTitle = ref('给一个念头留个位置')
const sampleError = ref('')
const feedback = ref('')

type State = 'loading' | 'empty' | 'draft' | 'saving' | 'saved' | 'error' | 'conflict'
const states: { id: State; label: string; title: string; description: string; icon: string }[] = [
  {
    id: 'loading',
    label: '加载中',
    title: '正在读取内容…',
    description: '保留页面结构，读取完成后再显示内容。',
    icon: 'ri-loader-4-line',
  },
  {
    id: 'empty',
    label: '空数据',
    title: '这里还没有内容。',
    description: '给一个明确的开始，不把读取失败当成空数据。',
    icon: 'ri-file-add-line',
  },
  {
    id: 'draft',
    label: '草稿',
    title: '还没写完，也没关系。',
    description: '业务页面应保留本地输入，切换设置项不丢失草稿。',
    icon: 'ri-edit-circle-line',
  },
  {
    id: 'saving',
    label: '保存中',
    title: '正在演示保存…',
    description: '防止重复提交，当前输入保持可见。',
    icon: 'ri-loader-4-line',
  },
  {
    id: 'saved',
    label: '已保存',
    title: '这一点，留下来了。',
    description: '这是状态演示；没有写入账号或服务器。',
    icon: 'ri-checkbox-circle-line',
  },
  {
    id: 'error',
    label: '失败重试',
    title: '这次还没有保存成功。',
    description: '在原位置说明原因，保留输入并提供重试。',
    icon: 'ri-error-warning-line',
  },
  {
    id: 'conflict',
    label: '版本冲突',
    title: '账号里的内容有了变化。',
    description: '业务页面需展示双方版本，由你选择；不静默覆盖草稿。',
    icon: 'ri-git-branch-line',
  },
]
const stateId = ref<State>('draft')
const state = computed(() => states.find((item) => item.id === stateId.value)!)
const timer = ref<ReturnType<typeof setTimeout>>()
function setState(id: State) {
  clearTimeout(timer.value)
  stateId.value = id
}
function retry() {
  setState('saving')
  timer.value = setTimeout(() => {
    stateId.value = 'saved'
  }, 700)
}

const dialogTypes = [
  { id: 'confirm', label: '短确认', size: '420–480px', description: '只展示需要决定的内容。' },
  {
    id: 'form',
    label: '普通表单',
    size: '640–760px',
    description: '短表单随内容增长，保存栏清楚可见。',
  },
  {
    id: 'settings',
    label: '复杂设置',
    size: '约 1000px',
    description: '分项导航、独立滚动与固定保存栏。',
  },
] as const
const dialog = ref<'confirm' | 'form' | 'settings' | null>(null)
const settingSection = ref('expression')
const settingItems = [
  { id: 'expression', label: '表达偏好', icon: 'ri-quill-pen-line' },
  { id: 'automation', label: '自动处理', icon: 'ri-magic-line' },
]
const defaultPrompt = '保留我的语气和事实，让表达更清楚，不为我补写经历。'
const prompt = ref(defaultPrompt)
const automatic = ref(true)
const formTitle = ref('留一点空白，再开始。')
const dirty = ref(false)
const saving = ref(false)
const saveText = ref('演示内容 · 未写入账号')
let saveTimer: ReturnType<typeof setTimeout> | undefined
function edit() {
  clearTimeout(saveTimer)
  saving.value = false
  dirty.value = true
  saveText.value = '演示草稿 · 仅保留在本页'
}
watch([prompt, automatic, formTitle], edit)
function saveDemo() {
  if (saving.value) return
  saving.value = true
  saveText.value = '正在演示保存…'
  saveTimer = setTimeout(() => {
    saving.value = false
    dirty.value = false
    saveText.value = '演示已保存 · 未写入账号'
  }, 700)
}
onBeforeUnmount(() => {
  clearTimeout(timer.value)
  clearTimeout(saveTimer)
})
const repository = 'https://github.com/wayuwacn/wao/blob/main/'
const detailDemoDescription = ref('先说明背景、目标与边界，再把具体执行拆成子任务。\n\n'+ '这里是长描述演示，切换分区不会清空正在输入的文字。\n'.repeat(16))
const detailDemoSubtasks = ref([
  { title: '确认目标与验收标准', done: true },
  { title: '完成第一步并留下证据', done: false },
  { title: '邀请伙伴验证结果', done: false },
])
const detailDemoComment = ref('')
</script>

<template>
  <main ref="root" class="design-reference" :data-wao-theme="theme" :style="{ colorScheme: theme }">
    <header class="reference-header">
      <div class="reference-signature">
        <WaoLogo wordmark tone="current" /><span class="reference-title">设计参考</span>
      </div>
      <div class="header-actions">
        <a href="/settings?tab=about">关于 WAO</a
        ><label class="theme-picker"
          >预览主题<select v-model="previewTheme" aria-label="预览主题">
            <option value="system">跟随系统</option>
            <option value="light">浅色</option>
            <option value="dark">深色</option>
          </select></label
        >
      </div>
    </header>
    <div class="reference-layout">
      <aside class="reference-sidebar">
        <p>界面的共同语言</p>
        <nav aria-label="设计参考分类">
          <button
            v-for="item in sections"
            :key="item.id"
            type="button"
            :aria-pressed="active === item.id"
            aria-controls="reference-content"
            @click="active = item.id"
          >
            <i :class="item.icon" aria-hidden="true"></i>{{ item.label }}
          </button>
        </nav>
        <div class="sidebar-note">
          <strong>留白线框</strong><span>浅底分区 · 浮层阴影</span><span>规范 1.0 · 2026.10</span>
        </div>
      </aside>
      <div id="reference-content" class="reference-content">
        <template v-if="active === 'base'">
          <div class="section-heading">
            <small>FOUNDATIONS</small>
            <h1>轻盈，有秩序。</h1>
            <p>蓝紫强调 · 清楚的文字 · 细线分隔 · 克制的圆角</p>
          </div>
          <section class="reference-section">
            <div class="section-title">
              <h2>颜色与层级</h2>
              <span>品牌色与操作色，各有用途</span>
            </div>
            <div class="color-grid">
              <div v-for="color in colors" :key="color.name" class="color-item">
                <div
                  class="color-swatch"
                  :style="{ background: color.token ? `var(${color.token})` : color.value }"
                ></div>
                <strong>{{ color.name }}</strong
                ><code>{{ color.token ? tokenValues[color.token] : color.value }}</code>
                <p>{{ color.use }}</p>
              </div>
            </div>
            <div class="semantic-colors">
              <span class="success">成功</span><span class="warning">待处理</span
              ><span class="danger">失败</span><span>辅助信息</span>
            </div>
          </section>
          <div class="two-columns">
            <section class="reference-section">
              <h2>文字与留白</h2>
              <span class="eyebrow">阅读优先</span>
              <h3 class="type-example">给一个念头留个位置</h3>
              <p class="body-example">随手写下来，稍后再慢慢整理。</p>
              <p>辅助信息不抢主内容，也不能淡到难读。</p>
              <p class="measurements">标题 20–24 · 正文 14 · 辅助 12–13</p>
              <div class="spacing-examples">
                <div v-for="(space, index) in [8, 16, 24, 32]" :key="space">
                  <i :style="{ width: `var(--wao-space-${index + 1})` }"></i
                  ><span>{{ space }}</span>
                </div>
              </div>
              <p>建议间距档位 · 8 / 16 / 24 / 32px</p>
            </section>
            <section class="reference-section">
              <h2>线条与轮廓</h2>
              <div class="outline-row">
                <span
                  ><i class="ri-quill-pen-line" aria-hidden="true"></i
                  ><i class="ri-checkbox-circle-line" aria-hidden="true"></i
                  ><i class="ri-settings-3-line" aria-hidden="true"></i></span
                ><small>线性图标 · 16–20px</small>
              </div>
              <WaoCard><span>内容卡片</span><small>1px · 12px 圆角</small></WaoCard
              ><WaoField label="输入与按钮"
                ><template #default="field"
                  ><input :id="field.id" readonly value="1px 细线 · 8px 圆角" /></template
              ></WaoField>
              <p>静态卡片轻线框；菜单、浮层和弹窗承担阴影。</p>
            </section>
          </div>
        </template>

        <template v-else-if="active === 'components'">
          <div class="section-heading">
            <small>COMPONENTS</small>
            <h1>轻重分明，内容先行。</h1>
            <p>线框用于独立内容，浅底用于弱分区。下面均为示例。</p>
          </div>
          <section class="reference-section">
            <h2>卡片与分区</h2>
            <div class="card-grid">
              <WaoCard padding="roomy"
                ><span class="eyebrow">随手记录 · 示例</span>
                <h3>一个念头，也值得留下。</h3>
                <p>先记下来，慢慢再说。不需要现在就把它整理完整。</p>
                <WaoButton
                  variant="ghost"
                  @click="feedback = '示例操作已触发，没有打开或修改真实小记。'"
                  >继续编辑
                  <i class="ri-arrow-right-up-line" aria-hidden="true"></i></WaoButton></WaoCard
              ><WaoCard padding="roomy"
                ><span class="eyebrow">清楚安排 · 示例</span>
                <h3>这一步，也值得开心。</h3>
                <p>用清楚的文字和轻量边界，保留对内容的关注。</p>
                <span class="example-tag success">一步步来</span></WaoCard
              ><WaoCard variant="soft" padding="roomy"
                ><span class="eyebrow">浅底说明 · 示例</span>
                <h3>歇一会儿，没关系。</h3>
                <p>说明需要被看见，也可以轻一点。不叠加强阴影。</p></WaoCard
              >
            </div>
          </section>
          <section class="reference-section">
            <h2>主清单与日常区</h2>
            <p>未完成子事项默认展开，仍在推进的任务直接可见，已完成内容按需回看。每日固定和随手记收在侧边，默认不展开，按需打开；窄屏入口横排，沿用页面滚动。以下为布局示例。</p>
            <TodoDayLayout primary-label="主清单布局示例" secondary-label="日常区布局示例" :routine-remaining="3" routine-count-state="ready" :note-count="2">
              <section aria-label="任务清单示例"><h3>今日安排 · 示例</h3><p>一件要做的事，清楚放在前面。</p><TodoTaskChecklist task-id="design-todo" :subs="todoExampleSubs" @toggle="toggleTodoExample" /><WaoButton variant="ghost" @click="feedback = '记进展与任务详情位于任务更多菜单；本示例没有修改真实任务。'">更多操作示例</WaoButton></section>
              <template #routine><WaoCard><h3>每日固定 · 示例</h3><p>早晨与晚上的日常事项。</p></WaoCard></template>
              <template #notes><WaoCard><h3>随手记 · 示例</h3><p>先看最近几条，需要时再展开；收起保留正在写的内容。</p></WaoCard></template>
            </TodoDayLayout>
          </section>
          <section class="reference-section">
            <h2>按钮层级</h2>
            <div class="button-examples">
              <WaoButton variant="primary" @click="feedback = '主操作演示，没有写入账号。'"
                >保存</WaoButton
              ><WaoButton @click="feedback = '次操作演示。'">取消</WaoButton
              ><WaoButton variant="ghost" @click="feedback = '轻操作演示。'">了解更多</WaoButton
              ><WaoButton disabled>暂不可用</WaoButton
              ><WaoButton variant="danger" @click="feedback = '危险操作外观演示，没有删除内容。'"
                >删除示例</WaoButton
              >
            </div>
            <p class="demo-feedback" role="status">
              {{ feedback || '一个操作区域通常只有一个主操作；危险动作写清楚。' }}
            </p>
          </section>
          <section class="reference-section">
            <h2>字段与校验</h2>
            <div class="two-columns">
              <WaoField label="标题示例" hint="标签始终可见，输入保留。"
                ><template #default="field"
                  ><input
                    :id="field.id"
                    v-model="sampleTitle"
                    :aria-describedby="field.describedBy" /></template></WaoField
              ><WaoField
                label="错误状态示例"
                :error="sampleError || '请输入一个标题。'"
                hint="演示输入不会保存。"
                ><template #default="field"
                  ><input
                    :id="field.id"
                    :aria-describedby="field.describedBy"
                    :aria-invalid="field.invalid"
                    placeholder="标题不能为空"
                    @input="
                      sampleError = ($event.target as HTMLInputElement).value.trim()
                        ? '这是固定的错误外观演示，输入仍保留。'
                        : ''
                    " /></template
              ></WaoField>
            </div>
          </section>
        </template>

        <template v-else-if="active === 'dialogs'">
          <div class="section-heading">
            <small>DIALOGS</small>
            <h1>把当前这一件事说清楚。</h1>
            <p>使用公共外壳，首次渲染到位。窄屏全屏，保留焦点和关闭后的返回。</p>
          </div>
          <section class="reference-section">
            <h2>按内容选择弹窗</h2>
            <div class="card-grid">
              <WaoCard v-for="item in dialogTypes" :key="item.id" padding="roomy"
                ><span class="eyebrow">{{ item.size }}</span>
                <h3>{{ item.label }}</h3>
                <p>{{ item.description }}</p>
                <WaoButton @click="dialog = item.id">查看{{ item.label }}</WaoButton></WaoCard
              >
            </div>
          </section>
          <WaoCard variant="soft"
            ><h3>一套外壳，保留各自的业务语义。</h3>
            <p>
              关闭是否保存、何时恢复默认、如何处理冲突，遵守对应功能的规则。统一外观时，输入和草稿不能被丢弃。
            </p></WaoCard
          >
          <section class="reference-section">
            <h2>浮层的边界</h2>
            <p>图片菜单使用同一套 13px 字体，名称与常用操作直接展示；精确尺寸放入展开区。长名称省略显示，完整内容通过悬浮查看。下面为图片名称示例，不写入账号。</p>
            <NoteReadingImages :images="[{ src: '/brand/wao-soft-loop-v4.svg', alt: 'image.png' }, { src: '/brand/wao-soft-loop-v4.svg', alt: '思考图（合成演示）', derived: true, sourceRev: 1, current: false, outline: '原文观察：合成记录。AI推测：待验证的关系。下一步：补充一次观察。', questions: ['哪一个条件值得单独观察？'] }]" :active="null" @question="feedback = '合成问题示例，没有打开私人会话。'" />
            <div class="popover-sample" role="group" aria-label="浮层外观示例">
              <strong>当前内容的操作</strong><span>轻量菜单使用浮层阴影</span
              ><WaoButton variant="ghost" @click="feedback = '浮层示例操作已触发。'"
                >查看说明</WaoButton
              >
            </div>
            <p role="status">{{ feedback }}</p>
          </section>
        </template>

        <template v-else-if="active === 'task-detail'">
          <div class="section-heading">
            <small>TASK DETAIL</small><h1>描述再长，也能找到下一步。</h1>
            <p>概览直接编辑描述并限制显示高度，子任务紧接其后；桌面默认展开属性与评论，描述页签用于阅读全文。</p>
          </div>
          <p class="demonstration-note">演示数据 · 下方输入只保留在本页，不写入账号。</p>
          <div class="task-detail-demo">
            <TaskDetailLayout context-key="design-reference" :description="detailDemoDescription" :subtask-count="detailDemoSubtasks.length">
              <template #heading><h2>把一个想法拆成可执行的行动</h2></template>
              <template #summary><span>进行中 · P2 · 演示成员</span><span>演示草稿 · 不写入账号</span></template>
              <template #description><WaoField v-slot="{ id }" label="描述草稿（演示）"><textarea :id="id" v-model="detailDemoDescription" class="detail-demo-input"></textarea></WaoField></template>
              <template #subtasks><h3>子任务</h3><label v-for="item in detailDemoSubtasks" :key="item.title" class="detail-demo-row"><input v-model="item.done" type="checkbox" />{{ item.title }}</label></template>
              <template #properties><p>编号 #演示 · 状态 进行中</p><p>负责人 演示成员</p><WaoField v-slot="{ id }" label="评论草稿（演示）"><textarea :id="id" v-model="detailDemoComment" class="detail-demo-input"></textarea></WaoField></template>
            </TaskDetailLayout>
          </div>
        </template>

        <template v-else-if="active === 'states'">
          <div class="section-heading">
            <small>STATES</small>
            <h1>每一步，都有回应。</h1>
            <p>用文字、图标和操作共同表达状态，输入始终受到保护。</p>
          </div>
          <div class="state-selectors">
            <WaoButton
              v-for="item in states"
              :key="item.id"
              :variant="stateId === item.id ? 'primary' : 'secondary'"
              :aria-pressed="stateId === item.id"
              @click="setState(item.id)"
              >{{ item.label }}</WaoButton
            >
          </div>
          <WaoCard class="state-stage" padding="roomy"
            ><div
              role="status"
              aria-live="polite"
              :class="['state-copy', { danger: stateId === 'error', success: stateId === 'saved' }]"
            >
              <i :class="state.icon" aria-hidden="true"></i>
              <h2>{{ state.title }}</h2>
              <p>{{ state.description }}</p>
            </div>
            <div class="state-actions">
              <WaoButton v-if="stateId === 'error'" variant="primary" @click="retry"
                >重试演示</WaoButton
              ><WaoButton
                v-else-if="stateId === 'empty'"
                variant="primary"
                @click="setState('draft')"
                >开始演示</WaoButton
              ><WaoButton v-else-if="stateId === 'draft'" variant="primary" @click="retry"
                >演示保存</WaoButton
              ><template v-else-if="stateId === 'conflict'"
                ><WaoButton variant="primary" @click="setState('draft')">演示保留草稿</WaoButton
                ><WaoButton @click="setState('saved')">演示使用账号版本</WaoButton></template
              ><WaoButton v-else-if="stateId === 'saved'" @click="setState('draft')"
                >再编辑一次</WaoButton
              >
            </div></WaoCard
          >
          <p class="demonstration-note">
            冲突操作仅切换示例状态；真实功能需提供双方内容对比和版本检查。
          </p>
        </template>

        <template v-else>
          <div class="section-heading">
            <small>ENGINEERING</small>
            <h1>同一份标准，一起维护。</h1>
            <p>已确认的风格沉淀在仓库，供不同开发者与 Agent 共同参考。</p>
          </div>
          <section class="reference-section">
            <h2>从这里开始</h2>
            <div class="reference-links">
              <a
                :href="`${repository}docs/design/wao-ui-system.md`"
                target="_blank"
                rel="noopener noreferrer"
                ><strong>界面设计系统</strong><span>通用规则、组件入口、验收与例外。</span
                ><i class="ri-external-link-line" aria-hidden="true"></i></a
              ><a href="/brand/guide.html" target="_blank" rel="noopener noreferrer"
                ><strong>品牌与素材</strong><span>标识、配色、伙伴和品牌表达。</span
                ><i class="ri-external-link-line" aria-hidden="true"></i></a
              ><a
                :href="`${repository}docs/design/settings-ui-pattern.md`"
                target="_blank"
                rel="noopener noreferrer"
                ><strong>设置规范</strong><span>分项布局、草稿保护与保存范围。</span
                ><i class="ri-external-link-line" aria-hidden="true"></i
              ></a>
            </div>
          </section>
          <section class="reference-section">
            <h2>开发顺序</h2>
            <ol class="engineering-list">
              <li>先读规范，定位已有组件和语义变量。</li>
              <li>复用卡片、按钮、字段及设置组件，保留业务保存与草稿规则。</li>
              <li>新增必要语义值时，同时维护主题、规范和这里的参考。</li>
              <li>执行增量样式检查，并验收明暗主题、窄屏、状态和键盘体验。</li>
            </ol>
            <code class="command">npm run ui:check -- --base origin/main</code>
            <p>
              检查新增的颜色字面值、圆角、阴影；不代替浏览器视觉验收。历史样式渐进迁移，必要例外逐声明记录理由。
            </p>
          </section>
          <WaoCard variant="soft"
            ><h3>参考与代码保持对应</h3>
            <p>
              颜色来自实际主题变量，卡片、按钮、字段、弹窗和设置区使用公共组件。组件变化，参考随同一版本更新。
            </p></WaoCard
          >
        </template>
        <footer class="reference-footer">
          <span>WAO Design · 规范 1.0</span
          ><span>交互演示 · 本页输入不写入账号，离开页面即清除</span>
        </footer>
      </div>
    </div>

    <SettingsDialogShell
      v-if="dialog"
      :theme="theme"
      :title="
        dialog === 'settings'
          ? '表达设置示例'
          : dialog === 'form'
            ? '记录表单示例'
            : '关闭前的选择示例'
      "
      description="仅用于设计参考，不修改任何真实设置。"
      :width="dialog === 'settings' ? '1000px' : dialog === 'form' ? '760px' : '460px'"
      :height="dialog === 'settings' ? undefined : 'auto'"
      @close="dialog = null"
    >
      <AiSettingsLayout
        v-if="dialog === 'settings'"
        v-model:active="settingSection"
        :items="settingItems"
        :scope-note="'交互演示，不连接模型目录\n不写入账号设置'"
      >
        <div class="dialog-demo-content">
          <template v-if="settingSection === 'expression'"
            ><h3>表达偏好</h3>
            <p>下面是演示内容，修改后切换项目或关闭重开，仍保留在本页。</p>
            <p>模型与思考深度按能力独立设置；正文润色默认低思考，其他能力保留各自选择。字段与保存栏沿用同一组件。</p>
            <AiPromptEditor
              v-model="prompt"
              :default-value="defaultPrompt"
              label="演示提示词"
              editor-id="design-prompt" /></template
          ><AiSettingsToggleRow
            v-else
            v-model="automatic"
            title="自动处理示例"
            description="展示现有开关组件的尺寸与层级，不执行自动处理。"
            icon="ri-magic-line"
            control-label="自动处理示例"
          />
        </div>
        <template #footer
          ><div class="demo-save-footer">
            <AiSettingsSaveStatus
              :text="saveText"
              :dirty="dirty"
              :loading="false"
              :saving="saving"
              :error="false"
              hint="演示输入仅存在本页，刷新后清除"
            />
            <div class="button-examples">
              <WaoButton @click="dialog = null">关闭</WaoButton
              ><WaoButton variant="primary" :loading="saving" :disabled="!dirty" @click="saveDemo"
                >演示保存</WaoButton
              >
            </div>
          </div></template
        >
      </AiSettingsLayout>
      <div v-else class="dialog-demo-content">
        <template v-if="dialog === 'form'"
          ><WaoField label="标题示例" hint="仅用于观察表单与草稿状态。"
            ><template #default="field"
              ><input
                :id="field.id"
                v-model="formTitle"
                :aria-describedby="field.describedBy" /></template></WaoField></template
        ><template v-else
          ><h3>还没写完，也没关系。</h3>
          <p>演示关闭后输入仍留在本页。业务弹窗应根据保存规则，清楚说明保留或放弃。</p></template
        >
      </div>
      <template v-if="dialog !== 'settings'" #footer
        ><div class="demo-save-footer">
          <AiSettingsSaveStatus
            :text="saveText"
            :dirty="dirty"
            :loading="false"
            :saving="saving"
            :error="false"
            hint="交互演示，不写入账号"
          />
          <div class="button-examples">
            <WaoButton @click="dialog = null">继续查看</WaoButton
            ><WaoButton
              v-if="dialog === 'form'"
              variant="primary"
              :loading="saving"
              :disabled="!dirty"
              @click="saveDemo"
              >演示保存</WaoButton
            ><WaoButton v-else variant="primary" @click="dialog = null">关闭示例</WaoButton>
          </div>
        </div></template
      >
    </SettingsDialogShell>
  </main>
</template>

<style scoped>
.task-detail-demo { height: 620px; display: flex; border: 1px solid var(--wao-line); border-radius: var(--wao-radius-modal); overflow: hidden; background: var(--wao-bg-card); }
.detail-demo-input { width: 100%; min-height: 180px; padding: var(--wao-space-2); resize: vertical; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text); font: inherit; }
.detail-demo-row { display: flex; align-items: center; gap: var(--wao-space-1); padding: var(--wao-space-2) 0; border-bottom: 1px solid var(--wao-line); }
.detail-demo-row input { accent-color: var(--wao-primary); }
.design-reference {
  min-height: 100dvh;
  background: var(--wao-bg-page);
  color: var(--wao-text);
  font-size: var(--wao-font-body);
  line-height: 1.6;
}
.reference-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--wao-space-2);
  padding: 20px var(--wao-space-4);
  border-bottom: 1px solid var(--wao-line);
}
.reference-signature {
  display: flex;
  align-items: center;
  gap: var(--wao-space-2);
  color: var(--wao-primary-text);
}
.reference-title {
  padding-left: var(--wao-space-2);
  border-left: 1px solid var(--wao-line);
  color: var(--wao-text);
}
.header-actions {
  display: flex;
  align-items: center;
  gap: var(--wao-space-3);
  font-size: var(--wao-font-caption);
}
.theme-picker {
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  color: var(--wao-text-3);
}
select {
  min-height: var(--wao-control-height);
  padding: 6px 8px;
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font: inherit;
}
.reference-layout {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  min-height: calc(100dvh - 81px);
}
.reference-sidebar {
  padding: var(--wao-space-3) 12px;
  background: var(--wao-bg-sub);
  border-right: 1px solid var(--wao-line);
  display: flex;
  flex-direction: column;
}
.reference-sidebar > p {
  padding: 0 12px;
  font-size: var(--wao-font-caption);
}
nav {
  display: grid;
  gap: var(--wao-space-1);
}
nav button {
  display: flex;
  gap: var(--wao-space-1);
  align-items: center;
  min-height: 44px;
  padding: 10px 12px;
  border: 0;
  border-radius: var(--wao-radius-control);
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
  text-align: left;
}
nav button[aria-pressed='true'] {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
nav button i {
  font-size: 16px;
}
.sidebar-note {
  display: grid;
  gap: 4px;
  margin: var(--wao-space-4) 12px 0;
  padding-top: var(--wao-space-3);
  border-top: 1px solid var(--wao-line);
  font-size: var(--wao-font-caption);
}
.sidebar-note span {
  color: var(--wao-text-3);
}
.reference-content {
  min-width: 0;
  width: 100%;
  max-width: 1200px;
  padding: var(--wao-space-4);
}
.section-heading {
  padding-bottom: var(--wao-space-3);
  border-bottom: 1px solid var(--wao-line);
}
.section-heading > small {
  color: var(--wao-primary-text);
  letter-spacing: 0.12em;
  font-size: 11px;
}
h1 {
  font-size: var(--wao-font-title);
  margin: 10px 0;
  font-weight: 600;
  line-height: 1.4;
}
h2 {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 var(--wao-space-2);
}
h3 {
  font-size: 16px;
  font-weight: 500;
  margin: 12px 0 10px;
  line-height: 1.5;
}
p {
  color: var(--wao-text-3);
  font-size: 13px;
  line-height: 1.8;
  margin: 0;
}
.reference-section {
  margin-top: var(--wao-space-3);
}
.section-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--wao-space-2);
}
.section-title > span,
small {
  font-size: var(--wao-font-caption);
  color: var(--wao-text-3);
}
.color-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--wao-space-2);
}
.color-swatch {
  height: 64px;
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-control);
  margin-bottom: 8px;
}
.color-item strong {
  font-size: 13px;
  font-weight: 500;
  display: block;
}
.color-item code {
  display: block;
  font: 11px/1.7 var(--wao-font-mono);
  color: var(--wao-text-3);
  overflow-wrap: anywhere;
}
.color-item p {
  font-size: var(--wao-font-caption);
}
.semantic-colors {
  display: flex;
  gap: var(--wao-space-1);
  flex-wrap: wrap;
  margin-top: var(--wao-space-2);
}
.semantic-colors span,
.example-tag {
  font-size: var(--wao-font-caption);
  border-radius: var(--wao-radius-control);
  padding: 4px 10px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-3);
}
.success {
  color: var(--wao-success-strong) !important;
}
.semantic-colors .success,
.example-tag.success {
  background: var(--wao-success-bg);
}
.warning {
  color: var(--wao-warning-strong) !important;
}
.semantic-colors .warning {
  background: var(--wao-warning-bg);
}
.danger {
  color: var(--wao-danger-strong) !important;
}
.semantic-colors .danger {
  background: var(--wao-danger-bg);
}
.two-columns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--wao-space-3);
}
.two-columns > .reference-section {
  padding-top: var(--wao-space-3);
  border-top: 1px solid var(--wao-line);
}
.eyebrow {
  font-size: var(--wao-font-caption);
  color: var(--wao-text-3);
}
.type-example {
  font-size: 20px;
  margin-top: var(--wao-space-2);
}
.body-example {
  color: var(--wao-text);
  font-size: var(--wao-font-body);
}
.measurements {
  margin-top: var(--wao-space-3);
  font-size: var(--wao-font-caption);
}
.spacing-examples {
  display: flex;
  gap: var(--wao-space-3);
  margin: var(--wao-space-2) 0;
}
.spacing-examples div {
  display: grid;
  gap: 4px;
  font-size: var(--wao-font-caption);
  color: var(--wao-text-3);
}
.spacing-examples i {
  display: block;
  height: 8px;
  background: var(--wao-primary-soft);
}
.outline-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: var(--wao-space-2) 0;
  gap: 8px;
}
.outline-row > span {
  display: flex;
  gap: var(--wao-space-2);
  color: var(--wao-primary-text);
  font-size: 20px;
}
.two-columns .wao-card {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: var(--wao-space-2);
}
.two-columns .wao-field {
  margin-bottom: var(--wao-space-2);
}
.card-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--wao-space-2);
}
.card-grid .wao-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}
.card-grid .wao-card p {
  margin-bottom: var(--wao-space-2);
}
.card-grid .wao-card .wao-button,
.card-grid .example-tag {
  margin-top: auto;
}
.button-examples {
  display: flex;
  gap: var(--wao-space-1);
  flex-wrap: wrap;
}
.demo-feedback {
  margin-top: var(--wao-space-2);
}
.popover-sample {
  display: grid;
  justify-items: start;
  gap: var(--wao-space-1);
  width: min(100%, 300px);
  padding: var(--wao-space-2);
  margin-bottom: var(--wao-space-3);
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-card);
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-pop);
}
.popover-sample span {
  font-size: var(--wao-font-caption);
  color: var(--wao-text-3);
}
.state-selectors {
  display: flex;
  gap: var(--wao-space-1);
  flex-wrap: wrap;
  margin: var(--wao-space-3) 0;
}
.state-stage {
  min-height: 280px;
  display: grid;
  align-content: center;
  justify-items: center;
  text-align: center;
  gap: var(--wao-space-2);
}
.state-copy {
  color: var(--wao-text-3);
}
.state-copy > i {
  font-size: 30px;
}
.state-copy h2 {
  margin: 12px 0 8px;
  color: var(--wao-text);
  font-size: 20px;
}
.state-copy p {
  max-width: 560px;
}
.state-actions {
  display: flex;
  gap: var(--wao-space-1);
  flex-wrap: wrap;
  justify-content: center;
}
.demonstration-note {
  margin-top: var(--wao-space-2);
}
.reference-links {
  display: grid;
  gap: var(--wao-space-2);
}
.reference-links a {
  display: grid;
  grid-template-columns: 140px minmax(0, 1fr) 18px;
  gap: var(--wao-space-2);
  padding: 16px 0;
  border-bottom: 1px solid var(--wao-line);
  color: var(--wao-text);
  text-decoration: none;
}
.reference-links a:hover strong {
  color: var(--wao-primary-text);
}
.reference-links span {
  color: var(--wao-text-3);
  font-size: 13px;
}
.engineering-list {
  padding-left: 20px;
  color: var(--wao-text-3);
  line-height: 2;
}
.command {
  display: block;
  padding: var(--wao-space-2);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-sub);
  font: 12px/1.8 var(--wao-font-mono);
  overflow-wrap: anywhere;
  margin-bottom: var(--wao-space-2);
}
.reference-section + .wao-card {
  margin-top: var(--wao-space-3);
}
.reference-footer {
  display: flex;
  gap: var(--wao-space-2);
  justify-content: space-between;
  flex-wrap: wrap;
  border-top: 1px solid var(--wao-line);
  padding-top: var(--wao-space-2);
  margin-top: var(--wao-space-4);
  font-size: var(--wao-font-caption);
  color: var(--wao-text-3);
}
.dialog-demo-content {
  padding: var(--wao-space-3);
}
.dialog-demo-content h3 {
  margin-top: 0;
}
.dialog-demo-content p {
  margin-bottom: var(--wao-space-2);
}
.demo-save-footer {
  display: flex;
  gap: var(--wao-space-2);
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
}
@media (max-width: 1000px) {
  .card-grid {
    grid-template-columns: 1fr;
  }
  .color-grid {
    gap: var(--wao-space-1);
  }
}
@media (max-width: 650px) {
  .reference-header {
    padding: var(--wao-space-2);
    flex-wrap: wrap;
  }
  .header-actions {
    width: 100%;
    justify-content: space-between;
  }
  .reference-layout {
    display: block;
  }
  .reference-sidebar {
    padding: 12px var(--wao-space-2);
    border-right: 0;
    border-bottom: 1px solid var(--wao-line);
  }
  .reference-sidebar > p,
  .sidebar-note {
    display: none;
  }
  nav {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 4px;
  }
  nav button {
    padding: 8px;
    font-size: 13px;
  }
  .reference-content {
    padding: var(--wao-space-3) var(--wao-space-2);
  }
  .two-columns {
    grid-template-columns: 1fr;
    gap: var(--wao-space-2);
  }
  .color-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--wao-space-2);
  }
  .section-title {
    display: block;
    margin-bottom: var(--wao-space-2);
  }
  .reference-links a {
    grid-template-columns: minmax(0, 1fr) 18px;
    gap: 4px;
  }
  .reference-links a span {
    grid-column: 1;
  }
  .reference-links a i {
    grid-column: 2;
    grid-row: 1;
  }
  .theme-picker select {
    font-size: 16px;
  }
  .demo-save-footer > .button-examples {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
