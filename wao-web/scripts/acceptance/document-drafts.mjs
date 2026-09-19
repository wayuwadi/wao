import { mkdirSync, readFileSync, writeFileSync, realpathSync, renameSync, rmSync } from 'node:fs'
import { resolve, relative, isAbsolute, dirname } from 'node:path'
import { tmpdir } from 'node:os'
import { randomUUID } from 'node:crypto'
import { setTimeout as delay } from 'node:timers/promises'
import { writeFault, clearFault, localOrigin, SAVE_FAILURE } from './faults.mjs'
import { registerCleanupDocuments } from './cleanup.mjs'

const PREFIX = 'WAO 验收 · 草稿回归 · '
const REQUIRED_STEPS = [
  '新建唯一验收文档', '保存中状态可见', '保存成功后刷新读取相同正文',
  '保存失败明确显示原因且保留正文', '应用页签切换不丢草稿',
  '保存失败后刷新恢复草稿与重试入口', '恢复连接后重试并刷新确认持久化',
  '另一窗口保存后本机未保存正文保持完整', '刷新发现版本冲突，未覆盖本机或服务器正文',
  '冲突副本和原服务器版本分别持久化', '仅将本轮验收文档移入回收站并刷新核实',
]
const repository = realpathSync(resolve(import.meta.dirname, '../../..'))

function writeArtifact(path, content) {
  // 原子替换避免中断时留下半份 JSON，也不会沿已有文件符号链接把私有证据写入其他位置。
  const temporary = `${path}.${randomUUID()}.tmp`
  try {
    writeFileSync(temporary, content, { mode: 0o600, flag: 'wx' })
    renameSync(temporary, path)
  } finally { rmSync(temporary, { force: true }) }
}

function outsideRepository(path) {
  const absolute = resolve(path)
  // 先建立父目录再解析真实路径，防止符号链接把截图写回仓库。
  mkdirSync(absolute, { recursive: true, mode: 0o700 })
  const actual = realpathSync(absolute)
  const rel = relative(repository, actual)
  if (!rel || (!(rel === '..' || rel.startsWith('../')) && !isAbsolute(rel))) throw new Error('验收产物必须保存在仓库外')
  return actual
}

/**
 * 供 Codex cua_repl 的浏览器 Tab 使用。只通过 UI 操作文档；不读取会话凭据或 Pinia 状态。
 * 每个公开步骤可单独调用，便于长任务保持进度反馈。失败时调用 finish(false) 清除故障并保留现场。
 */
export class DocumentDraftRegression {
  constructor(tab, { baseURL, kbPath, kbName, faultFile, outputDir } = {}) {
    this.tab = tab
    this.baseURL = localOrigin(baseURL, 'baseURL')
    if (!/^\/kb\/[A-Za-z0-9_-]+$/.test(kbPath ?? '')) throw new Error('kbPath 必须是明确的知识库首页路径')
    if (!kbName?.trim()) throw new Error('必须提供已核实的专用验收知识库名称')
    this.kbPath = kbPath
    this.kbName = kbName
    this.runId = `${new Date().toISOString().replace(/[:.]/g, '-')}-${randomUUID().slice(0, 8)}`
    this.title = `${PREFIX}${this.runId}`
    this.outputDir = outsideRepository(outputDir ?? `${tmpdir()}/wao-draft-regression/${this.runId}`)
    this.faultFile = resolve(faultFile ?? `${tmpdir()}/wao-draft-acceptance-${new URL(baseURL).port}/fault.json`)
    outsideRepository(resolve(this.faultFile, '..'))
    this.report = { runId: this.runId, title: this.title, startedAt: new Date().toISOString(),
      baseURL: this.baseURL, kbPath, steps: [], documents: [], status: 'running' }
    this.saveReport()
  }

