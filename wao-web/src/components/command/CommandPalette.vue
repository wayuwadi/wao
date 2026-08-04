<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { useTheme, type ThemeMode } from '@/composables/theme'
import { useQuickLinks } from '@/composables/quickLinks'
import { toast } from '@/composables/toast'
import { createAiPairCode, fetchAiRunners, wakeAiRunner } from '@/api/ai'
import { ALL_QUICK_LINKS } from '@/config/quickLinks'

interface Command {
  id: string
  title: string
  subtitle?: string
  icon: string
  keywords: string[]
  action: () => void | Promise<void>
}

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const router = useRouter()
const ui = useUiStore()
const theme = useTheme()
const quickLinks = useQuickLinks()

const query = ref('')
const selectedIndex = ref(0)
const listRef = ref<HTMLDivElement | null>(null)
const itemRefs = ref<Record<number, HTMLButtonElement>>({})

function close() {
  emit('update:modelValue', false)
  query.value = ''
  selectedIndex.value = 0
}

function navigate(path: string) {
  router.push(path)
  close()
}

function setTheme(mode: ThemeMode) {
  theme.setTheme(mode)
  toast(`已切换为${mode === 'light' ? '明亮' : mode === 'dark' ? '深色' : '跟随系统'}主题`, 'ri-palette-line')
  close()
}

async function pairCode() {
  try {
    const { code, expiresAt } = await createAiPairCode()
    toast(`配对码 ${code}（有效期至 ${new Date(expiresAt).toLocaleTimeString()}）`, 'ri-key-2-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '生成配对码失败', 'ri-error-warning-line')
  }
  close()
}

async function wakeRunner() {
  close()
  toast('正在唤醒所有执行电脑…', 'ri-computer-line')
  try {
    const runners = await fetchAiRunners()
    if (!runners.length) {
      toast('当前没有已注册的执行电脑', 'ri-error-warning-line')
      return
    }
    await Promise.all(runners.map((r) => wakeAiRunner(r.id)))
    toast('唤醒请求已发送', 'ri-check-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '唤醒失败', 'ri-error-warning-line')
  }
}

const THEME_LABELS: Record<ThemeMode, string> = {
  light: '明亮主题',
  dark: '深色主题',
  system: '跟随系统',
}

const commands = computed<Command[]>(() => {
  const list: Command[] = [
    // 页面导航
    { id: 'nav-home', title: 'AI 控制台', subtitle: '查看运行状态与执行电脑', icon: 'ri-pulse-line', keywords: ['ai', 'console', 'home', 'yunxing', 'zhuangtai'], action: () => navigate('/') },
    { id: 'nav-projects', title: '项目', subtitle: '所有项目与任务', icon: 'ri-folder-3-line', keywords: ['project', 'xiangmu', 'renwu'], action: () => navigate('/projects') },
    { id: 'nav-knowledge', title: '知识库', subtitle: '文档与小记', icon: 'ri-book-2-line', keywords: ['knowledge', 'wiki', 'doc', 'zhishiku', 'wendang'], action: () => navigate('/knowledge') },
    { id: 'nav-files', title: '文件', subtitle: '文件管理与下载', icon: 'ri-file-list-2-line', keywords: ['file', 'wenjian'], action: () => navigate('/files') },
    { id: 'nav-automation', title: '自动化', subtitle: '自动化任务与脚本', icon: 'ri-magic-line', keywords: ['automation', 'zidonghua'], action: () => navigate('/automation') },
    { id: 'nav-vault', title: 'Vault', subtitle: '密码与敏感信息', icon: 'ri-shield-keyhole-line', keywords: ['vault', 'mima', 'minan'], action: () => navigate('/vault') },
    { id: 'nav-settings', title: '设置', subtitle: '组织、通知与个人偏好', icon: 'ri-settings-3-line', keywords: ['settings', 'shezhi', 'peizhi'], action: () => navigate('/settings') },
    { id: 'nav-dashboard', title: '数据仪表盘', subtitle: 'AI 运行与任务完成统计', icon: 'ri-line-chart-line', keywords: ['dashboard', 'yibiaopan', 'tongji', 'shuju', 'tubiao'], action: () => navigate('/dashboard') },

    // 动作
    { id: 'action-project', title: '新建项目', subtitle: '创建一个新的项目', icon: 'ri-add-circle-line', keywords: ['new project', 'xinjian xiangmu', 'chuangjian'], action: () => { ui.openModal('project'); close() } },
    { id: 'action-task', title: '新建任务', subtitle: '创建一条新任务', icon: 'ri-task-line', keywords: ['new task', 'xinjian renwu'], action: () => { ui.openModal('task'); close() } },
    { id: 'action-note', title: '快速小记', subtitle: '打开快速笔记弹窗', icon: 'ri-sticky-note-line', keywords: ['note', 'xiaoji', 'biji'], action: () => { ui.openModal('note'); close() } },
    { id: 'action-pair', title: '生成 Runner 配对码', subtitle: '为新的执行电脑生成配对码', icon: 'ri-key-2-line', keywords: ['pair', 'peidui', 'runner'], action: pairCode },
    { id: 'action-wake', title: '唤醒执行电脑', subtitle: '向所有 Runner 发送唤醒请求', icon: 'ri-computer-line', keywords: ['wake', 'huanxing', 'runner'], action: wakeRunner },
    { id: 'action-assistant', title: '打开 AI 助手', subtitle: '智能对话与快捷创建', icon: 'ri-robot-2-line', keywords: ['assistant', 'zhushou', 'duihua', 'chat'], action: () => { ui.openAssistant?.(); close() } },

    // 主题
    { id: 'theme-light', title: '切换到明亮主题', subtitle: '固定使用浅色模式', icon: 'ri-sun-line', keywords: ['light', 'mingliang', 'qianse', 'zhuti'], action: () => setTheme('light') },
    { id: 'theme-dark', title: '切换到深色主题', subtitle: '固定使用深色模式', icon: 'ri-moon-line', keywords: ['dark', 'shense', 'yeshen', 'zhuti'], action: () => setTheme('dark') },
    { id: 'theme-system', title: '主题跟随系统', subtitle: '根据系统外观自动切换', icon: 'ri-computer-line', keywords: ['system', 'xitong', 'zhuti'], action: () => setTheme('system') },

    // 快捷链接（GitHub、PR、Issues、Actions…）
    ...quickLinks.links.value.map((link) => ({
      id: `link-${link.id}`,
      title: link.label,
      subtitle: link.subtitle,
      icon: link.icon,
      keywords: link.keywords,
      action: () => {
        window.open(link.url, '_blank', 'noopener')
        close()
      },
    })),
  ]
  return list
})

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return commands.value
  return commands.value.filter((cmd) => {
    const haystack = [cmd.title, cmd.subtitle, ...cmd.keywords].filter(Boolean).join(' ').toLowerCase()
    return haystack.includes(q)
  })
})

