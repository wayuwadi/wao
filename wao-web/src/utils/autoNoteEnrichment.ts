import { reactive } from 'vue'
import { fetchDoc, apiPatchDoc } from '@/api/board'
import { fetchArticleImage, startArticleImage } from '@/api/articleImages'
import { fetchPreference } from '@/api/favorites'
import { newPolishRequestId, waitForPolish } from '@/api/polishJobs'
import { fetchNotePolishVersions, saveNotePolishVersion, type NotePolishVersion } from '@/api/notePolishVersions'
import { useDataStore } from '@/stores/data'
import { ARTICLE_IMAGE_PREFERENCE_KEY, DEFAULT_ARTICLE_IMAGE_PROMPT } from '@/utils/articleImagePrompt'
import { cloneRichContent, legacyProjection, resolvedRichContent } from '@/utils/richDoc'

type Step = 'pending' | 'running' | 'done' | 'failed' | 'skipped'
export type NoteWorkflowProgress = { title: Step; image: Step; polish: Step; message?: string }
type AutoJob = {
  memberId: string; docId: string; rev: number
  titleRequestId: string; titleDone: boolean
  imageJobId?: string; imageDone: boolean
  polishRequestId?: string; polishDone?: boolean; polishEnabled?: boolean
  titleTerminalFailed?: boolean; polishTerminalFailed?: boolean; error?: string
}

const PREFIX = 'wao-note-auto-enrichment-v1:'
const COMPLETE_PREFIX = 'wao-note-enrichment-complete-v1:'
const running = new Set<string>()
const discarded = new Set<string>()
let activeMember = ''
let loadGeneration = 0
/** 当前成员已保存的小记润色版。 */
export const notePolishVersions = reactive<Record<string, NotePolishVersion>>({})
/** 小记保存后各步骤的真实进度。 */
export const noteEnrichmentProgress = reactive<Record<string, NoteWorkflowProgress>>({})
/** 卡片的阶段提示与失败原因。 */
export const noteEnrichmentStatus = reactive<Record<string, string>>({})
const keyOf = (memberId: string, docId: string) => `${PREFIX}${memberId}:${docId}`
const current = (memberId: string) => !activeMember || activeMember === memberId
const activeJob = (job: AutoJob) => current(job.memberId) && !discarded.has(keyOf(job.memberId, job.docId))

function hasUnsavedEdit(memberId: string, docId: string): boolean {
  try { return localStorage.getItem(`wao-note-edit-v1:${memberId}:${docId}`) !== null } catch { return false }
}
function save(job: AutoJob) {
  if (discarded.has(keyOf(job.memberId, job.docId))) return
  try { localStorage.setItem(keyOf(job.memberId, job.docId), JSON.stringify(job)) } catch { /* 本页继续执行。 */ }
}
function load(memberId: string, docId: string): AutoJob | null {
  try {
    const value = JSON.parse(localStorage.getItem(keyOf(memberId, docId)) || 'null') as AutoJob | null
    return value?.memberId === memberId && value?.docId === docId ? value : null
  } catch { return null }
}
function progress(job: AutoJob, stage?: 'title' | 'image' | 'polish', state?: Step) {
  if (!activeJob(job)) return
  const snapshot: NoteWorkflowProgress = noteEnrichmentProgress[job.docId] ?? {
    title: job.titleDone ? 'done' : 'pending',
    image: job.imageDone ? 'done' : 'pending',
    polish: job.polishEnabled === false ? 'skipped' : job.polishDone ? 'done' : 'pending',
  }
  if (stage && state) snapshot[stage] = state
  snapshot.message = job.error
  noteEnrichmentProgress[job.docId] = { ...snapshot }
}
function finish(job: AutoJob) {
  try { localStorage.removeItem(keyOf(job.memberId, job.docId)) } catch { /* 已完成，无需重试。 */ }
  try { localStorage.setItem(`${COMPLETE_PREFIX}${job.memberId}:${job.docId}`, job.polishEnabled === false ? 'skipped' : 'done') } catch { /* 页面内状态仍可见。 */ }
  if (!activeJob(job)) return
  noteEnrichmentStatus[job.docId] = job.polishEnabled === false
    ? '标题和配图已生成，已按选择保留原文' : '标题、配图和润色已完成'
  progress(job, 'polish', job.polishEnabled === false ? 'skipped' : 'done')
}

