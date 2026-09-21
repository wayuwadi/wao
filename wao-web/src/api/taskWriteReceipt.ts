import type { Task } from '@/types'

export interface TaskWriteReceipt { status: 'COMMITTED' | 'UNCONFIRMED'; task: Task | null }
export interface TaskWriteTransport {
  lookup(taskId: string, operation: string): Promise<TaskWriteReceipt>
  write(taskId: string, operation: string, task: Task, account: string): Promise<Task>
}
interface Pending { operation: string; snapshot: Task }
const PREFIX = 'wao-task-write-operation-v1:'
export const taskWriteKey = (account: string, task: string) => `${PREFIX}${encodeURIComponent(account)}:${encodeURIComponent(task)}`
export class TaskWriteUnconfirmed extends Error {
  constructor() { super('保存结果待确认，草稿已保留；重试会先查询原操作，不会新建重复任务。') }
}

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const status = (error: unknown) => {
  const value = error as { status?: number; response?: { status?: number } }
  return value?.status ?? value?.response?.status
}

/** Persist before sending; recover the previous exact operation before applying newer input. */
export async function saveTaskWithReceipt(
  task: Task, account: string, transport: TaskWriteTransport,
  currentAccount: () => string, storage: Storage = localStorage,
): Promise<Task> {
  const assertAccount = () => {
    if (!account || account !== currentAccount()) throw new Error('登录账号已变化，原账号草稿仍保留')
  }
  assertAccount()
  const key = taskWriteKey(account, task.id)
  let pending: Pending | null
  try {
    pending = JSON.parse(storage.getItem(key) || 'null') as Pending | null
    if (pending && (!pending.operation || pending.snapshot?.id !== task.id)) throw new Error()
  } catch { throw new Error('无法读取本机保存记录，请保留草稿并检查浏览器存储') }
  const clear = (operation: string) => {
    try {
      if ((JSON.parse(storage.getItem(key) || 'null') as Pending | null)?.operation === operation) storage.removeItem(key)
    } catch { /* A retained committed operation is safe to reconcile again. */ }
  }
  const lookup = async (operation: string) => {
    assertAccount()
    const receipt = await transport.lookup(task.id, operation)
    assertAccount()
    if (receipt.status === 'COMMITTED' && receipt.task?.id === task.id) return receipt.task
    if (receipt.status !== 'UNCONFIRMED') throw new Error('服务器保存回执不完整，草稿已保留')
    return null
  }
  const submit = async (operation: Pending) => {
    assertAccount()
    try {
      const saved = await transport.write(task.id, operation.operation, operation.snapshot, account)
      assertAccount()
      if (saved?.id !== task.id) throw new TaskWriteUnconfirmed()
      return saved
    } catch (error) {
      // A definite validation/version rejection permits a corrected future operation.
      if ([400, 404, 409, 413, 422].includes(status(error) ?? 0)) { clear(operation.operation); throw error }
      if ([401, 403].includes(status(error) ?? 0) || account !== currentAccount()) throw error
      try {
        const saved = await lookup(operation.operation)
        if (saved) return saved
      } catch { /* Keep the durable operation and every current edit. */ }
      throw new TaskWriteUnconfirmed()
    }
  }
  let snapshot = clone(task)
  if (pending) {
    let previous: Task | null
    try { previous = await lookup(pending.operation) }
    catch (error) {
      if ([401, 403].includes(status(error) ?? 0) || account !== currentAccount()) throw error
      throw new TaskWriteUnconfirmed()
    }
    // This replay is safe only because the server's account-bound transaction ledger exists.
    previous ??= await submit(pending)
    clear(pending.operation)
    if (JSON.stringify(snapshot) === JSON.stringify(pending.snapshot)) return previous
    // Preserve newer local content; only advance the exact base acknowledged by this operation.
    if (snapshot.editVersion === pending.snapshot.editVersion) {
      snapshot = { ...snapshot, editVersion: previous.editVersion, subtaskVersion: previous.subtaskVersion,
        number: previous.number, createdBy: previous.createdBy, createdAt: previous.createdAt }
    }
  }
  pending = { operation: crypto.randomUUID(), snapshot }
  try { storage.setItem(key, JSON.stringify(pending)) }
  catch { throw new Error('本机无法保存操作记录，本次尚未发送；请保留输入并检查浏览器存储') }
  const saved = await submit(pending)
  clear(pending.operation)
  return saved
}
