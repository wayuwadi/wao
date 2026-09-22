/*
 * Incremental behavior layer for IT Risk Management v7.
 * It deliberately reuses the existing page renderer, data, forms and visual styles.
 */

Object.assign(statusText, {
  Registered: '登记中',
  Assessing: '评估中',
  Assessed: '已评估',
  Mitigating: '处置中',
  Completed: '已完成',
  Closed: '已完成',
});

const demoLevelFromScore = (value) => value >= 9 ? 'High' : value >= 4 ? 'Medium' : 'Low';

function isRiskCompleted(risk) {
  return risk.status === 'Completed' || risk.status === 'Closed' || Boolean(risk.closeDate);
}

function derivedRiskStatus(risk, options = {}) {
  if (!options.ignoreClosure && isRiskCompleted(risk)) return 'Completed';

  const riskAssessments = assessmentsFor(risk.id);
  const riskActions = actionsFor(risk.id);
  if (!riskAssessments.length) return 'Registered';
  if (!riskActions.length) return 'Assessing';

  const hasProgress = riskActions.some((action) =>
    action.status === 'In Progress' ||
    action.status === 'Completed' ||
    Number(action.progress) > 0
  );
  return hasProgress ? 'Mitigating' : 'Assessed';
}

function syncRiskStatuses() {
  data.risks.forEach((risk) => {
    risk.status = derivedRiskStatus(risk);
    const latestAssessment = assessmentsFor(risk.id).at(-1);
    if (latestAssessment) risk.level = demoLevelFromScore(score(latestAssessment));
  });
}

function normalizeLegacyAssessmentScale() {
  data.assessments.forEach((assessment) => {
    if (assessment.likelihood <= 3 && assessment.impact <= 3) return;
    const legacyScore = assessment.likelihood * assessment.impact;
    if (legacyScore >= 15) {
      assessment.likelihood = 3;
      assessment.impact = 3;
    } else if (legacyScore >= 6) {
      assessment.likelihood = 2;
      assessment.impact = legacyScore >= 10 ? 3 : 2;
    } else {
      assessment.likelihood = 1;
      assessment.impact = Math.min(3, Math.max(1, Math.round(legacyScore)));
    }
  });
}

function riskStatusOptions() {
  return [
    ['All', '全部状态'],
    ['Registered', '登记中'],
    ['Assessing', '评估中'],
    ['Assessed', '已评估'],
    ['Mitigating', '处置中'],
    ['Completed', '已完成'],
  ];
}

state.actionQuery ??= '';
state.actionStatus ??= 'All';
state.editingAssessmentId ??= null;

function assessmentLevel(assessment) {
  return assessment ? demoLevelFromScore(score(assessment)) : null;
}

function assessmentLevelBadge(assessment) {
  if (!assessment) return '<span class="not-assessed">未评估</span>';
  const level = assessmentLevel(assessment);
  return `<span class="risk-level ${level.toLowerCase()}">${score(assessment)} · ${levelText[level]}</span>`;
}

function assessmentTypeText(type) {
  return ({
    Initial: 'Initial（初始）',
    Residual: 'Residual（残余）',
    'Second Round': 'Second Round（二次评估）',
  })[type] || esc(type);
}

commandBar = function () {
  const role = roles[state.role];
  let commands = '';

  if (state.page === 'risks') {
    commands = `${state.role === 'security' ? '<button class="cmd-btn primary" data-action="open-drawer" data-drawer="risk"><span>＋</span> New Risk</button><span class="cmd-sep"></span>' : ''}<button class="cmd-btn" data-action="refresh">↻ Refresh</button>`;
  } else if (state.page === 'detail') {
    const risk = selectedRisk();
    const securityCommands = state.role !== 'security' ? '' : isRiskCompleted(risk)
      ? '<button class="cmd-btn reopen-risk" data-action="reopen-risk">↺ Reopen Risk</button>'
      : '<button class="cmd-btn primary" data-action="open-drawer" data-drawer="assessment">＋ New Assessment</button><button class="cmd-btn" data-action="open-drawer" data-drawer="action">＋ New Action</button><button class="cmd-btn close-risk" data-action="open-drawer" data-drawer="closeRisk">✓ Close Risk</button>';
    commands = `<button class="cmd-btn" data-action="back">‹ Back</button>${securityCommands ? `<span class="cmd-sep"></span>${securityCommands}` : ''}`;
  } else {
    commands = '<button class="cmd-btn" data-action="refresh">↻ Refresh</button>';
  }

  return `<div class="commandbar">${commands}<span class="command-spacer"></span><span class="role-note">${esc(role.note)}</span></div>`;
};