/** 总览图单独展示，不把图片 Markdown 交给文字润色改写。 */
export function noteTextForPolish(markdown: string): string {
  return markdown.replace(/^!\[文章总览图\]\([^\n]+\)\s*/u, '').trim()
}

async function run(job: AutoJob) {
  const key = keyOf(job.memberId, job.docId)
  if (running.has(key) || !activeJob(job)) return
  running.add(key)
  const data = useDataStore()
  let stage: 'title' | 'image' | 'polish' = 'title'
  try {
    job.error = undefined
    progress(job)
    const source = await fetchDoc(job.docId)
    if (!activeJob(job)) return
    const articleText = noteTextForPolish(resolvedRichContent(source).markdown)
    if (!articleText) throw new Error('正文为空，无法生成标题、配图和润色版')
    if (data.docPending(job.docId) || hasUnsavedEdit(job.memberId, job.docId)) {
      throw new Error('小记有未保存编辑，请保存后重试；自动结果不会覆盖草稿')
    }
    if (!job.titleDone) {
      stage = 'title'
      progress(job, stage, 'running')
      noteEnrichmentStatus[job.docId] = '正在生成标题…'
      const result = await waitForPolish(articleText.slice(0, 12_000), 'NOTE_TITLE', {
        requestId: job.titleRequestId,
        isActive: () => activeJob(job),
        onProgress: value => { if (value.status === 'FAILED') job.titleTerminalFailed = true },
      })
      const title = result.polished?.trim().slice(0, 30)
      if (!title) throw new Error('AI 未返回可用标题')
      const latest = await fetchDoc(job.docId)
      if (!activeJob(job)) return
      if (latest.rev !== job.rev || data.docPending(job.docId) || hasUnsavedEdit(job.memberId, job.docId)) {
        throw new Error('小记已被修改，自动标题未覆盖现有内容；请重试')
      }
      const saved = await apiPatchDoc(job.docId, { title, expectedRev: job.rev })
      if (!activeJob(job)) return
      data.applyRemoteDoc(saved)
      job.rev = saved.rev ?? job.rev + 1
      job.titleDone = true
      job.titleTerminalFailed = false
      save(job)
      progress(job, stage, 'done')
    }
    if (!job.imageDone) {
      stage = 'image'
      progress(job, stage, 'running')
      noteEnrichmentStatus[job.docId] = '正在生成配图…'
      if (!job.imageJobId) {
        const preference = await fetchPreference(ARTICLE_IMAGE_PREFERENCE_KEY)
        if (!activeJob(job)) return
        const image = await startArticleImage(articleText.slice(0, 60_000), preference.trim() || DEFAULT_ARTICLE_IMAGE_PROMPT)
        job.imageJobId = image.id
        save(job)
      }
      const deadline = Date.now() + 35 * 60_000
      while (Date.now() < deadline) {
        if (!activeJob(job)) return
        const result = await fetchArticleImage(job.imageJobId!)
        if (result.status === 'FAILED') {
          job.imageJobId = undefined
          throw new Error(result.message || '配图生成失败')
        }
        if (result.status === 'SUCCEEDED') {
          if (!result.imageUrl) throw new Error('生成任务没有返回图片地址')
          const latest = await fetchDoc(job.docId)
          if (!activeJob(job)) return
          if (latest.rev !== job.rev || data.docPending(job.docId)
              || hasUnsavedEdit(job.memberId, job.docId)) {
            throw new Error('小记已被修改或有未保存编辑，新图未自动覆盖；请重试')
          }
          const content = cloneRichContent(resolvedRichContent(latest))
          const imageNode = { type: 'image', attrs: { src: result.imageUrl, alt: '文章总览图', width: '50%', height: null, align: 'center' } }
          if (content.document?.type === 'doc') content.document.content = [imageNode, ...(content.document.content ?? [])]
          content.markdown = `![文章总览图](${result.imageUrl} "wao-image-size:50%xauto")\n\n${content.markdown}`
          const saved = await apiPatchDoc(job.docId, { content, blocks: legacyProjection(content), expectedRev: job.rev })
          if (!activeJob(job)) return
          data.applyRemoteDoc(saved)
          job.rev = saved.rev ?? job.rev + 1
          job.imageDone = true
          save(job)
          progress(job, stage, 'done')
          break
        }
        await new Promise(resolve => setTimeout(resolve, 1800))
      }
      if (!job.imageDone) throw new Error('配图仍在处理中，稍后可继续查询原任务')
    }
    if (job.polishEnabled !== false && !job.polishDone) {
      stage = 'polish'
      progress(job, stage, 'running')
      noteEnrichmentStatus[job.docId] = '正在润色文案…'
      const latest = await fetchDoc(job.docId)
      if (!activeJob(job)) return
      if (latest.rev !== job.rev || data.docPending(job.docId)
          || hasUnsavedEdit(job.memberId, job.docId)) {
        throw new Error('小记正文已变化，旧润色任务不会覆盖新内容；请重试')
      }
      const sourceMarkdown = resolvedRichContent(latest).markdown
      const originalText = noteTextForPolish(sourceMarkdown)
      if (originalText.length > 12_000) throw new Error('正文超过单次润色的 12000 字上限，请分段处理')
      job.polishRequestId ??= newPolishRequestId()
      save(job)
      const result = await waitForPolish(originalText, 'NOTE_BODY', {
        requestId: job.polishRequestId,
        isActive: () => activeJob(job),
        onProgress: value => { if (value.status === 'FAILED') job.polishTerminalFailed = true },
      })
      if (!result.polished?.trim()) throw new Error('AI 未返回可用润色正文')
      if (!activeJob(job)) return
      const version = await saveNotePolishVersion(job.docId, sourceMarkdown, result.polished)
      if (!activeJob(job)) return
      notePolishVersions[job.docId] = version
      job.polishDone = true
      job.polishTerminalFailed = false
      save(job)
      progress(job, stage, 'done')
    }
    if (job.titleDone && job.imageDone && (job.polishDone || job.polishEnabled === false)) finish(job)
  } catch (cause) {
    if (!activeJob(job)) return
    job.error = cause instanceof Error ? cause.message : '自动整理失败'
    save(job)
    progress(job, stage, 'failed')
    noteEnrichmentStatus[job.docId] = `自动整理未完成：${job.error}`
  } finally { running.delete(key) }
}

