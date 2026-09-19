<script setup lang="ts">
import { ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import { toast } from '@/composables/toast'
import HomeParticleBackdrop from './HomeParticleBackdrop.vue'

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
  { title: '推进项目', icon: 'ri-stack-line', path: '/projects',
    description: '整理项目需求，选择要在本地电脑开发的任务。',
    next: '复制任务分享链接，在本地开发完成后回到项目跟进。', action: '打开项目' },
]
</script>

<template>
  <section class="start-guide" :class="{ 'is-collapsed': collapsed }" aria-labelledby="home-start-title">
    <header>
      <div><h2 id="home-start-title">从一件小事开始</h2><p v-if="!collapsed">选一个起点，也可以收起引导，继续使用首页。</p></div>
      <button type="button" :aria-expanded="!collapsed" aria-controls="home-start-options" @click="toggle">{{ collapsed ? '展开起步引导' : '收起起步引导' }}</button>
    </header>
    <HomeParticleBackdrop :active="!collapsed" />
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
.start-guide { position: relative; isolation: isolate; overflow: hidden; padding: 26px 24px 48px; border: 1px solid #164878; border-radius: 16px; background: radial-gradient(ellipse at 100% 100%, #20a9d4 0%, transparent 58%), linear-gradient(120deg, #061f51, #074a91); color: #fff; }
.start-guide::before { content: ''; position: absolute; inset: 0; pointer-events: none; background-image: linear-gradient(#91c9ff08 1px, transparent 1px), linear-gradient(90deg, #91c9ff08 1px, transparent 1px); background-size: 48px 48px; }
.start-guide.is-collapsed { padding: 18px 24px; }
header { position: relative; z-index: 1; display: flex; align-items: center; justify-content: space-between; gap: 12px; }
h2 { margin: 0; font-size: 26px; font-weight: 600; letter-spacing: 1px; }
.is-collapsed h2 { font-size: 16px; }
p { margin: 7px 0 0; color: #d9eaff; font-size: 12px; line-height: 1.6; }
header button { flex-shrink: 0; padding: 7px 10px; border: 1px solid #ffffff40; border-radius: 8px; color: #e1efff; background: #08295680; cursor: pointer; }
header button:hover { background: #0a386fc0; }
.start-options { position: relative; z-index: 1; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-top: 26px; }
article { display: flex; flex-direction: column; padding: 16px; border: 1px solid #bee3ff40; border-radius: 10px; background: #062954cc; }
h3 { display: flex; align-items: center; gap: 7px; margin: 0; font-size: 14px; }
h3 i { color: #91dfff; }
.next-step { flex: 1; }
a { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 14px; color: #a4e7ff; font-size: 13px; text-decoration: none; }
a:hover { text-decoration: underline; }
a:focus-visible, button:focus-visible { outline: 2px solid #fff; outline-offset: 3px; }
@media (max-width: 760px) {
  .start-guide { padding: 20px 16px 48px; }
  .start-guide.is-collapsed { padding: 14px 16px; }
  header { align-items: flex-start; flex-wrap: wrap; }
  h2 { font-size: 22px; }
  .start-options { grid-template-columns: 1fr; margin-top: 20px; }
}
</style>
