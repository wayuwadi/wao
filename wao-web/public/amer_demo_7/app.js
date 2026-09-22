// Keep the V3 key and read legacy fields additively; never reset existing demo records.
const STORAGE_KEY = 'amer-risk-demo-v3';
const SCHEMA_VERSION = 3;
const riskSources = ['应用安全项目评估','AI安全评估','供应商评估','第三方软件验证','其他'];
const riskDomains = ['应用安全','云安全','数字合规','数据安全','其他'];
const riskLocations = ['中国大陆','台湾地区','香港','澳门','澳大利亚','新西兰','日本','韩国','其他'];
const riskTeams = ['网络与数据安全团队 Cyber and Data Security - CDS','业务解决方案团队 Business Solution Team - BST','数字化解决方案团队 Digital Solution Team - DST','架构、项目管理办公室与技术团队 Architecture, PMO & Technology - APT','数据导航与分析团队 Data Navigation & Analysis - DNA','技术服务与解决方案团队 Technology Service & Solution - TSS','后台团队 Back Office - BO','集团网络安全风险管理团队 Group CSM','亚太地区 APAC','其他'];
const treatmentOptions = ['Remediation','Mitigation','Acceptance'];
const demoEmails = {'林晓红':'lin.xiaohong@example.invalid','孙倩':'sun.qian@example.invalid','李佳':'li.jia@example.invalid','陈晨':'chen.chen@example.invalid','刘洋':'liu.yang@example.invalid','王蕾':'wang.lei@example.invalid','王琳':'wang.lin@example.invalid','周宁':'zhou.ning@example.invalid'};
const emailFor = (value = '') => value.includes('@') ? value : demoEmails[value] || '';
const asList = (value) => Array.isArray(value) ? value : value ? [value] : [];

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
    note: '全量可见 · 唯一可新建、修改、评估和关闭的角色',
    canCreateRisk: true,
    canCreateAction: true,
    canAssess: true,
  },
  riskOwner: {
    label: 'Risk Owner',
    user: '陈晨',
    short: '陈',
    note: '只读 · 查看本人 Risk、其下全部 Action 和 Assessment',
    canCreateRisk: false,
    canCreateAction: false,
    canAssess: false,
  },
  actionOwner: {
    label: 'Action Owner',
    user: '赵明',
    short: '赵',
    note: '只读 · 查看本人 Action 及必要的关联 Risk 信息',
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
      ownerTeam: '数字化解决方案部',
      businessOwner: '王琳',
      businessTeam: '运营部',
      source: '内部审计邮件',
      domain: 'Application Security',
      impactedLocation: '中国大陆',
      impactedAsset: '供应商远程运维门户',
      treatment: 'Remediation',
      targetDate: isoDay(12),
      reassessmentDate: isoDay(5),
      createdDate: isoDay(-14),
      level: 'High',
      analyst: '林晓红',
      closeDate: '',
      closeReason: '',
      notes: '等待完成权限回收和复评。',
    },
    {
      id: 'RSK-2026-002',
      title: '备份恢复演练证据不完整',
      description: '最近一次恢复演练缺少关键截图、恢复耗时和业务确认记录。',
      category: '业务连续性',
      status: 'Assessing',
      owner: '刘洋',
      ownerTeam: '基础架构部',
      businessOwner: '刘洋',
      businessTeam: '供应链运营',
      source: '业务部门邮件',
      domain: 'Business Continuity',
      impactedLocation: 'APAC',
      impactedAsset: 'ERP 备份与恢复流程',
      treatment: 'Remediation',
      targetDate: isoDay(21),
      reassessmentDate: isoDay(14),
      createdDate: isoDay(-9),
      level: 'Medium',
      analyst: '孙倩',
      closeDate: '',
      closeReason: '',
      notes: '证据补齐后安排残余风险复评。',
    },
    {
      id: 'RSK-2026-003',
      title: '关键应用补丁窗口超期',
      description: '核心应用的高危漏洞修复未在计划窗口内完成，需要重新安排变更并跟踪验证。',
      category: '漏洞管理',
      status: 'Mitigating',
      owner: '王蕾',
      ownerTeam: '应用服务部',
      businessOwner: '周宁',
      businessTeam: '零售业务',
      source: '漏洞扫描邮件',
      domain: 'Vulnerability Management',
      impactedLocation: '中国大陆',
      impactedAsset: '关键零售应用',
      treatment: 'Remediation',
      targetDate: isoDay(7),
      reassessmentDate: isoDay(4),
      createdDate: isoDay(-20),
      level: 'High',
      analyst: '李佳',
      closeDate: '',
      closeReason: '',
      notes: '紧急变更窗口待 CAB 确认。',
    },
    {
      id: 'RSK-2026-004',
      title: '生产日志留存周期不一致',
      description: '不同系统的安全日志留存策略不一致，需要统一基线并确认容量。',
      category: '安全监控',
      status: 'Registered',
      owner: '陈晨',
      ownerTeam: '数字化解决方案部',
      businessOwner: '王琳',
      businessTeam: '运营部',
      source: '安全部门检测',
      domain: 'Security Monitoring',
      impactedLocation: '中国大陆',
      impactedAsset: '生产日志平台',
      treatment: 'Mitigate',
      targetDate: isoDay(30),
      reassessmentDate: isoDay(20),
      createdDate: isoDay(-3),
      level: 'Medium',
      analyst: '林晓红',
      closeDate: '',
      closeReason: '',
      notes: '正在确认统一留存基线。',
    },
  ],
  actions: [
    { id: 'ACT-2026-0001', riskId: 'RSK-2026-001', title: '完成第三方账号清单与业务确认', owner: '赵明', ownerTeam: '网络与数据安全', dueDate: isoDay(-1), status: 'Completed', progress: 100, workPerformed: '已完成 43 个账号的业务确认。', completedDate: isoDay(-1) },
    { id: 'ACT-2026-0002', riskId: 'RSK-2026-001', title: '回收无有效业务保荐人的权限', owner: '赵明', ownerTeam: '网络与数据安全', dueDate: isoDay(2), status: 'In Progress', progress: 60, workPerformed: '已回收 8 个账号，其余等待业务确认。', completedDate: '' },
    { id: 'ACT-2026-0003', riskId: 'RSK-2026-001', title: '建立季度账号复核提醒', owner: '孙宇', ownerTeam: '身份与访问管理', dueDate: isoDay(10), status: 'Not Started', progress: 0, workPerformed: '', completedDate: '' },
    { id: 'ACT-2026-0004', riskId: 'RSK-2026-002', title: '补齐恢复演练证据和业务签字', owner: '刘洋', ownerTeam: '基础架构部', dueDate: isoDay(8), status: 'In Progress', progress: 35, workPerformed: '恢复耗时与截图已补充，等待签字。', completedDate: '' },
    { id: 'ACT-2026-0005', riskId: 'RSK-2026-003', title: '安排紧急补丁变更窗口', owner: '赵明', ownerTeam: '网络与数据安全', dueDate: isoDay(-2), status: 'In Progress', progress: 75, workPerformed: '补丁已在预生产验证，等待 CAB 窗口。', completedDate: '' },
  ],
  assessments: [
    { id: 'ASM-2026-0001', riskId: 'RSK-2026-001', type: 'Initial', version: 1, likelihood: 5, impact: 5, assessor: '孙倩', date: isoDay(-13), notes: '高权限账号数量较多，且复核已超期。' },
    { id: 'ASM-2026-0002', riskId: 'RSK-2026-001', type: 'Residual', version: 2, likelihood: 3, impact: 5, assessor: '孙倩', date: isoDay(-1), notes: '完成账号确认后暴露面下降，仍需完成权限回收。' },
    { id: 'ASM-2026-0003', riskId: 'RSK-2026-002', type: 'Initial', version: 1, likelihood: 3, impact: 4, assessor: '孙倩', date: isoDay(-7), notes: '备份可用，但审计证据不足。' },
    { id: 'ASM-2026-0004', riskId: 'RSK-2026-003', type: 'Initial', version: 1, likelihood: 4, impact: 5, assessor: '李佳', date: isoDay(-18), notes: '高危补丁未按计划完成。' },
  ],
  activity: [
    { riskId: 'RSK-2026-001', date: isoDay(-14), actor: '林晓红', event: 'Risk 已由安全部门登记' },
    { riskId: 'RSK-2026-001', date: isoDay(-13), actor: '孙倩', event: 'Initial Assessment v1：25 · 高风险' },
    { riskId: 'RSK-2026-001', date: isoDay(-12), actor: '林晓红', event: '已创建 3 条整改 Action' },
    { riskId: 'RSK-2026-001', date: isoDay(-1), actor: '孙倩', event: 'Residual Assessment v2：15 · 高风险' },
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
  editingRisk: false,
  riskDrafts: {},
};

