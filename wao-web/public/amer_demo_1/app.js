const TODAY = '2026-09-03';

const seed = {
  risks: [
    { id:'RSK-2026-018', title:'第三方远程访问账号未按期复核', description:'外部运维账号已超过建议复核周期，存在非必要权限未及时回收的可能。', category:'访问控制', status:'整改中', priority:'P1', level:'高', owner:'陈晨', businessContext:'安全团队在季度权限复核中发现。', createdDate:'2026-08-21', targetClosureDate:'2026-09-12' },
    { id:'RSK-2026-017', title:'备份恢复演练记录不完整', description:'部分业务系统只有备份任务记录，缺少恢复验证的可追溯证据。', category:'业务连续性', status:'待评估', priority:'P2', level:'中', owner:'刘洋', businessContext:'年度备份有效性检查。', createdDate:'2026-08-27', targetClosureDate:'2026-09-20' },
    { id:'RSK-2026-015', title:'关键应用补丁窗口超期', description:'生产应用因变更冻结未在标准窗口内完成补丁升级。', category:'漏洞管理', status:'整改中', priority:'P1', level:'高', owner:'王蕾', businessContext:'八月度漏洞扫描与补丁合规检查。', createdDate:'2026-08-15', targetClosureDate:'2026-09-06' },
    { id:'RSK-2026-013', title:'生产日志留存周期不一致', description:'日志平台与部分应用本地留存策略未统一。', category:'安全监控', status:'已登记', priority:'P2', level:'中', owner:'陈晨', businessContext:'审计日志覆盖率抽查。', createdDate:'2026-08-12', targetClosureDate:'2026-09-25' },
    { id:'RSK-2026-011', title:'离职账号回收时效不稳定', description:'个别离职事件在目录服务中存在延迟。', category:'身份管理', status:'已关闭', priority:'P3', level:'低', owner:'周宁', businessContext:'已完成 HR 离职事件与目录服务的自动联动。', createdDate:'2026-07-29', targetClosureDate:'2026-08-28' }
  ],
  actions: [
    { id:'ACT-046', riskId:'RSK-2026-018', title:'完成第三方账号清单归并与业务确认', owner:'赵明', progress:100, status:'Completed', dueDate:'2026-09-02' },
    { id:'ACT-047', riskId:'RSK-2026-018', title:'回收无有效业务保荐人的访问权限', owner:'赵明', progress:60, status:'In Progress', dueDate:'2026-09-06' },
    { id:'ACT-048', riskId:'RSK-2026-018', title:'建立季度账号复核自动提醒', owner:'孙宇', progress:20, status:'In Progress', dueDate:'2026-09-12' },
    { id:'ACT-043', riskId:'RSK-2026-017', title:'补充核心系统恢复演练证据', owner:'赵明', progress:25, status:'In Progress', dueDate:'2026-09-18' },
    { id:'ACT-041', riskId:'RSK-2026-015', title:'安排紧急补丁变更窗口', owner:'赵明', progress:75, status:'In Progress', dueDate:'2026-09-01' },
    { id:'ACT-042', riskId:'RSK-2026-015', title:'完成补丁后回归验证', owner:'王蕾', progress:30, status:'In Progress', dueDate:'2026-09-07' },
    { id:'ACT-035', riskId:'RSK-2026-011', title:'打通 HR 离职事件自动锁定账号', owner:'赵明', progress:100, status:'Completed', dueDate:'2026-08-22' }
  ],
  assessments: [
    { id:'ASM-071', riskId:'RSK-2026-018', type:'Initial', likelihood:4, impact:5, score:20, date:'2026-08-22', assessor:'孙倩' },
    { id:'ASM-079', riskId:'RSK-2026-018', type:'Residual', likelihood:2, impact:4, score:8, date:'2026-09-02', assessor:'孙倩' },
    { id:'ASM-068', riskId:'RSK-2026-017', type:'Initial', likelihood:3, impact:4, score:12, date:'2026-08-28', assessor:'孙倩' },
    { id:'ASM-061', riskId:'RSK-2026-015', type:'Initial', likelihood:4, impact:5, score:20, date:'2026-08-16', assessor:'孙倩' },
    { id:'ASM-080', riskId:'RSK-2026-015', type:'Residual', likelihood:3, impact:4, score:12, date:'2026-09-01', assessor:'孙倩' },
    { id:'ASM-050', riskId:'RSK-2026-011', type:'Initial', likelihood:3, impact:3, score:9, date:'2026-07-30', assessor:'孙倩' },
    { id:'ASM-060', riskId:'RSK-2026-011', type:'Residual', likelihood:1, impact:3, score:3, date:'2026-08-27', assessor:'孙倩' }
  ],
  history: [
    { id:'H-1', riskId:'RSK-2026-018', time:'2026-09-02 16:20', actor:'孙倩', text:'新增残余风险评估，得分 8（中等）' },
    { id:'H-2', riskId:'RSK-2026-018', time:'2026-09-02 10:08', actor:'赵明', text:'整改措施 ACT-046 已完成' },
    { id:'H-3', riskId:'RSK-2026-018', time:'2026-08-22 09:35', actor:'孙倩', text:'完成初始风险评估，得分 20（高）' },
    { id:'H-4', riskId:'RSK-2026-018', time:'2026-08-21 14:12', actor:'林晓红', text:'创建风险并指定负责人陈晨' }
  ]
};

const roles = {
  admin: { name:'林晓红', label:'安全管理员', initial:'林', canRisk:true, canAction:true, canAssess:true, canUpdateAction:true, dashboard:true },
  riskOwner: { name:'陈晨', label:'风险负责人', initial:'陈', canRisk:false, canAction:true, canAssess:false, canUpdateAction:true, dashboard:false },
  actionOwner: { name:'赵明', label:'措施负责人', initial:'赵', canRisk:false, canAction:false, canAssess:false, canUpdateAction:true, dashboard:false },
  assessor: { name:'孙倩', label:'风险评估人', initial:'孙', canRisk:false, canAction:false, canAssess:true, canUpdateAction:false, dashboard:true },
  boss: { name:'何峰', label:'管理层（只读）', initial:'何', canRisk:false, canAction:false, canAssess:false, canUpdateAction:false, dashboard:true }
};

function clone(value){ return JSON.parse(JSON.stringify(value)); }
function loadData(){ try { return JSON.parse(localStorage.getItem('risk-demo-data')) || clone(seed); } catch { return clone(seed); } }
let data = loadData();
let state = { view:'workbench', role:'admin', selectedRiskId:data.risks[0].id, tab:'actions', query:'', statusFilter:'全部', levelFilter:'全部' };

