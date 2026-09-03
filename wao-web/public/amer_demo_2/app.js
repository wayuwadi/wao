const STORAGE_KEY = 'amer-risk-demo-v2';
const SCHEMA_VERSION = 2;

const today = new Date();
today.setHours(0, 0, 0, 0);

const isoDay = (offset = 0) => {
  const date = new Date(today);
  date.setDate(date.getDate() + offset);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
};

const roles = {
  security: {
    label: '安全部门',
    user: '林晓红',
    short: '林',
    note: '可登记风险、分配负责人并查看全部记录',
    canCreateRisk: true,
    canCreateAction: true,
    canAssess: true,
  },
  riskOwner: {
    label: 'Risk Owner',
    user: '陈晨',
    short: '陈',
    note: '查看并维护本人负责的 Risk 与关联记录',
    canCreateRisk: false,
    canCreateAction: true,
    canAssess: true,
  },
  actionOwner: {
    label: 'Action Owner',
    user: '赵明',
    short: '赵',
    note: '查看本人 Action、截止日期并更新执行进度',
    canCreateRisk: false,
    canCreateAction: false,
    canAssess: false,
  },
};

const seed = {
  version: SCHEMA_VERSION,
  risks: [
    {
      id: 'RSK-2026-001',
      title: '第三方远程访问账号未按期复核',
      description: '季度权限复核发现部分外部运维账号已超过建议复核周期，需要确认业务必要性并回收无效权限。',
      category: '访问控制',
      status: 'Mitigating',
      owner: '陈晨',
      targetDate: isoDay(12),
      createdDate: isoDay(-14),
      level: 'High',
    },
    {
      id: 'RSK-2026-002',
      title: '备份恢复演练证据不完整',
      description: '最近一次恢复演练缺少关键截图、恢复耗时和业务确认记录。',
      category: '业务连续性',
      status: 'Assessing',
      owner: '刘洋',
      targetDate: isoDay(21),
      createdDate: isoDay(-9),
      level: 'Medium',
    },
    {
      id: 'RSK-2026-003',
      title: '关键应用补丁窗口超期',
      description: '核心应用的高危漏洞修复未在计划窗口内完成，需要重新安排变更并跟踪验证。',
      category: '漏洞管理',
      status: 'Mitigating',
      owner: '王蕾',
      targetDate: isoDay(7),
      createdDate: isoDay(-20),
      level: 'High',
    },
    {
      id: 'RSK-2026-004',
      title: '生产日志留存周期不一致',
      description: '不同系统的安全日志留存策略不一致，需要统一基线并确认容量。',
      category: '安全监控',
      status: 'Registered',
      owner: '陈晨',
      targetDate: isoDay(30),
      createdDate: isoDay(-3),
      level: 'Medium',
    },
  ],
  actions: [
    { id: 'ACT-001', riskId: 'RSK-2026-001', title: '完成第三方账号清单与业务确认', owner: '赵明', dueDate: isoDay(-1), status: 'Completed', progress: 100 },
    { id: 'ACT-002', riskId: 'RSK-2026-001', title: '回收无有效业务保荐人的权限', owner: '赵明', dueDate: isoDay(2), status: 'In Progress', progress: 60 },
    { id: 'ACT-003', riskId: 'RSK-2026-001', title: '建立季度账号复核提醒', owner: '孙宇', dueDate: isoDay(10), status: 'Not Started', progress: 0 },
    { id: 'ACT-004', riskId: 'RSK-2026-002', title: '补齐恢复演练证据和业务签字', owner: '刘洋', dueDate: isoDay(8), status: 'In Progress', progress: 35 },
    { id: 'ACT-005', riskId: 'RSK-2026-003', title: '安排紧急补丁变更窗口', owner: '赵明', dueDate: isoDay(-2), status: 'In Progress', progress: 75 },
  ],
  assessments: [
    { id: 'ASM-001', riskId: 'RSK-2026-001', type: 'Initial', likelihood: 4, impact: 5, assessor: '孙倩', date: isoDay(-13), notes: '高权限账号数量较多，且复核已超期。' },
    { id: 'ASM-002', riskId: 'RSK-2026-001', type: 'Residual', likelihood: 2, impact: 4, assessor: '孙倩', date: isoDay(-1), notes: '完成账号确认后暴露面下降，仍需完成权限回收。' },
    { id: 'ASM-003', riskId: 'RSK-2026-002', type: 'Initial', likelihood: 3, impact: 4, assessor: '孙倩', date: isoDay(-7), notes: '备份可用，但审计证据不足。' },
    { id: 'ASM-004', riskId: 'RSK-2026-003', type: 'Initial', likelihood: 4, impact: 5, assessor: '李佳', date: isoDay(-18), notes: '高危补丁未按计划完成。' },
  ],
};

