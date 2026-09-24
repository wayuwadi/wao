import { reactive } from 'vue'
import { fetchDoc, apiPatchDoc } from '@/api/board'
import { fetchArticleImage, startArticleImage } from '@/api/articleImages'
import { fetchPreference } from '@/api/favorites'
import { newPolishRequestId, waitForPolish } from '@/api/polishJobs'
import { useDataStore } from '@/stores/data'
import { ARTICLE_IMAGE_PREFERENCE_KEY, DEFAULT_ARTICLE_IMAGE_PROMPT } from '@/utils/articleImagePrompt'
import { cloneRichContent, legacyProjection, resolvedRichContent } from '@/utils/richDoc'

type AutoJob = {
  memberId: string
  docId: string
  rev: number
  titleRequestId: string
  titleDone: boolean
  imageJobId?: string
  imageDone: boolean
  error?: string
}

const PREFIX = 'wao-note-auto-enrichment-v1:'
const running = new Set<string>()
/** 保存后进度按文档显示；失败保留任务，用户可重试。 */
export const noteEnrichmentStatus = reactive<Record<string, string>>({})
const keyOf = (memberId: string, docId: string) => `${PREFIX}${memberId}:${docId}`
function hasUnsavedEdit(memberId: string, docId: string): boolean {
  try { return localStorage.getItem(`wao-note-edit-v1:${memberId}:${docId}`) !== null } catch { return false }
}
function save(job: AutoJob) {
  try { localStorage.setItem(keyOf(job.memberId, job.docId), JSON.stringify(job)) } catch { /* 本页继续执行。 */ }
}
function load(memberId: string, docId: string): AutoJob | null {
  try {
    const value = JSON.parse(localStorage.getItem(keyOf(memberId, docId)) || 'null') as AutoJob | null
    return value?.memberId === memberId && value?.docId === docId ? value : null
  } catch { return null }
}
function finish(job: AutoJob) {
  try { localStorage.removeItem(keyOf(job.memberId, job.docId)) } catch { /* 已完成，无需重试。 */ }
  noteEnrichmentStatus[job.docId] = '标题和配图已自动生成'
}