const app = document.querySelector('#app');
const byId = id => document.getElementById(id);
const riskById = id => data.risks.find(r => r.id === id);
const actionsFor = id => data.actions.filter(a => a.riskId === id);
const assessmentsFor = id => data.assessments.filter(a => a.riskId === id);
const currentRole = () => roles[state.role];
const save = () => localStorage.setItem('risk-demo-data', JSON.stringify(data));
const esc = value => String(value ?? '').replace(/[&<>'"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));

function scoreLevel(score){ return score >= 15 ? '高' : score >= 6 ? '中' : '低'; }
function levelTone(level){ return level === '高' ? 'high' : level === '中' ? 'medium' : 'low'; }
function statusDot(status){ return status === '已关闭' ? 'green' : status === '待评估' ? 'violet' : status === '已登记' ? 'gray' : ''; }
function isOverdue(action){ return action.status !== 'Completed' && action.dueDate < TODAY; }
function dueSoon(action){ const ms=(new Date(action.dueDate)-new Date(TODAY))/86400000; return action.status !== 'Completed' && ms >= 0 && ms <= 3; }
function actionStatusLabel(status){ return ({'Open':'未开始','In Progress':'进行中','Completed':'已完成'})[status] || status; }
function lifecycleIndex(risk){ if(risk.status === '已关闭') return 5; if(risk.status === '待评估') return 1; const acts=actionsFor(risk.id); const asms=assessmentsFor(risk.id); if(asms.some(a=>a.type==='Residual')) return 4; if(acts.length && acts.every(a=>a.status==='Completed')) return 3; if(acts.length) return 2; return 1; }
function riskReduction(riskId){ const asms=assessmentsFor(riskId); const initial=asms.find(a=>a.type==='Initial'); const residual=[...asms].reverse().find(a=>a.type==='Residual'); return initial && residual ? Math.round((1-residual.score/initial.score)*100) : 0; }

function toast(message){ const el=byId('toast'); el.textContent=message; el.classList.add('show'); clearTimeout(toast.timer); toast.timer=setTimeout(()=>el.classList.remove('show'),2600); }
function openDialog(id){ const dialog=byId(id); if(dialog && !dialog.open) dialog.showModal(); }
function closeDialog(id){ const dialog=byId(id); if(dialog?.open) dialog.close(); }
function setView(view){ state.view=view; document.querySelector('#notifyDrawer')?.classList.remove('open'); render(); window.scrollTo({top:0,behavior:'smooth'}); }
function selectRisk(id, full=false){ state.selectedRiskId=id; if(full){ state.view='riskDetail'; state.tab='actions'; } render(); }

function header(){ const role=currentRole(); return `
  <header class="topbar">
    <div class="brand"><div class="brandmark">◈</div><div class="brandtitle">IT 安全风险管理<small>Risk Control Center</small></div></div>
    <span class="demo-pill">DEMO · 模拟数据</span>
    <div class="top-actions">
      <label class="sr-only" for="roleSwitch">演示角色</label>
      <select id="roleSwitch" class="role-select">
        <option value="admin" ${state.role==='admin'?'selected':''}>安全部门</option>
        <option value="riskOwner" ${state.role==='riskOwner'?'selected':''}>风险负责人</option>
        <option value="actionOwner" ${state.role==='actionOwner'?'selected':''}>措施负责人</option>
        <option value="assessor" ${state.role==='assessor'?'selected':''}>风险评估人</option>
        <option value="boss" ${state.role==='boss'?'selected':''}>管理层</option>
      </select>
      <button id="notifyBtn" class="icon-button" aria-label="查看通知">◌<span class="notification-dot"></span></button>
      <div class="user"><div class="usertext">${role.name}<small>${role.label}</small></div><div class="avatar">${role.initial}</div></div>
    </div>
  </header>`; }

const navItems = [
  ['workbench','▦','工作台'], ['risks','△','风险台账'], ['actions','◎','我的措施'], ['assessments','✓','风险评估'], ['dashboard','▤','管理驾驶舱']
];
function sidebar(){ return `<aside class="sidebar"><div class="nav-section">业务导航</div><nav aria-label="主导航">${navItems.map(([view,icon,label])=>`<button class="navitem ${state.view===view || (view==='risks'&&state.view==='riskDetail')?'active':''}" data-view="${view}"><span class="navico">${icon}</span><span class="navlabel">${label}</span></button>`).join('')}</nav><div class="nav-spacer"></div>${state.role==='admin'?`<button class="navitem ${state.view==='settings'?'active':''}" data-view="settings"><span class="navico">⚙</span><span class="navlabel">基础配置</span></button>`:''}<div class="sidebar-note">当前角色：${currentRole().label}<br>已按目标 Power Apps 权限结构演示</div></aside>`; }

function pageHead(title, lead, actions=''){ return `<div class="pagehead"><div><div class="crumb">IT 安全风险管理 › ${title}</div><h1>${title}</h1><p class="lead">${lead}</p></div><div class="page-actions">${actions}</div></div>`; }
function kpi(label,value,icon,tone='',delta=''){ return `<div class="kpi"><div class="kico ${tone}">${icon}</div><div><small>${label}</small><b>${value}</b>${delta?`<div class="delta">${delta}</div>`:''}</div></div>`; }
function riskTag(level){ return `<span class="tag ${levelTone(level)}">${level}风险</span>`; }
function statusTag(action){ if(action.status==='Completed') return `<span class="tag done">已完成</span>`; if(isOverdue(action)) return `<span class="tag overdue">已逾期</span>`; if(dueSoon(action)) return `<span class="tag due">3 天内到期</span>`; return `<span class="tag info">${actionStatusLabel(action.status)}</span>`; }

function visibleRisks(){ const role=currentRole(); if(state.role==='riskOwner') return data.risks.filter(r=>r.owner===role.name); if(state.role==='actionOwner'){ const ids=new Set(data.actions.filter(a=>a.owner===role.name).map(a=>a.riskId)); return data.risks.filter(r=>ids.has(r.id)); } return data.risks; }
function visibleActions(){ const role=currentRole(); if(state.role==='actionOwner') return data.actions.filter(a=>a.owner===role.name); if(state.role==='riskOwner'){ const ids=new Set(data.risks.filter(r=>r.owner===role.name).map(r=>r.id)); return data.actions.filter(a=>ids.has(a.riskId)); } return data.actions; }
function canUpdateThisAction(action){ return currentRole().canUpdateAction && (state.role==='admin' || action.owner===currentRole().name || (state.role==='riskOwner' && riskById(action.riskId)?.owner===currentRole().name)); }

function workbenchView(){
  const open=data.risks.filter(r=>r.status!=='已关闭').length;
  const high=data.risks.filter(r=>r.level==='高'&&r.status!=='已关闭').length;
  const overdue=data.actions.filter(isOverdue).length;
  const due=data.actions.filter(dueSoon).length;
  const role=currentRole();
  const roleActions = state.role==='actionOwner' ? visibleActions() : data.actions.filter(a=>a.owner===role.name || isOverdue(a)).slice(0,4);
  return `<div class="view">${pageHead('工作台',`你好，${role.name}。这里展示当前角色需要关注的风险与整改节点。`,role.canRisk?`<button class="btn btn-primary" data-open="riskDialog">+ 登记风险</button>`:'')}
    <section class="kpis">${kpi('开放风险',open,'△')}${kpi('高风险',high,'◈','red')}${kpi('逾期措施',overdue,'◷','amber')}${kpi('3 天内到期',due,'◌','violet')}</section>
    <div class="dashboard-grid"><div class="dashboard-stack">
      <section class="card"><div class="card-head"><h2>标准处理流程</h2><span class="tag info">6 个阶段</span></div><div class="process-wrap"><div class="process">${['登记风险','初始评估','制定措施','执行整改','残余评估','风险关闭'].map((s,i)=>`<div class="step ${i<3?'done':i===3?'current':''}"><span class="step-dot">${i<3?'✓':i+1}</span><span>${s}</span></div>`).join('')}</div></div></section>
      <section class="card"><div class="card-head"><h2>${state.role==='actionOwner'?'我负责的措施':'待办与重点事项'}</h2><button class="btn btn-subtle btn-sm" data-view="actions">查看全部 ›</button></div><div class="card-body">${roleActions.length?roleActions.map(a=>{const r=riskById(a.riskId);return `<div class="workflow-row"><div class="workflow-icon">◎</div><div><p>${esc(a.title)}</p><small>${r.id} · ${esc(r.title)} · 截止 ${a.dueDate}</small></div>${statusTag(a)}</div>`}).join(''):`<div class="empty">当前角色没有待办事项</div>`}</div></section>
    </div><div class="dashboard-stack">
      <section class="card"><div class="card-head"><h2>自动通知</h2><span class="tag done">流程运行中</span></div><div class="card-body alert-list"><div class="alert-item"><span>📩</span><div><p><b>新建 Risk 通知</b></p><small>保存后立即通知 Risk Owner</small></div></div><div class="alert-item"><span>⏰</span><div><p><b>Action 到期前 3 天</b></p><small>每日检查，排除 Completed</small></div></div><div class="alert-item"><span>⚠</span><div><p><b>Action 逾期提醒</b></p><small>Due Date &lt; Today 且未完成</small></div></div></div></section>
      <section class="card"><div class="card-head"><h2>风险责任分布</h2></div><div class="card-body">${['陈晨','王蕾','刘洋','周宁'].map((n,i)=>`<div class="chart-row"><span>${n}</span><div class="bar"><span style="width:${[88,66,48,28][i]}%"></span></div><b>${[5,4,3,2][i]}</b></div>`).join('')}</div></section>
    </div></div></div>`;
}

function riskRows(){
  let list=visibleRisks(); const q=state.query.toLowerCase();
  list=list.filter(r=>(r.id+r.title+r.category+r.owner).toLowerCase().includes(q));
  if(state.statusFilter!=='全部') list=list.filter(r=>r.status===state.statusFilter);
  if(state.levelFilter!=='全部') list=list.filter(r=>r.level===state.levelFilter);
  if(!list.length) return `<tr><td colspan="5"><div class="empty">没有匹配的风险记录</div></td></tr>`;
  return list.map(r=>`<tr class="clickable ${state.selectedRiskId===r.id?'selected':''}" data-risk="${r.id}"><td><div class="riskname">${esc(r.title)}</div><div class="meta">${r.id} · ${esc(r.category)}</div></td><td>${riskTag(r.level)}</td><td><span class="status-text"><i class="status-dot ${statusDot(r.status)}"></i>${r.status}</span></td><td>${r.owner}</td><td class="hide-mobile">${r.targetClosureDate}</td></tr>`).join('');
}
function riskPreview(r){ const acts=actionsFor(r.id); const done=acts.filter(a=>a.status==='Completed').length; return `<aside class="card detail-preview"><div class="detail-top"><div class="idrow"><span class="riskid">${r.id}</span>${riskTag(r.level)}</div><h2>${esc(r.title)}</h2><p>${esc(r.businessContext)}</p></div><div class="facts"><div class="fact"><small>风险负责人</small><b>${r.owner}</b></div><div class="fact"><small>目标关闭日</small><b>${r.targetClosureDate}</b></div><div class="fact"><small>整改措施</small><b>${done} / ${acts.length} 已完成</b></div><div class="fact"><small>当前状态</small><b>${r.status}</b></div></div><div class="detail-foot"><div class="progress-label"><span>风险降低</span><b>${riskReduction(r.id)}%</b></div><div class="track"><div class="fill" style="width:${riskReduction(r.id)}%"></div></div><button class="btn" style="width:100%;margin-top:10px" data-full-risk="${r.id}">查看完整详情 ›</button></div></aside>`; }

function risksView(){ const selected=riskById(state.selectedRiskId)||visibleRisks()[0]||data.risks[0]; state.selectedRiskId=selected.id; return `<div class="view">${pageHead('风险台账','登记风险、跟踪整改措施并完成残余风险评估',currentRole().canRisk?`<button class="btn btn-primary" data-open="riskDialog">+ 新建风险</button>`:'')}
    <div class="split"><section class="card"><div class="toolbar"><div class="search-wrap"><span class="search-icon">⌕</span><input id="riskSearch" class="control" value="${esc(state.query)}" placeholder="搜索编号、标题、分类或负责人"></div><select id="statusFilter" class="control">${['全部','已登记','待评估','整改中','已关闭'].map(v=>`<option ${state.statusFilter===v?'selected':''}>${v}</option>`).join('')}</select><select id="levelFilter" class="control">${['全部','高','中','低'].map(v=>`<option ${state.levelFilter===v?'selected':''}>${v}</option>`).join('')}</select></div><div class="table-wrap"><table><thead><tr><th>风险</th><th>等级</th><th>状态</th><th>负责人</th><th class="hide-mobile">目标日期</th></tr></thead><tbody>${riskRows()}</tbody></table></div></section>${riskPreview(selected)}</div></div>`; }

function processSteps(r){ const current=lifecycleIndex(r); return `<div class="process-wrap"><div class="process">${['登记风险','初始评估','制定措施','执行整改','残余评估','风险关闭'].map((s,i)=>`<div class="step ${i<current?'done':i===current?'current':''}"><span class="step-dot">${i<current?'✓':i+1}</span><span>${s}</span></div>`).join('')}</div></div>`; }
function actionsTab(r){ const acts=actionsFor(r.id); return `<div class="card-head"><h3>整改措施 (${acts.length})</h3>${currentRole().canAction?`<button class="btn btn-primary btn-sm" data-open-action="${r.id}">+ 新增 Action</button>`:''}</div><div class="table-wrap"><table><thead><tr><th>措施</th><th>负责人</th><th>进度</th><th>截止日期</th><th>状态</th></tr></thead><tbody>${acts.map(a=>`<tr><td><div class="riskname">${esc(a.title)}</div><div class="meta">${a.id}</div></td><td>${a.owner}</td><td><div class="action-progress"><b>${a.progress}%</b><div class="track"><div class="fill" style="width:${a.progress}%"></div></div></div></td><td>${a.dueDate}</td><td>${statusTag(a)}${canUpdateThisAction(a)?`<button class="btn btn-subtle btn-sm" data-progress="${a.id}">更新</button>`:''}</td></tr>`).join('')||`<tr><td colspan="5"><div class="empty">还没有整改措施</div></td></tr>`}</tbody></table></div>`; }
function assessmentsTab(r){ const asms=assessmentsFor(r.id); return `<div class="card-head"><h3>风险评估 (${asms.length})</h3>${currentRole().canAssess?`<button class="btn btn-primary btn-sm" data-open-assessment="${r.id}">+ 新增 Assessment</button>`:''}</div><div class="card-body"><div class="action-card-grid">${asms.map(a=>`<article class="action-card"><div class="idrow"><span class="tag ${a.type==='Initial'?'violet':'info'}">${a.type==='Initial'?'初始风险':'残余风险'}</span>${riskTag(scoreLevel(a.score))}</div><h3>${a.type==='Initial'?'整改前风险评估':'当前残余风险'}</h3><div class="facts" style="padding:8px 0 0"><div class="fact"><small>Likelihood</small><b>${a.likelihood} / 5</b></div><div class="fact"><small>Impact</small><b>${a.impact} / 5</b></div><div class="fact"><small>Score</small><b style="font-size:22px;color:var(--blue)">${a.score}</b></div><div class="fact"><small>评估人</small><b>${a.assessor}</b></div></div><footer><span class="meta">${a.id} · ${a.date}</span></footer></article>`).join('')||`<div class="empty">还没有风险评估</div>`}</div></div>`; }
function historyTab(r){ const items=data.history.filter(h=>h.riskId===r.id); return `<div class="card-head"><h3>风险更新记录</h3><span class="tag info">审计留痕</span></div><div class="card-body mini-list">${items.map(h=>`<div class="mini-item"><b>${esc(h.text)}</b><small>${h.time} · ${h.actor}</small></div>`).join('')||`<div class="empty">暂无更新记录</div>`}</div>`; }

function riskDetailView(){ const r=riskById(state.selectedRiskId)||data.risks[0]; const tabContent=state.tab==='actions'?actionsTab(r):state.tab==='assessments'?assessmentsTab(r):historyTab(r); return `<div class="view">${pageHead(r.id,esc(r.title),`<button class="btn" data-view="risks">‹ 返回台账</button>${currentRole().canRisk?`<button class="btn btn-primary" data-edit-risk="${r.id}">编辑风险</button>`:state.role==='riskOwner'?`<button class="btn btn-primary" data-risk-status="${r.id}">更新风险状态</button>`:''}`)}
    <section class="card" style="margin-bottom:18px"><div class="card-head"><div style="display:flex;gap:8px;align-items:center">${riskTag(r.level)}<span class="tag info">${r.status}</span></div><span class="meta">创建于 ${r.createdDate}</span></div>${processSteps(r)}</section>
    <div class="detail-layout"><div class="dashboard-stack"><section class="card"><div class="card-head"><h2>风险概要</h2></div><div class="summary-list"><div class="summary-item"><label>分类</label><p>${r.category}</p></div><div class="summary-item"><label>优先级</label><p>${r.priority}</p></div><div class="summary-item"><label>目标关闭日</label><p>${r.targetClosureDate}</p></div><div class="summary-item wide"><label>业务背景</label><p>${esc(r.businessContext)}</p></div><div class="summary-item wide"><label>风险描述</label><p>${esc(r.description)}</p></div></div></section><section class="card"><div class="tabbar"><button class="tab ${state.tab==='actions'?'active':''}" data-tab="actions">整改措施</button><button class="tab ${state.tab==='assessments'?'active':''}" data-tab="assessments">风险评估</button><button class="tab ${state.tab==='history'?'active':''}" data-tab="history">更新记录</button></div>${tabContent}</section></div>
      <aside class="right-stack"><section class="card"><div class="card-head"><h3>风险负责人</h3></div><div class="card-body owner-card"><div class="avatar">${r.owner[0]}</div><div><b>${r.owner}</b><div class="meta">Risk Owner</div></div></div></section><section class="card"><div class="card-head"><h3>风险变化</h3></div><div class="card-body"><div class="progress-label"><span>降低比例</span><b>${riskReduction(r.id)}%</b></div><div class="track"><div class="fill" style="width:${riskReduction(r.id)}%"></div></div><div class="meta">基于 Initial 与最新 Residual Score 计算</div></div></section><section class="card"><div class="card-head"><h3>自动化状态</h3></div><div class="card-body mini-list"><div class="mini-item"><b>Risk Owner 通知</b><small>已在创建时发送</small></div><div class="mini-item"><b>Action 日检查</b><small>每日 09:00 执行</small></div></div></section></aside>
    </div></div>`; }

function actionsView(){ const list=visibleActions(); return `<div class="view">${pageHead('我的措施','按措施负责人跟踪进度、截止日期和逾期情况')}
    <section class="kpis">${kpi('我的措施',list.length,'◎')}${kpi('已完成',list.filter(a=>a.status==='Completed').length,'✓','green')}${kpi('已逾期',list.filter(isOverdue).length,'◷','red')}${kpi('3 天内到期',list.filter(dueSoon).length,'◌','amber')}</section>
    <div class="action-card-grid">${list.map(a=>{const r=riskById(a.riskId);return `<article class="action-card"><div class="idrow"><span class="riskid">${a.id}</span>${statusTag(a)}</div><h3>${esc(a.title)}</h3><div class="meta">${r.id} · ${esc(r.title)}</div><div class="track"><div class="fill" style="width:${a.progress}%"></div></div><div class="progress-label"><span>当前进度</span><b>${a.progress}%</b></div><footer><span class="meta">负责人 ${a.owner} · 截止 ${a.dueDate}</span>${canUpdateThisAction(a)?`<button class="btn btn-sm" data-progress="${a.id}">更新进度</button>`:''}</footer></article>`}).join('')||`<div class="card empty">当前角色没有可查看的整改措施</div>`}</div></div>`; }

function assessmentsView(){ const queue=data.risks.filter(r=>r.status!=='已关闭'&&!assessmentsFor(r.id).some(a=>a.type==='Residual')); return `<div class="view">${pageHead('风险评估','使用 Likelihood × Impact 形成可追溯的初始和残余风险评估')}
    <div class="dashboard-grid"><section class="card"><div class="card-head"><h2>待评估风险</h2><span class="tag violet">${queue.length} 项</span></div><div class="table-wrap"><table><thead><tr><th>风险</th><th>负责人</th><th>已有评估</th><th></th></tr></thead><tbody>${queue.map(r=>`<tr><td><div class="riskname">${esc(r.title)}</div><div class="meta">${r.id}</div></td><td>${r.owner}</td><td>${assessmentsFor(r.id).length}</td><td>${currentRole().canAssess?`<button class="btn btn-sm" data-open-assessment="${r.id}">开始评估</button>`:`<button class="btn btn-sm" data-full-risk="${r.id}">查看</button>`}</td></tr>`).join('')}</tbody></table></div></section>
      <section class="card"><div class="card-head"><h2>5×5 评分矩阵</h2></div><div class="card-body"><div class="matrix"><span></span>${[1,2,3,4,5].map(n=>`<span class="axis">I${n}</span>`).join('')}${[5,4,3,2,1].map(l=>`<span class="axis">L${l}</span>${[1,2,3,4,5].map(i=>{const score=l*i;return `<span class="matrix-cell ${score>=15?'m-high':score>=6?'m-mid':'m-low'}">${score}</span>`}).join('')}`).join('')}</div><p class="meta" style="text-align:center;margin-top:12px">Score = Likelihood × Impact</p></div></section></div></div>`; }

function dashboardView(){ const byOwner=['陈晨','王蕾','刘洋','周宁'].map(n=>({n,count:data.risks.filter(r=>r.owner===n).length,overdue:data.actions.filter(a=>riskById(a.riskId)?.owner===n&&isOverdue(a)).length})); return `<div class="view">${pageHead('管理驾驶舱','从风险、整改和责任人维度查看当前安全风险概况')}
    <section class="kpis">${kpi('总风险',18,'△')}${kpi('高风险',5,'◈','red')}${kpi('未关闭',12,'◌','amber')}${kpi('整改完成率','64%','✓','green','↑ 8% 较上月')}</section>
    <div class="dashboard-grid"><div class="dashboard-stack"><section class="card"><div class="card-head"><h2>Risk Overview</h2></div><div class="donut-wrap"><div class="donut"></div><div class="legend"><span><i style="background:var(--red)"></i>高风险 5</span><span><i style="background:#f5b342"></i>中风险 6</span><span><i style="background:#66b986"></i>低风险 7</span></div></div></section><section class="card"><div class="card-head"><h2>Owner Responsibility</h2></div><div class="card-body">${byOwner.map((o,i)=>`<div class="chart-row"><span>${o.n}</span><div class="bar"><span style="width:${[92,68,54,36][i]}%"></span></div><b>${[5,4,3,2][i]}</b></div>`).join('')}</div></section></div>
      <div class="dashboard-stack"><section class="card"><div class="card-head"><h2>Action Tracking</h2></div><div class="card-body mini-list"><div class="mini-item"><b>逾期措施</b><small>${data.actions.filter(isOverdue).length} 项需要立即处理</small></div><div class="mini-item"><b>7 天内到期</b><small>${data.actions.filter(a=>{const d=(new Date(a.dueDate)-new Date(TODAY))/86400000;return a.status!=='Completed'&&d>=0&&d<=7}).length} 项已进入关注窗口</small></div><div class="mini-item"><b>整改完成率</b><small>${Math.round(data.actions.filter(a=>a.status==='Completed').length/data.actions.length*100)}%</small></div></div></section><section class="card"><div class="card-head"><h2>Residual Risk</h2></div><div class="card-body"><div class="progress-label"><span>已完成残余评估</span><b>${data.risks.filter(r=>assessmentsFor(r.id).some(a=>a.type==='Residual')).length} / ${data.risks.length}</b></div><div class="track"><div class="fill" style="width:60%"></div></div><div class="mini-list" style="margin-top:14px"><div class="mini-item"><b>平均风险降低 56%</b><small>基于已完成 Initial/Residual 对比的风险</small></div></div></div></section></div></div></div>`; }

function settingsView(){ if(state.role!=='admin') return workbenchView(); return `<div class="view">${pageHead('基础配置','统一管理分类、状态、优先级及演示数据')}
    <div class="config-grid" style="margin-bottom:18px">${[['Category',['访问控制','业务连续性','漏洞管理','身份管理','安全监控']],['Status',['已登记','待评估','整改中','已关闭']],['Priority',['P1','P2','P3']]].map(([t,items])=>`<section class="card"><div class="card-head"><h2>${t}</h2><button class="btn btn-sm">+ 新增</button></div><div class="card-body config-list">${items.map(v=>`<span class="tag info">${v}</span>`).join('')}</div></section>`).join('')}</div>
    <section class="card" style="margin-bottom:18px"><div class="card-head"><h2>Dataverse 目标数据模型</h2><span class="tag done">1:N 关联</span></div><div class="card-body model-grid"><div class="model-table"><h3>Risk</h3><ul><li class="key">🔑 Risk ID</li><li>Risk Title *</li><li>Description</li><li>Category / Status / Priority</li><li>Owner</li><li>Business Context</li><li>Created / Target Closure Date</li></ul></div><div class="model-table"><h3>Risk Action</h3><ul><li class="key">🔑 Action ID</li><li>Risk (Lookup) *</li><li>Action Title *</li><li>Owner</li><li>Progress (0-100)</li><li>Status / Due Date</li></ul></div><div class="model-table"><h3>Risk Assessment</h3><ul><li class="key">🔑 Assessment ID</li><li>Risk (Lookup) *</li><li>Assessment Type *</li><li>Likelihood / Impact</li><li>Score (Calculated)</li><li>Assessment Date / Assessor</li></ul></div></div></section>
    <section class="card"><div class="card-head"><h2>演示数据</h2></div><div class="card-body"><p class="lead" style="margin:0 0 12px">本 Demo 仅在当前浏览器保存模拟数据，重置后恢复初始示例。</p><button id="resetData" class="btn btn-danger">重置 Demo 数据</button></div></section></div>`; }

function viewContent(){ if(state.view==='risks') return risksView(); if(state.view==='riskDetail') return riskDetailView(); if(state.view==='actions') return actionsView(); if(state.view==='assessments') return assessmentsView(); if(state.view==='dashboard') return dashboardView(); if(state.view==='settings') return settingsView(); return workbenchView(); }

function notificationDrawer(){ const overdue=data.actions.filter(isOverdue); return `<aside id="notifyDrawer" class="drawer"><div class="card-head"><h3>通知中心</h3><button id="closeNotify" class="close-x" aria-label="关闭">×</button></div><div class="drawer-body">${overdue.map(a=>`<div class="alert-item"><span>⚠</span><div><p>${esc(a.title)}</p><small>${a.id} 已逾期 · ${a.owner}</small></div></div>`).join('')}<div class="alert-item"><span>📩</span><div><p>Risk Owner 新建通知模板已启用</p><small>模拟 Power Automate 流程</small></div></div></div></aside>`; }

function dialogs(){ return `
  <dialog id="riskDialog"><form id="riskForm"><div class="dialog-head"><h2 id="riskDialogTitle">新建 Risk</h2><button type="button" class="close-x" data-close="riskDialog">×</button></div><div class="dialog-body"><input type="hidden" name="editId"><div class="form-grid"><div class="field full"><label>风险标题 <span class="required">*</span></label><input name="title" class="control" required placeholder="请输入简洁明确的风险标题"></div><div class="field full"><label>风险描述</label><textarea name="description" class="control" placeholder="描述风险事件、原因及可能影响"></textarea></div><div class="field"><label>分类</label><select name="category" class="control"><option>访问控制</option><option>业务连续性</option><option>漏洞管理</option><option>身份管理</option><option>安全监控</option></select></div><div class="field"><label>优先级</label><select name="priority" class="control"><option>P1</option><option selected>P2</option><option>P3</option></select></div><div class="field"><label>Risk Owner <span class="required">*</span></label><select name="owner" class="control"><option>陈晨</option><option>王蕾</option><option>刘洋</option><option>周宁</option></select></div><div class="field"><label>目标关闭日 <span class="required">*</span></label><input name="targetClosureDate" type="date" class="control" value="2026-09-30" required></div><div class="field"><label>当前状态</label><select name="status" class="control"><option>已登记</option><option>待评估</option><option>整改中</option><option>已关闭</option></select></div><div class="field"><label>风险等级</label><select name="level" class="control"><option>高</option><option selected>中</option><option>低</option></select></div><div class="field full"><label>业务背景</label><textarea name="businessContext" class="control" placeholder="说明发现来源、业务范围和管理要求"></textarea></div></div></div><div class="dialog-foot"><button type="button" class="btn" data-close="riskDialog">取消</button><button class="btn btn-primary" type="submit">保存 Risk</button></div></form></dialog>
  <dialog id="actionDialog"><form id="actionForm"><div class="dialog-head"><h2>新增 Action</h2><button type="button" class="close-x" data-close="actionDialog">×</button></div><div class="dialog-body"><input type="hidden" name="riskId"><div class="form-grid"><div class="field full"><label>所属 Risk</label><input name="riskLabel" class="control" readonly></div><div class="field full"><label>措施标题 <span class="required">*</span></label><input name="title" class="control" required></div><div class="field"><label>Action Owner <span class="required">*</span></label><select name="owner" class="control"><option>赵明</option><option>王蕾</option><option>孙宇</option></select></div><div class="field"><label>截止日期 <span class="required">*</span></label><input name="dueDate" type="date" value="2026-09-15" class="control" required></div><div class="field"><label>初始状态</label><select name="status" class="control"><option>Open</option><option>In Progress</option></select></div><div class="field"><label>完成进度</label><input name="progress" type="number" min="0" max="100" value="0" class="control"></div></div><p class="field-note" style="margin-top:14px">Risk Lookup 由当前详情页自动带入，无需用户重复选择。</p></div><div class="dialog-foot"><button type="button" class="btn" data-close="actionDialog">取消</button><button class="btn btn-primary" type="submit">保存 Action</button></div></form></dialog>
  <dialog id="assessmentDialog"><form id="assessmentForm"><div class="dialog-head"><h2>新增 Assessment</h2><button type="button" class="close-x" data-close="assessmentDialog">×</button></div><div class="dialog-body"><input type="hidden" name="riskId"><div class="form-grid"><div class="field full"><label>所属 Risk</label><input name="riskLabel" class="control" readonly></div><div class="field"><label>评估类型 <span class="required">*</span></label><select name="type" class="control"><option>Initial</option><option selected>Residual</option></select></div><div class="field"><label>评估日期</label><input name="date" type="date" value="2026-09-03" class="control"></div><div class="field"><label>Likelihood (1-5)</label><select id="likelihood" name="likelihood" class="control">${[1,2,3,4,5].map(n=>`<option ${n===3?'selected':''}>${n}</option>`).join('')}</select></div><div class="field"><label>Impact (1-5)</label><select id="impact" name="impact" class="control">${[1,2,3,4,5].map(n=>`<option ${n===4?'selected':''}>${n}</option>`).join('')}</select></div><div class="field full"><div class="score-preview"><span>Score = Likelihood × Impact</span><b id="scorePreview">12</b></div></div></div></div><div class="dialog-foot"><button type="button" class="btn" data-close="assessmentDialog">取消</button><button class="btn btn-primary" type="submit">保存评估</button></div></form></dialog>
  <dialog id="progressDialog"><form id="progressForm"><div class="dialog-head"><h2>更新 Action 进度</h2><button type="button" class="close-x" data-close="progressDialog">×</button></div><div class="dialog-body"><input type="hidden" name="actionId"><div class="field"><label>措施</label><input name="title" class="control" readonly></div><div class="form-grid" style="margin-top:15px"><div class="field"><label>进度 (0-100)</label><input name="progress" type="number" min="0" max="100" class="control"></div><div class="field"><label>状态</label><select name="status" class="control"><option>Open</option><option>In Progress</option><option>Completed</option></select></div></div></div><div class="dialog-foot"><button type="button" class="btn" data-close="progressDialog">取消</button><button class="btn btn-primary" type="submit">保存更新</button></div></form></dialog>
  <dialog id="statusDialog"><form id="statusForm"><div class="dialog-head"><h2>更新 Risk 状态</h2><button type="button" class="close-x" data-close="statusDialog">×</button></div><div class="dialog-body"><input type="hidden" name="riskId"><div class="field"><label>风险</label><input name="title" class="control" readonly></div><div class="field" style="margin-top:15px"><label>当前状态</label><select name="status" class="control"><option>已登记</option><option>待评估</option><option>整改中</option><option>已关闭</option></select><span class="field-note">风险负责人仅可更新状态，其他主数据由安全部门维护。</span></div></div><div class="dialog-foot"><button type="button" class="btn" data-close="statusDialog">取消</button><button class="btn btn-primary" type="submit">保存状态</button></div></form></dialog>`; }

function render(){ app.innerHTML=`${header()}<div class="shell">${sidebar()}<main class="content"><div class="wrap">${viewContent()}</div></main></div>${notificationDrawer()}${dialogs()}<div id="toast" class="toast" role="status" aria-live="polite"></div>`; bind(); }

function bind(){
  document.querySelectorAll('[data-view]').forEach(el=>el.addEventListener('click',()=>setView(el.dataset.view)));
  byId('roleSwitch')?.addEventListener('change',e=>{ state.role=e.target.value; state.view=state.role==='boss'?'dashboard':'workbench'; render(); toast(`已切换为${currentRole().label}视图`); });
  document.querySelectorAll('[data-open]').forEach(el=>el.addEventListener('click',()=>el.dataset.open==='riskDialog'?prepareNewRisk():openDialog(el.dataset.open)));
  document.querySelectorAll('[data-close]').forEach(el=>el.addEventListener('click',()=>closeDialog(el.dataset.close)));
  document.querySelectorAll('[data-risk]').forEach(el=>el.addEventListener('click',()=>selectRisk(el.dataset.risk)));
  document.querySelectorAll('[data-full-risk]').forEach(el=>el.addEventListener('click',()=>selectRisk(el.dataset.fullRisk,true)));
  document.querySelectorAll('[data-tab]').forEach(el=>el.addEventListener('click',()=>{state.tab=el.dataset.tab;render()}));
  byId('riskSearch')?.addEventListener('input',e=>{state.query=e.target.value; const tbody=e.target.closest('.card').querySelector('tbody'); tbody.innerHTML=riskRows(); tbody.querySelectorAll('[data-risk]').forEach(el=>el.addEventListener('click',()=>selectRisk(el.dataset.risk)));});
  byId('statusFilter')?.addEventListener('change',e=>{state.statusFilter=e.target.value;render()});
  byId('levelFilter')?.addEventListener('change',e=>{state.levelFilter=e.target.value;render()});
  byId('notifyBtn')?.addEventListener('click',()=>byId('notifyDrawer').classList.toggle('open'));
  byId('closeNotify')?.addEventListener('click',()=>byId('notifyDrawer').classList.remove('open'));
  document.querySelectorAll('[data-open-action]').forEach(el=>el.addEventListener('click',()=>prepareAction(el.dataset.openAction)));
  document.querySelectorAll('[data-open-assessment]').forEach(el=>el.addEventListener('click',()=>prepareAssessment(el.dataset.openAssessment)));
  document.querySelectorAll('[data-progress]').forEach(el=>el.addEventListener('click',()=>prepareProgress(el.dataset.progress)));
  document.querySelectorAll('[data-edit-risk]').forEach(el=>el.addEventListener('click',()=>prepareRiskEdit(el.dataset.editRisk)));
  document.querySelectorAll('[data-risk-status]').forEach(el=>el.addEventListener('click',()=>prepareRiskStatus(el.dataset.riskStatus)));
  byId('riskForm')?.addEventListener('submit',submitRisk);
  byId('actionForm')?.addEventListener('submit',submitAction);
  byId('assessmentForm')?.addEventListener('submit',submitAssessment);
  byId('progressForm')?.addEventListener('submit',submitProgress);
  byId('statusForm')?.addEventListener('submit',submitRiskStatus);
  byId('likelihood')?.addEventListener('change',updateScorePreview); byId('impact')?.addEventListener('change',updateScorePreview);
  byId('resetData')?.addEventListener('click',()=>{data=clone(seed);save();state.selectedRiskId=data.risks[0].id;render();toast('已恢复初始 Demo 数据')});
}

function prepareNewRisk(){ const form=byId('riskForm'); form.reset(); form.elements.editId.value=''; form.elements.targetClosureDate.value='2026-09-30'; form.elements.status.value='已登记'; form.elements.level.value='中'; byId('riskDialogTitle').textContent='新建 Risk'; openDialog('riskDialog'); }
function prepareRiskEdit(riskId){ const r=riskById(riskId); const form=byId('riskForm'); form.elements.editId.value=r.id; for(const key of ['title','description','category','priority','owner','targetClosureDate','status','level','businessContext']) form.elements[key].value=r[key]||''; byId('riskDialogTitle').textContent=`编辑 ${r.id}`; openDialog('riskDialog'); }
function prepareRiskStatus(riskId){ const r=riskById(riskId); const form=byId('statusForm'); form.elements.riskId.value=r.id; form.elements.title.value=`${r.id} · ${r.title}`; form.elements.status.value=r.status; openDialog('statusDialog'); }
function prepareAction(riskId){ const r=riskById(riskId); const form=byId('actionForm'); form.elements.riskId.value=r.id; form.elements.riskLabel.value=`${r.id} · ${r.title}`; openDialog('actionDialog'); }
function prepareAssessment(riskId){ const r=riskById(riskId); const form=byId('assessmentForm'); form.elements.riskId.value=r.id; form.elements.riskLabel.value=`${r.id} · ${r.title}`; updateScorePreview(); openDialog('assessmentDialog'); }
function prepareProgress(actionId){ const a=data.actions.find(x=>x.id===actionId); const form=byId('progressForm'); form.elements.actionId.value=a.id; form.elements.title.value=a.title; form.elements.progress.value=a.progress; form.elements.status.value=a.status; openDialog('progressDialog'); }
function updateScorePreview(){ const l=Number(byId('likelihood')?.value||3); const i=Number(byId('impact')?.value||4); if(byId('scorePreview')) byId('scorePreview').textContent=l*i; }

function submitRisk(event){ event.preventDefault(); const fd=new FormData(event.currentTarget); const editId=fd.get('editId'); if(editId){ const risk=riskById(editId); for(const key of ['title','description','category','priority','owner','targetClosureDate','status','level','businessContext']) risk[key]=fd.get(key); data.history.unshift({id:`H-${Date.now()}`,riskId:risk.id,time:`${TODAY} 10:00`,actor:currentRole().name,text:'更新风险主数据'}); save(); closeDialog('riskDialog'); render(); toast(`Risk ${risk.id} 已更新`); return; } const next=String(Math.max(...data.risks.map(r=>Number(r.id.split('-').pop())))+1).padStart(3,'0'); const risk={id:`RSK-2026-${next}`,title:fd.get('title'),description:fd.get('description'),category:fd.get('category'),status:fd.get('status'),priority:fd.get('priority'),level:fd.get('level'),owner:fd.get('owner'),businessContext:fd.get('businessContext'),createdDate:TODAY,targetClosureDate:fd.get('targetClosureDate')}; data.risks.unshift(risk); data.history.unshift({id:`H-${Date.now()}`,riskId:risk.id,time:`${TODAY} 10:00`,actor:currentRole().name,text:`创建风险并指定负责人${risk.owner}`}); save(); state.selectedRiskId=risk.id; state.view='riskDetail'; closeDialog('riskDialog'); render(); toast(`Risk ${risk.id} 已保存，已模拟通知 ${risk.owner}`); }
function submitAction(event){ event.preventDefault(); const fd=new FormData(event.currentTarget); const max=data.actions.length?Math.max(...data.actions.map(a=>Number(a.id.split('-').pop()))):0; const action={id:`ACT-${String(max+1).padStart(3,'0')}`,riskId:fd.get('riskId'),title:fd.get('title'),owner:fd.get('owner'),progress:Number(fd.get('progress')),status:fd.get('status'),dueDate:fd.get('dueDate')}; data.actions.push(action); data.history.unshift({id:`H-${Date.now()}`,riskId:action.riskId,time:`${TODAY} 10:05`,actor:currentRole().name,text:`新增整改措施 ${action.id}，负责人${action.owner}`}); save(); state.tab='actions'; closeDialog('actionDialog'); render(); toast(`Action ${action.id} 已自动关联当前 Risk`); }
function submitAssessment(event){ event.preventDefault(); const fd=new FormData(event.currentTarget); const l=Number(fd.get('likelihood')),i=Number(fd.get('impact')),score=l*i; const max=data.assessments.length?Math.max(...data.assessments.map(a=>Number(a.id.split('-').pop()))):0; const asm={id:`ASM-${String(max+1).padStart(3,'0')}`,riskId:fd.get('riskId'),type:fd.get('type'),likelihood:l,impact:i,score,date:fd.get('date'),assessor:currentRole().name}; data.assessments.push(asm); const risk=riskById(asm.riskId); risk.level=scoreLevel(score); if(asm.type==='Initial'&&risk.status==='已登记') risk.status='整改中'; data.history.unshift({id:`H-${Date.now()}`,riskId:asm.riskId,time:`${TODAY} 10:10`,actor:currentRole().name,text:`新增${asm.type==='Initial'?'初始':'残余'}风险评估，得分 ${score}`}); save(); state.tab='assessments'; closeDialog('assessmentDialog'); render(); toast(`Assessment ${asm.id} 已保存，Score = ${score}`); }
function submitProgress(event){ event.preventDefault(); const fd=new FormData(event.currentTarget); const a=data.actions.find(x=>x.id===fd.get('actionId')); a.progress=Math.max(0,Math.min(100,Number(fd.get('progress')))); a.status=fd.get('status'); if(a.progress===100) a.status='Completed'; if(a.status==='Completed') a.progress=100; data.history.unshift({id:`H-${Date.now()}`,riskId:a.riskId,time:`${TODAY} 10:15`,actor:currentRole().name,text:`更新 ${a.id}：${actionStatusLabel(a.status)}，进度 ${a.progress}%`}); save(); closeDialog('progressDialog'); render(); toast(`${a.id} 进度已更新`); }
function submitRiskStatus(event){ event.preventDefault(); const fd=new FormData(event.currentTarget); const risk=riskById(fd.get('riskId')); risk.status=fd.get('status'); data.history.unshift({id:`H-${Date.now()}`,riskId:risk.id,time:`${TODAY} 10:20`,actor:currentRole().name,text:`风险状态更新为${risk.status}`}); save(); closeDialog('statusDialog'); render(); toast(`${risk.id} 状态已更新`); }

function registerWebMcp(){
  const context=document.modelContext; if(!context?.registerTool) return;
  const tools=[
    { name:'select_risk', title:'选择风险', description:'在台账中选择一个风险并打开其详情。', inputSchema:{type:'object',properties:{riskId:{type:'string'}},required:['riskId'],additionalProperties:false}, annotations:{readOnlyHint:true,untrustedContentHint:false}, execute({riskId}){ if(!riskById(riskId)) throw new Error('风险编号不存在'); selectRisk(riskId,true); return {riskId,view:'riskDetail'}; } },
    { name:'create_risk_action', title:'创建整改措施', description:'为指定风险创建一条模拟整改措施。', inputSchema:{type:'object',properties:{riskId:{type:'string'},title:{type:'string'},owner:{type:'string'},dueDate:{type:'string'}},required:['riskId','title','owner','dueDate'],additionalProperties:false}, annotations:{readOnlyHint:false,untrustedContentHint:false}, execute(input){ if(!riskById(input.riskId)) throw new Error('风险编号不存在'); if(!input.title?.trim()) throw new Error('措施标题不能为空'); const id=`ACT-${String(Math.max(...data.actions.map(a=>Number(a.id.split('-').pop())))+1).padStart(3,'0')}`; data.actions.push({id,riskId:input.riskId,title:input.title.trim(),owner:input.owner,progress:0,status:'Open',dueDate:input.dueDate}); save(); state.selectedRiskId=input.riskId; state.view='riskDetail'; state.tab='actions'; render(); return {id,riskId:input.riskId,status:'Open'}; } }
  ];
  tools.forEach(tool=>{ try { Promise.resolve(context.registerTool(tool)).catch(()=>{}); } catch{} });
}

render();
registerWebMcp();
