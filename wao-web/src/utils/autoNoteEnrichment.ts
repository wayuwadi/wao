import { reactive } from 'vue'
import { fetchDoc, fetchNoteTags, apiPatchDoc } from '@/api/board'
import { fetchArticleImage, startArticleImage } from '@/api/articleImages'
import { fetchPreference } from '@/api/favorites'
import { newPolishRequestId, waitForPolish } from '@/api/polishJobs'
import { fetchNotePolishVersions, saveNotePolishVersion, type NotePolishVersion } from '@/api/notePolishVersions'
import { useDataStore } from '@/stores/data'
import type { NoteAiDefaults } from '@/stores/preferences'
import { ARTICLE_IMAGE_PREFERENCE_KEY, DEFAULT_ARTICLE_IMAGE_PROMPT } from '@/utils/articleImagePrompt'
import { cloneRichContent, legacyProjection, resolvedRichContent } from '@/utils/richDoc'
import { resolveNoteModel } from '@/utils/noteAiModel'
import { readNoteDraft } from '@/utils/noteDraftStorage'

type Step = 'pending' | 'running' | 'done' | 'failed' | 'skipped'
export type NoteWorkflowProgress = { title: Step; image: Step; polish: Step; tags: Step; message?: string }
type AutoJob = {
  memberId: string; docId: string; rev: number
  titleRequestId: string; titleDone: boolean
  titleEnabled?: boolean
  imageJobId?: string; imageRequestId?: string; imageDone: boolean
  imageEnabled?: boolean
  polishRequestId?: string; polishDone?: boolean; polishEnabled?: boolean
  tagRequestId?: string; tagsDone?: boolean; tagsEnabled?: boolean; tagTerminalFailed?: boolean; allowedTags?: string[]
  titleTerminalFailed?: boolean; polishTerminalFailed?: boolean; error?: string
}

const PREFIX = 'wao-note-auto-enrichment-v1:'
const COMPLETE_PREFIX = 'wao-note-enrichment-complete-v1:'
const MODEL_PREFERENCE_KEY = 'note-ai-model-v1'
const TITLE_PROMPT_KEY = 'note-title-prompt-v1'
const DEFAULT_TITLE_PROMPT = '标题准确概括这条小记的主题，简洁自然，方便以后搜索；只返回一个标题。'
const running = new Set<string>()
const documentWrites = new Map<string, Promise<void>>()
type ScheduledTask = { run: () => Promise<unknown>; resolve: (value?: unknown) => void; reject: (error: unknown) => void }
const stageQueues = new Map<string, { active: number; capacity: number; queue: ScheduledTask[] }>()
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
  try { return readNoteDraft(`wao-note-edit-v1:${memberId}:${docId}`) !== null }
  catch { return true /* 无法读取草稿时不能把未知当作没有编辑。 */ }
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
function progress(job: AutoJob, stage?: 'title' | 'image' | 'polish' | 'tags', state?: Step) {
  if (!activeJob(job)) return
  const snapshot: NoteWorkflowProgress = noteEnrichmentProgress[job.docId] ?? {
    title: job.titleEnabled === false ? 'skipped' : job.titleDone ? 'done' : 'pending',
    image: job.imageEnabled === false ? 'skipped' : job.imageDone ? 'done' : 'pending',
    polish: job.polishEnabled === false ? 'skipped' : job.polishDone ? 'done' : 'pending',
    tags: job.tagsEnabled !== true ? 'skipped' : job.tagsDone ? 'done' : 'pending',
  }
  if (stage && state) snapshot[stage] = state
  snapshot.message = job.error
  noteEnrichmentProgress[job.docId] = { ...snapshot }
}
function finish(job: AutoJob) {
  if (!activeJob(job)) return
  progress(job)
  const completedStages = Object.fromEntries((['title', 'image', 'polish', 'tags'] as const)
    .map(stage => [stage, noteEnrichmentProgress[job.docId]?.[stage] === 'done' ? 'done' : 'skipped']))
  try { localStorage.removeItem(keyOf(job.memberId, job.docId)) } catch { /* 已完成，无需重试。 */ }
  try { localStorage.setItem(`${COMPLETE_PREFIX}${job.memberId}:${job.docId}`, JSON.stringify(completedStages)) } catch { /* 页面内状态仍可见。 */ }
  const completed = [job.titleEnabled !== false && '标题', job.imageEnabled !== false && '配图', job.polishEnabled !== false && '润色', job.tagsEnabled === true && '标签'].filter(Boolean)
  const completedLabel = completed.length > 1 ? `${completed.slice(0, -1).join('、')}和${completed[completed.length - 1]}` : completed[0] || ''
  noteEnrichmentStatus[job.docId] = job.polishEnabled === false
    ? `${completedLabel}已生成，已按选择保留原文`
    : `${completedLabel}已完成`
}