shell = function () {
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
          <div class="nav-bottom"><strong>${role.label} · ${role.user}</strong>${role.note}</div>
        </aside>
        <main class="main">
          ${commandBar()}
          <div class="content">${page()}</div>
        </main>
      </div>
      ${drawer()}
    </div>`;
};

page = function () {
  if (state.page === 'detail') return detailPage();
  if (state.page === 'actions') return actionsPage();
  if (state.page !== 'risks') state.page = 'risks';
  return risksPage();
};

risksPage = function () {
  const risks = visibleRisks();
  const role = roles[state.role];
  const open = risks.filter((risk) => !isRiskCompleted(risk)).length;
  const high = risks.filter((risk) => ['High', 'Critical'].includes(risk.level) && !isRiskCompleted(risk)).length;
  const overdue = visibleActions().filter((action) => dueState(action).key === 'overdue').length;
  const dueSoon = visibleActions().filter((action) => dueState(action).key === 'due-soon').length;
  return `
    <div class="page-title"><div><h1>Risk Register</h1><p>${state.role === 'security' ? '安全部门统一登记、评估、跟踪并关闭风险' : `只读显示与 ${role.user} 相关的记录`}</p></div><span class="scope-chip">${state.role === 'security' ? 'ALL RECORDS' : 'MY SCOPE · READ ONLY'}</span></div>
    <div class="kpis">
      ${kpi('开放 Risk', open, '▤')}${kpi('高风险', high, '⚠')}${kpi('逾期 Action', overdue, '!')}${kpi('3 天内到期', dueSoon, '◷')}
    </div>
    <section class="panel">
      <div class="panel-head"><h2>Risks <span class="count">(${risks.length})</span></h2></div>
      <div class="toolbar">
        <input id="risk-search" class="search" value="${esc(state.query)}" placeholder="搜索 Risk ID、标题、分类或 Owner" aria-label="搜索风险">
        <select id="status-filter" class="filter-select" aria-label="按状态筛选">
          ${riskStatusOptions().map(([value, label]) => `<option value="${value}" ${state.status === value ? 'selected' : ''}>${label}</option>`).join('')}
        </select>
      </div>
      <div class="table-scroll">${riskTable(risks)}</div>
    </section>`;
};

riskTable = function (risks) {
  if (!risks.length) return '<div class="empty">没有符合当前条件的 Risk。</div>';
  return `<table class="data-table risk-register-table"><thead><tr><th style="width:26%">Risk</th><th>Domain</th><th>Initial Risk Level</th><th>Current Risk Level</th><th>Status</th><th>Risk Owner</th><th>Target Date</th></tr></thead><tbody>${risks.map((risk) => {
    const assessments = assessmentsFor(risk.id);
    const initial = assessments.find((item) => item.type === 'Initial');
    const current = assessments.at(-1);
    return `<tr class="clickable" tabindex="0" data-action="open-risk" data-id="${risk.id}">
      <td><span class="risk-title">${esc(risk.title)}</span><span class="risk-id">${risk.id} · ${esc(risk.category)}</span></td>
      <td>${esc(risk.domain || risk.category)}</td>
      <td class="level-cell">${assessmentLevelBadge(initial)}</td>
      <td class="level-cell">${assessmentLevelBadge(current)}</td>
      <td><span class="status ${statusClass(risk.status)}">${statusText[risk.status]}</span></td>
      <td><div class="owner-cell"><span class="mini-avatar">${esc(risk.owner.slice(0, 1))}</span>${esc(risk.owner)}</div></td>
      <td>${risk.targetDate}</td>
    </tr>`;
  }).join('')}</tbody></table>`;
};

detailPage = function () {
  const risk = selectedRisk();
  if (!risk) return '<div class="empty">Risk 不存在。</div>';
  if (state.detailTab === 'activity') state.detailTab = state.role === 'actionOwner' ? 'actions' : 'assessments';
  const riskActions = state.role === 'actionOwner'
    ? actionsFor(risk.id).filter((item) => item.owner === roles.actionOwner.user)
    : actionsFor(risk.id);
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
          ${state.role === 'actionOwner' ? '' : `<button class="tab ${state.detailTab === 'assessments' ? 'active' : ''}" data-action="tab" data-tab="assessments" role="tab">Assessments (${riskAssessments.length})</button>`}
          <button class="tab ${state.detailTab === 'actions' ? 'active' : ''}" data-action="tab" data-tab="actions" role="tab">Actions (${riskActions.length})</button>
        </div>
        <div class="tab-body">${detailTab(risk, riskActions, riskAssessments)}</div>
      </section>
    </div>`;
};

