import { computed, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { todayIso } from '@/utils/time'

/** Explicit, member-scoped batch action; never rewrites a date that is already set. */
export function useTaskDeadlineDefaults() {
  const data = useDataStore(), session = useSessionStore()
  const busy = ref(false), result = ref('')
  const failures = ref<Array<{ id:string; title:string; error:string }>>([])
  watch(() => session.userId, () => { result.value = ''; failures.value = [] }, { flush:'sync' })
  const missingCount = computed(() => data.tasks.reduce((sum, task) => sum + (task.due ? 0 : 1) + task.subs.filter(sub => !sub.due).length, 0))
  async function fill() {
    if (busy.value || data.taskLoadState !== 'loaded') return
    const member = session.userId, today = todayIso()
    const ids = data.tasks.filter(task => !task.due || task.subs.some(sub => !sub.due)).map(task => task.id)
    busy.value = true; result.value = '正在补齐截止日期…'; failures.value = []
    let saved = 0, failed = 0
    function fail(id:string) {
      failed++
      failures.value.push({ id, title:data.taskById(id)?.title || '未命名任务', error:data.taskSaveError(id) || '保存失败' })
    }
    try {
      for (const id of ids) {
        if (member !== session.userId) return
        try {
          await data.flushTask(id)
          if (member !== session.userId) return
          if (data.taskSaveState(id) === 'error') { fail(id); continue }
          const task = data.taskById(id)
          if (!task) continue
          const count = (task.due ? 0 : 1) + task.subs.filter(sub => !sub.due).length
          if (!count) continue
          data.patchTask(id, {
            ...(!task.due ? { due:task.endAt?.slice(0,10) || today } : {}),
            ...(task.subs.some(sub => !sub.due) ? { subs:task.subs.map(sub => sub.due ? sub : { ...sub, due:today }) } : {}),
          })
          await data.flushTask(id)
          if (member !== session.userId) return
          if (data.taskSaveState(id) === 'saved') saved += count
          else fail(id)
        } catch { fail(id) }
        result.value = `已保存 ${saved} 项截止日期${failed ? `，${failed} 个任务保存失败，请查看保存问题` : ''}`
      }
      result.value = `已补齐 ${saved} 项截止日期${failed ? `；${failed} 个任务未保存，请在任务详情处理保存问题` : '，已有日期保持不变'}`
    } finally { busy.value = false }
  }
  return { busy, result, failures, missingCount, fill }
}
