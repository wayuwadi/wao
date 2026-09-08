import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, resolve } from 'node:path';
import { JSDOM, VirtualConsole } from 'jsdom';

const root = process.env.DEMO_ROOT ? resolve(process.env.DEMO_ROOT) : resolve(import.meta.dirname, '../public/amer_demo_5');
const mime = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8' };
const server = createServer(async (request, response) => {
  try {
    const pathname = new URL(request.url, 'http://127.0.0.1').pathname;
    const file = pathname === '/' ? 'index.html' : pathname.slice(1);
    response.writeHead(200, { 'Content-Type': mime[extname(file)] || 'application/octet-stream' });
    response.end(await readFile(resolve(root, file)));
  } catch {
    response.writeHead(404);
    response.end('Not found');
  }
});

await new Promise((resolveListen) => server.listen(0, '127.0.0.1', resolveListen));
const errors = [];
const registeredTools = [];
const consoleBridge = new VirtualConsole();
consoleBridge.on('jsdomError', (error) => errors.push(error.message));

try {
  const dom = await JSDOM.fromURL(`http://127.0.0.1:${server.address().port}/`, {
    resources: 'usable', runScripts: 'dangerously', pretendToBeVisual: true, virtualConsole: consoleBridge,
    beforeParse(window) {
      const modelContext = { registerTool(tool) { registeredTools.push(tool); } };
      Object.defineProperty(window.Document.prototype, 'modelContext', { configurable: true, get: () => modelContext });
    },
  });
  await new Promise((resolveLoad) => dom.window.addEventListener('load', resolveLoad, { once: true }));
  const document = dom.window.document;
  const assert = (value, message) => { if (!value) throw new Error(message); };
  const switchRole = (value) => {
    const select = document.querySelector('#role-switch');
    select.value = value;
    select.dispatchEvent(new dom.window.Event('change', { bubbles: true }));
  };

  assert(document.querySelector('h1')?.textContent === 'Risk Register', 'Risk Register did not render.');
  assert(document.title.includes('v5') && document.querySelector('.appname')?.textContent.includes('v5'), 'V5 release identity is missing.');
  assert(document.querySelectorAll('tbody tr[data-action="open-risk"]').length === 4, 'Expected four seeded Risks.');
  assert(!document.querySelector('.flow-strip'), 'Risk Register should not show the workflow strip.');

  switchRole('riskOwner');
  assert(document.querySelectorAll('tbody tr[data-action="open-risk"]').length === 2, 'Risk Owner scope is incorrect.');
  assert(document.querySelector('[data-drawer="risk"]')?.disabled, 'Risk Owner must not create Risks.');
  document.querySelector('tr[data-action="open-risk"]')?.click();
  assert(document.querySelector('[data-drawer="action"]')?.disabled, 'Risk Owner must not create Actions.');
  assert(!document.querySelector('[data-action="edit-action"]'), 'Risk Owner must not edit Actions.');

  switchRole('actionOwner');
  assert(document.querySelector('h1')?.textContent === 'My Actions', 'Action Owner did not land on My Actions.');
  assert(!document.querySelector('.flow-strip'), 'My Actions should not show the workflow strip.');
  assert(document.querySelectorAll('[data-action="edit-action"]').length === 0, 'Action Owner must be read-only.');
  assert(document.body.textContent.includes('赵明'), 'Action Owner scope did not include owned Actions.');
  document.querySelector('[data-action="open-risk"]')?.click();
  assert(document.querySelectorAll('.tab').length === 2, 'Action Owner should only see Actions and Activity tabs.');
  assert(document.querySelector('form[data-form="inlineEditRisk"]'), 'Risk details should be visible immediately.');
  assert(document.querySelector('form[data-form="inlineEditRisk"] input[name="title"]')?.readOnly, 'Action Owner Risk details must be read-only.');
  assert(document.querySelectorAll('.action-title').length === 2, 'Action Owner should only see owned Actions for the Risk.');

  switchRole('security');
  document.querySelector('tr[data-action="open-risk"]')?.click();
  let inlineRiskForm = document.querySelector('form[data-form="inlineEditRisk"]');
  assert(inlineRiskForm, 'Editable Risk details should render on entry.');
  const detailSections = [...inlineRiskForm.querySelectorAll('[data-risk-section]')];
  assert(detailSections.map((section) => section.dataset.riskSection).join(',') === 'basic', 'Only the compact basic information card should remain.');
  assert(detailSections[0].querySelector('input[name="title"]') && detailSections[0].querySelector('textarea[name="description"]'), 'Basic information must contain the title and description.');
  assert(detailSections[0].querySelector('select[name="source"]') && detailSections[0].querySelector('input[name="registrantEmail"][type="email"]'), 'Confirmed registration fields are missing.');
  assert(!inlineRiskForm.querySelector('[name="treatment"]') && !document.querySelector('.score-journey'), 'Assessment must not be duplicated outside its tab.');
  assert(inlineRiskForm.elements.creatorEmail.readOnly && !inlineRiskForm.elements.businessOwnerEmail.required, 'System-created and optional field rules are incorrect.');
  assert(inlineRiskForm.querySelectorAll('button[type="submit"]').length === 1, 'Basic information must use one save action.');
  const scalarFields = [...inlineRiskForm.elements].filter(field => field.name && field.type !== 'checkbox');
  assert(new Set(scalarFields.map(field => field.name)).size === scalarFields.length, 'Scalar fields must not be duplicated.');
  const originalAssessments = dom.window.eval('JSON.stringify(data.assessments)');
  inlineRiskForm.elements.title.value = '页签切换草稿';
  inlineRiskForm.elements.title.dispatchEvent(new dom.window.Event('input', { bubbles: true }));
  document.querySelector('[data-tab="assessments"]').click();
  assert(document.querySelector('input[name="title"]').value === '页签切换草稿', 'Tab change lost unsaved Risk edits.');
  document.querySelector('[data-tab="actions"]').click();
  inlineRiskForm = document.querySelector('form[data-form="inlineEditRisk"]');
  assert(!document.querySelector('[data-drawer="editRisk"]'), 'Separate Edit Risk action should be removed.');
  assert(!document.querySelector('[data-tab="overview"]'), 'Risk details should not be duplicated as a tab.');
  inlineRiskForm.elements.title.value = '第三方远程访问账号复核更新';
  inlineRiskForm.elements.impactedAsset.value = '供应商远程运维门户 v2';
  inlineRiskForm.querySelector('[name="domains"][value="云安全"]').checked = true;
  inlineRiskForm.querySelector('[name="locations"][value="日本"]').checked = true;
  inlineRiskForm.dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true }));
  let stored = JSON.parse(dom.window.localStorage.getItem('amer-risk-demo-v3'));
  assert(stored.risks.find((item) => item.id === 'RSK-2026-001').title.endsWith('更新'), 'Inline Risk title update failed.');
  assert(stored.risks.find((item) => item.id === 'RSK-2026-001').impactedAsset.endsWith('v2'), 'Inline Risk detail update failed.');
  assert(stored.risks[0].domains.includes('云安全') && stored.risks[0].locations.includes('日本'), 'Multiselect values were not saved.');
  assert(stored.risks[0].owner === '陈晨' && stored.risks[0].ownerEmail.includes('@'), 'Email editing broke owner identity.');
  assert(JSON.stringify(stored.assessments) === originalAssessments, 'Editing basic information changed assessment history.');
  const invalidForm = document.querySelector('form[data-form="inlineEditRisk"]');
  invalidForm.elements.ownerEmail.value = 'not-an-email';
  invalidForm.dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true }));
  assert(JSON.parse(dom.window.localStorage.getItem('amer-risk-demo-v3')).risks[0].ownerEmail === stored.risks[0].ownerEmail, 'Invalid email was persisted.');
  invalidForm.elements.ownerEmail.value = stored.risks[0].ownerEmail;
  invalidForm.dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true }));

  const retryForm = document.querySelector('form[data-form="inlineEditRisk"]');
  const storagePrototype = dom.window.Storage.prototype;
  const originalSetItem = storagePrototype.setItem;
  storagePrototype.setItem = () => { throw new Error('simulated quota error'); };
  retryForm.elements.title.value = '保存失败后保留的草稿';
  retryForm.dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true }));
  assert(document.body.textContent.includes('保存失败') && retryForm.elements.title.value === '保存失败后保留的草稿', 'Failed save lost the draft.');
  storagePrototype.setItem = originalSetItem;
  assert(JSON.parse(dom.window.localStorage.getItem('amer-risk-demo-v3')).risks[0].title === stored.risks[0].title, 'Failed save changed persisted Risk.');
  retryForm.elements.title.value = stored.risks[0].title;
  retryForm.dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true }));

  document.querySelector('[data-action="edit-action"]')?.click();
  assert(document.querySelector('form[data-form="editAction"]'), 'Edit Action drawer did not open.');
  document.querySelector('.drawer .close-btn')?.click();
  assert(!document.querySelector('.drawer'), 'Edit Action close button did not close the drawer.');
  document.querySelector('[data-action="edit-action"]')?.click();
  document.querySelector('.drawer-foot [data-action="close-drawer"]')?.click();
  assert(!document.querySelector('.drawer'), 'Edit Action Cancel did not close the drawer.');

  document.querySelector('[data-drawer="action"]')?.click();
  assert(document.querySelector('.drawer input[readonly]')?.value.startsWith('RSK-2026-001'), 'New Action is not bound to current Risk.');
  document.querySelector('form[data-form="action"] input[name="title"]').value = '验证自动关联措施';
  document.querySelector('form[data-form="action"] input[name="dueDate"]').value = '2026-09-20';
  document.querySelector('form[data-form="action"] textarea[name="workPerformed"]').value = '已完成验证计划。';
  document.querySelector('form[data-form="action"]').dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true }));
  stored = JSON.parse(dom.window.localStorage.getItem('amer-risk-demo-v3'));
  assert(stored.actions.at(-1).riskId === 'RSK-2026-001', 'Saved Action lookup is incorrect.');
  assert(stored.actions.at(-1).workPerformed === '已完成验证计划。', 'Work performed was not saved.');

  const beforeAssessments = stored.assessments.filter((item) => item.riskId === 'RSK-2026-001').length;
  document.querySelector('[data-drawer="assessment"]')?.click();
  const assessmentForm = document.querySelector('form[data-form="assessment"]');
  assert(assessmentForm.elements.type.value === 'Residual' && assessmentForm.elements.type.options.length === 1, 'Initial assessment must not be duplicated.');
  assessmentForm.elements.treatment.value = 'Mitigation';
  assessmentForm.elements.likelihood.value = '2';
  assessmentForm.elements.likelihood.dispatchEvent(new dom.window.Event('change', { bubbles: true }));
  assessmentForm.elements.impact.value = '4';
  assessmentForm.elements.impact.dispatchEvent(new dom.window.Event('change', { bubbles: true }));
  assert(document.querySelector('#live-score')?.textContent.startsWith('8'), 'Live score calculation failed.');
  assessmentForm.dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true }));
  stored = JSON.parse(dom.window.localStorage.getItem('amer-risk-demo-v3'));
  assert(stored.assessments.filter((item) => item.riskId === 'RSK-2026-001').length === beforeAssessments + 1, 'Assessment version was not appended.');
  assert(stored.assessments.at(-1).likelihood * stored.assessments.at(-1).impact === 8, 'Calculated score source values are incorrect.');
  assert(stored.assessments.at(-1).treatment === 'Mitigation', 'Treatment must be saved on each assessment.');
  assert(document.body.textContent.includes('v3') && document.body.textContent.includes('25'), 'Assessment history did not preserve prior scores.');

  document.querySelector('[data-drawer="closeRisk"]')?.click();
  const closeForm = document.querySelector('form[data-form="closeRisk"]');
  closeForm.elements.closeReason.value = 'Residual risk accepted after mitigation.';
  closeForm.dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true }));
  stored = JSON.parse(dom.window.localStorage.getItem('amer-risk-demo-v3'));
  assert(stored.risks.find((item) => item.id === 'RSK-2026-001').status === 'Closed', 'Risk close failed.');
  assert(document.body.textContent.includes('Residual risk accepted'), 'Close activity was not preserved.');

  document.querySelector('[data-page="reminders"]')?.click();
  assert(document.body.textContent.includes('Risk created') && document.body.textContent.includes('Action due / overdue'), 'Notification rules are incomplete.');
  document.querySelector('[data-page="model"]')?.click();
  assert(document.body.textContent.includes('Dataverse GUID') && document.body.textContent.includes('版本历史'), 'Data model is incomplete.');
  document.querySelector('[data-page="permissions"]')?.click();
  assert(document.body.textContent.includes('Role matrix') && document.body.textContent.includes('所有业务写操作'), 'Permissions design is missing.');
  const registerRisk = registeredTools.find((tool) => tool.name === 'register_risk');
  assert(registerRisk, 'WebMCP register_risk tool was not registered.');
  const result = await registerRisk.execute({ title: 'WebMCP 测试风险', description: '验证结构化风险登记能力。', owner: '陈晨', targetDate: '2026-10-01' });
  assert(result.riskNumber.startsWith('RSK-'), 'WebMCP risk registration failed.');
  stored = JSON.parse(dom.window.localStorage.getItem('amer-risk-demo-v3'));
  assert(stored.risks.some((item) => item.title === 'WebMCP 测试风险'), 'WebMCP did not use the visible application state.');
  document.querySelector('[data-page="risks"]').click();
  document.querySelector('[data-drawer="risk"]').click();
  const newRiskForm = document.querySelector('form[data-form="risk"]');
  newRiskForm.elements.title.value = '新字段登记用例';
  newRiskForm.elements.description.value = '验证最新字段与多选登记。';
  newRiskForm.elements.source.value = 'AI安全评估';
  newRiskForm.elements.impactedAsset.value = '测试资产';
  newRiskForm.elements.ownerEmail.value = 'chen.chen@example.invalid';
  newRiskForm.elements.ownerTeam.value = '其他';
  newRiskForm.querySelector('[name="domains"][value="应用安全"]').checked = true;
  newRiskForm.querySelector('[name="locations"][value="中国大陆"]').checked = true;
  newRiskForm.dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true }));
  stored = JSON.parse(dom.window.localStorage.getItem('amer-risk-demo-v3'));
  assert(stored.risks[0].title === '新字段登记用例' && stored.risks[0].domains[0] === '应用安全', 'New Risk did not use the confirmed fields.');
  assert(stored.risks[0].creatorEmail === 'lin.xiaohong@example.invalid' && stored.risks[0].businessOwnerEmail === '', 'Creator and optional business owner are incorrect.');
  assert(errors.length === 0, `Runtime errors: ${errors.join('; ')}`);

  console.log(JSON.stringify({ riskList: true, workflowStripsRemoved: true, roleScopes: true, readOnlyOwners: true, inlineRiskDetails: true, inlineRiskEdit: true, editActionClose: true, actionAutoLink: true, workPerformed: true, automaticScore: true, assessmentHistory: true, riskClosure: true, notificationRules: true, dataModel: true, permissions: true, webMcp: true, runtimeErrors: 0 }));
  dom.window.close();
} finally {
  await new Promise((resolveClose) => server.close(resolveClose));
}