const root = document.querySelector('#app');
const esc = (value = '') => String(value).replace(/[&<>'"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' })[char]);
const save = () => localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
const selectedRisk = () => data.risks.find((risk) => risk.id === state.selectedRiskId) || data.risks[0];
const actionsFor = (riskId) => data.actions.filter((action) => action.riskId === riskId);
const assessmentsFor = (riskId) => data.assessments.filter((assessment) => assessment.riskId === riskId);
const formatDate = (value) => new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit' }).format(new Date(`${value}T00:00:00`));
const score = (assessment) => assessment.likelihood * assessment.impact;
const levelFromScore = (value) => value >= 20 ? 'Critical' : value >= 15 ? 'High' : value >= 6 ? 'Medium' : 'Low';

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
const levelText = { Critical: '严重风险', High: '高风险', Medium: '中风险', Low: '低风险' };
// Derive the current level from the latest assessment; historical rows stay unchanged.
data.risks.forEach(risk => {
  const latest = assessmentsFor(risk.id).at(-1);
  if (latest) risk.level = levelFromScore(score(latest));
});

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

function shell() {
  const role = roles[state.role];
  return `
    <div class="shell">
      <header class="powerbar">
        <div class="waffle" aria-hidden="true">⠿</div>
        <div class="powerbrand"><div class="powerlogo"><span>P</span></div><strong>Power Apps</strong></div>
        <div class="appname"><span>IT Risk Management v7</span><b class="demo-tag">HTML Demo · 模拟数据</b></div>
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
          ${navButton('reminders', '◷', '到期与通知')}
          ${navButton('model', '⌘', '数据模型')}
          ${navButton('permissions', '♙', '权限方案')}
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
    const risk = selectedRisk();
    commands = `<button class="cmd-btn" data-action="back">‹ Back</button><span class="cmd-sep"></span><button class="cmd-btn primary" data-action="open-drawer" data-drawer="action" ${role.canCreateAction ? '' : 'disabled'}>＋ New Action</button><button class="cmd-btn" data-action="open-drawer" data-drawer="assessment" ${role.canAssess ? '' : 'disabled'}>＋ New Assessment</button><button class="cmd-btn close-risk" data-action="open-drawer" data-drawer="closeRisk" ${state.role === 'security' && risk?.status !== 'Closed' ? '' : 'disabled'}>✓ Close Risk</button>`;
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
  if (state.page === 'permissions') return permissionsPage();
  return risksPage();
}

function risksPage() {
  const risks = visibleRisks();
  const role = roles[state.role];
  const open = risks.filter((risk) => risk.status !== 'Closed').length;
  const high = risks.filter((risk) => ['High','Critical'].includes(risk.level) && risk.status !== 'Closed').length;
  const overdue = visibleActions().filter((action) => dueState(action).key === 'overdue').length;
  const dueSoon = visibleActions().filter((action) => dueState(action).key === 'due-soon').length;
  return `
    <div class="page-title"><div><h1>Risk Register</h1><p>${state.role === 'security' ? '安全部门统一登记、评估、跟踪并关闭风险' : `只读显示与 ${role.user} 相关的记录`}</p></div><span class="scope-chip">${state.role === 'security' ? 'ALL RECORDS' : 'MY SCOPE · READ ONLY'}</span></div>
    <div class="kpis">
      ${kpi('开放 Risk', open, '▤')}${kpi('高／严重风险', high, '⚠')}${kpi('逾期 Action', overdue, '!')}${kpi('3 天内到期', dueSoon, '◷')}
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
  return `<table class="data-table"><thead><tr><th style="width:30%">Risk</th><th>Domain</th><th>Level</th><th>Status</th><th>Risk Owner</th><th>Target Date</th></tr></thead><tbody>${risks.map((risk) => `
    <tr class="clickable" tabindex="0" data-action="open-risk" data-id="${risk.id}">
      <td><span class="risk-title">${esc(risk.title)}</span><span class="risk-id">${risk.id} · ${esc(risk.category)}</span></td>
      <td>${esc(risk.domain || risk.category)}</td>
      <td><span class="risk-level ${risk.level.toLowerCase()}">${levelText[risk.level]}</span></td>
      <td><span class="status ${statusClass(risk.status)}">${statusText[risk.status]}</span></td>
      <td><div class="owner-cell"><span class="mini-avatar">${esc(risk.owner.slice(0,1))}</span>${esc(risk.owner)}</div></td>
      <td>${risk.targetDate}</td>
    </tr>`).join('')}</tbody></table>`;
}