/** 切换成员时清除上位成员的阅读版本和进度，迟到结果不进入新成员页面。 */
export function activateNoteWorkflowMember(memberId: string) {
  if (activeMember === memberId) return
  activeMember = memberId
  loadGeneration++
  for (const id of Object.keys(notePolishVersions)) delete notePolishVersions[id]
  for (const id of Object.keys(noteEnrichmentProgress)) delete noteEnrichmentProgress[id]
  for (const id of Object.keys(noteEnrichmentStatus)) delete noteEnrichmentStatus[id]
}

/** 读取当前成员已保存的润色版，然后恢复浏览器中尚未完成的原任务。 */
export async function initializeNoteWorkflow(memberId: string) {
  activateNoteWorkflowMember(memberId)
  const generation = loadGeneration
  try {
    const versions = await fetchNotePolishVersions()
    if (activeMember === memberId && generation === loadGeneration) {
      for (const version of versions) notePolishVersions[version.docId] = version
    }
  } catch { /* 自动任务仍可继续；卡片展示原文，后续刷新重试读取。 */ }
  if (activeMember === memberId && generation === loadGeneration) resumeAutoNoteEnrichment(memberId)
}

/** 保存新小记或编辑完成后启动；标题、配图可按已有结果跳过，润色可逐篇关闭。 */
export function startAutoNoteEnrichment(memberId: string, docId: string, rev: number,
    completed: { title?: boolean; image?: boolean; polishEnabled?: boolean } = {}) {
  if (!memberId || load(memberId, docId)) return
  activateNoteWorkflowMember(memberId)
  discarded.delete(keyOf(memberId, docId))
  try { localStorage.removeItem(`${COMPLETE_PREFIX}${memberId}:${docId}`) } catch { /* 非关键状态。 */ }
  delete noteEnrichmentProgress[docId]
  const job: AutoJob = {
    memberId, docId, rev, titleRequestId: newPolishRequestId(), titleDone: !!completed.title,
    imageDone: !!completed.image, polishEnabled: completed.polishEnabled !== false,
  }
  save(job)
  progress(job)
  noteEnrichmentStatus[docId] = '已保存，等待自动整理…'
  void run(job)
}