const clone = (value) => JSON.parse(JSON.stringify(value));
const loadData = () => {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY));
    return parsed?.version === SCHEMA_VERSION ? parsed : clone(seed);
  } catch {
    return clone(seed);
  }
};

let data = loadData();
let state = {
  page: 'risks',
  role: 'security',
  selectedRiskId: data.risks[0]?.id,
  detailTab: 'actions',
  query: '',
  status: 'All',
  drawer: null,
  editingActionId: null,
};

const root = document.querySelector('#app');
const esc = (value = '') => String(value).replace(/[&<>'"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' })[char]);
const save = () => localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
const selectedRisk = () => data.risks.find((risk) => risk.id === state.selectedRiskId) || data.risks[0];
const actionsFor = (riskId) => data.actions.filter((action) => action.riskId === riskId);
const assessmentsFor = (riskId) => data.assessments.filter((assessment) => assessment.riskId === riskId);
const formatDate = (value) => new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit' }).format(new Date(`${value}T00:00:00`));
const score = (assessment) => assessment.likelihood * assessment.impact;
const levelFromScore = (value) => value >= 15 ? 'High' : value >= 7 ? 'Medium' : 'Low';

const statusText = {
  Registered: '已登记',
  Assessing: '评估中',
  Mitigating: '整改中',
  Closed: '已关闭',
  'Not Started': '未开始',
  'In Progress': '进行中',
  Completed: '已完成',
};
const statusClass = (value) => value.toLowerCase().replaceAll(' ', '-');
const levelText = { High: '高风险', Medium: '中风险', Low: '低风险' };

function dueState(action) {
  if (action.status === 'Completed') return { key: 'completed', label: '已完成', days: null };
  const due = new Date(`${action.dueDate}T00:00:00`);
  const days = Math.round((due - today) / 86400000);
  if (days < 0) return { key: 'overdue', label: `逾期 ${Math.abs(days)} 天`, days };
  if (days <= 3) return { key: 'due-soon', label: `${days} 天内到期`, days };
  return { key: 'on-track', label: '正常', days };
}

function visibleRisks() {
  const role = roles[state.role];
  let risks = [...data.risks];
  if (state.role === 'riskOwner') risks = risks.filter((risk) => risk.owner === role.user);
  if (state.role === 'actionOwner') {
    const riskIds = new Set(data.actions.filter((action) => action.owner === role.user).map((action) => action.riskId));
    risks = risks.filter((risk) => riskIds.has(risk.id));
  }
  if (state.query) {
    const term = state.query.trim().toLowerCase();
    risks = risks.filter((risk) => `${risk.id} ${risk.title} ${risk.category} ${risk.owner}`.toLowerCase().includes(term));
  }
  if (state.status !== 'All') risks = risks.filter((risk) => risk.status === state.status);
  return risks;
}

function visibleActions() {
  const role = roles[state.role];
  if (state.role === 'security') return [...data.actions];
  if (state.role === 'actionOwner') return data.actions.filter((action) => action.owner === role.user);
  const ownedRisks = new Set(data.risks.filter((risk) => risk.owner === role.user).map((risk) => risk.id));
  return data.actions.filter((action) => ownedRisks.has(action.riskId));
}

function processStage(risk) {
  if (risk.status === 'Registered') return 1;
  if (risk.status === 'Assessing') return 2;
  if (risk.status === 'Closed') return 4;
  return 3;
}

function shell() {
  const role = roles[state.role];
  return `
    <div class="shell">
      <header class="powerbar">
        <div class="waffle" aria-hidden="true">⠿</div>
        <div class="powerbrand"><div class="powerlogo"><span>P</span></div><strong>Power Apps</strong></div>
        <div class="appname"><span>IT Risk Management</span><b class="demo-tag">HTML Demo · 模拟数据</b></div>
        <label class="role-label" for="role-switch">查看身份</label>
        <select class="role-select" id="role-switch" aria-label="切换演示用户身份">
          ${Object.entries(roles).map(([key, item]) => `<option value="${key}" ${state.role === key ? 'selected' : ''}>${item.label}</option>`).join('')}
        </select>
        <div class="avatar" aria-label="当前用户 ${role.user}">${role.short}</div>
      </header>
      <div class="workspace">
        <aside class="sidenav">
          <div class="nav-caption">应用导航</div>
          ${navButton('risks', '▤', 'Risk Register')}
          ${navButton('actions', '✓', 'My Actions')}
          ${navButton('reminders', '◷', '到期提醒')}
          ${navButton('model', '⌘', '数据模型')}
          <div class="nav-bottom"><strong>${role.label} · ${role.user}</strong>${role.note}</div>
        </aside>
        <main class="main">
          ${commandBar()}
          <div class="content">${page()}</div>
        </main>
      </div>
      ${drawer()}
    </div>`;
}