const originalDetailTab = detailTab;
detailTab = function (risk, riskActions, riskAssessments) {
  if (state.detailTab !== 'assessments') return originalDetailTab(risk, riskActions, riskAssessments);
  if (!riskAssessments.length) return '<div class="empty">当前 Risk 还没有 Assessment。</div>';
  const canUpdate = state.role === 'security';
  return `<div class="history-note">Assessment 是 1:N 历史表；每次复评创建新版本，Risk Value = Likelihood × Impact 自动计算。</div><div class="table-scroll"><table class="data-table"><thead><tr><th>Assessment</th><th>Version</th><th>Type</th><th>Likelihood</th><th>Impact</th><th>Calculated Score</th><th>Risk Treatment</th><th>Assessor</th><th>Date</th>${canUpdate ? '<th></th>' : ''}</tr></thead><tbody>${riskAssessments.map((item, index) => `
    <tr><td><span class="risk-title">${item.id}</span><span class="risk-id">${esc(item.notes)}</span></td><td>v${item.version || index + 1}</td><td>${assessmentTypeText(item.type)}</td><td>${item.likelihood} / 3</td><td>${item.impact} / 3</td><td>${assessmentLevelBadge(item)}</td><td>${esc(item.treatment || '未记录')}</td><td>${esc(item.assessor)}</td><td>${item.date}</td>${canUpdate ? `<td><button class="inline-btn" data-action="edit-assessment" data-id="${item.id}">Update</button></td>` : ''}</tr>`).join('')}</tbody></table></div>`;
};

riskForm = function () {
  const base = riskDetailsPanel({
    id: 'new-risk', title: '', description: '', createdDate: isoDay(0),
    registrantEmail: emailFor(roles.security.user), creatorEmail: emailFor(roles.security.user),
    source: '', domains: [], locations: [], impactedAsset: '',
    ownerEmail: '', ownerTeam: '', businessOwnerEmail: '', businessTeam: '',
  });
  const openingEnd = base.indexOf('>') + 1;
  const inner = base.slice(openingEnd, base.lastIndexOf('</form>'))
    .replace('<button class="btn primary" type="submit">Save changes</button>', '');
  return `<form data-form="risk" style="display:contents"><div class="drawer-body"><section class="panel risk-detail-panel compact-risk-panel">${inner}</section></div><div class="drawer-foot"><button type="button" class="btn" data-action="close-drawer">Cancel</button><button class="btn primary" type="submit">Save Risk</button></div></form>`;
};