watch(filtered, () => {
  selectedIndex.value = 0
})

watch(() => props.modelValue, async (open) => {
  if (open) {
    query.value = ''
    selectedIndex.value = 0
    quickLinks.load()
    await nextTick()
    listRef.value?.querySelector('input')?.focus()
  }
})

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    selectedIndex.value = (selectedIndex.value + 1) % filtered.value.length
    scrollSelectedIntoView()
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    selectedIndex.value = (selectedIndex.value - 1 + filtered.value.length) % filtered.value.length
    scrollSelectedIntoView()
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const cmd = filtered.value[selectedIndex.value]
    if (cmd) cmd.action()
  } else if (e.key === 'Escape') {
    e.preventDefault()
    close()
  }
}

function scrollSelectedIntoView() {
  nextTick(() => {
    const el = itemRefs.value[selectedIndex.value]
    el?.scrollIntoView({ block: 'nearest' })
  })
}

function run(cmd: Command) {
  cmd.action()
}
</script>

<template>
  <Teleport to="body">
    <Transition name="palette">
      <div v-if="modelValue" class="palette-overlay" @click.self="close">
        <div class="palette" role="dialog" aria-label="命令面板" @keydown="onKeydown">
          <div class="palette-input-row">
            <i class="ri-command-line"></i>
            <input
              ref="inputRef"
              v-model="query"
              type="text"
              placeholder="输入命令或搜索页面…"
              aria-autocomplete="list"
              autocomplete="off"
              spellcheck="false"
            />
            <kbd class="shortcut-hint">Esc</kbd>
          </div>

          <div v-if="filtered.length === 0" class="palette-empty">
            <i class="ri-ghost-line"></i>
            <span>没有找到匹配的命令</span>
          </div>

          <div v-else ref="listRef" class="palette-list">
            <button
              v-for="(cmd, i) in filtered"
              :key="cmd.id"
              :ref="(el) => { if (el) itemRefs[i] = el as HTMLButtonElement }"
              class="palette-item"
              :class="{ selected: i === selectedIndex }"
              @mouseenter="selectedIndex = i"
              @click="run(cmd)"
            >
              <i :class="cmd.icon"></i>
              <span class="palette-item-text">
                <span class="palette-item-title">{{ cmd.title }}</span>
                <span v-if="cmd.subtitle" class="palette-item-subtitle">{{ cmd.subtitle }}</span>
              </span>
            </button>
          </div>

          <div class="palette-foot">
            <span><kbd>↑</kbd><kbd>↓</kbd>选择</span>
            <span><kbd>↵</kbd>执行</span>
            <span><kbd>Esc</kbd>关闭</span>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.palette-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 120px;
  background: rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(2px);
}
.palette {
  width: min(560px, calc(100vw - 32px));
  max-height: min(560px, calc(100vh - 160px));
  display: flex;
  flex-direction: column;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-modal);
  box-shadow: var(--wao-shadow-modal);
  overflow: hidden;
}
.palette-input-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--wao-line);
}
.palette-input-row > i {
  font-size: 18px;
  color: var(--wao-text-weak);
}
.palette-input-row input {
  flex: 1;
  min-width: 0;
  border: none;
  background: transparent;
  color: var(--wao-text);
  font-size: 15px;
  outline: none;
}
.palette-input-row input::placeholder {
  color: var(--wao-text-faint);
}
.shortcut-hint {
  padding: 3px 7px;
  border: 1px solid var(--wao-line-strong);
  border-radius: 5px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
  font-size: 11px;
  font-family: var(--wao-font-mono);
}
.palette-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 48px 20px;
  color: var(--wao-text-weak);
  font-size: 13px;
}
.palette-empty i {
  font-size: 32px;
  color: var(--wao-text-faint);
}
.palette-list {
  flex: 1;
  overflow-y: auto;
  padding: 6px;
}
.palette-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 9px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--wao-text);
  font-size: 13.5px;
  text-align: left;
  cursor: pointer;
}
.palette-item:hover,
.palette-item.selected {
  background: var(--wao-bg-hover);
}
.palette-item i {
  width: 22px;
  text-align: center;
  font-size: 17px;
  color: var(--wao-text-3);
}
.palette-item-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.palette-item-title {
  font-weight: 500;
}
.palette-item-subtitle {
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.palette-foot {
  display: flex;
  gap: 16px;
  padding: 10px 16px;
  border-top: 1px solid var(--wao-line);
  color: var(--wao-text-weak);
  font-size: 11px;
}
.palette-foot span {
  display: flex;
  align-items: center;
  gap: 5px;
}
.palette-foot kbd {
  padding: 2px 5px;
  border: 1px solid var(--wao-line-strong);
  border-radius: 4px;
  background: var(--wao-bg-sub);
  font-family: var(--wao-font-mono);
  font-size: 10px;
}

.palette-enter-active,
.palette-leave-active {
  transition: opacity 0.15s ease;
}
.palette-enter-from,
.palette-leave-to {
  opacity: 0;
}
.palette-enter-active .palette,
.palette-leave-active .palette {
  transition: transform 0.15s ease, opacity 0.15s ease;
}
.palette-enter-from .palette,
.palette-leave-to .palette {
  opacity: 0;
  transform: scale(0.98) translateY(-6px);
}

@media (max-width: 760px) {
  .palette-overlay {
    align-items: flex-end;
    padding: 0;
    background: rgba(0, 0, 0, 0.45);
  }
  .palette {
    width: 100%;
    max-height: 80vh;
    border-radius: 16px 16px 0 0;
  }
}
</style>