function navButton(page, icon, label) {
  const active = state.page === page || (page === 'risks' && state.page === 'detail');
  return `<button class="nav-btn ${active ? 'active' : ''}" data-action="nav" data-page="${page}"><span class="nav-icon">${icon}</span><span>${label}</span></button>`;
}

function commandBar() {
  const role = roles[state.role];
  let commands = '';
  if (state.page === 'risks') {
    commands = `<button class="cmd-btn primary" data-action="open-drawer" data-drawer="risk" ${role.canCreateRisk ? '' : 'disabled'}><span>＋</span> New Risk</button><span class="cmd-sep"></span><button class="cmd-btn" data-action="refresh">↻ Refresh</button>`;
  } else if (state.page === 'detail') {
    commands = `<button class="cmd-btn" data-action="back">‹ Back</button><span class="cmd-sep"></span><button class="cmd-btn primary" data-action="open-drawer" data-drawer="action" ${role.canCreateAction ? '' : 'disabled'}>＋ New Action</button><button class="cmd-btn" data-action="open-drawer" data-drawer="assessment" ${role.canAssess ? '' : 'disabled'}>＋ New Assessment</button>`;
  } else if (state.page === 'actions') {
    commands = `<button class="cmd-btn" data-action="refresh">↻ Refresh</button>`;
  } else {
    commands = `<button class="cmd-btn" data-action="refresh">↻ Refresh</button>`;
  }
  return `<div class="commandbar">${commands}<span class="command-spacer"></span><span class="role-note">${esc(role.note)}</span></div>`;
}

function page() {
  if (state.page === 'detail') return detailPage();
  if (state.page === 'actions') return actionsPage();
  if (state.page === 'reminders') return remindersPage();
  if (state.page === 'model') return modelPage();
  return risksPage();
}

function flowStrip(active = 1) {
  const steps = [
    ['登记 Risk', '安全部门创建记录'],
    ['风险评估', '记录 Assessment'],
    ['执行 Action', 'Owner 跟踪截止日'],
    ['复评与关闭', '保留评估记录'],
  ];
  return `<div class="flow-strip">${steps.map((step, index) => {
    const no = index + 1;
    const mode = no < active ? 'done' : no === active ? 'current' : '';
    return `<div class="flow-step ${mode}"><span class="step-no">${no < active ? '✓' : no}</span><div><strong>${step[0]}</strong><small>${step[1]}</small></div></div>`;
  }).join('')}</div>`;
}

function risksPage() {
  const risks = visibleRisks();
  const role = roles[state.role];
  const open = risks.filter((risk) => risk.status !== 'Closed').length;
  const high = risks.filter((risk) => risk.level === 'High' && risk.status !== 'Closed').length;
  const overdue = visibleActions().filter((action) => dueState(action).key === 'overdue').length;
  const dueSoon = visibleActions().filter((action) => dueState(action).key === 'due-soon').length;
  return `
    <div class="page-title"><div><h1>Risk Register</h1><p>${state.role === 'security' ? '统一登记、评估并跟踪 IT 风险' : `显示与 ${role.user} 相关的风险记录`}</p></div></div>
    ${flowStrip(1)}
    <div class="kpis">
      ${kpi('开放 Risk', open, '▤')}${kpi('高风险', high, '⚠')}${kpi('逾期 Action', overdue, '!')}${kpi('3 天内到期', dueSoon, '◷')}
    </div>
    <section class="panel">
      <div class="panel-head"><h2>Risks <span class="count">(${risks.length})</span></h2></div>
      <div class="toolbar">
        <input id="risk-search" class="search" value="${esc(state.query)}" placeholder="搜索 Risk ID、标题、分类或 Owner" aria-label="搜索风险">
        <select id="status-filter" class="filter-select" aria-label="按状态筛选">
          ${[['All','全部状态'],['Registered','已登记'],['Assessing','评估中'],['Mitigating','整改中'],['Closed','已关闭']].map(([value,label]) => `<option value="${value}" ${state.status === value ? 'selected' : ''}>${label}</option>`).join('')}
        </select>
      </div>
      <div class="table-scroll">${riskTable(risks)}</div>
    </section>`;
}