function actionEditorForm(action = null) {
  const risk = action ? data.risks.find((item) => item.id === action.riskId) : selectedRisk();
  const isEditing = Boolean(action);
  const status = action?.status || 'Not Started';
  return `<form data-form="${isEditing ? 'editAction' : 'action'}" class="form-grid" style="display:contents"><div class="drawer-body"><div class="form-grid">
    <div class="form-field"><label>Related Risk</label><input readonly value="${risk.id} · ${esc(risk.title)}"><div class="form-hint">自动关联当前 Risk。</div></div>
    ${inputField('Action', 'title', 'text', true, '输入需要完成的整改措施', action?.title || '')}
    ${selectField('Action Owner', 'owner', ['赵明', '孙宇', '刘洋', '王蕾'], action?.owner || '赵明')}
    ${inputField('Action Owner Team', 'ownerTeam', 'text', true, '', action?.ownerTeam || '网络与数据安全')}
    ${inputField('Due Date', 'dueDate', 'date', true, '', action?.dueDate || isoDay(7))}
    ${selectField('Status', 'status', ['Not Started', 'In Progress', 'Completed'], status)}
    ${inputField('Progress (%)', 'progress', 'number', true, '', action?.progress ?? 0, '0', '100')}
    ${textareaField('Work performed / plan', 'workPerformed', false, action?.workPerformed || '')}
  </div></div><div class="drawer-foot"><button type="button" class="btn" data-action="close-drawer">Cancel</button><button class="btn primary" type="submit">${isEditing ? 'Save Update' : 'Save Action'}</button></div></form>`;
}

actionForm = function () {
  return actionEditorForm();
};

editActionForm = function () {
  const action = data.actions.find((item) => item.id === state.editingActionId);
  return action ? actionEditorForm(action) : '<div class="drawer-body">Action 不存在。</div>';
};

function assessmentTypeField(selected) {
  const choices = [
    ['Initial', 'Initial（初始）'],
    ['Residual', 'Residual（残余）'],
    ['Second Round', 'Second Round（二次评估）'],
  ];
  return `<div class="form-field"><label for="field-type">Assessment Type</label><select id="field-type" name="type">${choices.map(([value, label]) => `<option value="${value}" ${value === selected ? 'selected' : ''}>${label}</option>`).join('')}</select></div>`;
}

function scoreField(name, label, selected, items) {
  return `<div class="score-field-block"><div class="form-field"><label for="field-${name}">${label}</label><select id="field-${name}" name="${name}" class="score-input">${['1', '2', '3'].map((value) => `<option value="${value}" ${Number(value) === Number(selected) ? 'selected' : ''}>${value}</option>`).join('')}</select></div><div class="field-score-guide"><strong>等级说明</strong><ul>${items.map((item) => `<li>${item}</li>`).join('')}</ul></div></div>`;
}