function detailPage() {
  const risk = selectedRisk();
  if (!risk) return '<div class="empty">Risk 不存在。</div>';
  const riskActions = state.role === 'actionOwner' ? actionsFor(risk.id).filter((item) => item.owner === roles.actionOwner.user) : actionsFor(risk.id);
  const riskAssessments = state.role === 'actionOwner' ? [] : assessmentsFor(risk.id);
  return `
    <div class="breadcrumb"><button data-action="back">Risk Register</button> / ${risk.id}</div>
    <div class="record-header">
      <div class="record-main"><div class="risk-square">R</div><div><h1>${esc(risk.title)}</h1><div class="record-meta"><span>${risk.id}</span><span>·</span><span class="risk-level ${risk.level.toLowerCase()}">${levelText[risk.level]}</span><span class="status ${statusClass(risk.status)}">${statusText[risk.status]}</span></div></div></div>
    </div>
    ${riskDetailsPanel(risk)}
    <div class="detail-grid risk-related-grid">
      <section class="panel">
        <div class="tabs" role="tablist">
          <button class="tab ${state.detailTab === 'actions' ? 'active' : ''}" data-action="tab" data-tab="actions" role="tab">Actions (${riskActions.length})</button>
          ${state.role === 'actionOwner' ? '' : `<button class="tab ${state.detailTab === 'assessments' ? 'active' : ''}" data-action="tab" data-tab="assessments" role="tab">Assessments (${riskAssessments.length})</button>`}
          <button class="tab ${state.detailTab === 'activity' ? 'active' : ''}" data-action="tab" data-tab="activity" role="tab">Activity</button>
        </div>
        <div class="tab-body">${detailTab(risk, riskActions, riskAssessments)}</div>
      </section>
    </div>`;
}

function riskDetailsPanel(record) {
  const canEdit = state.role === 'security';
  const risk = { ...record, ...(canEdit ? state.riskDrafts[record.id] : {}) };
  const readOnly = canEdit ? '' : 'readonly';
  const disabled = canEdit ? '' : 'disabled';
  const input = (label, name, value, type = 'text', required = false, span = '', system = false) => `<label class="risk-detail-field ${span}"><span>${label}${required ? ' *' : ''}</span><input name="${name}" type="${type}" value="${esc(value || '')}" ${required ? 'required' : ''} ${system ? 'readonly' : readOnly}></label>`;
  const select = (label, name, choices, value, required = false) => `<label class="risk-detail-field"><span>${label}${required ? ' *' : ''}</span><select name="${name}" ${disabled} ${required ? 'required' : ''}>${[...new Set(['',...choices,...(value ? [value] : [])])].map(item => `<option value="${esc(item)}" ${item === (value || '') ? 'selected' : ''}>${esc(item || '请选择')}</option>`).join('')}</select></label>`;
  const multi = (label, name, choices, values) => {
    const selected = asList(values);
    return `<fieldset class="risk-detail-field risk-multiselect span-2"><legend>${label} *</legend><div class="risk-option-list">${[...new Set([...choices,...selected])].map(item => `<label><input type="checkbox" name="${name}" value="${esc(item)}" ${selected.includes(item) ? 'checked' : ''} ${disabled}><span>${esc(item)}</span></label>`).join('')}</div></fieldset>`;
  };
  const domains = risk.domains ?? asList(risk.domain);
  const locations = risk.locations ?? asList(risk.impactedLocation);
  return `<form class="panel risk-detail-panel compact-risk-panel" data-form="inlineEditRisk">
    <div class="panel-head risk-detail-head"><div><h2>基本信息 <span class="section-english">Basic information</span></h2><p data-save-state>${canEdit ? (state.riskDrafts[record.id] ? '有未保存的修改' : '可直接编辑；* 为必填项。') : '只读；如需修改请联系安全部门。'}</p></div><span class="panel-spacer"></span>${canEdit ? '<button class="btn primary" type="submit">Save changes</button>' : '<span class="read-only-badge">只读</span>'}</div>
    <section class="risk-detail-grid" data-risk-section="basic">
      ${input('风险标题 Risk Title','title',risk.title,'text',true,'span-2')}
      ${input('登记时间 Registration Time','createdDate',risk.createdDate,'date',true)}
      ${input('登记人 Risk Registrant','registrantEmail',risk.registrantEmail ?? emailFor(risk.analyst),'email',true)}
      <label class="risk-detail-field span-all"><span>风险描述 Risk Description *</span><textarea name="description" required ${readOnly}>${esc(risk.description)}</textarea></label>
      ${select('风险来源 Source','source',riskSources,risk.source,true)}
      ${input('创建人 Created By','creatorEmail',record.creatorEmail ?? emailFor(record.analyst),'email',false,'',true)}
      ${input('受影响资产 Impacted Asset','impactedAsset',risk.impactedAsset,'text',true,'span-2')}
      ${multi('风险域 Risk Domain','domains',riskDomains,domains)}
      ${multi('影响区域 Impacted Location','locations',riskLocations,locations)}
      ${input('IT风险负责人 IT Risk Owner','ownerEmail',risk.ownerEmail ?? emailFor(risk.owner),'email',true)}
      ${select('IT负责人团队 IT Risk Owner Team','ownerTeam',riskTeams,risk.ownerTeam,true)}
      ${input('业务风险负责人 Business Risk Owner','businessOwnerEmail',risk.businessOwnerEmail ?? emailFor(risk.businessOwner),'email')}
      ${select('业务负责人团队 Business Risk Owner Team','businessTeam',riskTeams,risk.businessTeam)}
    </section>
  </form>`;
}

