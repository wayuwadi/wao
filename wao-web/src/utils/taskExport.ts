import type { ImageRef, Task, TaskStep } from '@/types'

export interface TaskExportContext {
  origin: string
  projectName: (id: string) => string
  memberName: (id: string) => string
}

/** 仅生成文本，不渲染描述、不访问关联资源，也不改变任务或分享权限。 */
export function exportTasksMarkdown(tasks: readonly Task[], context: TaskExportContext): string {
  const unique = [...new Map(tasks.map((task) => [task.id, task])).values()]
  const url = (path: string) => {
    try { return new URL(path, context.origin).href } catch { return path }
  }
  const label = (text: string) => text.replace(/\r?\n/g, ' ').replace(/[\\[\]]/g, '\\$&')
  const link = (text: string, path: string) => `[${label(text)}](<${url(path).replace(/>/g, '%3E')}>)`
  const images = (items: ImageRef[] = []) => items.map((item) => `!${link(item.name || '图片', item.url)}`).join('\n')
  const steps = (items: TaskStep[]) => items.map((step) => [
    `- [${step.done ? 'x' : ' '}] ${step.text}`,
    step.id ? `  子任务 ID：${step.id}` : '',
    step.desc ? step.desc.split('\n').map((line) => `  ${line}`).join('\n') : '',
    images(step.images).split('\n').filter(Boolean).map((line) => `  ${line}`).join('\n'),
  ].filter(Boolean).join('\n')).join('\n\n')
  const section = (name: string, body: string) => body ? `### ${name}\n\n${body}` : ''
  const bodies = unique.map((task, index) => {
    const metadata: [string, string | undefined | null][] = [
      ['任务 ID', task.id], ['所属项目', context.projectName(task.project)],
      ['状态', task.status], ['优先级', task.pr],
      ['负责人', task.owner ? context.memberName(task.owner) : '未指定'],
      ['参与人', task.parts.map(context.memberName).join('、')],
      ['创建人', task.createdBy ? context.memberName(task.createdBy) : ''],
      ['创建时间', task.createdAt], ['开始日期', task.start], ['截止日期', task.due],
      ['开始时间', task.startAt], ['结束时间', task.endAt], ['完成时间', task.completedAt],
      ['等待事项', task.waiting], ['分组 ID', task.groupId],
    ]
    return [
      `## ${index + 1}. ${task.title}`,
      metadata.filter(([, value]) => value).map(([key, value]) => `- ${key}：${value}`).join('\n'),
      `- 来源：${link('任务原文', `/api/share/tasks/${encodeURIComponent(task.id)}.md`)}`,
      section('描述', task.desc), section('图片', images(task.images)),
      section('步骤', steps(task.steps)), section('子任务', steps(task.subs)),
      section('自定义属性', task.custom.map((item) => `- ${item.name}：${item.value}`).join('\n')),
      section('附件（引用，不包含文件正文）', task.files.map((file) =>
        `- ${file.id ? link(file.name, `/f/${encodeURIComponent(file.id)}`) : file.name}${file.size ? ` · ${file.size}` : ''}`).join('\n')),
      section('关联文档（引用）', task.docs.map((id) => `- ${link(id, `/api/share/docs/${encodeURIComponent(id)}.md`)}`).join('\n')),
      section('关联任务（引用）', task.linkTasks.map((item) =>
        `- ${item.rel}：${link(item.id, `/api/share/tasks/${encodeURIComponent(item.id)}.md`)}`).join('\n')),
      section('托管页（资源 ID）', (task.hostedPages ?? []).map((id) => `- ${id}`).join('\n')),
      section('评论', task.comments.map((item) => `#### ${context.memberName(item.who)} · ${item.time}\n\n${item.text}`).join('\n\n')),
      section('活动记录', task.activity.map((item) =>
        `- ${item.time}${item.by ? ` · ${context.memberName(item.by)}` : ''}：${item.text}`).join('\n')),
      section('历史复盘', (task.pdcaCycles ?? []).map((cycle) => [
        `#### ${cycle.id} · ${cycle.startedAt}`, `计划：\n${cycle.plan}`, `检查：\n${cycle.check}`,
        `行动：\n${cycle.act}`, cycle.outcome ? `结果：${cycle.outcome}` : '',
        cycle.reviewedAt ? `复盘时间：${cycle.reviewedAt}` : '',
      ].filter(Boolean).join('\n\n')).join('\n\n')),
    ].filter(Boolean).join('\n\n')
  })
  return [
    `# 任务清单（${unique.length} 项）`,
    '以下为所选任务的内容快照。包含已完成项及内部评论，请核对范围后交给本地工具；附件和关联资源仅列引用。',
    unique.map((task) => `- [${task.status === '已完成' ? 'x' : ' '}] ${task.title}（${task.id}）`).join('\n'),
    ...bodies,
  ].join('\n\n')
}
