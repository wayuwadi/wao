import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'
import { useSessionStore } from '@/stores/session'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/auth/feishu/callback',
      name: 'feishu-callback',
      component: () => import('@/views/FeishuCallbackView.vue'),
      meta: { public: true },
    },
    {
      path: '/auth/wechat/callback',
      name: 'wechat-callback',
      component: () => import('@/views/WechatCallbackView.vue'),
      meta: { public: true },
    },
    {
      path: '/auth/claim',
      name: 'auth-claim',
      component: () => import('@/views/AccountClaimView.vue'),
      meta: { public: true },
    },
    // 文件下载页：公开免登录，但要人点一次并过一道人机校验才换得到一次下载。
    {
      path: '/f/:id',
      name: 'file-download',
      component: () => import('@/views/FileDownloadView.vue'),
      meta: { public: true },
    },
    // 任务分享页：公开只读，免登录。/s/t/:id 含子任务；/s/t/:id/:subId 只含该子任务。
    {
      path: '/s/t/:id/:subId?',
      name: 'share-task',
      component: () => import('@/views/ShareTaskView.vue'),
      meta: { public: true },
    },
    // 文档 / 小记分享页：公开只读，免登录。
    {
      path: '/s/d/:id',
      name: 'share-doc',
      component: () => import('@/views/ShareDocView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '', name: 'home', component: () => import('@/views/HomeView.vue'), meta: { module: 'home', tabKind: 'module' } },
        { path: 'projects', name: 'projects', component: () => import('@/views/ProjectsView.vue'), meta: { module: 'projects', tabKind: 'module' } },
        { path: 'projects/:id', name: 'project', component: () => import('@/views/ProjectDetailView.vue'), meta: { module: 'projects', tabKind: 'project' } },
        { path: 'calendar', name: 'calendar', component: () => import('@/views/CalendarView.vue'), meta: { module: 'calendar', tabKind: 'module' } },
        { path: 'todo', name: 'todo', component: () => import('@/views/TodoView.vue'), meta: { module: 'todo', tabKind: 'module' } },
        { path: 'knowledge', name: 'knowledge', component: () => import('@/views/KnowledgeView.vue'), meta: { module: 'knowledge', tabKind: 'module' } },
        { path: 'kb/:id', name: 'kb', component: () => import('@/views/KbDetailView.vue'), meta: { module: 'knowledge', tabKind: 'kb' } },
        { path: 'files', name: 'files', component: () => import('@/views/FilesView.vue'), meta: { module: 'files', tabKind: 'module' } },
        { path: 'automation', name: 'automation', component: () => import('@/views/AutomationView.vue'), meta: { module: 'automation', tabKind: 'module' } },
        { path: 'games', name: 'games', component: () => import('@/views/GamesView.vue'), meta: { module: 'games', tabKind: 'module' } },
        { path: 'dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { tabKind: 'dashboard' } },
        { path: 'media-agent', redirect: '/apps/media-agent' },
        { path: 'apps/:appId', name: 'external-app', component: () => import('@/views/ExternalAppView.vue'), meta: { tabKind: 'external' } },
        { path: 'vault', name: 'vault', component: () => import('@/views/VaultView.vue'), meta: { module: 'vault', tabKind: 'module' } },
        { path: 'settings', name: 'settings', component: () => import('@/views/SettingsView.vue'), meta: { module: 'settings', tabKind: 'module' } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  const session = useSessionStore()
  await session.initialize()
  if (!to.meta.public && !session.authed) {
    return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : undefined }
  }
  if (to.name === 'login' && session.authed) {
    return { path: (to.query.redirect as string) || '/' }
  }
  return true
})

export default router