function detailTab(risk, riskActions, riskAssessments) {
  if (state.detailTab === 'activity') {
    const events = data.activity.filter((item) => item.riskId === risk.id);
    return `<div class="timeline">${events.map((item) => `<div class="timeline-item"><span></span><div><strong>${esc(item.event)}</strong><small>${item.date} · ${esc(item.actor)}</small></div></div>`).join('') || '<div class="empty">暂无操作记录。</div>'}</div>`;
  }
  if (state.detailTab === 'assessments') {
    if (!riskAssessments.length) return '<div class="empty">当前 Risk 还没有 Assessment。</div>';
    return `<div class="history-note">Assessment 是 1:N 历史表；每次复评创建新版本，Score = Impact × Likelihood 自动计算。</div><div class="table-scroll"><table class="data-table"><thead><tr><th>Assessment</th><th>Version</th><th>Type</th><th>Likelihood</th><th>Impact</th><th>Calculated Score</th><th>Risk Treatment</th><th>Assessor</th><th>Date</th></tr></thead><tbody>${riskAssessments.map((item, index) => `
      <tr><td><span class="risk-title">${item.id}</span><span class="risk-id">${esc(item.notes)}</span></td><td>v${item.version || index + 1}</td><td>${item.type === 'Initial' ? 'Initial 初始评估' : 'Residual 残余评估'}</td><td>${item.likelihood} / 5</td><td>${item.impact} / 5</td><td><span class="risk-level ${levelFromScore(score(item)).toLowerCase()}">${score(item)} · ${levelText[levelFromScore(score(item))]}</span></td><td>${esc(item.treatment || '未记录')}</td><td>${esc(item.assessor)}</td><td>${item.date}</td></tr>`).join('')}</tbody></table></div>`;
  }
  if (!riskActions.length) return '<div class="empty">当前 Risk 还没有 Action。使用顶部 “New Action” 创建时会自动关联本 Risk。</div>';
  return `<div class="table-scroll"><table class="data-table"><thead><tr><th style="width:30%">Action</th><th>Owner / Team</th><th>Due Date</th><th>Reminder</th><th>Work performed</th><th>Status</th><th></th></tr></thead><tbody>${riskActions.map(actionRow).join('')}</tbody></table></div>`;
}

function actionRow(action) {
  const due = dueState(action);
  const canEdit = state.role === 'security';
  return `<tr><td><span class="action-title">${esc(action.title)}</span><span class="risk-id">${action.id} · Lookup ${action.riskId}</span></td><td><div class="owner-cell"><span class="mini-avatar">${esc(action.owner.slice(0,1))}</span><span>${esc(action.owner)}<small>${esc(action.ownerTeam || '')}</small></span></div></td><td class="${due.key === 'overdue' ? 'due-overdue' : due.key === 'due-soon' ? 'due-soon' : ''}">${action.dueDate}</td><td><span class="reminder-state ${due.key}">${due.label}</span></td><td><span class="work-note">${esc(action.workPerformed || '尚未记录')}</span></td><td><span class="status ${statusClass(action.status)}">${statusText[action.status]}</span></td><td>${canEdit ? `<button class="inline-btn" data-action="edit-action" data-id="${action.id}">Update</button>` : '<span class="read-only-cell">只读</span>'}</td></tr>`;
}

