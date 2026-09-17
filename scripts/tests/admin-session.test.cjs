'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../src/main/resources/static/admin/admin.js'), 'utf8');
const between = (start, end) => source.slice(source.indexOf(start), source.indexOf(end));
const reply = (status, data, errorMsg = '') => ({ ok: status === 200, status, json: async () => ({ code: status === 200 ? 0 : status, data, errorMsg }) });
function setup(handler) {
  const nodes = new Map(), requests = [];
  function node() { return { value: '', hidden: false, disabled: false, open: false, textContent: '', events: {},
    addEventListener(name, fn) { this.events[name] = fn; }, showModal() { this.open = true; }, close() { this.open = false; },
    replaceChildren() {}, closest() { return this; }, querySelector() { return this; } }; }
  const $ = id => { if (!nodes.has(id)) nodes.set(id, node()); return nodes.get(id); };
  const state = { tab: 'policies', page: 1, editorSequence: 0, editor: null, ai: null, session: { authenticated: false, csrfToken: 'expired', csrfHeaderName: 'X-CSRF-TOKEN' } };
  const context = vm.createContext({ state, $, Headers, FormData, URLSearchParams,
    document: { querySelectorAll: () => [] }, window: { confirm: () => true },
    names: { policies: '政策', promos: '广告' }, singular: { policies: '政策', promos: '广告' }, descriptions: {}, aiCollections: ['policies', 'promos'],
    clearAi() { state.ai = null; }, aiHasWork: () => false, el: () => node(), loadList: async () => {}, ensureCatalogs: async () => {}, renderEditor: () => {},
    message(target, text) { target.textContent = text || ''; },
    fetch: async (url, options) => { const request = { path: url.slice('/admin/api/'.length), method: options.method || 'GET', token: options.headers.get('X-CSRF-TOKEN') }; requests.push(request); return handler(request, state); }
  });
  vm.runInContext(between('  async function api(', "  $('navigation').addEventListener") + '\n' + between('  async function openEditor(', '  async function showAiReview(') + '\nglobalThis.testApi = api; globalThis.testOpen = openEditor; globalThis.testTab = selectTab;', context);
  $('login-username').value = 'admin'; $('login-password').value = 'test-password-only';
  return { state, $, requests, context };
}
let failures = 0;
async function test(name, action) { try { await action(); console.log('PASS ' + name); } catch (error) { failures++; console.error('FAIL ' + name + '\n' + error.stack); } }
(async () => {
  await test('expired login page obtains current CSRF before sending credentials', async () => {
    let authenticated = false;
    const env = setup(request => request.method === 'GET' ? reply(200, { authenticated, csrfToken: 'fresh', csrfHeaderName: 'X-CSRF-TOKEN' }) : request.token === 'fresh' ? (authenticated = true, reply(200, {})) : reply(403, {}, 'CSRF失效'));
    env.$('login-dialog').open = true;
    await env.$('login-form').events.submit({ preventDefault() {} });
    assert.deepEqual(env.requests.map(request => request.method), ['GET', 'POST', 'GET']);
    assert.equal(env.requests[1].token, 'fresh'); assert.equal(env.state.session.authenticated, true); assert.equal(env.$('login-dialog').open, false);
  });
  await test('expired save opens login and preserves draft without replaying writes', async () => {
    const env = setup(request => request.method === 'GET' ? reply(200, { authenticated: false, csrfToken: 'fresh' }) : reply(403, {}, 'CSRF失效'));
    const editor = { dirty: true, item: { title: '尚未保存的人工内容' } }; env.state.editor = editor;
    await assert.rejects(env.context.testApi('policies/id', { method: 'PUT', body: '{}' }), error => error.status === 403);
    assert.deepEqual(env.requests.map(request => request.method), ['PUT', 'GET']);
    assert.equal(env.$('login-dialog').open, true); assert.equal(env.state.editor, editor); assert.equal(editor.dirty, true);
  });
  await test('permission 403 does not treat an authenticated administrator as logged out', async () => {
    const env = setup(request => request.method === 'GET' ? reply(200, { authenticated: true, csrfToken: 'fresh' }) : reply(403, {}, '没有权限'));
    env.state.session.authenticated = true;
    await assert.rejects(env.context.testApi('policies/id', { method: 'PUT', body: '{}' }), error => error.status === 403);
    assert.equal(env.$('login-dialog').open, false); assert.equal(env.state.session.authenticated, true);
    assert.equal(env.requests.filter(request => request.method === 'PUT').length, 1);
  });
  for (const fails of [false, true]) await test('late editor ' + (fails ? 'error' : 'response') + ' cannot overwrite another module', async () => {
    let finish; const env = setup(() => new Promise(resolve => { finish = resolve; }));
    const pending = env.context.testOpen('policies', 'old-id');
    env.context.testTab('promos');
    finish(fails ? reply(500, {}, '旧请求失败') : reply(200, { id: 'old-id', title: '旧栏目内容' })); await pending;
    assert.equal(env.state.tab, 'promos'); assert.equal(env.state.editor, null); assert.equal(env.$('editor-dialog').open, false); assert.equal(env.$('page-message').textContent, '');
  });
  await test('logout invalidates a pending editor request', async () => {
    let finish; const env = setup(request => request.path !== 'session' ? new Promise(resolve => { finish = resolve; }) : reply(200, { authenticated: false, csrfToken: 'fresh' }));
    const pending = env.context.testOpen('policies', 'old-id');
    await env.$('logout').events.click(); finish(reply(200, { id: 'old-id' })); await pending;
    assert.equal(env.state.editor, null); assert.equal(env.$('editor-dialog').open, false); assert.equal(env.$('login-dialog').open, true);
  });
  await test('editor started while logout is pending cannot reopen after logout', async () => {
    let finishLogout, finishEditor;
    const env = setup(request => request.method === 'DELETE' ? new Promise(resolve => { finishLogout = resolve; }) : request.path === 'session' ? reply(200, { authenticated: false, csrfToken: 'fresh' }) : new Promise(resolve => { finishEditor = resolve; }));
    const logout = env.$('logout').events.click();
    const pending = env.context.testOpen('policies', 'old-id');
    finishLogout(reply(200, {})); await logout;
    finishEditor(reply(200, { id: 'old-id' })); await pending;
    assert.equal(env.state.editor, null); assert.equal(env.$('editor-dialog').open, false); assert.equal(env.$('login-dialog').open, true);
  });
  process.exitCode = failures ? 1 : 0;
})();