function kpi(label, value, icon) {
  return `<div class="kpi"><div><div class="kpi-label">${label}</div><div class="kpi-value">${value}</div></div><div class="kpi-icon">${icon}</div></div>`;
}

function riskTable(risks) {
  if (!risks.length) return '<div class="empty">没有符合当前条件的 Risk。</div>';
  return `<table class="data-table"><thead><tr><th style="width:36%">Risk</th><th>Level</th><th>Status</th><th>Risk Owner</th><th>Target Date</th></tr></thead><tbody>${risks.map((risk) => `
    <tr class="clickable" tabindex="0" data-action="open-risk" data-id="${risk.id}">
      <td><span class="risk-title">${esc(risk.title)}</span><span class="risk-id">${risk.id} · ${esc(risk.category)}</span></td>
      <td><span class="risk-level ${risk.level.toLowerCase()}">${levelText[risk.level]}</span></td>
      <td><span class="status ${statusClass(risk.status)}">${statusText[risk.status]}</span></td>
      <td><div class="owner-cell"><span class="mini-avatar">${esc(risk.owner.slice(0,1))}</span>${esc(risk.owner)}</div></td>
      <td>${risk.targetDate}</td>
    </tr>`).join('')}</tbody></table>`;
}

function detailPage() {
  const risk = selectedRisk();
  if (!risk) return '<div class="empty">Risk 不存在。</div>';
  const riskActions = actionsFor(risk.id);
  const riskAssessments = assessmentsFor(risk.id);
  const completed = riskActions.filter((action) => action.status === 'Completed').length;
  const initial = riskAssessments.find((assessment) => assessment.type === 'Initial');
  const residual = [...riskAssessments].reverse().find((assessment) => assessment.type === 'Residual');
  return `
    <div class="breadcrumb"><button data-action="back">Risk Register</button> / ${risk.id}</div>
    <div class="record-header">
      <div class="record-main"><div class="risk-square">R</div><div><h1>${esc(risk.title)}</h1><div class="record-meta"><span>${risk.id}</span><span>·</span><span class="risk-level ${risk.level.toLowerCase()}">${levelText[risk.level]}</span><span class="status ${statusClass(risk.status)}">${statusText[risk.status]}</span></div></div></div>
      <div class="record-fields">
        <div class="field"><label>Risk Owner</label><strong>${esc(risk.owner)}</strong></div>
        <div class="field"><label>Category</label><strong>${esc(risk.category)}</strong></div>
        <div class="field"><label>Created Date</label><strong>${risk.createdDate}</strong></div>
        <div class="field"><label>Target Close Date</label><strong>${risk.targetDate}</strong></div>
      </div>
    </div>
    ${flowStrip(processStage(risk))}
    <div class="detail-grid">
      <section class="panel">
        <div class="tabs" role="tablist">
          <button class="tab ${state.detailTab === 'actions' ? 'active' : ''}" data-action="tab" data-tab="actions" role="tab">Actions (${riskActions.length})</button>
          <button class="tab ${state.detailTab === 'assessments' ? 'active' : ''}" data-action="tab" data-tab="assessments" role="tab">Assessments (${riskAssessments.length})</button>
          <button class="tab ${state.detailTab === 'overview' ? 'active' : ''}" data-action="tab" data-tab="overview" role="tab">Risk details</button>
        </div>
        <div class="tab-body">${detailTab(risk, riskActions, riskAssessments)}</div>
      </section>
      <aside>
        <div class="aside-card"><h3>Action progress</h3><div class="aside-item"><label>Completed</label><strong>${completed} / ${riskActions.length || 0}</strong><div class="progress-track"><div class="progress-bar" style="width:${riskActions.length ? Math.round(completed / riskActions.length * 100) : 0}%"></div></div></div></div>
        <div class="aside-card"><h3>Assessment summary</h3><div class="aside-list"><div class="aside-item"><label>Initial Score</label><strong>${initial ? `${score(initial)} · ${levelText[levelFromScore(score(initial))]}` : '待评估'}</strong></div><div class="aside-item"><label>Residual Score</label><strong>${residual ? `${score(residual)} · ${levelText[levelFromScore(score(residual))]}` : '待复评'}</strong></div></div></div>
        <div class="aside-card"><h3>Data relationships</h3><div class="aside-list"><div class="aside-item"><label>Risk → Risk Actions</label><strong>1 : ${riskActions.length}</strong></div><div class="aside-item"><label>Risk → Risk Assessments</label><strong>1 : ${riskAssessments.length}</strong></div></div></div>
      </aside>
    </div>`;
}