function assessmentEditorForm(assessment = null) {
  const risk = selectedRisk();
  const isEditing = Boolean(assessment);
  const likelihood = assessment?.likelihood || 3;
  const impact = assessment?.impact || 3;
  const value = likelihood * impact;
  const defaultType = assessment?.type || (assessmentsFor(risk.id).some((item) => item.type === 'Initial') ? 'Residual' : 'Initial');
  return `<form data-form="${isEditing ? 'editAssessment' : 'assessment'}" class="form-grid" style="display:contents"><div class="drawer-body"><div class="form-grid assessment-form-grid">
    <div class="form-field"><label>Related Risk</label><input readonly value="${risk.id} · ${esc(risk.title)}"></div>
    ${assessmentTypeField(defaultType)}
    ${scoreField('likelihood', 'Likelihood（发生概率，1–3 分）', likelihood, [
      '<b>3 分｜高：</b>很可能发生，年度内大概率出现；已有同类事件，现有控制措施薄弱。',
      '<b>2 分｜中：</b>有可能发生，特定条件下会触发；管控部分到位，但仍存在漏洞。',
      '<b>1 分｜低：</b>不太可能发生；现有防护措施完善，发生条件苛刻。',
    ])}
    ${scoreField('impact', 'Impact（安全影响，1–3 分）', impact, [
      '<b>3 分｜重大：</b>大规模数据泄露、核心系统瘫痪、重大处罚或品牌严重受损。',
      '<b>2 分｜一般：</b>局部业务中断、小范围数据泄露，修复投入较大或存在合规隐患。',
      '<b>1 分｜轻微：</b>影响很小，可快速修复，无合规处罚，业务基本不受影响。',
    ])}
    <div class="score-live score-live-enhanced">
      <span>Calculated Score / 自动计算结果</span>
      <strong id="live-score">${value} · ${levelText[demoLevelFromScore(value)]}</strong>
      <small class="score-formula">风险值 = Likelihood × Impact</small>
      <ul class="score-rules">
        <li><b>9 分（3×3）：高风险</b>，需优先处置，尽快制定缓解措施。</li>
        <li><b>4–6 分（2×2 / 2×3 / 3×2）：</b>中风险，按计划落实缓解方案。</li>
        <li><b>1–3 分：</b>低风险，常规监控即可。</li>
      </ul>
    </div>
    ${selectField('风险处置 Risk Treatment', 'treatment', treatmentOptions, assessment?.treatment || treatmentOptions[0])}
    ${selectField('Assessor', 'assessor', ['孙倩', '李佳', '陈晨'], assessment?.assessor || '孙倩')}
    ${textareaField('Assessment notes', 'notes', false, assessment?.notes || '')}
    <div class="demo-assessment-note"><strong>Demo 说明：</strong>打分规则及示例仅用于 Demo 演示，正式开发将依据企业现有风险框架修订。</div>
  </div></div><div class="drawer-foot"><button type="button" class="btn" data-action="close-drawer">Cancel</button><button class="btn primary" type="submit">${isEditing ? 'Save Update' : 'Save Assessment'}</button></div></form>`;
}

assessmentForm = function () {
  return assessmentEditorForm();
};

function editAssessmentForm() {
  const assessment = data.assessments.find((item) => item.id === state.editingAssessmentId);
  return assessment ? assessmentEditorForm(assessment) : '<div class="drawer-body">Assessment 不存在。</div>';
}

const originalDrawer = drawer;
drawer = function () {
  if (state.drawer === 'editAssessment') return drawerFrame('Update Assessment', editAssessmentForm());
  return originalDrawer();
};

actionsPage = function () {
  const roleActions = visibleActions();
  const term = state.actionQuery.trim().toLowerCase();
  const actions = roleActions.filter((action) => {
    const risk = data.risks.find((item) => item.id === action.riskId);
    const searchable = `${action.id} ${action.title} ${action.owner} ${action.ownerTeam || ''} ${action.workPerformed || ''} ${action.riskId} ${risk?.title || ''}`.toLowerCase();
    return (!term || searchable.includes(term)) && (state.actionStatus === 'All' || action.status === state.actionStatus);
  });
  const overdue = actions.filter((action) => dueState(action).key === 'overdue').length;
  const dueSoon = actions.filter((action) => dueState(action).key === 'due-soon').length;
  const completed = actions.filter((action) => action.status === 'Completed').length;
  return `
    <div class="page-title"><div><h1>My Actions</h1><p>${state.role === 'security' ? '安全部门可查看并维护全部整改措施' : `只读显示 ${roles[state.role].user} 权限范围内的 Action`}</p></div><span class="scope-chip">${state.role === 'security' ? 'ALL ACTIONS' : 'READ ONLY'}</span></div>
    <div class="kpis">${kpi('筛选结果', actions.length, '✓')}${kpi('逾期', overdue, '!')}${kpi('3 天内到期', dueSoon, '◷')}${kpi('已完成', completed, '✓')}</div>
    <section class="panel"><div class="panel-head"><h2>Risk Actions <span class="count">(${actions.length})</span></h2></div>
      <div class="toolbar action-filters"><input id="action-search" class="search" value="${esc(state.actionQuery)}" placeholder="搜索 Action、Risk、Owner、Team 或工作记录" aria-label="搜索 Action"><select id="action-status-filter" class="filter-select" aria-label="按 Action 状态筛选">${[['All', '全部状态'], ['Not Started', '未开始'], ['In Progress', '进行中'], ['Completed', '已完成']].map(([value, label]) => `<option value="${value}" ${state.actionStatus === value ? 'selected' : ''}>${label}</option>`).join('')}</select></div>
      <div class="table-scroll"><table class="data-table"><thead><tr><th style="width:25%">Action</th><th>Related Risk</th><th>Owner</th><th>Due Date</th><th>Reminder</th><th>Work performed</th><th>Status</th><th></th></tr></thead><tbody>${actions.map((action) => {
        const risk = data.risks.find((item) => item.id === action.riskId);
        const due = dueState(action);
        const canEdit = state.role === 'security';
        return `<tr><td><span class="action-title">${esc(action.title)}</span><span class="risk-id">${action.id}</span></td><td><button class="inline-btn" data-action="open-risk" data-id="${action.riskId}">${risk?.id}<br>${esc(risk?.title || '')}</button></td><td>${esc(action.owner)}<span class="risk-id">${esc(action.ownerTeam || '')}</span></td><td>${action.dueDate}</td><td><span class="reminder-state ${due.key}">${due.label}</span></td><td><span class="work-note">${esc(action.workPerformed || '尚未记录')}</span></td><td><span class="status ${statusClass(action.status)}">${statusText[action.status]}</span></td><td>${canEdit ? `<button class="inline-btn" data-action="edit-action" data-id="${action.id}">Update</button>` : ''}</td></tr>`;
      }).join('')}</tbody></table>${actions.length ? '' : '<div class="empty">没有符合当前条件的 Action。</div>'}</div></section>`;
};

