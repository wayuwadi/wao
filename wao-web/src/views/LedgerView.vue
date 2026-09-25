<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { toast } from '@/composables/toast'
import {
  addLedgerSettlement, createLedgerBook, createLedgerEntry, getLedgerBook,
  listLedgerBooks, revokeLedgerBookAccess, shareLedgerBook, updateLedgerEntry,
  type LedgerBook, type LedgerBookDetail, type LedgerCategory, type LedgerEntry,
  type LedgerEntryInput, type LedgerEntryRevision, type LedgerStatus,
} from '@/api/ledger'

const session = useSessionStore()
const books = ref<LedgerBook[]>([])
const bookId = ref('')
const detail = ref<LedgerBookDetail | null>(null)
const selectedId = ref<string | null>(null)
const category = ref<'ALL' | LedgerCategory>('ALL')
const query = ref('')
const pane = ref<'entries' | 'plan'>('entries')
const detailOpen = ref(false)
const loading = ref(true)
const loadError = ref('')
const bookTitle = ref('')
const creatingBook = ref(false)
const bookOpen = ref(false)
const shareOpen = ref(false)
const shareMemberId = ref('')
const shareRole = ref<'EDITOR' | 'VIEWER'>('EDITOR')
const sharing = ref(false)
const shareError = ref('')
const editor = ref<LedgerEntryInput | null>(null)
const editingId = ref<string | null>(null)
const editorOriginal = ref('')
const saving = ref(false)
const saveError = ref('')
const settlementOpen = ref(false)
const settlementAmount = ref<number | string>('')
const settlementDate = ref(localToday())
const settlementNote = ref('')
const settling = ref(false)
const settlementError = ref('')

const categoryName: Record<LedgerCategory, string> = { OLD: '旧账', BAD: '烂账', FUTURE: '未来账' }
const statusName: Record<LedgerStatus, string> = {
  TO_VERIFY: '待核实', CONFIRMED: '已确认', DISPUTED: '有争议', VOID: '已作废',
}
const directionName = { RECEIVE: '应收', PAY: '应付' }
const money = (value: number) => new Intl.NumberFormat('zh-CN', {
  style: 'currency', currency: 'CNY', minimumFractionDigits: 2,
}).format(value)
function localToday() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}
const activeBook = computed(() => detail.value?.book ?? books.value.find(book => book.id === bookId.value) ?? null)
const canEdit = computed(() => activeBook.value?.role === 'OWNER' || activeBook.value?.role === 'EDITOR')
const canShare = computed(() => activeBook.value?.role === 'OWNER')
const entries = computed(() => detail.value?.entries ?? [])
const visibleEntries = computed(() => entries.value.filter(item =>
  (category.value === 'ALL' || item.category === category.value) &&
  `${item.title} ${item.counterparty} ${item.evidence ?? ''}`.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase()),
))
const selected = computed(() => visibleEntries.value.find(item => item.id === selectedId.value)
  ?? visibleEntries.value[0] ?? null)
const editorDirty = computed(() => !!editor.value && JSON.stringify(editor.value) !== editorOriginal.value)
const memberOptions = computed(() => session.members.filter(member =>
  member.id !== session.userId && !detail.value?.members.some(access => access.memberId === member.id),
))
const memberName = (id: string) => session.members.find(member => member.id === id)?.name ?? id
function revisionLines(revision: LedgerEntryRevision) {
  const fields: Array<[keyof LedgerEntryRevision['before'], string]> = [
    ['title', '事项'], ['counterparty', '对方'], ['category', '归类'], ['direction', '方向'],
    ['status', '状态'], ['amount', '原始金额'], ['occurredOn', '发生日期'], ['dueOn', '到期日'],
    ['evidence', '确认依据'], ['disputeNote', '争议说明'], ['nextAction', '下一步'],
  ]
  return fields.filter(([key]) => revision.before[key] !== revision.after[key]).map(([key, label]) => ({
    label, before: String(revision.before[key] ?? '未填'), after: String(revision.after[key] ?? '未填'),
  }))
}
const monthRows = computed(() => {
  const totals = new Map<string, { receivableCents: number; payableCents: number; count: number }>()
  for (const entry of entries.value) {
    if (entry.status !== 'CONFIRMED' || !entry.dueOn || entry.remainingAmount <= 0) continue
    const month = entry.dueOn.slice(0, 7)
    const row = totals.get(month) ?? { receivableCents: 0, payableCents: 0, count: 0 }
    if (entry.direction === 'RECEIVE') row.receivableCents += Math.round(entry.remainingAmount * 100)
    else row.payableCents += Math.round(entry.remainingAmount * 100)
    row.count++
    totals.set(month, row)
  }
  return [...totals.entries()].sort(([a], [b]) => a.localeCompare(b))
})
watch([category, query], () => { detailOpen.value = false })