function actionsPage() {
  const actions = visibleActions();
  const overdue = actions.filter((action) => dueState(action).key === 'overdue').length;
  const dueSoon = actions.filter((action) => dueState(action).key === 'due-soon').length;
  const completed = actions.filter((action) => action.status === 'Completed').length;
  return `
    <div class="page-title"><div><h1>My Actions</h1><p>${state.role === 'security' ? '安全部门可查看并维护全部整改措施' : `只读显示 ${roles[state.role].user} 权限范围内的 Action`}</p></div><span class="scope-chip">${state.role === 'security' ? 'ALL ACTIONS' : 'READ ONLY'}</span></div>
    <div class="kpis">${kpi('我的 Action', actions.length, '✓')}${kpi('逾期', overdue, '!')}${kpi('3 天内到期', dueSoon, '◷')}${kpi('已完成', completed, '✓')}</div>
    <section class="panel"><div class="panel-head"><h2>Risk Actions <span class="count">(${actions.length})</span></h2></div><div class="table-scroll"><table class="data-table"><thead><tr><th style="width:25%">Action</th><th>Related Risk</th><th>Owner</th><th>Due Date</th><th>Reminder</th><th>Work performed</th><th>Status</th><th></th></tr></thead><tbody>${actions.map((action) => {
      const risk = data.risks.find((item) => item.id === action.riskId);
      const due = dueState(action);
      const canEdit = state.role === 'security';
      return `<tr><td><span class="action-title">${esc(action.title)}</span><span class="risk-id">${action.id}</span></td><td><button class="inline-btn" data-action="open-risk" data-id="${action.riskId}">${risk?.id}<br>${esc(risk?.title || '')}</button></td><td>${esc(action.owner)}<span class="risk-id">${esc(action.ownerTeam || '')}</span></td><td>${action.dueDate}</td><td><span class="reminder-state ${due.key}">${due.label}</span></td><td><span class="work-note">${esc(action.workPerformed || '尚未记录')}</span></td><td><span class="status ${statusClass(action.status)}">${statusText[action.status]}</span></td><td>${canEdit ? `<button class="inline-btn" data-action="edit-action" data-id="${action.id}">Update</button>` : '<span class="read-only-cell">只读</span>'}</td></tr>`;
    }).join('')}</tbody></table></div></section>`;
}

