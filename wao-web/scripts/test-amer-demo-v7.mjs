import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, resolve } from 'node:path';
import { JSDOM, VirtualConsole } from 'jsdom';

const root = resolve(import.meta.dirname, '../public/amer_demo_7');
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
const consoleBridge = new VirtualConsole();
consoleBridge.on('jsdomError', (error) => errors.push(error.message));

try {
  const dom = await JSDOM.fromURL(`http://127.0.0.1:${server.address().port}/`, {
    resources: 'usable', runScripts: 'dangerously', pretendToBeVisual: true, virtualConsole: consoleBridge,
  });
  await new Promise((resolveLoad) => dom.window.addEventListener('load', resolveLoad, { once: true }));
  const document = dom.window.document;
  const assert = (value, message) => { if (!value) throw new Error(message); };

  assert(document.title.includes('v7'), 'V7 document title is missing.');
  assert(document.querySelector('.appname')?.textContent.includes('v7'), 'V7 application identity is missing.');
  assert(document.querySelector('h1')?.textContent === 'Risk Register', 'Risk Register did not render.');
  assert(document.querySelector('.risk-register-table'), 'Enhanced Risk Register table did not render.');
  assert(document.body.textContent.includes('Initial Risk Level'), 'Initial Risk Level is missing.');
  assert(document.body.textContent.includes('Current Risk Level'), 'Current Risk Level is missing.');
  assert([...document.querySelectorAll('.sidenav .nav-btn > span:last-child')].map((item) => item.textContent.trim()).join('|') === 'Risk Register|My Actions', 'V7 navigation was not simplified.');

  document.querySelector('tr[data-action="open-risk"]')?.click();
  assert(document.querySelector('[data-drawer="assessment"]'), 'New Assessment action is missing.');
  assert(document.querySelector('[data-drawer="action"]'), 'New Action action is missing.');
  assert(document.querySelector('[data-action="edit-assessment"]'), 'Assessment update action is missing.');
  assert([...document.querySelectorAll('.tabs .tab')].map((item) => item.dataset.tab).join('|') === 'assessments|actions', 'Risk detail contains unexpected tabs.');
  assert(!document.body.textContent.includes('Activity'), 'Activity should not be visible in V7 Risk details.');

  document.querySelector('[data-page="actions"]')?.click();
  assert(document.querySelector('#action-search'), 'Action search is missing.');
  assert(document.querySelector('#action-status-filter'), 'Action status filter is missing.');
  assert(errors.length === 0, `Runtime errors: ${errors.join('; ')}`);

  console.log(JSON.stringify({ version: 7, simplifiedNavigation: true, simplifiedRiskTabs: true, riskLevels: true, assessmentCreateUpdate: true, actionCreateUpdate: true, actionFilters: true, runtimeErrors: 0 }));
  dom.window.close();
} finally {
  await new Promise((resolveClose) => server.close(resolveClose));
}