async function refreshBooks(preferredId = bookId.value) {
  loading.value = true
  loadError.value = ''
  try {
    books.value = await listLedgerBooks()
    bookId.value = books.value.some(book => book.id === preferredId) ? preferredId : books.value[0]?.id ?? ''
    if (bookId.value) await refreshDetail(bookId.value)
    else detail.value = null
  } catch {
    loadError.value = '账务本读取失败。已保存的账目仍在服务端，请重试。'
    detail.value = null
  } finally {
    loading.value = false
  }
}

async function refreshDetail(id = bookId.value, preferredEntry = selectedId.value) {
  if (!id) return
  const result = await getLedgerBook(id)
  if (bookId.value !== id) return
  detail.value = result
  selectedId.value = result.entries.some(item => item.id === preferredEntry)
    ? preferredEntry : result.entries[0]?.id ?? null
  books.value = books.value.map(book => book.id === id ? result.book : book)
}

async function pickBook(id: string) {
  if (id === bookId.value) return
  if (editorDirty.value && !window.confirm('当前账目还有未保存的修改，切换账本会保留草稿。继续吗？')) return
  bookId.value = id
  selectedId.value = null
  detailOpen.value = false
  loadError.value = ''
  loading.value = true
  try { await refreshDetail(id) }
  catch { loadError.value = '账本读取失败，请重试。'; detail.value = null }
  finally { loading.value = false }
}

async function saveBook() {
  if (!bookTitle.value.trim() || creatingBook.value) return
  creatingBook.value = true
  try {
    const book = await createLedgerBook(bookTitle.value.trim())
    bookOpen.value = false
    bookTitle.value = ''
    await refreshBooks(book.id)
    toast('账本已创建')
  } catch { toast('账本创建失败，请重试') }
  finally { creatingBook.value = false }
}

async function saveShare() {
  if (!bookId.value || !shareMemberId.value || sharing.value) return
  sharing.value = true
  shareError.value = ''
  try {
    await shareLedgerBook(bookId.value, shareMemberId.value, shareRole.value)
    await refreshDetail()
    shareOpen.value = false
    shareMemberId.value = ''
    toast('共享成员已更新')
  } catch (error) { shareError.value = errorMessage(error) }
  finally { sharing.value = false }
}
async function changeShare(memberId: string, role: 'EDITOR' | 'VIEWER') {
  shareError.value = ''
  try { await shareLedgerBook(bookId.value, memberId, role); await refreshDetail() }
  catch (error) { shareError.value = errorMessage(error) }
}
async function removeShare(memberId: string) {
  if (!window.confirm(`移除 ${memberName(memberId)} 后，对方将不能再查看此账本。确定吗？`)) return
  shareError.value = ''
  try { await revokeLedgerBookAccess(bookId.value, memberId); await refreshDetail() }
  catch (error) { shareError.value = errorMessage(error) }
}

function draftKey() { return `wao-ledger-draft:${bookId.value}:${editingId.value ?? 'new'}` }
function emptyInput(): LedgerEntryInput {
  return { title: '', counterparty: '', category: 'OLD', direction: 'RECEIVE', status: 'TO_VERIFY',
    amount: 0, occurredOn: null, dueOn: null, evidence: '', disputeNote: '', nextAction: '', version: null }
}
function openEditor(item?: LedgerEntry) {
  if (!canEdit.value) return
  if (editorDirty.value) {
    if (!window.confirm('当前账目还有未保存的修改。草稿会保留，确定切换吗？')) return
    sessionStorage.setItem(draftKey(), JSON.stringify(editor.value))
  }
  editingId.value = item?.id ?? null
  const initial: LedgerEntryInput = item ? {
    title: item.title, counterparty: item.counterparty, category: item.category, direction: item.direction,
    status: item.status, amount: item.amount, occurredOn: item.occurredOn, dueOn: item.dueOn,
    evidence: item.evidence ?? '', disputeNote: item.disputeNote ?? '', nextAction: item.nextAction ?? '',
    version: item.version,
  } : emptyInput()
  editorOriginal.value = JSON.stringify(initial)
  let saved: LedgerEntryInput | null = null
  try { saved = JSON.parse(sessionStorage.getItem(draftKey()) ?? 'null') as LedgerEntryInput | null }
  catch { /* 损坏草稿不用来覆盖当前账目。 */ }
  editor.value = saved ? { ...initial, ...saved } : initial
  saveError.value = saved ? '已恢复上次未保存的草稿，请核对后保存。' : ''
}
watch(editor, value => {
  if (value && editorDirty.value) sessionStorage.setItem(draftKey(), JSON.stringify(value))
}, { deep: true, flush: 'sync' })