function detailTab(risk, riskActions, riskAssessments) {
  if (state.detailTab === 'overview') return `<div class="summary-card"><h3>Risk description</h3><div class="description">${esc(risk.description)}</div></div>`;
  if (state.detailTab === 'assessments') {
    if (!riskAssessments.length) return '<div class="empty">当前 Risk 还没有 Assessment。</div>';
    return `<div class="table-scroll"><table class="data-table"><thead><tr><th>Assessment</th><th>Type</th><th>Likelihood</th><th>Impact</th><th>Score</th><th>Assessor</th><th>Date</th></tr></thead><tbody>${riskAssessments.map((item) => `
      <tr><td><span class="risk-title">${item.id}</span><span class="risk-id">${esc(item.notes)}</span></td><td>${item.type === 'Initial' ? 'Initial 初始评估' : 'Residual 残余评估'}</td><td>${item.likelihood} / 5</td><td>${item.impact} / 5</td><td><span class="risk-level ${levelFromScore(score(item)).toLowerCase()}">${score(item)}</span></td><td>${esc(item.assessor)}</td><td>${item.date}</td></tr>`).join('')}</tbody></table></div>`;
  }
  if (!riskActions.length) return '<div class="empty">当前 Risk 还没有 Action。使用顶部 “New Action” 创建时会自动关联本 Risk。</div>';
  return `<div class="table-scroll"><table class="data-table"><thead><tr><th style="width:34%">Action</th><th>Owner</th><th>Due Date</th><th>Reminder</th><th>Progress</th><th>Status</th><th></th></tr></thead><tbody>${riskActions.map(actionRow).join('')}</tbody></table></div>`;
}

function actionRow(action) {
  const due = dueState(action);
  const canEdit = state.role === 'security' || (state.role === 'actionOwner' && action.owner === roles[state.role].user) || (state.role === 'riskOwner' && selectedRisk()?.owner === roles[state.role].user);
  return `<tr><td><span class="action-title">${esc(action.title)}</span><span class="risk-id">${action.id} · Lookup ${action.riskId}</span></td><td><div class="owner-cell"><span class="mini-avatar">${esc(action.owner.slice(0,1))}</span>${esc(action.owner)}</div></td><td class="${due.key === 'overdue' ? 'due-overdue' : due.key === 'due-soon' ? 'due-soon' : ''}">${action.dueDate}</td><td><span class="reminder-state ${due.key}">${due.label}</span></td><td>${action.progress}%<div class="progress-track"><div class="progress-bar" style="width:${action.progress}%"></div></div></td><td><span class="status ${statusClass(action.status)}">${statusText[action.status]}</span></td><td>${canEdit ? `<button class="inline-btn" data-action="edit-action" data-id="${action.id}">Update</button>` : ''}</td></tr>`;
}

function actionsPage() {
  const actions = visibleActions();
  const overdue = actions.filter((action) => dueState(action).key === 'overdue').length;
  const dueSoon = actions.filter((action) => dueState(action).key === 'due-soon').length;
  const completed = actions.filter((action) => action.status === 'Completed').length;
  return `
    <div class="page-title"><div><h1>My Actions</h1><p>${state.role === 'security' ? '安全部门可查看全部整改措施' : `显示 ${roles[state.role].user} 负责或管理的 Action`}</p></div></div>
    ${flowStrip(3)}
    <div class="kpis">${kpi('我的 Action', actions.length, '✓')}${kpi('逾期', overdue, '!')}${kpi('3 天内到期', dueSoon, '◷')}${kpi('已完成', completed, '✓')}</div>
    <section class="panel"><div class="panel-head"><h2>Risk Actions <span class="count">(${actions.length})</span></h2></div><div class="table-scroll"><table class="data-table"><thead><tr><th style="width:30%">Action</th><th>Related Risk</th><th>Owner</th><th>Due Date</th><th>Reminder</th><th>Progress</th><th>Status</th><th></th></tr></thead><tbody>${actions.map((action) => {
      const risk = data.risks.find((item) => item.id === action.riskId);
      const due = dueState(action);
      const canEdit = state.role === 'security' || (state.role === 'actionOwner' && action.owner === roles[state.role].user);
      return `<tr><td><span class="action-title">${esc(action.title)}</span><span class="risk-id">${action.id}</span></td><td><button class="inline-btn" data-action="open-risk" data-id="${action.riskId}">${risk?.id}<br>${esc(risk?.title || '')}</button></td><td>${esc(action.owner)}</td><td>${action.dueDate}</td><td><span class="reminder-state ${due.key}">${due.label}</span></td><td>${action.progress}%</td><td><span class="status ${statusClass(action.status)}">${statusText[action.status]}</span></td><td>${canEdit ? `<button class="inline-btn" data-action="edit-action" data-id="${action.id}">Update</button>` : ''}</td></tr>`;
    }).join('')}</tbody></table></div></section>`;
}

