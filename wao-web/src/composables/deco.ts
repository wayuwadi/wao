import { taskExecutionLabel } from '@/utils/taskSchedule'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { dueInfo, priorityMeta, statusMeta } from '@/utils/domain'
import type { Project, Task } from '@/types'
import { projectCompletion, projectCompletionLabel } from '@/utils/projectCompletion'

/** 任务 / 项目的展示装饰 —— 对齐原型 decoTask / decoProj，供列表、看板、总览、抽屉复用。 */
export function useDeco() {
  const data = useDataStore()
  const session = useSessionStore()

  function decoTask(t: Task) {
    const p = data.projectById(t.project)
    const pr = priorityMeta(t.pr)
    const st = statusMeta(t.status)
    const due = dueInfo(t.due, data.today)
    const owner = session.memberById(t.owner)
    const done = t.status === '已完成'
    return {
      ...t,
      done,
      unfinishedSubCount: done ? t.subs.filter(sub => !sub.done).length : 0,
      deco: done ? 'line-through' : 'none',
      // 标题同时用于项目列表、看板和「我的任务」；必须交给主题 token 决定颜色，
      // 否则内联的浅色主题黑字会覆盖深色模式样式，导致卡片标题不可见。
      titleColor: done ? 'var(--wao-text-weak)' : 'var(--wao-text)',
      checkBg: done ? '#4358D6' : '#FFFFFF',
      checkBorder: done ? '#4358D6' : '#C9CDDA',
      checkOp: done ? 1 : 0,
      projName: p?.name ?? '未关联',
      projColor: data.areaColor(p?.area ?? ''),
      prBg: pr.bg,
      prColor: pr.color,
      stBg: st.bg,
      stColor: st.color,
      dueStr: due.label,
      executionLabel: taskExecutionLabel(t, data.today),
      dueColor: due.color,
      ownerName: owner.name,
      ownerColor: owner.color,
      ownerInitial: owner.initial,
      waitBadge: t.waiting ?? '',
    }
  }

  function decoProj(p: Project) {
    const a = data.areaById(p.area)
    const st = statusMeta(p.status)
    const due = dueInfo(p.due, data.today)
    const owner = session.memberById(p.owner)
    const projectTasks = data.tasksByProject(p.id)
    const completion = projectCompletion(projectTasks)
    return {
      ...p,
      areaName: a?.name ?? '未分类',
      areaColor: a?.color ?? '#9A9DA9',
      stBg: st.bg,
      stColor: st.color,
      completion,
      completionLabel: projectCompletionLabel(projectTasks),
      dueStr: p.due ? due.label : '未排期',
      dueColor: due.color,
      ownerName: owner.name,
      ownerColor: owner.color,
      ownerInitial: owner.initial,
      memberAvs: p.members.map((m) => {
        const mm = session.memberById(m)
        return { initial: mm.initial, color: mm.color }
      }),
      initialChar: (p.name || '·').slice(0, 1),
      taskOpen: completion.open,
      taskTotal: completion.total,
      goalShort: p.goal,
    }
  }

  return { decoTask, decoProj }
}