root.addEventListener('submit', (event) => {
  const form = event.target;
  if (!['action', 'editAction', 'assessment', 'editAssessment'].includes(form.dataset.form)) return;
  event.preventDefault();
  event.stopImmediatePropagation();
  if (!form.reportValidity()) return;

  const values = Object.fromEntries(new FormData(form));

  if (form.dataset.form === 'action' || form.dataset.form === 'editAction') {
    const isEditing = form.dataset.form === 'editAction';
    const existing = isEditing ? data.actions.find((item) => item.id === state.editingActionId) : null;
    const riskId = existing?.riskId || selectedRisk().id;
    const status = values.status;
    const progress = status === 'Completed' ? 100 : status === 'Not Started' ? 0 : Math.min(100, Math.max(0, Number(values.progress)));
    if (existing) {
      Object.assign(existing, {
        title: values.title.trim(), owner: values.owner, ownerTeam: values.ownerTeam.trim(),
        dueDate: values.dueDate, status, progress,
        workPerformed: values.workPerformed.trim(), completedDate: status === 'Completed' ? isoDay(0) : '',
      });
      data.activity.unshift({ riskId, date: isoDay(0), actor: roles.security.user, event: `${existing.id} 已更新为 ${statusText[status]}` });
    } else {
      const id = `ACT-${today.getFullYear()}-${String(data.actions.length + 1).padStart(4, '0')}`;
      data.actions.push({
        id, riskId, title: values.title.trim(), owner: values.owner, ownerTeam: values.ownerTeam.trim(),
        dueDate: values.dueDate, status, progress, workPerformed: values.workPerformed.trim(),
        completedDate: status === 'Completed' ? isoDay(0) : '',
      });
      data.activity.unshift({ riskId, date: isoDay(0), actor: roles.security.user, event: `${id} 已创建；已排队通知 ${values.owner}` });
    }
    state.drawer = null;
    state.editingActionId = null;
    syncRiskStatuses();
    save();
    render();
    showToast(existing ? 'Action 已更新' : 'Action 已创建');
    return;
  }

  const isEditing = form.dataset.form === 'editAssessment';
  const existing = isEditing ? data.assessments.find((item) => item.id === state.editingAssessmentId) : null;
  const risk = selectedRisk();
  const duplicateInitial = values.type === 'Initial' && assessmentsFor(risk.id).some((item) => item.type === 'Initial' && item.id !== existing?.id);
  if (duplicateInitial) {
    showToast('Initial（初始）评估已存在，请选择其他 Assessment Type');
    return;
  }
  if (existing) {
    Object.assign(existing, {
      type: values.type, likelihood: Number(values.likelihood), impact: Number(values.impact),
      treatment: values.treatment, assessor: values.assessor, notes: values.notes.trim(),
    });
    data.activity.unshift({ riskId: risk.id, date: isoDay(0), actor: values.assessor, event: `${existing.id} 已更新：${score(existing)} · ${levelText[assessmentLevel(existing)]}` });
  } else {
    const id = `ASM-${today.getFullYear()}-${String(data.assessments.length + 1).padStart(4, '0')}`;
    const assessment = {
      id, riskId: risk.id, type: values.type, version: assessmentsFor(risk.id).length + 1,
      likelihood: Number(values.likelihood), impact: Number(values.impact), treatment: values.treatment,
      assessor: values.assessor, date: isoDay(0), notes: values.notes.trim(),
    };
    data.assessments.push(assessment);
    data.activity.unshift({ riskId: risk.id, date: isoDay(0), actor: values.assessor, event: `${values.type} Assessment v${assessment.version}：${score(assessment)} · ${levelText[assessmentLevel(assessment)]}` });
  }
  state.detailTab = 'assessments';
  state.drawer = null;
  state.editingAssessmentId = null;
  syncRiskStatuses();
  save();
  render();
  showToast(existing ? 'Assessment 已更新' : 'Assessment 已保存');
}, true);