async function run(job: AutoJob) {
  const key = keyOf(job.memberId, job.docId)
  if (running.has(key)) return
  running.add(key)
  const data = useDataStore()
  try {
    job.error = undefined
    const source = await fetchDoc(job.docId)
    const articleText = resolvedRichContent(source).markdown.trim()
    if (!articleText) throw new Error('正文为空，无法生成标题和配图')
    if (data.docPending(job.docId) || hasUnsavedEdit(job.memberId, job.docId)) {
      throw new Error('小记有未保存编辑，自动结果未覆盖草稿；保存后可手动重新生成')
    }
    if (!job.imageDone && !job.imageJobId) {
      try {
        const preference = await fetchPreference(ARTICLE_IMAGE_PREFERENCE_KEY)
        const image = await startArticleImage(articleText.slice(0, 60_000), preference.trim() || DEFAULT_ARTICLE_IMAGE_PROMPT)
        job.imageJobId = image.id
        save(job)
      } catch (cause) {
        job.error = cause instanceof Error ? cause.message : '配图任务无法启动'
        save(job)
      }
    }
    if (!job.titleDone) {
      noteEnrichmentStatus[job.docId] = '正在生成标题…'
      try {
        const result = await waitForPolish(articleText.slice(0, 12_000), 'NOTE_TITLE', { requestId: job.titleRequestId })
        const title = result.polished?.trim().slice(0, 30)
        if (!title) throw new Error('AI 未返回可用标题')
        const latest = await fetchDoc(job.docId)
        if (data.docPending(job.docId) || hasUnsavedEdit(job.memberId, job.docId)) {
          job.error = '小记有未保存编辑，自动标题未覆盖草稿'
        } else if (latest.rev === job.rev) {
          const saved = await apiPatchDoc(job.docId, { title, expectedRev: job.rev })
          data.applyRemoteDoc(saved)
          job.rev = saved.rev ?? job.rev + 1
        } else job.error = '小记已被修改，自动标题未覆盖现有标题'
        job.titleDone = true
        save(job)
      } catch (cause) {
        job.error = cause instanceof Error ? cause.message : '标题生成失败'
        save(job)
      }
    }
    if (job.imageJobId && !job.imageDone) {
      noteEnrichmentStatus[job.docId] = '正在生成配图…'
      const deadline = Date.now() + 35 * 60_000
      try {
        while (Date.now() < deadline) {
          const result = await fetchArticleImage(job.imageJobId)
          if (result.status === 'FAILED') {
            job.imageJobId = undefined
            throw new Error(result.message)
          }
          if (result.status === 'SUCCEEDED') {
            if (!result.imageUrl) throw new Error('生成任务没有返回图片地址')
            const latest = await fetchDoc(job.docId)
            if (latest.rev !== job.rev || data.docPending(job.docId)
                || hasUnsavedEdit(job.memberId, job.docId)) {
              job.imageDone = true
              throw new Error('小记已被修改或有未保存编辑，新图未自动覆盖；可在编辑器中手动生成')
            }
            const content = cloneRichContent(resolvedRichContent(latest))
            const imageNode = { type: 'image', attrs: { src: result.imageUrl, alt: '文章总览图', width: '50%', height: null, align: 'center' } }
            if (content.document?.type === 'doc') {
              content.document.content = [imageNode, ...(content.document.content ?? [])]
            }
            content.markdown = `![文章总览图](${result.imageUrl} "wao-image-size:50%xauto")\n\n${content.markdown}`
            const saved = await apiPatchDoc(job.docId, { content, blocks: legacyProjection(content), expectedRev: job.rev })
            data.applyRemoteDoc(saved)
            job.rev = saved.rev ?? job.rev + 1
            job.imageDone = true
            save(job)
            break
          }
          await new Promise(resolve => setTimeout(resolve, 1800))
        }
        if (!job.imageDone) throw new Error('配图仍在处理中，稍后返回小记可继续查询')
      } catch (cause) {
        job.error = cause instanceof Error ? cause.message : '配图生成失败'
        save(job)
      }
    }
    if (job.titleDone && job.imageDone && !job.error) finish(job)
    else if (job.titleDone && job.imageDone && job.error) {
      try { localStorage.removeItem(key) } catch { /* 不再重试已过期内容。 */ }
      noteEnrichmentStatus[job.docId] = `自动生成未完成：${job.error}`
    }
    else noteEnrichmentStatus[job.docId] = `自动生成未完成：${job.error || '请稍后重试'}`
  } catch (cause) {
    job.error = cause instanceof Error ? cause.message : '自动生成失败'
    if (job.error.includes('未保存编辑')) {
      try { localStorage.removeItem(key) } catch { /* 不再自动覆盖编辑草稿。 */ }
    } else save(job)
    noteEnrichmentStatus[job.docId] = `自动生成未完成：${job.error}`
  } finally { running.delete(key) }
}

/** 新小记先保存正文，再后台生成标题和配图；已有正文不会因 AI 失败而丢失。 */
export function startAutoNoteEnrichment(memberId: string, docId: string, rev: number,
    completed: { title?: boolean; image?: boolean } = {}) {
  if (!memberId || load(memberId, docId)) return
  const job: AutoJob = { memberId, docId, rev, titleRequestId: newPolishRequestId(),
    titleDone: !!completed.title, imageDone: !!completed.image }
  save(job)
  noteEnrichmentStatus[docId] = '正在自动生成标题和配图…'
  void run(job)
}

/** 页面刷新后继续读取已有任务，不重复提交生成请求。 */
export function resumeAutoNoteEnrichment(memberId: string) {
  if (!memberId) return
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)
      if (!key?.startsWith(`${PREFIX}${memberId}:`)) continue
      const job = load(memberId, key.slice(`${PREFIX}${memberId}:`.length))
      if (job) void run(job)
    }
  } catch { /* 浏览器拒绝本地存储时，已发起的当前任务仍会继续。 */ }
}

export function retryAutoNoteEnrichment(memberId: string, docId: string) {
  const job = load(memberId, docId)
  if (!job) return
  if (!job.titleDone && job.error) job.titleRequestId = newPolishRequestId()
  job.error = undefined
  save(job)
  void run(job)
}

export function canRetryAutoNoteEnrichment(memberId: string, docId: string) {
  return !!load(memberId, docId)
}