function remindersPage() {
  const attention = data.actions.filter((action) => ['overdue','due-soon'].includes(dueState(action).key));
  return `
    <div class="page-title"><div><h1>Action Due Reminder</h1><p>Power Automate 按 Action Owner 和 Due Date 发送提醒</p></div></div>
    <div class="reminder-layout">
      <section class="flow-card">
        <div class="automation-head"><div class="flow-logo">↯</div><div><h2>Action Due Date Reminder</h2><p>Scheduled cloud flow · 每日 09:00</p></div><span class="panel-spacer"></span><span class="status completed">已启用</span></div>
        <div class="automation-steps">
          ${automationStep(1, 'Recurrence', '每天 09:00 自动运行')}
          ${automationStep(2, 'List rows · Risk Actions', '从 Dataverse 查询 Action ID、Risk Lookup、Owner、Due Date 和 Status')}
          ${automationStep(3, 'Condition', 'Status ≠ Completed，且 Due Date ≤ 今天 + 3 天')}
          ${automationStep(4, 'Send email notification', '发送给 Action Owner，并附带 Risk 编号、Action 内容和到期日期')}
        </div>
        <div class="rule-callout"><strong>提醒规则：</strong>逾期 Action 每天提醒；3 天内到期 Action 提前提醒；Completed 不发送。</div>
      </section>
      <section class="panel"><div class="panel-head"><h2>本次运行预览 <span class="count">(${attention.length})</span></h2></div><div>${attention.map((action) => {
        const risk = data.risks.find((item) => item.id === action.riskId);
        const due = dueState(action);
        return `<div class="summary-card" style="margin:12px"><span class="reminder-state ${due.key}">${due.label}</span><h3 style="margin-top:10px">${esc(action.title)}</h3><div class="description">收件人：${esc(action.owner)}<br>Risk：${risk?.id} · ${esc(risk?.title || '')}<br>Due Date：${action.dueDate}</div></div>`;
      }).join('') || '<div class="empty">今天没有需要发送的提醒。</div>'}</div></section>
    </div>`;
}

function automationStep(no, title, text) {
  return `<div class="automation-step"><div class="automation-no">${no}</div><div class="automation-box"><strong>${title}</strong><span>${text}</span></div></div>`;
}

function modelPage() {
  return `
    <div class="page-title"><div><h1>Dataverse Data Model</h1><p>三个核心表，两组清晰的 1:N 关系</p></div></div>
    <div class="model-diagram">
      ${entity('Risk', '主表', [['Risk ID','Autonumber'],['Title','Text'],['Description','Multiline'],['Category','Choice'],['Status','Choice'],['Owner','User'],['Target Close Date','Date']])}
      <div class="relation"><div class="relation-line"><b>1:N</b></div>Risk Lookup<br><small>父记录删除策略在正式设计时确认</small></div>
      <div class="children">
        ${entity('Risk Action', '子表', [['Action ID','Autonumber'],['Risk','Lookup'],['Action','Text'],['Owner','User'],['Due Date','Date'],['Status','Choice'],['Progress','Number']])}
        ${entity('Risk Assessment', '子表', [['Assessment ID','Autonumber'],['Risk','Lookup'],['Type','Choice'],['Likelihood','Number'],['Impact','Number'],['Score','Calculated'],['Assessor','User']])}
      </div>
    </div>
    <div class="dataverse-note"><strong>Power Apps 实现提示：</strong>Risk 详情页以当前记录为上下文，Actions 和 Assessments Gallery 使用 <code>Filter(..., Risk = ThisItem)</code>；New Action 表单的 Risk Lookup 默认设置为当前 Risk。</div>`;
}

function entity(name, tag, fields) {
  return `<section class="entity"><div class="entity-head"><h2>${name}</h2><span>${tag}</span></div><ul class="entity-fields">${fields.map(([name,type]) => `<li><span>${name}</span><code>${type}</code></li>`).join('')}</ul></section>`;
}

function drawer() {
  if (!state.drawer) return '';
  if (state.drawer === 'risk') return drawerFrame('New Risk', riskForm());
  if (state.drawer === 'action') return drawerFrame('New Action', actionForm());
  if (state.drawer === 'assessment') return drawerFrame('New Assessment', assessmentForm());
  if (state.drawer === 'editAction') return drawerFrame('Update Action', editActionForm());
  return '';
}