  /** 浏览器工具会话重置后恢复本机报告；不会重做写入、跳过验收或读取浏览器私有状态。 */
  static resume(tab, { reportFile, kbName, peerTab, faultFile } = {}) {
    const outputDir = outsideRepository(dirname(resolve(reportFile)))
    const report = JSON.parse(readFileSync(resolve(reportFile), 'utf8'))
    if (!/^[A-Za-z0-9-]{1,100}$/.test(report.runId ?? '') || report.title !== PREFIX + report.runId
      || !/^\/kb\/[A-Za-z0-9_-]+$/.test(report.kbPath ?? '') || !kbName?.trim()
      || !Array.isArray(report.steps) || !Array.isArray(report.documents) || report.documents.length > 2) {
      throw new Error('报告不符合本工具格式，不能恢复')
    }
    const run = Object.create(this.prototype)
    Object.assign(run, { tab, peerTab, outputDir, report, runId: report.runId, title: report.title,
      baseURL: localOrigin(report.baseURL, 'baseURL'), kbPath: report.kbPath, kbName })
    for (const [index, doc] of report.documents.entries()) {
      const url = new URL(doc.url)
      const title = run.title + (index === 1 ? '（冲突副本）' : '')
      if (!/^[A-Za-z0-9_-]{1,120}$/.test(doc.id ?? '') || doc.title !== title
        || url.origin !== run.baseURL || url.pathname !== run.kbPath || url.searchParams.get('doc') !== doc.id) {
        throw new Error('报告文档身份不完整，不能恢复')
      }
    }
    run.docId = report.documents[0]?.id
    run.docURL = report.documents[0]?.url
    run.faultFile = resolve(faultFile ?? `${tmpdir()}/wao-draft-acceptance-${new URL(run.baseURL).port}/fault.json`)
    outsideRepository(dirname(run.faultFile))
    run.baseline = `已保存基线 ${run.runId}`
    run.draft = `失败时仍应保留的本机草稿 ${run.runId}`
    run.localVersion = `冲突前保留的本机版本 ${run.runId}`
    run.remoteVersion = `第二窗口已保存的服务器版本 ${run.runId}`
    return run
  }

  saveReport() { writeArtifact(resolve(this.outputDir, 'report.json'), JSON.stringify(this.report, null, 2)) }
  body(tab = this.tab) { return tab.playwright.locator('.tiptap[contenteditable="true"]') }
  titleInput(tab = this.tab) { return tab.playwright.getByPlaceholder('未命名文档', { exact: true }) }
  async titleValue() {
    return this.tab.playwright.evaluate(() => {
      const inputs = document.querySelectorAll('input.doc-title')
      return inputs.length === 1 ? inputs[0].value : null
    })
  }
  async titleEquals(expected) {
    const deadline = Date.now() + 30_000
    while (await this.titleValue() !== expected) {
      if (Date.now() >= deadline) throw new Error('文档标题与本轮记录不一致，停止操作')
      await delay(200)
    }
  }
  async snapshot(tab = this.tab) { return tab.playwright.domSnapshot() }
  async visible(locator, timeoutMs = 30_000) {
    const deadline = Date.now() + timeoutMs
    // 部分浏览器连接器单次选择器等待上限短于本地应用冷启动；只重试观察，不重做写入。
    while (true) {
      try { return await locator.waitFor({ state: 'visible', timeoutMs: Math.min(5000, deadline - Date.now()) }) }
      catch (error) {
        if (Date.now() >= deadline || !/timed out|deadline exceeded/i.test(String(error))) throw error
      }
    }
  }
  async text(expected, tab = this.tab) {
    // locator.waitFor 会等待组件加载；正文采用精确比较，不能把旁边目录中的同名文本算作成功。
    await this.visible(this.body(tab))
    const deadline = Date.now() + 30_000
    do {
      if ((await this.body(tab).innerText()).trim() === expected) return
      // 等待刷新后的真实正文/草稿恢复，不依赖连接器对富文本 hasText 的实现差异。
      await delay(200)
    } while (Date.now() < deadline)
    throw new Error('正文与本步骤输入不一致，现场已保留')
  }
  async saved(tab = this.tab) {
    await this.visible(tab.playwright.getByText(/^自动保存成功 /))
    await this.snapshot(tab)
  }
  async record(name, tab = this.tab) {
    const index = String(this.report.steps.length + 1).padStart(2, '0')
    const screenshot = `${index}.png`
    writeArtifact(resolve(this.outputDir, screenshot), await tab.screenshot({ fullPage: false }))
    this.report.steps.push({ name, status: 'passed', at: new Date().toISOString(), screenshot })
    this.saveReport()
    return { step: name, status: 'passed', report: resolve(this.outputDir, 'report.json') }
  }
  fault(mode, delayMs) {
    if (!this.docId) throw new Error('尚未创建本轮文档')
    writeFault(this.faultFile, { docId: this.docId, mode, delayMs, expiresAt: Date.now() + 10 * 60_000 })
  }

