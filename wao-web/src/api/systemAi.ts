import { fetchAiCapabilities, fetchAssistantConversations, type AiCapability } from './assistant'
import { fetchCodexModels, type CodexModelCatalog } from './codexModels'
import { fetchCompanion } from './noteAiCompanion'
import { fetchDiscoveryCurrent } from './noteDiscoveries'
import { fetchTextPolishWorkspace } from './textPolish'
import { fetchTianrenAiStatus, fetchTianrenVisual, fetchTodayTianrenReading } from './tianren'
import type { SystemAiFeature } from '@/utils/systemAiCatalog'

export interface AiReadState<T> {
  value: T | null
  error: string
}
async function read<T>(request: Promise<T>): Promise<AiReadState<T>> {
  try {
    return { value: await request, error: '' }
  } catch {
    return { value: null, error: '读取失败，请重试；尚不能判断运行状态。' }
  }
}
export async function fetchSystemAiResources(): Promise<{
  capabilities: AiReadState<AiCapability[]>
  models: AiReadState<CodexModelCatalog>
}> {
  const [capabilities, models] = await Promise.all([
    read(fetchAiCapabilities()),
    read(fetchCodexModels()),
  ])
  return { capabilities, models }
}
export interface SystemAiRecord {
  title: string
  detail: string
  at?: string
}
function stateLabel(value: string) {
  return (
    (
      {
        QUEUED: '等待执行',
        RUNNING: '执行中',
        SUCCEEDED: '已成功',
        FAILED: '失败',
        DISABLED: '已关闭',
        NOT_GENERATED: '尚未生成',
        BRIEF_FAILED: '摘要生成失败',
        DEFERRED: '暂时延后',
        READY: '已就绪',
        PUBLISHED: '已生成发现',
        WAITING: '等待处理',
        PAUSED: '已暂停',
        IDLE: '暂无待执行任务',
        secure_queued: '等待执行',
        provider_running: '执行中',
        succeeded: '已成功',
        failed: '失败',
        running: '执行中',
        queued: '等待执行',
      } as Record<string, string>
    )[value] || value
  )
}
export interface SystemAiActivity {
  summary: string
  items: SystemAiRecord[]
  note: string
}
/** 只读取已有成员接口；访问此页不会起卦、触发生成或提交 AI 任务。 */
export async function fetchSystemAiActivity(feature: SystemAiFeature): Promise<SystemAiActivity> {
  switch (feature.group) {
    case 'notes':
    case 'companion': {
      if (feature.id === 'doc-image')
        return {
          summary: '文档配图记录按文档保存',
          items: [],
          note: '打开原文档查看对应配图任务；当前没有跨文档配图日志接口。',
        }
      const value = await fetchCompanion()
      return {
        summary: value.message,
        items: [
          {
            title: '本人小记处理队列',
            detail: `等待 ${value.queued} · 执行中 ${value.running} · 未读成果 ${value.unread}`,
          },
          { title: '成果采用方式', detail: value.mode === 'AUTO' ? '自动采用' : '先看建议' },
        ],
        note: '以上为小记整体队列，包含不同处理阶段；单条尝试与问答记录在对应小记的伴读中查看。',
      }
    }
    case 'discovery': {
      const value = await fetchDiscoveryCurrent()
      return {
        summary: '本人顺手发现',
        items: [
          { title: '最近执行状态', detail: stateLabel(value.automationState) },
          ...(value.lastOutcome
            ? [{ title: '最近处理结果', detail: stateLabel(value.lastOutcome) }]
            : []),
        ],
        note: '发现内容与原文依据在小记查看；此处仅展示真实执行状态。',
      }
    }
    case 'tianren': {
      const [status, reading] = await Promise.all([
        fetchTianrenAiStatus(),
        fetchTodayTianrenReading(),
      ])
      const items: SystemAiRecord[] = [
        {
          title: '解读执行状态',
          detail: status.runStatus ? stateLabel(status.runStatus) : status.message,
        },
      ]
      if (reading) {
        items.push({ title: '今天的卦象', detail: reading.primary.name, at: reading.createdAt })
        if (feature.id === 'tianren-image') {
          const visual = await fetchTianrenVisual(reading.id)
          items.push({
            title: '本次配图',
            detail: `${stateLabel(visual.state)} · ${visual.message}`,
          })
        }
      }
      return {
        summary: reading ? '今天的卦象记录' : '今天尚未起卦',
        items,
        note: '历史解读与完整追问保留在天人一卦；查看状态不会自动起卦。',
      }
    }
    case 'polish': {
      const value = await fetchTextPolishWorkspace()
      return {
        summary: `已读取 ${value.records.length} 条本人润色记录`,
        items: value.records.map((item) => ({
          title: '润色完成',
          detail: '原文、润色方法与结果保留在工作台',
          at: item.createdAt,
        })),
        note: '这里不展示原文内容；文档选区任务的结果保留在原编辑器。',
      }
    }
    case 'assistant': {
      const value = await fetchAssistantConversations()
      return {
        summary: `已读取 ${value.length} 条本人问答会话`,
        items: value.map((item) => ({ title: item.title, detail: '最近更新', at: item.updatedAt })),
        note: '会话记录不代表每次调用成功；完整消息与选择的来源在页面 AI 助手查看。',
      }
    }
  }
}