/** 页面刷新后继续读取已有任务，不重复提交生成请求。 */
export function resumeAutoNoteEnrichment(memberId: string) {
  if (!memberId) return
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)
      if (key?.startsWith(`${COMPLETE_PREFIX}${memberId}:`)) {
        const docId = key.slice(`${COMPLETE_PREFIX}${memberId}:`.length)
        if (!noteEnrichmentProgress[docId]) noteEnrichmentProgress[docId] = {
          title: 'done', image: 'done', polish: localStorage.getItem(key) === 'done' ? 'done' : 'skipped',
        }
      }
      if (!key?.startsWith(`${PREFIX}${memberId}:`)) continue
      const job = load(memberId, key.slice(`${PREFIX}${memberId}:`.length))
      if (job) { progress(job); void run(job) }
    }
  } catch { /* 浏览器拒绝本地存储时，已发起的当前任务仍会继续。 */ }
}

/** 只重试未完成的步骤；网络中断继续用原请求，终态失败才创建新 AI 请求。 */
export async function retryAutoNoteEnrichment(memberId: string, docId: string) {
  const job = load(memberId, docId)
  if (!job) return
  try {
    const latest = await fetchDoc(docId)
    if (!activeJob(job)) return
    if (latest.rev !== undefined && latest.rev !== job.rev) {
      job.rev = latest.rev
      if (!job.titleDone) job.titleRequestId = newPolishRequestId()
      if (!job.imageDone) job.imageJobId = undefined
      if (!job.polishDone) job.polishRequestId = newPolishRequestId()
    }
  } catch {
    noteEnrichmentStatus[docId] = '读取小记失败，保存的内容未变；请再次重试'
    return
  }
  if (!job.titleDone && (job.titleTerminalFailed || job.error?.includes('已被修改'))) job.titleRequestId = newPolishRequestId()
  if (!job.polishDone && (job.polishTerminalFailed || job.error?.includes('已变化'))) job.polishRequestId = newPolishRequestId()
  job.error = undefined
  save(job)
  progress(job, !job.titleDone ? 'title' : !job.imageDone ? 'image' : 'polish', 'pending')
  void run(job)
}

export function canRetryAutoNoteEnrichment(memberId: string, docId: string) {
  return !!load(memberId, docId)
}

/** 删除小记后取消本页仍在等待的自动结果，避免刷新后重试已删除文档。 */
export function discardAutoNoteEnrichment(memberId: string, docId: string) {
  discarded.add(keyOf(memberId, docId))
  try {
    localStorage.removeItem(keyOf(memberId, docId))
    localStorage.removeItem(`${COMPLETE_PREFIX}${memberId}:${docId}`)
  } catch { /* 页面状态仍清除。 */ }
  delete noteEnrichmentProgress[docId]
  delete noteEnrichmentStatus[docId]
  delete notePolishVersions[docId]
}