root.addEventListener('click', (event) => {
  const target = event.target.closest('[data-action]');
  if (!target) return;

  if (target.dataset.action === 'reopen-risk') {
    const risk = selectedRisk();
    if (state.role !== 'security' || !risk) return;
    risk.closeDate = '';
    risk.closeReason = '';
    risk.status = derivedRiskStatus(risk, { ignoreClosure: true });
    data.activity.unshift({
      riskId: risk.id,
      date: isoDay(0),
      actor: roles.security.user,
      event: `Risk 已恢复，当前状态：${statusText[risk.status]}`,
    });
    save();
    render();
    showToast(`${risk.id} 已恢复为${statusText[risk.status]}`);
  }

  if (target.dataset.action === 'edit-assessment') {
    if (state.role !== 'security') return;
    state.editingAssessmentId = target.dataset.id;
    state.drawer = 'editAssessment';
    render();
  }

  if (target.dataset.action === 'close-drawer') state.editingAssessmentId = null;

  if (target.dataset.action === 'open-risk' && state.role !== 'actionOwner') {
    state.detailTab = 'assessments';
    render();
  }
});

root.addEventListener('submit', (event) => {
  const form = event.target;
  if (!form.checkValidity()) return;
  if (['risk', 'inlineEditRisk'].includes(form.dataset.form)) {
    const fields = readRiskFields(form);
    if (!fields.title.trim() || !fields.description.trim() || !fields.impactedAsset.trim() || !fields.domains.length || !fields.locations.length) return;
  }
  syncRiskStatuses();
  save();
  render();
});

root.addEventListener('input', (event) => {
  if (event.target.id !== 'action-search') return;
  state.actionQuery = event.target.value;
  const cursor = event.target.selectionStart ?? state.actionQuery.length;
  render();
  const search = document.querySelector('#action-search');
  search?.focus();
  search?.setSelectionRange(cursor, cursor);
});

root.addEventListener('change', (event) => {
  if (event.target.id === 'action-status-filter') {
    state.actionStatus = event.target.value;
    render();
    return;
  }
  if (!event.target.classList.contains('score-input')) return;
  const form = event.target.closest('form');
  const likelihood = Number(form.elements.likelihood.value);
  const impact = Number(form.elements.impact.value);
  const value = likelihood * impact;
  const live = form.querySelector('#live-score');
  if (live) live.textContent = `${value} · ${levelText[demoLevelFromScore(value)]}`;
});

normalizeLegacyAssessmentScale();
syncRiskStatuses();
save();
render();