/** 总览图单独展示，不把图片 Markdown 交给文字润色改写。 */
export function noteTextForPolish(markdown: string): string {
  return markdown.replace(/^!\[文章总览图\]\([^\n]+\)\s*/u, '').trim()
}

function readExecutionSettings(value: string): Pick<NoteAiDefaults, 'executionMode' | 'maxConcurrent'> {
  try {
    const parsed = JSON.parse(value) as Partial<NoteAiDefaults>
    return {
      executionMode: parsed.executionMode === 'serial' ? 'serial' : 'parallel',
      maxConcurrent: [1, 2, 3, 4, 5].includes(parsed.maxConcurrent as number)
        ? parsed.maxConcurrent as NoteAiDefaults['maxConcurrent'] : 3,
    }
  } catch { return { executionMode: 'parallel', maxConcurrent: 3 } }
}

function drainStageQueue(memberId: string) {
  const scheduler = stageQueues.get(memberId)
  if (!scheduler) return
  while (scheduler.active < scheduler.capacity && scheduler.queue.length) {
    const task = scheduler.queue.shift()!
    scheduler.active++
    void task.run().then(task.resolve, task.reject).finally(() => {
      scheduler.active--
      drainStageQueue(memberId)
    })
  }
}

function scheduleStage<T>(memberId: string, settings: Pick<NoteAiDefaults, 'executionMode' | 'maxConcurrent'>,
    task: () => Promise<T>): Promise<T> {
  let scheduler = stageQueues.get(memberId)
  if (!scheduler) {
    scheduler = { active: 0, capacity: 1, queue: [] }
    stageQueues.set(memberId, scheduler)
  }
  scheduler.capacity = settings.executionMode === 'serial' ? 1 : settings.maxConcurrent
  const result = new Promise<T>((resolve, reject) => {
    scheduler!.queue.push({ run: task, resolve: value => resolve(value as T), reject })
  })
  drainStageQueue(memberId)
  return result
}

async function serializeDocumentWrite<T>(job: AutoJob, task: () => Promise<T>): Promise<T> {
  const key = keyOf(job.memberId, job.docId)
  const previous = documentWrites.get(key) ?? Promise.resolve()
  let release!: () => void
  const lock = new Promise<void>(resolve => { release = resolve })
  const queued = previous.then(() => lock)
  documentWrites.set(key, queued)
  await previous
  try { return await task() }
  finally {
    release()
    if (documentWrites.get(key) === queued) documentWrites.delete(key)
  }
}