function closeEditor() {
  if (saving.value) return
  if (editorDirty.value && !window.confirm('修改尚未保存。草稿会留在本浏览器，确定关闭吗？')) return
  editor.value = null
  saveError.value = ''
}

function errorMessage(error: unknown) {
  return error instanceof Error && error.message ? error.message : '保存失败，请检查后重试。'
}

async function saveEntry() {
  if (!editor.value || !bookId.value || saving.value) return
  saving.value = true
  saveError.value = ''
  try {
    const input = { ...editor.value, amount: Number(editor.value.amount),
      occurredOn: editor.value.occurredOn || null, dueOn: editor.value.dueOn || null }
    const saved = editingId.value
      ? await updateLedgerEntry(bookId.value, editingId.value, input)
      : await createLedgerEntry(bookId.value, input)
    sessionStorage.removeItem(draftKey())
    editor.value = null
    try {
      await refreshDetail(bookId.value, saved.id)
      detailOpen.value = true
      toast('账目已保存')
    } catch {
      loadError.value = '账目已保存，但列表刷新失败。请重试读取。'
      toast('账目已保存，刷新失败')
    }
  } catch (error) { saveError.value = errorMessage(error) }
  finally { saving.value = false }
}

async function voidEntry() {
  if (!selected.value || !canEdit.value || !window.confirm('作废后这笔账仍会留痕，但不再计入汇总。确定作废吗？')) return
  const item = selected.value
  try {
    await updateLedgerEntry(bookId.value, item.id, {
      title: item.title, counterparty: item.counterparty, category: item.category,
      direction: item.direction, status: 'VOID', amount: item.amount,
      occurredOn: item.occurredOn, dueOn: item.dueOn, evidence: item.evidence ?? '',
      disputeNote: item.disputeNote ?? '', nextAction: item.nextAction ?? '', version: item.version,
    })
    await refreshDetail(bookId.value, item.id)
    toast('账目已作废')
  } catch (error) { toast(errorMessage(error)) }
}

function openSettlement() {
  settlementAmount.value = ''
  settlementDate.value = localToday()
  settlementNote.value = ''
  settlementError.value = ''
  settlementOpen.value = true
}
async function saveSettlement() {
  if (!selected.value || settling.value) return
  settling.value = true
  settlementError.value = ''
  try {
    await addLedgerSettlement(bookId.value, selected.value.id, {
      amount: Number(settlementAmount.value), settledOn: settlementDate.value, note: settlementNote.value,
    })
    await refreshDetail(bookId.value, selected.value.id)
    settlementOpen.value = false
    toast('结算已记录')
  } catch (error) { settlementError.value = errorMessage(error) }
  finally { settling.value = false }
}

function selectEntry(id: string) { selectedId.value = id; detailOpen.value = true }
function beforeUnload(event: BeforeUnloadEvent) { if (editorDirty.value) event.preventDefault() }
onBeforeRouteLeave(() => !editorDirty.value || window.confirm('账目草稿尚未保存，离开后可在本浏览器恢复。确定离开吗？'))
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void refreshBooks() })
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>