function drawerFrame(title, body) {
  return `<div class="drawer-backdrop" data-action="close-drawer"><section class="drawer" role="dialog" aria-modal="true" aria-label="${title}" onclick="event.stopPropagation()"><div class="drawer-head"><h2>${title}</h2><button class="close-btn" data-action="close-drawer" aria-label="关闭">×</button></div>${body}</section></div>`;
}

function riskForm() {
  return `<form data-form="risk" class="form-grid" style="display:contents"><div class="drawer-body"><div class="form-grid">
    ${inputField('Risk title', 'title', 'text', true, '例如：第三方账号未按期复核')}
    ${textareaField('Description', 'description', true)}
    ${selectField('Category', 'category', ['访问控制','身份管理','漏洞管理','业务连续性','安全监控'])}
    ${selectField('Risk Owner', 'owner', ['陈晨','刘洋','王蕾','周宁'])}
    ${selectField('Risk Level', 'level', ['High','Medium','Low'])}
    ${inputField('Target Close Date', 'targetDate', 'date', true, '', isoDay(30))}
  </div></div><div class="drawer-foot"><button type="button" class="btn" data-action="close-drawer">Cancel</button><button class="btn primary" type="submit">Save Risk</button></div></form>`;
}

function actionForm() {
  const risk = selectedRisk();
  return `<form data-form="action" class="form-grid" style="display:contents"><div class="drawer-body"><div class="form-grid">
    <div class="form-field"><label>Related Risk</label><input readonly value="${risk.id} · ${esc(risk.title)}"><div class="form-hint">由当前 Risk 自动关联，正式 Power Apps 中对应 Dataverse Lookup 默认值。</div></div>
    ${inputField('Action', 'title', 'text', true, '输入需要完成的整改措施')}
    ${selectField('Action Owner', 'owner', ['赵明','孙宇','刘洋','王蕾'])}
    ${inputField('Due Date', 'dueDate', 'date', true, '', isoDay(7))}
  </div></div><div class="drawer-foot"><button type="button" class="btn" data-action="close-drawer">Cancel</button><button class="btn primary" type="submit">Save Action</button></div></form>`;
}

function assessmentForm() {
  const risk = selectedRisk();
  return `<form data-form="assessment" class="form-grid" style="display:contents"><div class="drawer-body"><div class="form-grid">
    <div class="form-field"><label>Related Risk</label><input readonly value="${risk.id} · ${esc(risk.title)}"></div>
    ${selectField('Assessment Type', 'type', ['Initial','Residual'])}
    ${selectField('Likelihood (1–5)', 'likelihood', ['1','2','3','4','5'], '3')}
    ${selectField('Impact (1–5)', 'impact', ['1','2','3','4','5'], '3')}
    ${selectField('Assessor', 'assessor', ['孙倩','李佳','陈晨'])}
    ${textareaField('Assessment notes', 'notes', false)}
  </div></div><div class="drawer-foot"><button type="button" class="btn" data-action="close-drawer">Cancel</button><button class="btn primary" type="submit">Save Assessment</button></div></form>`;
}

function editActionForm() {
  const action = data.actions.find((item) => item.id === state.editingActionId);
  if (!action) return '<div class="drawer-body">Action 不存在。</div>';
  return `<form data-form="editAction" class="form-grid" style="display:contents"><div class="drawer-body"><div class="form-grid">
    <div class="form-field"><label>Action</label><input readonly value="${esc(action.title)}"></div>
    <div class="form-field"><label>Related Risk</label><input readonly value="${action.riskId}"></div>
    ${selectField('Status', 'status', ['Not Started','In Progress','Completed'], action.status)}
    ${inputField('Progress (%)', 'progress', 'number', true, '', action.progress, '0', '100')}
    ${inputField('Due Date', 'dueDate', 'date', true, '', action.dueDate)}
  </div></div><div class="drawer-foot"><button type="button" class="btn" data-action="close-drawer">Cancel</button><button class="btn primary" type="submit">Save Update</button></div></form>`;
}

function inputField(label, name, type = 'text', required = false, placeholder = '', value = '', min = '', max = '') {
  return `<div class="form-field"><label for="field-${name}">${label}${required ? ' <span class="required">*</span>' : ''}</label><input id="field-${name}" name="${name}" type="${type}" value="${esc(value)}" placeholder="${esc(placeholder)}" ${required ? 'required' : ''} ${min !== '' ? `min="${min}"` : ''} ${max !== '' ? `max="${max}"` : ''}></div>`;
}

function textareaField(label, name, required = false) {
  return `<div class="form-field"><label for="field-${name}">${label}${required ? ' <span class="required">*</span>' : ''}</label><textarea id="field-${name}" name="${name}" ${required ? 'required' : ''}></textarea></div>`;
}