  async create() {
    if (this.docId) throw new Error('本轮已经创建文档；重跑须使用新实例')
    if (await this.tab.url() !== this.baseURL + this.kbPath) await this.tab.goto(this.baseURL + this.kbPath)
    await this.visible(this.tab.playwright.getByRole('heading', { name: this.kbName, exact: true }))
    await this.snapshot()
    await this.tab.playwright.getByRole('button', { name: '新增内容', exact: true }).click()
    await this.snapshot()
    await this.tab.playwright.getByRole('menuitem', { name: '新建文档', exact: true }).click()
    await this.visible(this.titleInput())
    await this.snapshot()
    const url = new URL(await this.tab.url())
    this.docId = url.searchParams.get('doc')
    if (url.origin !== this.baseURL || url.pathname !== this.kbPath || !this.docId) throw new Error('新建文档没有返回明确地址')
    this.docURL = url.href
    this.report.documents.push({ id: this.docId, title: this.title, url: this.docURL, cleaned: false })
    this.saveReport()
    await this.titleInput().fill(this.title)
    await this.saved()
    return this.record('新建唯一验收文档')
  }

  async saveAndReload() {
    this.fault('delay', 2000)
    this.baseline = `已保存基线 ${this.runId}`
    await this.body().fill(this.baseline)
    await this.visible(this.tab.playwright.getByText('保存中…', { exact: true }), 5000)
    await this.record('保存中状态可见')
    await this.saved()
    clearFault(this.faultFile)
    await this.tab.reload()
    await this.snapshot()
    await this.text(this.baseline)
    return this.record('保存成功后刷新读取相同正文')
  }

  async failureAndTabSwitch() {
    this.fault('fail')
    this.draft = `失败时仍应保留的本机草稿 ${this.runId}`
    await this.body().fill(this.draft)
    await this.visible(this.tab.playwright.getByText(new RegExp(`保存失败：${SAVE_FAILURE}`)))
    await this.snapshot()
    await this.text(this.draft)
    await this.record('保存失败明确显示原因且保留正文')
    await this.tab.playwright.getByRole('button', { name: '切换到首页', exact: true }).click()
    await this.snapshot()
    await this.tab.playwright.getByRole('button', { name: `切换到${this.kbName}`, exact: true }).click()
    await this.snapshot()
    await this.text(this.draft)
    return this.record('应用页签切换不丢草稿')
  }

  async reloadAndRetry() {
    await this.tab.reload()
    await this.snapshot()
    await this.visible(this.tab.playwright.getByRole('button', { name: '重试', exact: true }))
    await this.text(this.draft)
    await this.record('保存失败后刷新恢复草稿与重试入口')
    clearFault(this.faultFile)
    await this.tab.playwright.getByRole('button', { name: '重试', exact: true }).click()
    await this.saved()
    await this.tab.reload()
    await this.snapshot()
    await this.text(this.draft)
    return this.record('恢复连接后重试并刷新确认持久化')
  }

  async prepareConflict(peerTab, peerBaseURL) {
    const peerOrigin = localOrigin(peerBaseURL, 'peerBaseURL')
    if (peerOrigin === this.baseURL) throw new Error('第二窗口须使用独立源，隔离浏览器本机草稿')
    this.peerTab = peerTab
    this.peerURL = peerOrigin + this.kbPath + `?doc=${encodeURIComponent(this.docId)}`
    await peerTab.goto(this.peerURL)
    await this.text(this.draft, peerTab)
    this.fault('fail')
    this.localVersion = `冲突前保留的本机版本 ${this.runId}`
    await this.body().fill(this.localVersion)
    await this.visible(this.tab.playwright.getByRole('button', { name: '重试', exact: true }))
    await this.snapshot()
    this.remoteVersion = `第二窗口已保存的服务器版本 ${this.runId}`
    await this.body(peerTab).fill(this.remoteVersion)
    await this.saved(peerTab)
    await peerTab.reload()
    await this.snapshot(peerTab)
    await this.text(this.remoteVersion, peerTab)
    await this.text(this.localVersion)
    await this.record('另一窗口保存后本机未保存正文保持完整')
    clearFault(this.faultFile)
    await this.tab.reload()
    await this.snapshot()
    await this.visible(this.tab.playwright.getByRole('alert').filter({ hasText: '服务器已有另一版' }))
    await this.text(this.localVersion)
    return this.record('刷新发现版本冲突，未覆盖本机或服务器正文')
  }

  async saveConflictCopy() {
    await this.tab.playwright.getByRole('button', { name: '本机草稿另存副本', exact: true }).click()
    return this.verifyConflictCopy()
  }

  async verifyConflictCopy() {
    // 用持久存在的副本标题与地址确认，不依赖可能已经消失的成功通知。
    await this.visible(this.titleInput())
    await this.titleEquals(`${this.title}（冲突副本）`)
    await this.snapshot()
    const copyURL = new URL(await this.tab.url())
    const id = copyURL.searchParams.get('doc')
    if (!id || id === this.docId || copyURL.pathname !== this.kbPath || copyURL.origin !== this.baseURL) throw new Error('副本文档地址不正确')
    if (!this.report.documents.some(doc => doc.id === id)) this.report.documents.push({ id, title: `${this.title}（冲突副本）`, url: copyURL.href, cleaned: false })
    this.saveReport()
    await this.tab.reload()
    await this.snapshot()
    await this.text(this.localVersion)
    await this.peerTab.reload()
    await this.snapshot(this.peerTab)
    await this.text(this.remoteVersion, this.peerTab)
    return this.record('冲突副本和原服务器版本分别持久化')
  }