<template>
  <div class="ledger">
    <header class="topbar">
      <div><h1>账务本</h1><p>把要明算的旧账、烂账和未来账逐笔理清楚。</p></div>
      <div class="top-actions">
        <button class="ghost" @click="bookOpen = true"><i class="ri-book-2-line"></i> 新建账本</button>
        <button v-if="canShare" class="ghost" @click="shareOpen = true"><i class="ri-user-add-line"></i> 共享成员</button>
        <button v-if="canEdit && pane === 'entries'" class="primary" @click="openEditor()"><i class="ri-add-line"></i> 记一笔</button>
      </div>
    </header>
    <div v-if="loadError" class="notice error" role="alert">{{ loadError }} <button @click="refreshBooks()">重试</button></div>
    <div v-if="loading" class="notice">账务本加载中…</div>
    <div v-else-if="!books.length && !loadError" class="first-empty">
      <i class="ri-book-2-line"></i><h2>还没有账本</h2>
      <p>先建一本，只记录双方决定明算的账。创建后默认只有你能看到。</p>
      <button class="primary" @click="bookOpen = true">创建账本</button>
    </div>
    <template v-else-if="detail">
      <div class="summary">
        <div><span>已确认待收</span><strong>{{ money(detail.summary.receivable) }}</strong></div>
        <div><span>已确认待付</span><strong>{{ money(detail.summary.payable) }}</strong></div>
        <div><span>净额</span><strong>{{ money(detail.summary.net) }}</strong></div>
        <div><span>待核对</span><strong>{{ detail.summary.toVerifyCount + detail.summary.disputedCount }} 笔</strong></div>
      </div>
      <div class="scope-note">只统计已确认的未结金额；争议、待核实、作废及私下不入账事项不计入。</div>
      <div class="workspace">
        <aside class="books">
          <div class="column-title">账本</div>
          <button v-for="book in books" :key="book.id" class="book" :class="{ active: book.id === bookId }" @click="pickBook(book.id)">
            <i class="ri-book-open-line"></i><span>{{ book.title }}</span>
          </button>
          <div class="share-hint">{{ activeBook?.role === 'OWNER' ? '仅你与邀请的成员可查看' : '你受邀加入了此账本' }}</div>
        </aside>
        <main class="content">
          <div class="switches"><button :class="{ active: pane === 'entries' }" @click="pane = 'entries'">明账</button><button :class="{ active: pane === 'plan' }" @click="pane = 'plan'">到期计划</button></div>
          <template v-if="pane === 'entries'">
            <div class="filters">
              <button v-for="option in [{ id: 'ALL', label: '全部' }, { id: 'OLD', label: '旧账' }, { id: 'BAD', label: '烂账' }, { id: 'FUTURE', label: '未来账' }]" :key="option.id" :class="{ active: category === option.id }" @click="category = option.id as typeof category">{{ option.label }}</button>
              <input v-model="query" type="search" placeholder="搜索事项或对方" aria-label="搜索账目" />
            </div>
            <div class="entry-layout">
              <div class="entry-list" :class="{ mobileHidden: detailOpen }">
                <button v-for="item in visibleEntries" :key="item.id" class="entry-row" :class="{ active: selected?.id === item.id }" @click="selectEntry(item.id)">
                  <span class="entry-top"><b>{{ item.title }}</b><span>{{ money(item.remainingAmount) }}</span></span>
                  <span class="entry-bottom">{{ categoryName[item.category] }} · {{ item.counterparty }} · {{ directionName[item.direction] }} <em>{{ statusName[item.status] }}</em></span>
                </button>
                <div v-if="!visibleEntries.length" class="empty">{{ entries.length ? '没有符合筛选条件的账目' : '还没有明账，点“记一笔”开始。' }}</div>
              </div>
              <section class="entry-detail" :class="{ mobileOpen: detailOpen }">
                <button class="mobile-back" @click="detailOpen = false"><i class="ri-arrow-left-line"></i> 返回账目</button>
                <template v-if="selected">
                  <div class="detail-head"><div><h2>{{ selected.title }}</h2><p>{{ categoryName[selected.category] }} · {{ statusName[selected.status] }}</p></div><div class="spacer"></div><button v-if="canEdit" class="ghost" @click="openEditor(selected)">编辑</button><button v-if="canEdit && selected.status !== 'VOID'" class="ghost danger" @click="voidEntry">作废</button></div>
                  <div class="amount-line"><div><small>原始金额</small><strong>{{ money(selected.amount) }}</strong></div><div><small>已结</small><strong>{{ money(selected.settledAmount) }}</strong></div><div><small>未结</small><strong>{{ money(selected.remainingAmount) }}</strong></div></div>
                  <dl class="facts"><div><dt>方向</dt><dd>{{ directionName[selected.direction] }}</dd></div><div><dt>对方</dt><dd>{{ selected.counterparty }}</dd></div><div><dt>发生日期</dt><dd>{{ selected.occurredOn || '待补充' }}</dd></div><div><dt>到期 / 复核日</dt><dd>{{ selected.dueOn || '日期未定' }}</dd></div><div><dt>确认依据</dt><dd>{{ selected.evidence || '尚未填写' }}</dd></div><div><dt>争议 / 风险</dt><dd>{{ selected.disputeNote || '无' }}</dd></div><div><dt>下一步</dt><dd>{{ selected.nextAction || '待约定' }}</dd></div></dl>
                  <div class="settle-head"><h3>结算记录</h3><button v-if="canEdit && selected.status !== 'VOID' && selected.remainingAmount > 0" class="ghost" @click="openSettlement">记录结算</button></div>
                  <div v-if="!selected.settlements.length" class="empty compact">尚无结算记录</div>
                  <div v-for="record in selected.settlements" :key="record.id" class="settle-row"><span>{{ record.settledOn }} · {{ memberName(record.createdBy) }}</span><b>{{ money(record.amount) }}</b><small v-if="record.note">{{ record.note }}</small></div>
                  <h3 class="revision-title">修改记录</h3>
                  <div v-if="!selected.revisions.length" class="empty compact">这笔账还没有修改记录</div>
                  <details v-for="revision in selected.revisions" :key="revision.id" class="revision">
                    <summary>{{ revision.changedAt.slice(0, 16).replace('T', ' ') }} · {{ memberName(revision.changedBy) }} 修改</summary>
                    <div v-for="line in revisionLines(revision)" :key="line.label" class="revision-line"><b>{{ line.label }}</b><span>{{ line.before }} → {{ line.after }}</span></div>
                  </details>
                </template>
                <div v-else class="empty">选择一笔账查看详情</div>
              </section>
            </div>
          </template>
          <div v-else class="plan">
            <p>以下仅是有到期日的已确认未结明账，帮助安排核对和收付；没有收入、日常支出与期初现金时，不能据此判断月末结余。</p>
            <div v-if="detail.summary.undatedConfirmedCount" class="notice warn">还有 {{ detail.summary.undatedConfirmedCount }} 笔已确认未结账目没有到期日，未列入月份。</div>
            <table v-if="monthRows.length"><thead><tr><th>月份</th><th>到期应收</th><th>到期应付</th><th>笔数</th></tr></thead><tbody><tr v-for="[month, row] in monthRows" :key="month"><td>{{ month }}</td><td>{{ money(row.receivableCents / 100) }}</td><td>{{ money(row.payableCents / 100) }}</td><td>{{ row.count }}</td></tr></tbody></table>
            <div v-else class="empty">暂无已确认且填写了到期日的未结账目</div>
          </div>
        </main>
      </div>
    </template>

    <div v-if="bookOpen" class="mask" @click="bookOpen = false"><div class="dialog small" role="dialog" aria-modal="true" aria-label="新建账本" @click.stop><div class="dialog-head"><h2>新建账本</h2><button @click="bookOpen = false">×</button></div><p>创建后仅你可见，之后可以邀请 WAO 成员加入。</p><label>名称<input v-model="bookTitle" maxlength="128" placeholder="例如：兄弟明账" @keydown.enter="saveBook" /></label><div class="dialog-actions"><button class="ghost" @click="bookOpen = false">取消</button><button class="primary" :disabled="!bookTitle.trim() || creatingBook" @click="saveBook">{{ creatingBook ? '创建中…' : '创建' }}</button></div></div></div>
    <div v-if="shareOpen" class="mask" @click="shareOpen = false"><div class="dialog small" role="dialog" aria-modal="true" aria-label="共享账本" @click.stop><div class="dialog-head"><h2>共享账本</h2><button @click="shareOpen = false">×</button></div><p>邀请后对方可看到本账本全部明账与结算记录。</p><div v-for="member in detail?.members" :key="member.memberId" class="shared-member">{{ memberName(member.memberId) }} <select :value="member.role" :aria-label="`${memberName(member.memberId)} 的权限`" @change="changeShare(member.memberId, ($event.target as HTMLSelectElement).value as 'EDITOR' | 'VIEWER')"><option value="EDITOR">可编辑</option><option value="VIEWER">只读</option></select><button class="ghost danger" @click="removeShare(member.memberId)">移除</button></div><label>WAO 成员<select v-model="shareMemberId"><option value="">选择成员</option><option v-for="member in memberOptions" :key="member.id" :value="member.id">{{ member.name }}</option></select></label><label>权限<select v-model="shareRole"><option value="EDITOR">可编辑</option><option value="VIEWER">只读</option></select></label><p v-if="shareError" class="error-text" role="alert">{{ shareError }}</p><div class="dialog-actions"><button class="ghost" @click="shareOpen = false">取消</button><button class="primary" :disabled="!shareMemberId || sharing" @click="saveShare">{{ sharing ? '保存中…' : '邀请' }}</button></div></div></div>
    <div v-if="editor" class="mask" @click="closeEditor"><div class="dialog editor-dialog" role="dialog" aria-modal="true" aria-label="编辑明账" @click.stop><div class="dialog-head"><h2>{{ editingId ? '编辑明账' : '记一笔明账' }}</h2><button @click="closeEditor">×</button></div><div class="form-grid"><label>事项 *<input v-model="editor.title" maxlength="160" placeholder="这笔账是什么" /></label><label>对方 *<input v-model="editor.counterparty" maxlength="160" placeholder="与谁核对" /></label><label>归类<select v-model="editor.category"><option value="OLD">旧账</option><option value="BAD">烂账</option><option value="FUTURE">未来账</option></select></label><label>方向<select v-model="editor.direction"><option value="RECEIVE">应收</option><option value="PAY">应付</option></select></label><label>原始金额（元） *<input v-model.number="editor.amount" type="number" min="0.01" step="0.01" /></label><label>核实状态<select v-model="editor.status"><option value="TO_VERIFY">待核实</option><option value="CONFIRMED">已确认</option><option value="DISPUTED">有争议</option><option value="VOID">已作废</option></select></label><label>发生日期<input v-model="editor.occurredOn" type="date" /></label><label>到期 / 复核日<input v-model="editor.dueOn" type="date" /></label><label class="wide">确认依据 {{ editor.status === 'CONFIRMED' ? '*' : '' }}<textarea v-model="editor.evidence" maxlength="1000" rows="2" placeholder="合同、转账记录或双方确认的线索"></textarea></label><label class="wide">争议 / 风险 {{ editor.status === 'DISPUTED' ? '*' : '' }}<textarea v-model="editor.disputeNote" maxlength="1000" rows="2" placeholder="金额、事实或回收性的问题"></textarea></label><label class="wide">下一步约定<textarea v-model="editor.nextAction" maxlength="1000" rows="2" placeholder="下次核对时间和动作"></textarea></label></div><p class="hint">私下心知肚明、不准备明算的事项无需录入。</p><p v-if="saveError" class="error-text" role="alert">{{ saveError }}</p><div class="dialog-actions"><button class="ghost" @click="closeEditor">关闭</button><button class="primary" :disabled="!editor.title.trim() || !editor.counterparty.trim() || !Number(editor.amount) || saving" @click="saveEntry">{{ saving ? '保存中…' : '保存明账' }}</button></div></div></div>
    <div v-if="settlementOpen" class="mask" @click="settlementOpen = false"><div class="dialog small" role="dialog" aria-modal="true" aria-label="记录结算" @click.stop><div class="dialog-head"><h2>记录结算</h2><button @click="settlementOpen = false">×</button></div><p>结算记录会保留，不能直接删除；请核对金额与日期。</p><label>本次结算金额（元）<input v-model.number="settlementAmount" type="number" min="0.01" :max="selected?.remainingAmount" step="0.01" /></label><label>结算日期<input v-model="settlementDate" type="date" /></label><label>说明<textarea v-model="settlementNote" rows="2" maxlength="500" placeholder="例如：银行转账，凭据位置"></textarea></label><p v-if="settlementError" class="error-text" role="alert">{{ settlementError }}</p><div class="dialog-actions"><button class="ghost" @click="settlementOpen = false">取消</button><button class="primary" :disabled="!Number(settlementAmount) || !settlementDate || settling" @click="saveSettlement">{{ settling ? '记录中…' : '确认记录' }}</button></div></div></div>
  </div>