function remindersPage() {
  const attention = data.actions.filter((action) => ['overdue','due-soon'].includes(dueState(action).key));
  return `
    <div class="page-title"><div><h1>Due & Notifications</h1><p>Power Automate 负责创建通知、到期前提醒和逾期催办</p></div></div>
    <div class="notification-grid">
      ${notificationCard('Risk created', 'Risk Owner', '创建后立即发送', 'RSK')}
      ${notificationCard('Action created', 'Action Owner', '创建后立即发送', 'ACT')}
      ${notificationCard('Risk due soon', 'Risk Owner + Security', '目标日期前 7 天', 'RISK')}
      ${notificationCard('Action due / overdue', 'Action Owner + Security', '提前 3 天；逾期每日', 'DUE')}
    </div>
    <div class="reminder-layout">
      <section class="flow-card">
        <div class="automation-head"><div class="flow-logo">↯</div><div><h2>Action Due / Overdue Reminder</h2><p>Scheduled cloud flow · 每日 09:00</p></div><span class="panel-spacer"></span><span class="status completed">方案启用</span></div>
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

function notificationCard(title, recipient, timing, icon) {
  return `<div class="notification-card"><span>${icon}</span><div><strong>${title}</strong><small>To：${recipient}</small><small>${timing}</small></div></div>`;
}

function automationStep(no, title, text) {
  return `<div class="automation-step"><div class="automation-no">${no}</div><div class="automation-box"><strong>${title}</strong><span>${text}</span></div></div>`;
}

function modelPage() {
  return `
    <div class="page-title"><div><h1>Dataverse Data Model</h1><p>三个核心表，两组清晰的 1:N 关系</p></div></div>
    <div class="id-callout"><strong>ID 设计</strong><span>Dataverse GUID 作为不可见技术主键；界面显示不可变业务编号，如 RSK-2026-0001。删除记录不会重排历史编号，正式系统建议停用/归档而不是物理删除。</span></div>
    <div class="model-diagram">
      ${entity('Risk', '主表', [['Risk Number','Autonumber'],['Title / Description','Text'],['Source / Domain','Choice'],['Impacted Location / Asset','Text'],['IT + Business Owner','User'],['Treatment / Status','Choice'],['Target / Reassessment / Close Date','Date'],['Analyst / Close reason / Notes','Text']])}
      <div class="relation"><div class="relation-line"><b>1:N</b></div>Risk Lookup<br><small>子记录保留独立历史</small></div>
      <div class="children">
        ${entity('Risk Action', '子表', [['Action Number','Autonumber'],['Risk','Lookup'],['Action / Work performed','Text'],['Owner / Team','User + Text'],['Due / Completion Date','Date'],['Status / Progress','Choice + Number']])}
        ${entity('Risk Assessment', '版本历史', [['Assessment Number','Autonumber'],['Risk','Lookup'],['Type / Version','Choice + Number'],['Likelihood × Impact','Number'],['Score / Level','Calculated'],['Date / Assessor','Date + User'],['Rationale','Multiline']])}
      </div>
    </div>
    <div class="dataverse-note"><strong>Power Apps 实现提示：</strong>Risk 详情页以当前记录为上下文，Actions 和 Assessments Gallery 使用 <code>Filter(..., Risk = ThisItem)</code>；New Action 表单的 Risk Lookup 默认设置为当前 Risk。评估保存时新增记录，禁止覆盖旧版本。</div>`;
}

function permissionsPage() {
  const rows = [
    ['查看 Risk','全部','本人负责','关联到本人 Action 的必要信息'],
    ['查看 Action','全部','本人 Risk 下全部','仅本人 Action'],
    ['查看 Assessment','全部','本人 Risk 下全部','关联 Risk 的摘要'],
    ['新建/修改 Risk','允许','禁止','禁止'],
    ['新建/修改 Action','允许','禁止','禁止'],
    ['执行评估/关闭 Risk','允许','禁止','禁止'],
  ];
  return `<div class="page-title"><div><h1>Permissions</h1><p>推荐采用最小可见范围；所有业务写操作集中由安全部门执行</p></div></div>
    <section class="panel permission-panel"><div class="panel-head"><h2>Role matrix</h2><span class="recommend-badge">推荐方案</span></div><div class="table-scroll"><table class="data-table matrix"><thead><tr><th>能力</th><th>安全部门</th><th>Risk Owner</th><th>Action Owner</th></tr></thead><tbody>${rows.map((row) => `<tr>${row.map((cell,index) => `<td class="${cell === '允许' ? 'allow' : cell === '禁止' ? 'deny' : ''}">${index ? (cell === '允许' ? '✓ ' : cell === '禁止' ? '— ' : '') : ''}${cell}</td>`).join('')}</tr>`).join('')}</tbody></table></div></section>
    <div class="permission-notes"><section><span>01</span><div><h3>安全团队集中管理</h3><p>Risk、Action 和 Assessment 均由安全部门创建或修改；其他人员发现问题时通过邮件反馈。</p></div></section><section><span>02</span><div><h3>Owner 登录后只读</h3><p>按钮不可用不是唯一控制；正式实现需同时用 Dataverse Security Role、Owner Team 和记录级共享限制写权限。</p></div></section><section><span>03</span><div><h3>可选宽松方案</h3><p>若客户后续接受更广泛可见范围，可让两类 Owner 查看完整 Risk Register，但仍保持只读。当前 Demo 采用更严格方案。</p></div></section></div>`;
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
  if (state.drawer === 'closeRisk') return drawerFrame('Close Risk', closeRiskForm());
  return '';
}

function drawerFrame(title, body) {
  return `<div class="drawer-backdrop" data-action="close-drawer"><section class="drawer" role="dialog" aria-modal="true" aria-label="${title}"><div class="drawer-head"><h2>${title}</h2><button class="close-btn" data-action="close-drawer" aria-label="关闭">×</button></div>${body}</section></div>`;
}

function riskForm() {
  const form = riskDetailsPanel({
    id: 'new-risk', title: '', description: '', createdDate: isoDay(0),
    registrantEmail: emailFor(roles.security.user), creatorEmail: emailFor(roles.security.user),
    source: '', domains: [], locations: [], impactedAsset: '',
    ownerEmail: '', ownerTeam: '', businessOwnerEmail: '', businessTeam: '',
  }).replace('data-form="inlineEditRisk"', 'data-form="risk"')
    .replace('Save changes</button>', 'Save Risk</button><button type="button" class="btn" data-action="close-drawer">Cancel</button>');
  return `<div class="drawer-body">${form}</div>`;
}

function actionForm() {
  const risk = selectedRisk();
  return `<form data-form="action" class="form-grid" style="display:contents"><div class="drawer-body"><div class="form-grid">
    <div class="form-field"><label>Related Risk</label><input readonly value="${risk.id} · ${esc(risk.title)}"><div class="form-hint">由当前 Risk 自动关联，正式 Power Apps 中对应 Dataverse Lookup 默认值。</div></div>
    ${inputField('Action', 'title', 'text', true, '输入需要完成的整改措施')}
    ${selectField('Action Owner', 'owner', ['赵明','孙宇','刘洋','王蕾'])}
    ${inputField('Action Owner Team', 'ownerTeam', 'text', true, '', '网络与数据安全')}
    ${inputField('Due Date', 'dueDate', 'date', true, '', isoDay(7))}
    ${textareaField('Work performed / plan', 'workPerformed', false)}
  </div></div><div class="drawer-foot"><button type="button" class="btn" data-action="close-drawer">Cancel</button><button class="btn primary" type="submit">Save Action</button></div></form>`;
}

function assessmentForm() {
  const risk = selectedRisk();
  return `<form data-form="assessment" class="form-grid" style="display:contents"><div class="drawer-body"><div class="form-grid">
    <div class="form-field"><label>Related Risk</label><input readonly value="${risk.id} · ${esc(risk.title)}"></div>
    ${selectField('Assessment Type', 'type', assessmentsFor(risk.id).some(item => item.type === 'Initial') ? ['Residual'] : ['Initial'])}
    ${selectField('Likelihood (1–5)', 'likelihood', ['1','2','3','4','5'], '3', 'score-input')}
    ${selectField('Impact (1–5)', 'impact', ['1','2','3','4','5'], '3', 'score-input')}
    <div class="score-live"><span>Calculated score</span><strong id="live-score">9 · 中风险</strong><small>Likelihood × Impact，无需手工选择总分</small></div>
    ${selectField('风险处置 Risk Treatment', 'treatment', treatmentOptions)}
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
    ${textareaField('Work performed', 'workPerformed', false, action.workPerformed)}
  </div></div><div class="drawer-foot"><button type="button" class="btn" data-action="close-drawer">Cancel</button><button class="btn primary" type="submit">Save Update</button></div></form>`;
}

function closeRiskForm() {
  const risk = selectedRisk();
  const latest = assessmentsFor(risk.id).at(-1);
  return `<form data-form="closeRisk" class="form-grid" style="display:contents"><div class="drawer-body"><div class="close-summary"><span>${risk.id}</span><h3>${esc(risk.title)}</h3><p>Latest assessment：${latest ? `${score(latest)} · ${levelText[levelFromScore(score(latest))]}` : '尚未评估'}</p></div><div class="form-grid">
    ${inputField('Risk Close Date', 'closeDate', 'date', true, '', isoDay(0))}
    ${textareaField('Reason for risk closure', 'closeReason', true)}
  </div><div class="warning-box">关闭后记录保留并可查询；业务编号和历史 Assessment 不会被重排或覆盖。</div></div><div class="drawer-foot"><button type="button" class="btn" data-action="close-drawer">Cancel</button><button class="btn primary" type="submit">Confirm close</button></div></form>`;
}

function inputField(label, name, type = 'text', required = false, placeholder = '', value = '', min = '', max = '') {
  return `<div class="form-field"><label for="field-${name}">${label}${required ? ' <span class="required">*</span>' : ''}</label><input id="field-${name}" name="${name}" type="${type}" value="${esc(value)}" placeholder="${esc(placeholder)}" ${required ? 'required' : ''} ${min !== '' ? `min="${min}"` : ''} ${max !== '' ? `max="${max}"` : ''}></div>`;
}

function textareaField(label, name, required = false, value = '') {
  return `<div class="form-field"><label for="field-${name}">${label}${required ? ' <span class="required">*</span>' : ''}</label><textarea id="field-${name}" name="${name}" ${required ? 'required' : ''}>${esc(value)}</textarea></div>`;
}

function selectField(label, name, values, selected = values[0], className = '') {
  return `<div class="form-field"><label for="field-${name}">${label}</label><select id="field-${name}" name="${name}" class="${className}">${values.map((value) => `<option value="${value}" ${value === selected ? 'selected' : ''}>${value}</option>`).join('')}</select></div>`;
}

function render() {
  root.innerHTML = shell();
}

function readRiskFields(form) {
  const fields = new FormData(form);
  return { ...Object.fromEntries(fields), domains: fields.getAll('domains'), locations: fields.getAll('locations') };
}

function rememberRiskDraft(event) {
  const form = event.target.closest('form[data-form="inlineEditRisk"]');
  if (!form || state.role !== 'security') return;
  state.riskDrafts[selectedRisk().id] = readRiskFields(form);
  form.querySelector('[data-save-state]').textContent = '有未保存的修改';
}
root.addEventListener('input', rememberRiskDraft);
root.addEventListener('change', rememberRiskDraft);

function showToast(message) {
  const region = document.querySelector('#toast-region');
  const item = document.createElement('div');
  item.className = 'toast';
  item.textContent = message;
  region.append(item);
  setTimeout(() => item.remove(), 3200);
}

function createRiskRecord(values) {
  const id = `RSK-${today.getFullYear()}-${String(data.risks.length + 1).padStart(4, '0')}`;
  const risk = {
    id,
    title: values.title.trim(),
    description: values.description.trim(),
    source: values.source || '安全部门检测',
    category: values.category || '安全监控',
    domain: values.domain || 'Security Monitoring',
    impactedLocation: values.impactedLocation?.trim() || '待确认',
    impactedAsset: values.impactedAsset?.trim() || '待确认',
    status: 'Registered',
    owner: values.owner || Object.keys(demoEmails).find(name => demoEmails[name] === values.ownerEmail) || values.ownerEmail,
    ownerEmail: values.ownerEmail || emailFor(values.owner),
    registrantEmail: values.registrantEmail || emailFor(roles.security.user),
    creatorEmail: emailFor(roles.security.user),
    domains: values.domains || asList(values.domain),
    locations: values.locations || asList(values.impactedLocation),
    businessOwnerEmail: values.businessOwnerEmail || emailFor(values.businessOwner),
    ownerTeam: values.ownerTeam?.trim() || '待确认',
    businessOwner: values.businessOwner || values.businessOwnerEmail || '',
    businessTeam: values.businessTeam?.trim() || '',
    treatment: values.treatment || 'Remediation',
    targetDate: values.targetDate || isoDay(30),
    reassessmentDate: values.reassessmentDate || values.targetDate || isoDay(14),
    createdDate: values.createdDate || isoDay(0),
    level: 'Medium',
    analyst: roles.security.user,
    closeDate: '',
    closeReason: '',
    notes: '',
  };
  if (values.domains) risk.domain = values.domains.join('、');
  if (values.locations) risk.impactedLocation = values.locations.join('、');
  data.risks.unshift(risk);
  data.activity.unshift({ riskId: id, date: isoDay(0), actor: roles.security.user, event: 'Risk 已由安全部门登记；已排队发送创建通知' });
  save();
  return risk;
}

function registerWebMcp() {
  const context = document.modelContext;
  if (!context?.registerTool) return;
  const datePattern = '^\\d{4}-\\d{2}-\\d{2}$';
  void Promise.resolve(context.registerTool({
    name: 'register_risk',
    title: 'Register risk',
    description: 'Register a new Risk as the Security Team and show its detail page.',
    inputSchema: {
      type: 'object',
      properties: {
        title: { type: 'string', minLength: 3 },
        description: { type: 'string', minLength: 3 },
        owner: { type: 'string', minLength: 1 },
        targetDate: { type: 'string', pattern: datePattern },
      },
      required: ['title','description','owner','targetDate'],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: false, untrustedContentHint: false },
    execute(input) {
      if (state.role !== 'security') throw new Error('Only the Security Team can register a Risk.');
      if (!input || typeof input !== 'object' || String(input.title || '').trim().length < 3 || String(input.description || '').trim().length < 3 || !String(input.owner || '').trim() || !/^\d{4}-\d{2}-\d{2}$/.test(String(input.targetDate || ''))) throw new Error('Invalid Risk input.');
      const risk = createRiskRecord(input);
      state.selectedRiskId = risk.id;
      state.page = 'detail';
      state.detailTab = 'actions';
      render();
      return { riskNumber: risk.id, status: risk.status, owner: risk.owner };
    },
  })).catch(() => {});
}

root.addEventListener('click', (event) => {
  const target = event.target.closest('[data-action]');
  if (!target) return;
  if (target.classList.contains('drawer-backdrop') && event.target !== target) return;
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
    if (state.role !== 'security' && ['risk','action','assessment','closeRisk'].includes(target.dataset.drawer)) {
      showToast('当前角色为只读，请联系安全部门修改');
      return;
    }
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
  } else if (event.target.classList.contains('score-input')) {
    const form = event.target.closest('form');
    const likelihood = Number(form.elements.likelihood.value);
    const impact = Number(form.elements.impact.value);
    const value = likelihood * impact;
    const live = form.querySelector('#live-score');
    if (live) live.textContent = `${value} · ${levelText[levelFromScore(value)]}`;
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
  if (state.role !== 'security') {
    showToast('只读角色不能保存修改');
    return;
  }
  if (form.dataset.form === 'risk') {
    const fields = readRiskFields(form);
    if (!form.reportValidity() || !fields.title.trim() || !fields.description.trim() || !fields.impactedAsset.trim() || !fields.domains.length || !fields.locations.length) {
      showToast('请填写必填信息，风险域和影响区域至少各选一项');
      return;
    }
    const risk = createRiskRecord(fields);
    state.selectedRiskId = risk.id;
    state.page = 'detail';
    state.detailTab = 'actions';
    state.drawer = null;
    render(); showToast(`${risk.id} 已登记`);
  } else if (form.dataset.form === 'inlineEditRisk') {
    const risk = selectedRisk();
    const fields = readRiskFields(form);
    state.riskDrafts[risk.id] = fields;
    if (!form.reportValidity() || !fields.title.trim() || !fields.description.trim() || !fields.impactedAsset.trim() || !fields.domains.length || !fields.locations.length) {
      showToast('请填写必填信息，风险域和影响区域至少各选一项');
      return;
    }
    const previous = clone(risk);
    const updated = {
      title: fields.title.trim(), description: fields.description.trim(),
      createdDate: fields.createdDate, registrantEmail: fields.registrantEmail.trim(),
      creatorEmail: risk.creatorEmail ?? emailFor(risk.analyst),
      source: fields.source, domains: fields.domains, domain: fields.domains.join('、'),
      locations: fields.locations, impactedLocation: fields.locations.join('、'),
      impactedAsset: fields.impactedAsset.trim(), ownerEmail: fields.ownerEmail.trim(),
      owner: Object.keys(demoEmails).find(name => demoEmails[name].toLowerCase() === fields.ownerEmail.trim().toLowerCase()) || fields.ownerEmail.trim(),
      ownerTeam: fields.ownerTeam, businessOwnerEmail: fields.businessOwnerEmail.trim(),
      businessOwner: Object.keys(demoEmails).find(name => demoEmails[name].toLowerCase() === fields.businessOwnerEmail.trim().toLowerCase()) || fields.businessOwnerEmail.trim(),
      businessTeam: fields.businessTeam,
    };
    Object.assign(risk, updated);
    data.activity.unshift({ riskId: risk.id, date: isoDay(0), actor: roles.security.user, event: 'Risk 基本信息已更新' });
    try { save(); }
    catch {
      data.risks[data.risks.indexOf(risk)] = previous;
      data.activity.shift();
      showToast('保存失败，修改仍保留在表单中，请重试');
      return;
    }
    delete state.riskDrafts[risk.id];
    render(); showToast(`${risk.id} 已保存`);
  } else if (form.dataset.form === 'action') {
    const id = `ACT-${today.getFullYear()}-${String(data.actions.length + 1).padStart(4, '0')}`;
    data.actions.push({ id, riskId: selectedRisk().id, title: values.title.trim(), owner: values.owner, ownerTeam: values.ownerTeam.trim(), dueDate: values.dueDate, status: 'Not Started', progress: 0, workPerformed: values.workPerformed.trim(), completedDate: '' });
    data.activity.unshift({ riskId: selectedRisk().id, date: isoDay(0), actor: roles.security.user, event: `${id} 已创建；已排队通知 ${values.owner}` });
    state.drawer = null;
    save(); render(); showToast(`${id} 已创建，并自动关联 ${selectedRisk().id}`);
  } else if (form.dataset.form === 'assessment') {
    if (values.type === 'Initial' && assessmentsFor(selectedRisk().id).some(item => item.type === 'Initial')) {
      showToast('初始评估已存在，请新增 Residual 复评');
      return;
    }
    const id = `ASM-${today.getFullYear()}-${String(data.assessments.length + 1).padStart(4, '0')}`;
    const version = assessmentsFor(selectedRisk().id).length + 1;
    const assessment = { id, riskId: selectedRisk().id, type: values.type, version, likelihood: Number(values.likelihood), impact: Number(values.impact), treatment: values.treatment, assessor: values.assessor, date: isoDay(0), notes: values.notes.trim() };
    data.assessments.push(assessment);
    selectedRisk().level = levelFromScore(score(assessment));
    selectedRisk().status = values.type === 'Initial' ? 'Mitigating' : selectedRisk().status;
    data.activity.unshift({ riskId: selectedRisk().id, date: isoDay(0), actor: values.assessor, event: `${values.type} Assessment v${version}：${score(assessment)} · ${levelText[levelFromScore(score(assessment))]}` });
    state.detailTab = 'assessments';
    state.drawer = null;
    save(); render(); showToast(`${id} 已保存`);
  } else if (form.dataset.form === 'editAction') {
    const item = data.actions.find((action) => action.id === state.editingActionId);
    if (item) {
      item.status = values.status;
      item.progress = values.status === 'Completed' ? 100 : Number(values.progress);
      item.dueDate = values.dueDate;
      item.workPerformed = values.workPerformed.trim();
      item.completedDate = values.status === 'Completed' ? isoDay(0) : '';
      data.activity.unshift({ riskId: item.riskId, date: isoDay(0), actor: roles.security.user, event: `${item.id} 已更新为 ${statusText[item.status]}` });
    }
    state.drawer = null;
    state.editingActionId = null;
    save(); render(); showToast('Action 已更新');
  } else if (form.dataset.form === 'closeRisk') {
    const risk = selectedRisk();
    risk.status = 'Closed';
    risk.closeDate = values.closeDate;
    risk.closeReason = values.closeReason.trim();
    data.activity.unshift({ riskId: risk.id, date: values.closeDate, actor: roles.security.user, event: `Risk 已关闭：${risk.closeReason}` });
    state.drawer = null;
    state.detailTab = 'activity';
    save(); render(); showToast(`${risk.id} 已关闭，历史记录已保留`);
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
registerWebMcp();
