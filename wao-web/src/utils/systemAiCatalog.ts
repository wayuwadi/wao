export type SystemAiType = 'AI' | 'Python' | 'AI + 工具'
export type SystemAiSettingsGroup =
  'notes' | 'tianren' | 'companion' | 'discovery' | 'polish' | 'assistant'
export interface SystemAiFeature {
  id: string
  name: string
  summary: string
  icon: string
  type: SystemAiType
  source: string
  path: string
  group: SystemAiSettingsGroup
  steps: string[]
  scope: string
}

/** 只登记仍有真实浏览器入口及服务的能力；类型预留不等于已经接入执行器。 */
export const systemAiFeatures: SystemAiFeature[] = [
  {
    id: 'note-polish',
    name: '小记整理',
    summary: '整理标题、正文与标签',
    icon: 'ri-quill-pen-line',
    type: 'AI',
    source: '小记',
    path: '/notes',
    group: 'notes',
    steps: ['保留原稿', '整理标题与正文', '完成后直接更新正文'],
    scope: '本人小记；手动内容与历史原稿保留',
  },
  {
    id: 'note-image',
    name: '小记配图',
    summary: '根据当前内容生成总览图',
    icon: 'ri-image-line',
    type: 'AI',
    source: '小记',
    path: '/notes',
    group: 'notes',
    steps: ['读取本次内容', '生成与原文对应的图片', '保存配图与原稿关联'],
    scope: '本人小记；开启后只影响之后的处理',
  },
  {
    id: 'doc-image',
    name: '文档配图',
    summary: '编辑器中的文章总览图',
    icon: 'ri-file-image-line',
    type: 'AI',
    source: '文档编辑器',
    path: '/knowledge',
    group: 'notes',
    steps: ['选定当前文档', '手动请求总览图', '确认后插入文档'],
    scope: '当前有权编辑的文档；配图提示词与小记共用',
  },
  {
    id: 'companion',
    name: '小记伴读',
    summary: '阅读、问答与结果对照',
    icon: 'ri-chat-smile-2-line',
    type: 'AI',
    source: '小记 · AI 伴读',
    path: '/notes',
    group: 'companion',
    steps: ['选择本人可读的小记', '基于来源回答', '阅读原稿与整理结果对照'],
    scope: '本人小记及明确选择的来源；不自动发布文章',
  },
  {
    id: 'discovery',
    name: '顺手发现',
    summary: '从本人小记寻找有依据的联系',
    icon: 'ri-lightbulb-flash-line',
    type: 'AI',
    source: '小记 · 顺手发现',
    path: '/notes',
    group: 'discovery',
    steps: ['索引本人小记', '在后台分析已有内容', '展示原文依据与发现'],
    scope: '本人小记；暂停保留已有发现和收藏',
  },
  {
    id: 'tianren',
    name: '卦象解读',
    summary: '解读、快速总结与追问',
    icon: 'ri-yin-yang-line',
    type: 'AI',
    source: '天人一卦',
    path: '/tianren',
    group: 'tianren',
    steps: ['服务端起卦', '专属上下文解读', '保存总结与追问'],
    scope: '本人卦象；专用上下文不混入其他资产',
  },
  {
    id: 'tianren-image',
    name: '卦象配图',
    summary: '表达本次解读里的变与不变',
    icon: 'ri-landscape-line',
    type: 'AI',
    source: '天人一卦',
    path: '/tianren',
    group: 'tianren',
    steps: ['读取已保存的解读', '提炼配图内容', '生成并关联当前解读'],
    scope: '本人卦象；模型及提示词与小记独立',
  },
  {
    id: 'polish',
    name: '文本润色',
    summary: '润色工作台与编辑器选区',
    icon: 'ri-magic-line',
    type: 'AI',
    source: '润色 / 文档编辑器',
    path: '/text-polish',
    group: 'polish',
    steps: ['保留输入原文', '按润色方法生成', '确认后采用结果'],
    scope: '本人工作台；选区结果确认后才替换',
  },
  {
    id: 'assistant',
    name: '页面问答',
    summary: '基于页面与可见来源回答',
    icon: 'ri-question-answer-line',
    type: 'AI',
    source: '页面 AI 助手',
    path: '/',
    group: 'assistant',
    steps: ['选择本次执行能力', '读取允许的页面与来源', '保存本人问答会话'],
    scope: '当前成员可见来源；执行能力按对话选择',
  },
]

export function filterSystemAiFeatures(query: string, type: '全部' | SystemAiType) {
  const word = query.trim().toLocaleLowerCase()
  return systemAiFeatures.filter(
    (item) =>
      (type === '全部' || item.type === type) &&
      `${item.name} ${item.summary} ${item.source} ${item.type} ${item.steps.join(' ')}`
        .toLocaleLowerCase()
        .includes(word),
  )
}

export const localAiFeatureIds = systemAiFeatures.map((item) => item.id)
/** 当前目录只证明 API 可供页面问答选择，不据此推断其他目的的授权。 */
export const apiAiFeatureIds = ['assistant']