  async cleanup() {
    clearFault(this.faultFile)
    registerCleanupDocuments(`${this.faultFile}.owned.json`, this.report.documents)
    for (const doc of this.report.documents) {
      if (doc.cleaned) continue
      if (await this.tab.url() !== doc.url) await this.tab.goto(doc.url)
      await this.visible(this.titleInput())
      await this.snapshot()
      // 标题由本轮生成，ID 来自本轮新建结果；不按前缀批量删除。
      if (!doc.title.startsWith(PREFIX)) throw new Error('清理前文档身份不一致，停止清理')
      await this.titleEquals(doc.title)
      await this.tab.playwright.locator('button[title="删除这篇文档（进回收站）"]').click()
      await this.snapshot()
      const dialog = this.tab.playwright.getByRole('dialog', { name: '本轮验收文档清理', exact: true })
      await this.visible(dialog)
      await dialog.getByRole('button', { name: '确认移入回收站', exact: true }).click()
      await this.snapshot()
      const titles = await this.directoryTitles()
      if (titles.includes(doc.title)) throw new Error('刷新后文档仍在目录，不能声称清理成功')
      doc.cleaned = true
      this.saveReport()
    }
    clearFault(`${this.faultFile}.owned.json`)
    return this.record('仅将本轮验收文档移入回收站并刷新核实')
  }

  async directoryTitles() {
    const url = this.baseURL + this.kbPath
    if (await this.tab.url() !== url) await this.tab.goto(url)
    else await this.tab.reload()
    await this.visible(this.tab.playwright.getByRole('heading', { name: this.kbName, exact: true }))
    await this.snapshot()
    const deadline = Date.now() + 30_000
    do {
      const state = await this.tab.playwright.evaluate(() => {
        const home = document.querySelector('.library-home')
        if (!home || home.textContent.includes('正在加载目录…')) return { ready: false, titles: [] }
        const titles = Array.from(home.querySelectorAll('.home-documents button > span:first-child')).map(el => el.textContent)
        const count = Number(home.querySelector('.library-heading p')?.textContent.match(/(\d+)\s*篇文档/)?.[1] ?? -1)
        return { ready: count === titles.length && Boolean(home.querySelector('.home-folder') || home.textContent.includes('从第一篇文档开始整理知识')), titles }
      })
      if (state.ready) return state.titles
      await delay(200)
    } while (Date.now() < deadline)
    throw new Error('目录尚未加载完成，不能确认清理')
  }

  /** 工具无法处理原生确认框时，由用户在页面软删除后调用；仍须刷新目录核实。 */
  async verifyCleanup() {
    clearFault(this.faultFile)
    if (this.report.documents.length !== 2) throw new Error('本轮两篇文档尚未完整记录')
    const titles = await this.directoryTitles()
    if (this.report.documents.some(doc => titles.includes(doc.title))) throw new Error('本轮文档仍在目录中，清理未完成')
    this.report.documents.forEach(doc => { doc.cleaned = true })
    this.saveReport()
    return this.record('仅将本轮验收文档移入回收站并刷新核实')
  }

  async finish(success, error) {
    clearFault(this.faultFile)
    clearFault(`${this.faultFile}.owned.json`)
    const passed = new Set(this.report.steps.filter(step => step.status === 'passed').map(step => step.name))
    if (success && (REQUIRED_STEPS.some(name => !passed.has(name)) || this.report.documents.length !== 2 || this.report.documents.some(doc => !doc.cleaned))) {
      success = false
      error = new Error('回归步骤或两篇验收文档清理未完成，不能标记全通过')
    }
    this.report.status = success ? 'passed' : 'failed'
    this.report.finishedAt = new Date().toISOString()
    if (error) this.report.error = String(error.message ?? error)
    else if (success) delete this.report.error
    if (!success) {
      try { writeArtifact(resolve(this.outputDir, 'failure.png'), await this.tab.screenshot({ fullPage: false })) } catch { /* 页面失联时仍保留已有步骤和报告 */ }
    }
    this.saveReport()
    return { status: this.report.status, steps: this.report.steps.length, report: resolve(this.outputDir, 'report.json') }
  }
}