</template>

<style scoped>
.ledger { height: 100%; min-height: 0; display: flex; flex-direction: column; padding: 20px 24px; box-sizing: border-box; color: var(--wao-text); }
button { cursor: pointer; font: inherit; }
.topbar,.top-actions,.detail-head,.settle-head,.dialog-head,.dialog-actions,.filters,.switches { display: flex; align-items: center; gap: 10px; }
.topbar { justify-content: space-between; flex-wrap: wrap; } h1 { margin: 0; font-size: 23px; } h2 { margin: 0; font-size: 18px; } h3 { margin: 0; font-size: 14px; }
.topbar p,.dialog p { margin: 4px 0; color: var(--wao-text-weak); font-size: 13px; }
.spacer { flex: 1; }.top-actions { flex-wrap: wrap; }
.ghost,.primary { border-radius: 8px; padding: 8px 13px; border: 1px solid var(--wao-line-strong); background: var(--wao-bg-card); color: var(--wao-text); white-space: nowrap; }
.primary { border-color: var(--wao-primary); background: var(--wao-primary); color: white; }.ghost.danger { color: #a83432; }.primary:disabled,.ghost:disabled { opacity: .5; cursor: default; }
.notice { margin: 12px 0; padding: 12px 14px; border-radius: 9px; background: var(--wao-bg-sub); font-size: 13px; }.notice.error { color: #9d2828; background: var(--wao-danger-bg); }.notice.warn { color: #80531c; background: var(--wao-warning-bg); }.notice button { margin-left: 10px; }
.summary { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 12px; margin-top: 18px; }.summary>div { border: 1px solid var(--wao-line); border-radius: 11px; padding: 12px 15px; background: var(--wao-bg-card); display: flex; flex-direction: column; gap: 4px; }.summary span { font-size: 12px; color: var(--wao-text-weak); }.summary strong { font-size: 19px; font-variant-numeric: tabular-nums; }.scope-note { font-size: 12px; color: var(--wao-text-weak); padding: 8px 2px 12px; }
.workspace { flex: 1; min-height: 0; display: flex; border: 1px solid var(--wao-line); border-radius: 12px; overflow: hidden; background: var(--wao-bg-card); }.books { width: 190px; flex-shrink: 0; background: var(--wao-bg-sub); border-right: 1px solid var(--wao-line); padding: 12px 8px; overflow: auto; }.column-title { color: var(--wao-text-weak); font-size: 11px; letter-spacing: 1px; padding: 3px 8px 10px; }.book { width: 100%; border: 0; background: transparent; color: var(--wao-text); display: flex; align-items: center; gap: 8px; text-align: left; padding: 10px 9px; border-radius: 8px; }.book span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.book.active,.filters button.active,.switches button.active { background: var(--wao-primary-soft); color: var(--wao-primary-text); }.share-hint { padding: 12px 8px; color: var(--wao-text-weak); font-size: 11px; line-height: 1.5; }.content { flex: 1; min-width: 0; display: flex; flex-direction: column; }.switches { border-bottom: 1px solid var(--wao-line); padding: 8px 14px; }.switches button,.filters button { border: 0; background: transparent; color: var(--wao-text-2); border-radius: 7px; padding: 6px 11px; }.filters { padding: 10px 14px; flex-wrap: wrap; border-bottom: 1px solid var(--wao-line); }.filters input { margin-left: auto; max-width: 220px; }
.entry-layout { flex: 1; min-height: 0; display: flex; }.entry-list { width: 37%; min-width: 240px; overflow: auto; border-right: 1px solid var(--wao-line); }.entry-row { display: block; width: 100%; border: 0; border-bottom: 1px solid var(--wao-line); background: transparent; text-align: left; padding: 14px; color: var(--wao-text); }.entry-row.active { background: var(--wao-primary-soft); }.entry-top,.entry-bottom { display: flex; justify-content: space-between; gap: 8px; }.entry-top b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.entry-top span { flex-shrink: 0; font-variant-numeric: tabular-nums; }.entry-bottom { margin-top: 7px; color: var(--wao-text-weak); font-size: 12px; }.entry-bottom em { font-style: normal; flex-shrink: 0; }.entry-detail { flex: 1; min-width: 0; overflow: auto; padding: 18px 22px; }.detail-head p { color: var(--wao-text-weak); font-size: 12px; margin: 5px 0 0; }.amount-line { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); margin: 20px 0; gap: 8px; }.amount-line>div { display: flex; flex-direction: column; padding: 13px; border-radius: 9px; background: var(--wao-bg-sub); }.amount-line small { color: var(--wao-text-weak); }.amount-line strong { margin-top: 5px; font-size: 16px; font-variant-numeric: tabular-nums; }.facts { margin: 0; }.facts>div { display: grid; grid-template-columns: 125px 1fr; padding: 9px 0; border-bottom: 1px solid var(--wao-line); font-size: 13px; }.facts dt { color: var(--wao-text-weak); }.facts dd { margin: 0; overflow-wrap: anywhere; white-space: pre-wrap; }.settle-head { margin-top: 24px; }.settle-head button { margin-left: auto; }.settle-row { padding: 10px 0; border-bottom: 1px solid var(--wao-line); display: flex; flex-wrap: wrap; gap: 8px; font-size: 12px; }.settle-row b { margin-left: auto; }.settle-row small { width: 100%; color: var(--wao-text-weak); }.empty { padding: 48px 18px; text-align: center; color: var(--wao-text-weak); font-size: 13px; }.empty.compact { padding: 22px; }.first-empty { margin: auto; text-align: center; }.first-empty i { font-size: 32px; color: var(--wao-primary); }.first-empty p { color: var(--wao-text-weak); }.plan { padding: 20px; overflow: auto; }.plan p { color: var(--wao-text-2); font-size: 13px; line-height: 1.7; }.plan table { width: 100%; border-collapse: collapse; font-size: 13px; }.plan th,.plan td { text-align: left; border-bottom: 1px solid var(--wao-line); padding: 12px; }.plan th { color: var(--wao-text-weak); }.mobile-back { display: none; }
.mask { position: fixed; inset: 0; z-index: 1000; background: #11182788; display: flex; align-items: center; justify-content: center; padding: 18px; box-sizing: border-box; }.dialog { width: min(650px,100%); max-height: min(85vh,850px); overflow: auto; border-radius: 14px; padding: 22px; box-sizing: border-box; background: var(--wao-bg-card); box-shadow: 0 18px 55px #0003; }.dialog.small { width: min(440px,100%); }.dialog-head { justify-content: space-between; margin-bottom: 12px; }.dialog-head button { border: 0; background: none; color: var(--wao-text-weak); font-size: 24px; }.dialog label { display: flex; flex-direction: column; gap: 5px; font-size: 12px; margin: 10px 0; }.dialog input,.dialog select,.dialog textarea,.filters input { box-sizing: border-box; border: 1px solid var(--wao-line-strong); border-radius: 7px; padding: 9px 10px; background: var(--wao-bg-card); color: var(--wao-text); font: inherit; }.dialog-actions { justify-content: flex-end; margin-top: 18px; }.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 14px; }.form-grid .wide { grid-column: 1 / -1; }.hint { font-size: 12px; }.error-text { color: #a83432 !important; }.shared-member { padding: 7px 0; display: flex; justify-content: space-between; font-size: 12px; }.shared-member span { color: var(--wao-text-weak); }
.revision-title { margin-top: 24px; }.revision { border-bottom: 1px solid var(--wao-line); padding: 9px 0; font-size: 12px; }.revision summary { cursor: pointer; color: var(--wao-text-2); }.revision-line { display: grid; grid-template-columns: 90px 1fr; gap: 8px; margin: 8px 0 0; overflow-wrap: anywhere; }.revision-line b { color: var(--wao-text-weak); }.shared-member { align-items: center; gap: 6px; }.shared-member select { width: auto; }.shared-member button { padding: 5px 8px; }
@media (max-width: 760px) { .ledger { padding: 14px; }.summary { grid-template-columns: repeat(2,minmax(0,1fr)); }.summary strong { font-size: 15px; }.workspace { flex-direction: column; }.books { width: auto; display: flex; overflow-x: auto; border-right: 0; border-bottom: 1px solid var(--wao-line); padding: 5px; }.books .column-title,.share-hint { display: none; }.book { width: auto; flex-shrink: 0; }.content { min-height: 0; }.entry-list { width: 100%; border-right: 0; }.entry-list.mobileHidden { display: none; }.entry-detail { display: none; }.entry-detail.mobileOpen { display: block; }.mobile-back { display: block; border: 0; background: transparent; color: var(--wao-primary-text); padding: 0 0 15px; }.amount-line { grid-template-columns: 1fr; }.form-grid { grid-template-columns: 1fr; }.form-grid .wide { grid-column: auto; }.filters input { margin-left: 0; max-width: none; width: 100%; }.top-actions { width: 100%; }.top-actions button { flex: 1; } }
</style>