async function run(job: AutoJob) {
  const key = keyOf(job.memberId, job.docId)
  if (running.has(key) || !activeJob(job)) return
  running.add(key)
  const data = useDataStore()
  let stage: 'title' | 'image' | 'polish' | 'tags' = 'title'
  const stageErrors: string[] = []
  let polishOutput: string | undefined
  try {
    job.error = undefined
    progress(job)
    const source = await fetchDoc(job.docId)
    if (!activeJob(job)) return
    const articleText = noteTextForPolish(resolvedRichContent(source).markdown)
    const [modelValue, titlePromptValue, imagePromptValue, polishWorkspace, executionValue] = await Promise.all([
      fetchPreference(MODEL_PREFERENCE_KEY), fetchPreference(TITLE_PROMPT_KEY),
      fetchPreference(ARTICLE_IMAGE_PREFERENCE_KEY), fetchPreference('text-polish.prompt.v1'),
      fetchPreference('note-ai-defaults-v1'),
    ])
    const model = resolveNoteModel(modelValue)
    const titlePrompt = titlePromptValue.trim() || DEFAULT_TITLE_PROMPT
    const imagePrompt = imagePromptValue.trim() || DEFAULT_ARTICLE_IMAGE_PROMPT
    const polishPrompt = polishWorkspace
    const execution = readExecutionSettings(executionValue)
    if (!articleText) throw new Error('正文为空，无法自动整理')
    if (data.docPending(job.docId) || hasUnsavedEdit(job.memberId, job.docId)) {
      throw new Error('小记有未保存编辑，请保存后重试；自动结果不会覆盖草稿')
    }
    let conflicted = false
    const runTitle = async () => {
      if (!activeJob(job)) return
      progress(job, 'title', 'running')
      noteEnrichmentStatus[job.docId] = '正在生成标题…'
      try {
        const result = await waitForPolish(articleText.slice(0, 12_000), 'NOTE_TITLE', {
          model, titlePrompt,
          requestId: job.titleRequestId,
          isActive: () => activeJob(job),
          onProgress: value => { if (value.status === 'FAILED') job.titleTerminalFailed = true },
        })
        const title = result.polished?.trim().slice(0, 30)
        if (!title) throw new Error('AI 未返回可用标题')
        await serializeDocumentWrite(job, async () => {
          if (conflicted || !activeJob(job)) return
          const latest = await fetchDoc(job.docId)
          if (!activeJob(job)) return
          if (latest.rev !== job.rev || data.docPending(job.docId) || hasUnsavedEdit(job.memberId, job.docId)) {
            throw new Error('小记已被修改，自动标题未覆盖现有内容；请重试')
          }
          const saved = await apiPatchDoc(job.docId, { title, expectedRev: job.rev })
          if (!activeJob(job)) return
          data.applyRemoteDoc(saved)
          job.rev = saved.rev ?? job.rev + 1
        })
        if (!activeJob(job) || conflicted) return
        job.titleDone = true
        job.titleTerminalFailed = false
        save(job)
        progress(job, 'title', 'done')
      } catch (cause) {
        if (!activeJob(job)) return
        job.titleTerminalFailed = true
        const message = cause instanceof Error ? cause.message : '标题生成失败'
        if (/已被修改|有未保存编辑/.test(message)) conflicted = true
        stageErrors.push(`标题：${message}`)
        job.error = stageErrors.join('；')
        save(job)
        progress(job, 'title', 'failed')
      }
    }
    const runTags = async () => {
      if (!activeJob(job)) return
      stage = 'tags'
      progress(job, 'tags', 'running')
      noteEnrichmentStatus[job.docId] = '正在推荐已有标签…'
      try {
        job.tagRequestId ??= newPolishRequestId()
        save(job)
        const result = await waitForPolish(articleText.slice(0, 12_000), 'NOTE_TAGS', {
          model, allowedTags: job.allowedTags ?? [], requestId: job.tagRequestId,
          isActive: () => activeJob(job),
          onProgress: value => { if (value.status === 'FAILED') job.tagTerminalFailed = true },
        })
        const suggested = [...new Set((result.polished ?? '').split(/\r?\n/u).map(tag => tag.trim()).filter(tag => (job.allowedTags ?? []).includes(tag)))].slice(0, 3)
        if (!activeJob(job)) return
        if (suggested.length) await serializeDocumentWrite(job, async () => {
          if (conflicted || !activeJob(job)) return
          const latest = await fetchDoc(job.docId)
          if (!activeJob(job)) return
          // 标签仅依赖正文与既有标签；独立标题回写不应使推荐失效。
          const inputsChanged = latest.rev !== job.rev && (
            JSON.stringify(resolvedRichContent(latest)) !== JSON.stringify(resolvedRichContent(source))
            || JSON.stringify(latest.tags ?? []) !== JSON.stringify(source.tags ?? []))
          if (latest.rev == null || inputsChanged || data.docPending(job.docId) || hasUnsavedEdit(job.memberId, job.docId)) {
            throw new Error('小记已被修改，标签推荐未写入；请保存后重试')
          }
          const existingTags = await fetchNoteTags()
          if (!activeJob(job)) return
          if (data.docPending(job.docId) || hasUnsavedEdit(job.memberId, job.docId)) {
            throw new Error('小记有未保存编辑，标签推荐未写入；请保存后重试')
          }
          const stillExisting = suggested.filter(tag => existingTags.includes(tag))
          if (!stillExisting.length) return
          const nextTags = [...new Set([...(latest.tags ?? []), ...stillExisting])].slice(0, 20)
          const saved = await apiPatchDoc(job.docId, { tags: nextTags, expectedRev: latest.rev })
          if (!activeJob(job)) return
          data.applyRemoteDoc(saved)
          job.rev = saved.rev ?? job.rev + 1
        })
        if (!activeJob(job)) return
        job.tagsDone = true
        job.tagTerminalFailed = false
        save(job)
        progress(job, 'tags', 'done')
      } catch (cause) {
        if (!activeJob(job)) return
        const message = cause instanceof Error ? cause.message : '标签推荐失败'
        if (/已被修改|有未保存编辑/.test(message)) conflicted = true
        stageErrors.push(`标签：${message}`)
        job.error = stageErrors.join('；')
        save(job)
        progress(job, 'tags', 'failed')
      }
    }
    const runImage = async () => {
      if (!activeJob(job)) return
      progress(job, 'image', 'running')
      noteEnrichmentStatus[job.docId] = '正在生成配图…'
      try {
        const startDeadline = Date.now() + 60 * 60_000
        let retryDelay = 5_000
        while (!job.imageJobId && Date.now() < startDeadline) {
          if (!activeJob(job)) return
          job.imageRequestId ??= newPolishRequestId().replace(/-/g, '')
          save(job)
          try {
            const image = await startArticleImage(articleText.slice(0, 60_000), imagePrompt, model, job.imageRequestId)
            job.imageJobId = image.id
            save(job)
          } catch (cause) {
            const message = cause instanceof Error ? cause.message : '配图任务提交失败'
            const slotOccupied = message.includes('已有一张总览图正在生成')
            const concurrentInsert = message.includes('数据刚刚被其他请求占用')
            if (!slotOccupied && !concurrentInsert) throw cause
            noteEnrichmentStatus[job.docId] = slotOccupied
              ? '另一张总览图正在生成，空出后会自动继续…'
              : '图片任务正在由其他页面接续，稍后自动恢复…'
            await new Promise(resolve => setTimeout(resolve, retryDelay))
            retryDelay = Math.min(60_000, retryDelay * 2)
          }
        }
        if (!job.imageJobId) throw new Error('等待图片生成位置超过 1 小时，请重试')
        const deadline = Date.now() + 35 * 60_000
        while (Date.now() < deadline) {
          if (!activeJob(job)) return
          const result = await fetchArticleImage(job.imageJobId)
          if (result.status === 'FAILED') {
            job.imageJobId = undefined
            job.imageRequestId = undefined
            throw new Error(result.message || '配图生成失败')
          }
          if (result.status === 'SUCCEEDED') {
            if (!result.imageUrl) throw new Error('生成任务没有返回图片地址')
            await serializeDocumentWrite(job, async () => {
              if (conflicted || !activeJob(job)) return
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
            })
            if (!activeJob(job) || conflicted) return
            job.imageDone = true
            job.imageRequestId = undefined
            save(job)
            progress(job, 'image', 'done')
            break
          }
          await new Promise(resolve => setTimeout(resolve, 1800))
        }
        if (!job.imageDone) throw new Error('配图仍在处理中，稍后可继续查询原任务')
      } catch (cause) {
        if (!activeJob(job)) return
        const message = cause instanceof Error ? cause.message : '配图生成失败'
        if (/已被修改|有未保存编辑/.test(message)) throw cause
        stageErrors.push(`配图：${message}`)
        job.error = stageErrors.join('；')
        save(job)
        progress(job, 'image', 'failed')
      }
    }
    const runPolish = async () => {
      if (!activeJob(job)) return
      progress(job, 'polish', 'running')
      noteEnrichmentStatus[job.docId] = '正在润色文案…'
      try {
        if (articleText.length > 12_000) throw new Error('正文超过单次润色的 12000 字上限，请分段处理')
        job.polishRequestId ??= newPolishRequestId()
        save(job)
        const result = await waitForPolish(articleText, 'NOTE_BODY', {
          model, notePolishPrompt: polishPrompt,
          requestId: job.polishRequestId,
          isActive: () => activeJob(job),
          onProgress: value => { if (value.status === 'FAILED') job.polishTerminalFailed = true },
        })
        if (!result.polished?.trim()) throw new Error('AI 未返回可用润色正文')
        if (!activeJob(job)) return
        polishOutput = result.polished
        job.polishTerminalFailed = false
      } catch (cause) {
        if (!activeJob(job)) return
        job.polishTerminalFailed = true
        const message = cause instanceof Error ? cause.message : '文案润色失败'
        stageErrors.push(`润色：${message}`)
        job.error = stageErrors.join('；')
        save(job)
        progress(job, 'polish', 'failed')
      }
    }
    const stages = [
      job.titleEnabled !== false && !job.titleDone ? scheduleStage(job.memberId, execution, runTitle) : Promise.resolve(),
      job.tagsEnabled === true && !job.tagsDone ? scheduleStage(job.memberId, execution, runTags) : Promise.resolve(),
      job.imageEnabled !== false && !job.imageDone ? scheduleStage(job.memberId, execution, runImage) : Promise.resolve(),
      job.polishEnabled !== false && !job.polishDone ? scheduleStage(job.memberId, execution, runPolish) : Promise.resolve(),
    ]
    const stageResults = await Promise.allSettled(stages)
    const rejectedStage = stageResults.find((result): result is PromiseRejectedResult => result.status === 'rejected')
    if (rejectedStage) throw rejectedStage.reason
    if (!activeJob(job)) return
    if (conflicted) throw new Error('小记已被修改，自动结果未覆盖现有内容；请重试')
    if (polishOutput && job.polishEnabled !== false && !job.polishDone) {
      stage = 'polish'
      const version = await serializeDocumentWrite(job, async () => {
        const latest = await fetchDoc(job.docId)
        if (!activeJob(job)) return null
        if (latest.rev !== job.rev || data.docPending(job.docId) || hasUnsavedEdit(job.memberId, job.docId)) {
          throw new Error('小记正文已变化，旧润色任务不会覆盖新内容；请重试')
        }
        const sourceMarkdown = resolvedRichContent(latest).markdown
        if (noteTextForPolish(sourceMarkdown) !== articleText) throw new Error('小记正文已变化，旧润色任务不会覆盖新内容；请重试')
        return saveNotePolishVersion(job.docId, sourceMarkdown, polishOutput!)
      })
      if (!activeJob(job)) return
      if (!version) return
        notePolishVersions[job.docId] = version
        job.polishDone = true
        job.polishTerminalFailed = false
        save(job)
        progress(job, stage, 'done')
    }
    if ((job.titleDone || job.titleEnabled === false) && (job.imageDone || job.imageEnabled === false)
        && (job.polishDone || job.polishEnabled === false) && (job.tagsDone || job.tagsEnabled !== true)) finish(job)
    else if (stageErrors.length) {
      job.error = stageErrors.join('；')
      save(job)
      noteEnrichmentStatus[job.docId] = `自动整理未完成：${job.error}`
    }
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

/** 保存新小记或编辑完成后启动；已有结果与三项自动整理开关逐篇保留。 */
export function startAutoNoteEnrichment(memberId: string, docId: string, rev: number,
    completed: { title?: boolean; titleEnabled?: boolean; image?: boolean; imageEnabled?: boolean; polishEnabled?: boolean; tagsEnabled?: boolean; allowedTags?: string[] } = {}) {
  if (!memberId || load(memberId, docId)) return
  activateNoteWorkflowMember(memberId)
  discarded.delete(keyOf(memberId, docId))
  try { localStorage.removeItem(`${COMPLETE_PREFIX}${memberId}:${docId}`) } catch { /* 非关键状态。 */ }
  delete noteEnrichmentProgress[docId]
  const job: AutoJob = {
    memberId, docId, rev, titleRequestId: newPolishRequestId(), titleDone: !!completed.title, titleEnabled: completed.titleEnabled !== false,
    imageDone: !!completed.image, imageEnabled: completed.imageEnabled !== false, polishEnabled: completed.polishEnabled !== false,
    tagsEnabled: completed.tagsEnabled === true && (completed.allowedTags?.length ?? 0) > 0,
    tagsDone: completed.tagsEnabled !== true || (completed.allowedTags?.length ?? 0) === 0,
    allowedTags: completed.allowedTags?.slice(0, 100),
  }
  save(job)
  progress(job)
  noteEnrichmentStatus[docId] = '已保存，等待自动整理…'
  void run(job)
}

/** 从小记卡片启动单项生成；历史小记没有自动整理记录时也可按需使用。 */
export function startNoteEnrichmentStage(memberId: string, docId: string, rev: number,
    stage: 'title' | 'image' | 'polish' | 'tags', allowedTags: string[] = []) {
  if (!memberId || !docId || running.has(keyOf(memberId, docId))) return
  if (stage === 'tags' && !allowedTags.length && !load(memberId, docId)?.allowedTags?.length) return
  activateNoteWorkflowMember(memberId)
  const key = keyOf(memberId, docId)
  discarded.delete(key)
  let job = load(memberId, docId)
  if (!job) {
    job = {
      memberId, docId, rev, titleRequestId: newPolishRequestId(), titleDone: false,
      titleEnabled: stage === 'title', imageDone: false, imageEnabled: stage === 'image',
      polishDone: false, polishEnabled: stage === 'polish',
      tagsEnabled: stage === 'tags', tagsDone: stage !== 'tags', allowedTags: allowedTags.slice(0, 100),
    }
  } else {
    job.rev = rev || job.rev
    if (stage === 'title') {
      job.titleEnabled = true; job.titleDone = false; job.titleTerminalFailed = false
      job.titleRequestId = newPolishRequestId()
    } else if (stage === 'image') {
      job.imageEnabled = true; job.imageDone = false; job.imageJobId = undefined; job.imageRequestId = undefined
    } else if (stage === 'tags') {
      job.tagsEnabled = true; job.tagsDone = false; job.tagTerminalFailed = false
      if (allowedTags.length) job.allowedTags = allowedTags.slice(0, 100)
      job.tagRequestId = newPolishRequestId()
    } else {
      job.polishEnabled = true; job.polishDone = false; job.polishTerminalFailed = false
      job.polishRequestId = newPolishRequestId()
    }
  }
  try { localStorage.removeItem(`${COMPLETE_PREFIX}${memberId}:${docId}`) } catch { /* 非关键状态。 */ }
  save(job)
  progress(job, stage, 'pending')
  noteEnrichmentStatus[docId] = `正在启动${stage === 'title' ? '标题生成' : stage === 'image' ? '配图生成' : stage === 'tags' ? '标签推荐' : '正文润色'}…`
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
        if (!noteEnrichmentProgress[docId]) {
          const value = localStorage.getItem(key) || ''
          const completed = value.startsWith('{') ? JSON.parse(value) as { title?: Step; image?: Step; polish?: Step; tags?: Step } : null
          noteEnrichmentProgress[docId] = {
            title: completed?.title === 'skipped' ? 'skipped' : 'done', image: completed?.image === 'skipped' ? 'skipped' : 'done',
            polish: (completed?.polish ?? value) === 'done' ? 'done' : 'skipped', tags: completed?.tags === 'done' ? 'done' : 'skipped',
          }
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
      if (job.titleEnabled !== false && !job.titleDone) job.titleRequestId = newPolishRequestId()
      if (job.tagsEnabled === true && !job.tagsDone) job.tagRequestId = newPolishRequestId()
      if (job.imageEnabled !== false && !job.imageDone) { job.imageJobId = undefined; job.imageRequestId = undefined }
      if (!job.polishDone) job.polishRequestId = newPolishRequestId()
    }
  } catch {
    noteEnrichmentStatus[docId] = '读取小记失败，保存的内容未变；请再次重试'
    return
  }
  if (job.titleEnabled !== false && !job.titleDone && (job.titleTerminalFailed || job.error?.includes('已被修改'))) job.titleRequestId = newPolishRequestId()
  // 其他阶段可能已推进 job.rev；服务端明确拒绝了旧输入时仍需换号。
  // 仅处理标签的明确拒绝，网络中断继续查询原任务，完成的其他阶段不重做。
  if (job.tagsEnabled === true && !job.tagsDone && (job.tagTerminalFailed || job.error?.includes('小记已被修改')
    || job.error?.includes('标签：请求内容已改变'))) job.tagRequestId = newPolishRequestId()
  if (!job.polishDone && (job.polishTerminalFailed || job.error?.includes('已变化'))) job.polishRequestId = newPolishRequestId()
  if (job.imageEnabled !== false && !job.imageDone && job.error?.includes('FAILED')) { job.imageJobId = undefined; job.imageRequestId = undefined }
  job.error = undefined
  save(job)
  progress(job, job.titleEnabled !== false && !job.titleDone ? 'title' : job.tagsEnabled && !job.tagsDone ? 'tags' : job.imageEnabled !== false && !job.imageDone ? 'image' : 'polish', 'pending')
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
