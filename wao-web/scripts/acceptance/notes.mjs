import { mkdirSync, readFileSync, writeFileSync, realpathSync, renameSync, rmSync } from 'node:fs'
import { resolve, relative, isAbsolute, dirname, sep } from 'node:path'
import { tmpdir } from 'node:os'
import { randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { setTimeout as delay } from 'node:timers/promises'
import { localOrigin, writeFault, clearFault, SAVE_FAILURE } from './faults.mjs'

const repository = realpathSync(resolve(import.meta.dirname, '../../..'))
const PREFIX = 'WAO 验收 · 小记回归 · '
export const NOTE_STEPS = ['新建与独立源持久化', '连续保存与重新编辑', '失败收起刷新恢复与重试',
  '多窗口竞争保留本次输入与远端标题', '保存中状态与重复操作保护', '窄屏错误与重试可见', '精确软删除与双源刷新确认']
const SAME_ORIGIN_STEP = '同源独立页签草稿与保存失败恢复'

export function privateDirectory(path) {
  const absolute = resolve(path)
  mkdirSync(absolute, { recursive: true, mode: 0o700 })
  const actual = realpathSync(absolute)
  const rel = relative(repository, actual)
  if (!rel || (!rel.startsWith(`..${sep}`) && rel !== '..' && !isAbsolute(rel))) throw new Error('证据必须保存在仓库外')
  return actual
}
function write(path, value) {
  const temporary = `${path}.${randomUUID()}.tmp`
  try { writeFileSync(temporary, value, { flag: 'wx', mode: 0o600 }); renameSync(temporary, path) }
  finally { rmSync(temporary, { force: true }) }
}
export function validateNoteReport(report) {
  if (!/^[A-Za-z0-9-]{1,100}$/.test(report?.runId ?? '') || report.title !== PREFIX + report.runId
    || !Array.isArray(report.steps) || !['running', 'failed', 'passed'].includes(report.status)) throw new Error('无效的小记报告')
  if (report.docId != null && !/^[A-Za-z0-9_-]{1,32}$/.test(report.docId)) throw new Error('无效的小记 ID')
  if (localOrigin(report.baseURL, 'baseURL') === localOrigin(report.peerURL, 'peerURL')) throw new Error('持久化复核必须使用独立源')
  return report
}
export function assertNoteComplete(report) {
  validateNoteReport(report)
  if (report.status === 'failed' || !report.docId || !report.cleaned || NOTE_STEPS.some(name => !report.steps.some(step => step.name === name && step.status === 'passed')))
    throw new Error('步骤或清理尚未全部核实，不得标记通过')
}

/** 正常登录后通过真实控件验收；不读取 Cookie、应用内部状态或执行浏览器请求。 */
export class NotesRegression {
  constructor(tab, peerTab, { baseURL, peerURL, outputDir, faultFile } = {}) {
    this.tab = tab; this.peerTab = peerTab
    const runId = `${new Date().toISOString().replace(/[:.]/g, '-')}-${randomUUID().slice(0, 8)}`
    this.report = validateNoteReport({ runId, title: PREFIX + runId, baseURL, peerURL,
      startedAt: new Date().toISOString(), steps: [], status: 'running', cleaned: false,
      gitSha: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repository, encoding: 'utf8' }).trim(),
      worktreeDirty: !!execFileSync('git', ['status', '--porcelain'], { cwd: repository, encoding: 'utf8' }).trim() })
    this.outputDir = privateDirectory(outputDir ?? `${tmpdir()}/wao-notes-regression/${runId}`)
    this.faultFile = resolve(faultFile ?? `${tmpdir()}/wao-draft-acceptance-${new URL(baseURL).port}/fault.json`)
    privateDirectory(dirname(this.faultFile))
    this.saveReport()
  }
  static resume(tab, peerTab, { reportFile, faultFile } = {}) {
    const report = validateNoteReport(JSON.parse(readFileSync(reportFile, 'utf8')))
    const run = Object.create(this.prototype)
    Object.assign(run, { tab, peerTab, report, outputDir: privateDirectory(dirname(reportFile)),
      faultFile: resolve(faultFile ?? `${tmpdir()}/wao-draft-acceptance-${new URL(report.baseURL).port}/fault.json`) })
    privateDirectory(dirname(run.faultFile))
    return run
  }
  saveReport() { write(resolve(this.outputDir, 'report.json'), JSON.stringify(this.report, null, 2)) }
  // 使用已由页面读取的 data-note-id，避免标题截断和同名小记误命中。
  note(tab = this.tab) {
    if (!this.report.docId) throw new Error('尚未登记小记 ID')
    return tab.playwright.locator(`[data-note-id="${this.report.docId}"]`)
  }
  editor(tab = this.tab) { return this.note(tab).getByRole('textbox').nth(1) }
  text(label) { return `${this.report.title}。合成验收：${label}；预算 120 元，不购买额外服务。` }
  async checkText(locator, expected) {
    const deadline = Date.now() + 20_000
    do {
      if ((await locator.innerText()).trim() === expected) return
      await delay(150)
    } while (Date.now() < deadline)
    throw new Error('可见正文与预期不一致，保留现场')
  }
  async record(name) {
    if (![...NOTE_STEPS, SAME_ORIGIN_STEP].includes(name)) throw new Error('未知步骤')
    const screenshot = `${String(this.report.steps.length + 1).padStart(2, '0')}.png`
    write(resolve(this.outputDir, screenshot), await this.tab.screenshot({ fullPage: false }))
    this.report.steps.push({ name, status: 'passed', at: new Date().toISOString(), screenshot })
    this.saveReport()
    return { name, status: 'passed', report: resolve(this.outputDir, 'report.json') }
  }
  async open(tab = this.tab) {
    await this.note(tab).waitFor({ state: 'visible' })
    if (!await this.note(tab).getByRole('textbox', { name: '小记标题', exact: true }).isVisible()) await this.note(tab).dblclick()
    await this.editor(tab).waitFor({ state: 'visible' })
  }
  async save(tab = this.tab, retry = false) {
    await this.note(tab).getByRole('button', { name: retry ? '重试保存' : '保存修改', exact: true }).click()
    // 保存按钮会立即改名为“正在保存”，它消失不能证明请求完成。
    await this.saved(tab)
  }
  async saved(tab = this.tab) {
    const deadline = Date.now() + 30_000
    while (await this.note(tab).getByRole('textbox', { name: '小记标题', exact: true }).isVisible()) {
      if (await this.note(tab).getByRole('button', { name: '重试保存', exact: true }).isVisible()) throw new Error('保存失败，保留正文和故障现场')
      if (Date.now() >= deadline) throw new Error('保存未完成，不能确认持久化')
      await delay(150)
    }
  }
  async persisted(expected, tab = this.peerTab) {
    await tab.reload()
    await this.ready(tab)
    await this.filterToRun(tab)
    await this.note(tab).waitFor({ state: 'visible' })
    await this.checkText(this.note(tab).getByRole('textbox'), expected)
    if ((await this.note(tab).innerText()).includes('有修改待保存')) throw new Error('独立源仍有草稿，不能用作持久化证据')
  }
  async filterToRun(tab) {
    await tab.playwright.getByRole('button', { name: /筛选与排序/ }).click()
    await tab.playwright.getByRole('button', { name: '清除筛选', exact: true }).click()
    // resetFilters 不关闭浮层；主动收起，避免它遮住卡片或影响后续点击。
    await tab.playwright.getByRole('button', { name: /筛选与排序/ }).click()
    await tab.playwright.getByRole('searchbox', { name: '搜索小记内容', exact: true }).fill(this.report.runId)
  }
  async ready(tab) {
    const button = tab.playwright.getByRole('button', { name: '刷新小记清单', exact: true })
    await button.waitFor({ state: 'visible' })
    const deadline = Date.now() + 30_000
    while (!await button.isEnabled()) {
      if (Date.now() >= deadline) throw new Error('小记清单加载未完成')
      await delay(150)
    }
    if (await tab.playwright.getByRole('button', { name: '重试加载', exact: true }).count()) throw new Error('清单读取失败')
  }
  fault(mode, delayMs) { writeFault(this.faultFile, { docId: this.report.docId, mode, delayMs, expiresAt: Date.now() + 600_000 }) }
  async create() {
    if (this.report.docId) throw new Error('本轮已创建；请恢复报告，不重复创建')
    for (const [tab, url] of [[this.tab, this.report.baseURL], [this.peerTab, this.report.peerURL]]) {
      if (await tab.url() !== `${url}/notes`) await tab.goto(`${url}/notes`)
      await tab.playwright.getByRole('region', { name: '记录小记', exact: true }).waitFor({ state: 'visible' })
      const input = tab.playwright.getByRole('region', { name: '记录小记', exact: true }).getByRole('textbox')
      await input.waitFor({ state: 'visible' })
      if ((await input.innerText()).trim()) throw new Error('新建区已有输入；不可覆盖现有草稿')
      await this.ready(tab)
      await this.filterToRun(tab)
    }
    const region = this.tab.playwright.getByRole('region', { name: '记录小记', exact: true })
    await region.getByRole('textbox').fill(this.text('初次保存'))
    await region.getByRole('button', { name: '记下来了', exact: true }).click()
    const card = this.tab.playwright.getByRole('article').filter({ hasText: this.report.runId })
    await card.waitFor({ state: 'visible' })
    this.report.docId = await card.getAttribute('data-note-id')
    validateNoteReport(this.report); this.saveReport()
    await this.persisted(this.text('初次保存'))
    return this.record(NOTE_STEPS[0])
  }
  async consecutiveSaves() {
    for (const label of ['第二次保存', '再次编辑保存']) {
      await this.open(); await this.editor().fill(this.text(label)); await this.save()
      await this.persisted(this.text(label))
    }
    await this.tab.reload(); await this.open(); await this.checkText(this.editor(), this.text('再次编辑保存'))
    await this.note().getByRole('button', { name: '收起编辑', exact: true }).click()
    return this.record(NOTE_STEPS[1])
  }
  async failureRecovery() {
    await this.open(); await this.editor().fill(this.text('断网后保留输入')); this.fault('fail')
    await this.note().getByRole('button', { name: '保存修改', exact: true }).click()
    await this.note().getByRole('button', { name: '重试保存', exact: true }).waitFor({ state: 'visible' })
    if (!(await this.note().innerText()).includes(SAVE_FAILURE)) throw new Error('没有显示失败原因')
    await this.checkText(this.editor(), this.text('断网后保留输入'))
    await this.note().getByRole('button', { name: '收起编辑', exact: true }).click()
    await this.checkText(this.note().getByRole('textbox'), this.text('断网后保留输入'))
    await this.tab.reload(); await this.open(); await this.checkText(this.editor(), this.text('断网后保留输入'))
    clearFault(this.faultFile); await this.save(); await this.persisted(this.text('断网后保留输入'))
    return this.record(NOTE_STEPS[2])
  }
  async competingWrites() {
    await this.open(); await this.editor().fill(this.text('本窗口尚未保存'))
    await this.open(this.peerTab); await this.editor(this.peerTab).fill(this.text('第二窗口已保存'))
    await this.note(this.peerTab).getByRole('textbox', { name: '小记标题', exact: true }).fill(`验收标题 ${this.report.runId}`)
    await this.save(this.peerTab)
    await this.tab.playwright.getByRole('button', { name: '刷新小记清单', exact: true }).click()
    await this.checkText(this.editor(), this.text('本窗口尚未保存'))
    await this.save(); await this.persisted(this.text('本窗口尚未保存'))
    if (!(await this.note(this.peerTab).innerText()).includes(`验收标题 ${this.report.runId}`)) throw new Error('未修改的远端标题丢失')
    return this.record(NOTE_STEPS[3])
  }
  async savingProtection() {
    await this.open(); await this.editor().fill(this.text('延迟保存')); this.fault('delay', 3000)
    await this.note().getByRole('button', { name: '保存修改', exact: true }).click()
    const saving = this.note().getByRole('button', { name: '正在保存…', exact: true })
    await saving.waitFor({ state: 'visible' })
    if (await saving.isEnabled() || await this.note().getByRole('button', { name: '收起编辑', exact: true }).isEnabled()) throw new Error('保存时未禁止重复操作')
    await this.saved()
    clearFault(this.faultFile); await this.persisted(this.text('延迟保存'))
    return this.record(NOTE_STEPS[4])
  }
  /** 额外场景：传入独立打开、同源同账号的页签；不模拟浏览器存储。 */
  async sameOriginDrafts(otherTab) {
    const url = new URL(await otherTab.url())
    if (otherTab.id === this.tab.id || url.origin !== this.report.baseURL || url.pathname !== '/notes')
      throw new Error('需要独立打开的同源小记页签')
    // 第三页签可能早于本轮创建；先刷新真实清单再定位精确 ID。
    await otherTab.reload()
    await this.ready(otherTab); await this.filterToRun(otherTab)
    await this.open(); await this.open(otherTab)
    const first = this.text('同源第一页独有 A'), second = this.text('同源第二页独有 B')
    await this.editor().fill(first); await this.editor(otherTab).fill(second)
    await this.tab.reload(); await this.open(); await this.checkText(this.editor(), first)
    await otherTab.reload(); await this.open(otherTab); await this.checkText(this.editor(otherTab), second)
    await this.save(); await this.persisted(first); await this.checkText(this.editor(otherTab), second)
    this.fault('fail')
    await this.note(otherTab).getByRole('button', { name: '保存修改', exact: true }).click()
    await this.note(otherTab).getByRole('button', { name: '重试保存', exact: true }).waitFor({ state: 'visible' })
    await otherTab.reload(); await this.open(otherTab); await this.checkText(this.editor(otherTab), second)
    clearFault(this.faultFile); await this.save(otherTab); await this.persisted(second)
    await this.tab.reload(); await this.note().waitFor({ state: 'visible' })
    await this.checkText(this.note().getByRole('textbox'), second)
    return this.record(SAME_ORIGIN_STEP)
  }
  /** 调用前通过浏览器 viewport 能力设置为 390px；调用后由调用者还原。 */
  async narrowFailure() {
    const width = await this.tab.playwright.evaluate(() => document.documentElement.clientWidth)
    if (width > 420 || width < 320) throw new Error('请先设置 320—420px 窄屏')
    await this.open(); await this.editor().fill(this.text('窄屏失败恢复')); this.fault('fail')
    await this.note().getByRole('button', { name: '保存修改', exact: true }).click()
    await this.note().getByRole('button', { name: '重试保存', exact: true }).waitFor({ state: 'visible' })
    const fits = await this.note().getByRole('button', { name: '重试保存', exact: true }).evaluate(el => {
      const rect = el.getBoundingClientRect(); return rect.left >= 0 && rect.right <= document.documentElement.clientWidth
    })
    if (!fits || !(await this.note().innerText()).includes(SAVE_FAILURE)) throw new Error('窄屏错误或重试入口不可用')
    write(resolve(this.outputDir, 'narrow-failure.png'), await this.tab.screenshot({ fullPage: false }))
    clearFault(this.faultFile); await this.save(this.tab, true); await this.persisted(this.text('窄屏失败恢复'))
    return this.record(NOTE_STEPS[5])
  }
  /** 用正常页面把本轮精确 ID 软删除后调用；工具不自动删除其他内容。 */
  async verifyCleanup() {
    for (const tab of [this.tab, this.peerTab]) {
      await tab.reload()
      await this.ready(tab)
      await this.filterToRun(tab)
      await tab.playwright.getByRole('button', { name: '刷新小记清单', exact: true }).click()
      await this.ready(tab)
      if (await this.note(tab).count()) throw new Error('本轮小记仍可见，不能确认清理')
    }
    this.report.cleaned = true
    return this.record(NOTE_STEPS[6])
  }
  async finish(success, error) {
    clearFault(this.faultFile)
    if (success) assertNoteComplete(this.report)
    this.report.status = success ? 'passed' : 'failed'
    this.report.finishedAt = new Date().toISOString()
    if (error) this.report.error = String(error).slice(0, 1000)
    this.saveReport()
    return { status: this.report.status, report: resolve(this.outputDir, 'report.json') }
  }
}