function selectField(label, name, values, selected = values[0]) {
  return `<div class="form-field"><label for="field-${name}">${label}</label><select id="field-${name}" name="${name}">${values.map((value) => `<option value="${value}" ${value === selected ? 'selected' : ''}>${value}</option>`).join('')}</select></div>`;
}

function render() {
  root.innerHTML = shell();
}

function showToast(message) {
  const region = document.querySelector('#toast-region');
  const item = document.createElement('div');
  item.className = 'toast';
  item.textContent = message;
  region.append(item);
  setTimeout(() => item.remove(), 3200);
}

root.addEventListener('click', (event) => {
  const target = event.target.closest('[data-action]');
  if (!target) return;
  const action = target.dataset.action;
  if (action === 'nav') {
    state.page = target.dataset.page;
    state.drawer = null;
    render();
  } else if (action === 'open-risk') {
    state.selectedRiskId = target.dataset.id;
    state.page = 'detail';
    state.detailTab = 'actions';
    render();
  } else if (action === 'back') {
    state.page = 'risks';
    render();
  } else if (action === 'tab') {
    state.detailTab = target.dataset.tab;
    render();
  } else if (action === 'open-drawer') {
    state.drawer = target.dataset.drawer;
    render();
    requestAnimationFrame(() => document.querySelector('.drawer input:not([readonly]), .drawer select')?.focus());
  } else if (action === 'close-drawer') {
    state.drawer = null;
    state.editingActionId = null;
    render();
  } else if (action === 'edit-action') {
    state.editingActionId = target.dataset.id;
    state.drawer = 'editAction';
    render();
  } else if (action === 'refresh') {
    render();
    showToast('数据已刷新');
  }
});

root.addEventListener('change', (event) => {
  if (event.target.id === 'role-switch') {
    state.role = event.target.value;
    state.page = state.role === 'actionOwner' ? 'actions' : 'risks';
    state.query = '';
    state.status = 'All';
    render();
    showToast(`已切换为 ${roles[state.role].label} 视图`);
  } else if (event.target.id === 'risk-search') {
    state.query = event.target.value;
    render();
  } else if (event.target.id === 'status-filter') {
    state.status = event.target.value;
    render();
  }
});

root.addEventListener('keydown', (event) => {
  const row = event.target.closest('tr[data-action="open-risk"]');
  if (row && (event.key === 'Enter' || event.key === ' ')) {
    event.preventDefault();
    row.click();
  }
});

root.addEventListener('submit', (event) => {
  event.preventDefault();
  const form = event.target;
  const values = Object.fromEntries(new FormData(form));
  if (form.dataset.form === 'risk') {
    const id = `RSK-${today.getFullYear()}-${String(data.risks.length + 1).padStart(3, '0')}`;
    data.risks.unshift({ id, title: values.title.trim(), description: values.description.trim(), category: values.category, status: 'Registered', owner: values.owner, targetDate: values.targetDate, createdDate: isoDay(0), level: values.level });
    state.selectedRiskId = id;
    state.page = 'detail';
    state.detailTab = 'actions';
    state.drawer = null;
    save(); render(); showToast(`${id} 已登记`);
  } else if (form.dataset.form === 'action') {
    const id = `ACT-${String(data.actions.length + 1).padStart(3, '0')}`;
    data.actions.push({ id, riskId: selectedRisk().id, title: values.title.trim(), owner: values.owner, dueDate: values.dueDate, status: 'Not Started', progress: 0 });
    state.drawer = null;
    save(); render(); showToast(`${id} 已创建，并自动关联 ${selectedRisk().id}`);
  } else if (form.dataset.form === 'assessment') {
    const id = `ASM-${String(data.assessments.length + 1).padStart(3, '0')}`;
    data.assessments.push({ id, riskId: selectedRisk().id, type: values.type, likelihood: Number(values.likelihood), impact: Number(values.impact), assessor: values.assessor, date: isoDay(0), notes: values.notes.trim() });
    state.detailTab = 'assessments';
    state.drawer = null;
    save(); render(); showToast(`${id} 已保存`);
  } else if (form.dataset.form === 'editAction') {
    const item = data.actions.find((action) => action.id === state.editingActionId);
    if (item) {
      item.status = values.status;
      item.progress = values.status === 'Completed' ? 100 : Number(values.progress);
      item.dueDate = values.dueDate;
    }
    state.drawer = null;
    state.editingActionId = null;
    save(); render(); showToast('Action 已更新');
  }
});

document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && state.drawer) {
    state.drawer = null;
    state.editingActionId = null;
    render();
  }
});

render();
