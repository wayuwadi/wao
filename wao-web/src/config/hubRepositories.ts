/** HUB 只登记已有真实仓库；增加新仓库不改变现有路由。 */
export const hubRepositories = [
  {
    id: 'skills',
    title: 'Skill 仓库',
    icon: 'ri-sparkling-line',
    path: '/skills',
    description: '发现经过审核的工作流与技能，下载固定版本，交给你的 AI 使用。',
  },
  {
    id: 'pets',
    title: 'Pet 仓库',
    icon: 'ri-bear-smile-line',
    path: '/pets',
    description: '把自己创作的宠物带给更多人：上传图集、预览动作、分享安装指令。',
  },
] as const
