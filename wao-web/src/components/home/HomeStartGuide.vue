<script setup lang="ts">
import { ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import { toast } from '@/composables/toast'

const session = useSessionStore()
const collapsed = ref(false)
const key = (memberId: string) => `wao-home-start-collapsed:${memberId}`
watch(() => session.userId, (memberId) => {
  collapsed.value = false
  if (!memberId) return
  try { collapsed.value = localStorage.getItem(key(memberId)) === '1' }
  catch { /* 无法读取偏好时仍展示真实起步入口。 */ }
}, { immediate: true })
function toggle() {
  collapsed.value = !collapsed.value
  if (!session.userId) return
  try { localStorage.setItem(key(session.userId), collapsed.value ? '1' : '0') }
  catch { toast('显示已切换，但本机无法记住选择', 'ri-information-line') }
}
const starts = [
  { title: '安排今天', icon: 'ri-checkbox-circle-line', path: '/todo',
    description: '新建一项任务，填写要做的事并选择归属项目。',
    next: '保存后在待办列表跟进，完成时更新任务状态。', action: '打开待办' },
  { title: '整理资料', icon: 'ri-folder-upload-line', path: '/files',
    description: '上传一份文件或图片，把需要的资料存下来。',
    next: '上传后在文件列表查看、下载或分享。', action: '打开文件库' },
  { title: '交给 AI', icon: 'ri-sparkling-line', path: '/ai-sessions?new=1',
    description: '描述目标，选择项目和执行方式，确认后开始任务。',
    next: '在同一会话查看执行结果，并继续提问或补充要求。', action: '创建 AI 任务' },
]
</script>

<template>
  <section class="start-guide" aria-labelledby="home-start-title">
    <header>
      <div><h2 id="home-start-title">从一件小事开始</h2><p v-if="!collapsed">选一个起点，也可以收起引导，继续使用首页。</p></div>
      <button type="button" :aria-expanded="!collapsed" aria-controls="home-start-options" @click="toggle">{{ collapsed ? '展开起步引导' : '收起起步引导' }}</button>
    </header>
    <div v-if="!collapsed" id="home-start-options" class="start-options">
      <article v-for="start in starts" :key="start.path">
        <h3><i :class="start.icon" aria-hidden="true"></i>{{ start.title }}</h3>
        <p>{{ start.description }}</p>
        <p class="next-step">{{ start.next }}</p>
        <RouterLink :to="start.path">{{ start.action }}<i class="ri-arrow-right-line" aria-hidden="true"></i></RouterLink>
      </article>
    </div>
  </section>
</template>

<style scoped>
.start-guide { padding: 18px 20px; border: 1px solid var(--wao-line); border-radius: 14px; background: var(--wao-bg-card); color: var(--wao-text); }
header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
h2 { margin: 0; font-size: 16px; }
p { margin: 7px 0 0; color: var(--wao-text-weak); font-size: 12px; line-height: 1.6; }
header button { flex-shrink: 0; padding: 7px 10px; border: 1px solid var(--wao-line-control); border-radius: 8px; color: var(--wao-text-weak); background: var(--wao-bg-sub); cursor: pointer; }
.start-options { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-top: 16px; }
article { display: flex; flex-direction: column; padding: 14px; border: 1px solid var(--wao-divider); border-radius: 10px; background: var(--wao-bg-sub); }
h3 { display: flex; align-items: center; gap: 7px; margin: 0; font-size: 14px; }
h3 i { color: var(--wao-primary); }
.next-step { flex: 1; }
a { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 14px; color: var(--wao-primary); font-size: 13px; text-decoration: none; }
a:hover { text-decoration: underline; }
a:focus-visible, button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 3px; }
@media (max-width: 760px) { .start-guide { padding: 14px; } .start-options { grid-template-columns: 1fr; } }
</style>
