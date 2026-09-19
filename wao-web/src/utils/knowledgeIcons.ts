export const knowledgeIcons = [
  { value: 'wao-kb-icon-notebook', label: '笔记' },
  { value: 'wao-kb-icon-growth', label: '成长' },
  { value: 'wao-kb-icon-documents', label: '文档' },
  { value: 'wao-kb-icon-ideas', label: '想法' },
  { value: 'wao-kb-icon-target', label: '目标' },
  { value: 'wao-kb-icon-work', label: '工作' },
  { value: 'wao-kb-icon-pictures', label: '图片' },
  { value: 'wao-kb-icon-messages', label: '交流' },
  { value: 'wao-kb-icon-reading', label: '阅读' },
  { value: 'wao-kb-icon-location', label: '地点' },
  { value: 'wao-kb-icon-team', label: '团队' },
  { value: 'wao-kb-icon-calendar', label: '日历' },
  { value: 'wao-kb-icon-code', label: '代码' },
  { value: 'wao-kb-icon-discussion', label: '讨论' },
  { value: 'wao-kb-icon-checklist', label: '清单' },
  { value: 'wao-kb-icon-books', label: '书籍' },
  { value: 'wao-kb-icon-folder', label: '文件夹' },
  { value: 'wao-kb-icon-writing', label: '写作' },
] as const

export const isColoredKnowledgeIcon = (icon: string) => knowledgeIcons.some(item => item.value === icon)
