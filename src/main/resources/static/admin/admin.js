'use strict';
const policyCategories = ['科技创新', '成果转化', '知识产权', '产业扶持'];
function allowedViewCategories(catalogs, audience, resourceType) {
  return [...(catalogs[audience]?.categories || []), ...(catalogs[audience]?.legacyCategories || [])].filter(category => !category.pending && category.value === resourceType).map(category => [category.value, category.label]);
}
function listQuery(collection, { page, pageSize, keyword = '', resourceAudience = '', resourceCategory = '' }) {
  const query = new URLSearchParams({ page, pageSize });
  if (collection !== 'audit') query.set('keyword', keyword);
  if (collection === 'resources' && resourceAudience) { query.set('audience', resourceAudience); query.set('category', resourceCategory || 'all'); }
  return query.toString();
}function splitList(value) { return [...new Set(String(value || '').split(/[,，;；\n]/).map(item => item.trim()).filter(Boolean))]; }
function sectionParagraphs(value) { return String(value || '').split(/\r?\n/).map(item => item.trim()).filter(Boolean); }
function safeImageUrl(value) {
  const url = String(value || '').trim();
  if (/^\/assets\/phase1\/service-(lab|equipment|material)\.png$/.test(url)) return url;
  try { const parsed = new URL(url); return parsed.protocol === 'https:' && !parsed.username && !parsed.password ? parsed.href : ''; } catch { return ''; }
}
function attributeDefinitions(catalogs, resourceType) {
  const definitions = new Map();
  for (const catalog of Object.values(catalogs)) for (const filter of [...(catalog.filtersByCategory?.[resourceType] || []), ...(catalog.preservedFiltersByCategory?.[resourceType] || [])]) {
    const field = filter.field || filter.key;
    if (['sort', 'industry', 'cooperation', 'industries', 'cooperationModes', 'kind', 'publisherRole', 'region', 'city', 'status'].includes(field)) continue;
    const definition = definitions.get(field) || { field, label: filter.label || field, multiple: !!filter.multiple || field === 'indications', options: [] };
    for (const option of filter.options || []) {
      const value = typeof option === 'string' ? option : option.value;
      if (value && value !== 'all' && !definition.options.some(item => item.value === value)) definition.options.push({ value, label: option.label || value });
    }
    definitions.set(field, definition);
  }
  return [...definitions.values()];
}

const resourceRoles = [['investor', '投资人'], ['enterprise', '企业'], ['scientist', '科学家/科研院所'], ['manager', '服务机构']];
function resourceCategories(catalogs, audience) {
  return (catalogs[audience]?.categories || []).filter(category => category.value !== 'all' && !category.pending);
}
function editableResourceCategories(catalogs, audience, item) {
  return [...resourceCategories(catalogs,audience),...(item.id?(catalogs[audience]?.legacyCategories || []).filter(c=>c.value === item.resourceType):[])];
}
function resourceFilters(catalogs, audience, category) {
  return (catalogs[audience]?.filtersByCategory?.[category] || []).filter(filter => filter.key !== 'sort').map(filter => {
    const field = filter.field || ({industry:'industries', cooperation:'cooperationModes'}[filter.key]) || filter.key;
    return { field, label:filter.label || field, storage:['industries','cooperationModes','kind'].includes(field) ? 'root' : 'attributes', multiple:!!filter.multiple || ['industries','cooperationModes','indications'].includes(field), options:(filter.options || []).map(option => typeof option === 'string' ? {value:option,label:option} : option).filter(option => option.value && option.value !== 'all') };
  });
}
function resourceSelection(catalogs, item, preferred, allowBlank = false) {
  const views=item.views || [];
  const first=views.find(view => view.audience === preferred) || views.find(view => resourceRoles.some(([key]) => key === view.audience)) || views[0];
  if (allowBlank) return { audience:first?.audience || preferred || '', category:first?.category || item.resourceType || '' };
  const audience=first?.audience || (editableResourceCategories(catalogs, preferred, item).some(c => !item.resourceType || c.value === item.resourceType) ? preferred : resourceRoles.find(([key]) => editableResourceCategories(catalogs,key,item).some(c => !item.resourceType || c.value === item.resourceType))?.[0]) || 'investor';
  return { audience, category:first?.category || item.resourceType || resourceCategories(catalogs,audience)[0]?.value };
}
function resourcePayload(catalogs, item, type, edits) {
  const allowed=new Set(attributeDefinitions(catalogs,type).map(definition => definition.field));
  const values={...(type === item.resourceType ? item.attributes : {}), ...edits};
  return Object.fromEntries(Object.entries(values).filter(([field]) => allowed.has(field)));
}

function resourceViewSelection(views, audience, category, previousAudience, retainPrevious) {
  if (!audience || !category) return [];
  const next=views.filter(view=>view.category === category && (retainPrevious || view.audience !== previousAudience || view.audience === audience)).map(view=>({...view}));
  if(!next.some(view=>view.audience === audience))next.push({audience,category,sortOrder:0});
  return next;
}
const aiCollections = ['resources', 'policies', 'promos', 'institutions'];
function aiDraftItem(collection, draft, previous = {}) {
  if (!aiCollections.includes(collection) || !draft || typeof draft !== 'object' || Array.isArray(draft)) throw new Error('识别结果格式无效，请重试。');
  const fields = {
    resources: ['title','summary','resourceType','kind','publisherRole','issuer','region','city','amountWan','amountLabel','industries','tags','cooperationModes','status','tone','attributes','views'],
    policies: ['title','summary','category','region','date','sourceName','sourceUrl'],
    promos: ['title','eyebrow','description','tone','buttonText'],
    institutions: ['name','summary','region','industries','serviceTags']
  }[collection];
  const item = { publicationStatus:'DRAFT', sortOrder:Number.isSafeInteger(previous.sortOrder) && previous.sortOrder >= 0 ? previous.sortOrder : 0 };
  for (const field of [...fields, 'sourceKind', 'sourceNote', 'sections']) if (Object.hasOwn(draft, field) && draft[field] != null) item[field] = draft[field];
  // Associations, uploaded images and home recommendations require a staff choice.
  if (collection === 'resources' || collection === 'promos') item.imageUrl = previous.imageUrl || '';
  if (collection === 'institutions') item.images = previous.images || [];
  if (collection === 'policies') item.homeRecommended = previous.homeRecommended === true;
  if (collection === 'resources' && Array.isArray(item.views)) item.views = item.views.map(view => {
    const stored = previous.views?.find(entry => entry.audience === view.audience && entry.category === view.category);
    return { audience:view.audience, category:view.category, sortOrder:Number.isSafeInteger(stored?.sortOrder) && stored.sortOrder >= 0 ? stored.sortOrder : 0 };
  });
  if (collection === 'promos') {
    const type = ['article','resource','policy','none'].includes(draft.action?.type) ? draft.action.type : '';
    item.action = { type, targetId:type === previous.action?.type ? previous.action.targetId || '' : '' };
  }
  return item;
}
if (typeof module !== 'undefined' && module.exports) module.exports = { splitList, sectionParagraphs, safeImageUrl, attributeDefinitions, policyCategories, allowedViewCategories, listQuery, resourceCategories, resourceFilters, resourceSelection, resourcePayload, resourceViewSelection, aiDraftItem };
if (typeof document !== 'undefined') {
  const $ = id => document.getElementById(id);
  const names = { featured: '首页主推', promos: '广告管理', policies: '政策资讯', resources: '资源管理', institutions: '机构管理', stats: '数据概览', audit: '操作记录' };
  const singular = { featured: '主推', promos: '广告', policies: '资讯', resources: '资源', institutions: '机构' };
  const descriptions = { featured: '选择优质资源，管理首页推荐内容与展示顺序。', promos: '维护移动广告的文案、视觉和内容跳转。', policies: '维护资讯正文与来源，让每一次发布都有据可查。', resources: '按角色模块维护资源，选择二级分类后补充可选筛选信息。', institutions: '维护机构介绍、服务领域与图片资料。', stats: '实时读取在架资源数量，了解平台内容构成。', audit: '查看内容变更、发布与下架的操作记录。' };
  const audiences = [['pool', '骊珠要素'], ['investor', '投资人专区'], ['enterprise', '企业专区'], ['scientist', '科研专区'], ['manager', '服务机构专区']];
  const resourceTypes = [['project', '项目'], ['mah', 'MAH'], ['scene', '场景'], ['talent', '人才'], ['technology', '技术'], ['patent', '专利'], ['data', '数据'], ['service', '服务'], ['achievement', '历史成果'], ['cro', 'CRO服务'], ['cdmo', 'CDMO服务'], ['solution', '骊珠整体解决方案'], ['ip_service', '知识产权服务'], ['financing_service', '投融资服务']];
  const tones = [['medical', '医药青绿'], ['cyber', '科技蓝'], ['material', '材料暖金'], ['robot', '智能蓝'], ['energy', '能源绿'], ['network', '互联蓝'], ['lab', '实验室青'], ['build', '产业灰蓝'], ['car', '装备蓝'], ['bio', '生物绿'], ['mint', '薄荷绿'], ['blue', '明亮蓝'], ['aqua', '水青色'], ['navy', '深蓝']];
  const state = { tab: 'featured', page: 1, pageSize: 20, keyword: '', total: 0, listSequence: 0, editorSequence: 0, catalogs: {}, session: null, editor: null, ai: null, uploads: 0, resourceAudience: 'investor', resourceCategory: 'all' };
  let controlId = 0;
  function el(tag, className, text) { const node = document.createElement(tag); if (className) node.className = className; if (text != null) node.textContent = String(text); return node; }
  function button(text, className, action) { const node = el('button', className || 'secondary-button', text); node.type = 'button'; if (action) node.addEventListener('click', action); return node; }
  function message(node, text, kind = 'error') { node.textContent = text || ''; node.className = 'message ' + kind; node.hidden = !text; }
  function badge(status) { return el('span', 'status-badge ' + (status === 'PUBLISHED' ? 'status-published' : status === 'OFFLINE' ? 'status-offline' : ''), { PUBLISHED: '已发布', OFFLINE: '已下架', DRAFT: '草稿' }[status] || '草稿'); }
  function formatDate(value) { if (!value) return '—'; const date = new Date(value); return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false }); }
  async function api(path, options = {}) {
    const headers = new Headers(options.headers || {}), method = options.method || 'GET';
    if (method !== 'GET' && state.session?.csrfToken) headers.set(state.session.csrfHeaderName || 'X-CSRF-TOKEN', state.session.csrfToken);
    if (options.body && !(options.body instanceof FormData) && !(options.body instanceof URLSearchParams)) headers.set('Content-Type', 'application/json');
    let response;
    try { response = await fetch('/admin/api/' + path, { credentials: 'same-origin', cache: 'no-store', ...options, headers }); }
    catch (error) { if (error.name === 'AbortError') throw error; throw new Error('无法连接服务，请检查网络后重试。当前输入已保留。'); }
    let result; try { result = await response.json(); } catch { throw new Error('服务返回了无法识别的响应，请稍后重试。'); }
    if (!response.ok || result.code !== 0) {
      const error = new Error(result.errorMsg || `操作失败（${response.status}），请稍后重试。`); error.status = response.status;
      if (response.status === 401 && path !== 'session') { state.session = null; showLogin('登录已过期，请重新登录。当前编辑内容已保留。'); refreshSession().catch(() => {}); }
      if (response.status === 403 && path !== 'session') {
        // An expired session fails CSRF before authentication; never replay the write.
        try {
          const session = await refreshSession();
          if (!session.authenticated) { error.message = '登录已过期，请重新登录。当前编辑内容已保留。'; showLogin(error.message); }
        } catch { /* Preserve the original failure and the unsaved editor. */ }
      }
      throw error;
    }
    return result.data;
  }
  async function refreshSession() { state.session = await api('session'); $('username').textContent = state.session.username || '管理员'; $('logout').hidden = !state.session.authenticated; return state.session; }
  function showLogin(reason) { message($('login-error'), reason); if (!$('login-dialog').open) $('login-dialog').showModal(); }
  $('login-dialog').addEventListener('cancel', event => event.preventDefault());
  $('login-form').addEventListener('submit', async event => {
    event.preventDefault(); $('login-submit').disabled = true; message($('login-error'), '');
    try {
      await refreshSession();
      await api('session', { method: 'POST', body: new URLSearchParams({ username: $('login-username').value.trim(), password: $('login-password').value }) });
      await refreshSession(); $('login-password').value = ''; $('login-dialog').close(); if (!state.editor) await loadList();
    } catch (error) { message($('login-error'), error.message); } finally { $('login-submit').disabled = false; }
  });
  $('logout').addEventListener('click', async () => {
    if (!canDiscard()) return; state.editorSequence++; $('logout').disabled = true;
    try { await api('session', { method: 'DELETE' }); state.editorSequence++; clearAi(); state.editor = null; $('editor-dialog').close(); $('preview-dialog').close(); $('list-content').replaceChildren(el('div', 'empty-state', '请先登录管理工作台')); $('pagination').hidden = true; await refreshSession(); showLogin(''); }
    catch (error) { message($('page-message'), error.message); } finally { $('logout').disabled = false; }
  });
  function canDiscard() { return !(state.editor?.dirty || aiHasWork()) || window.confirm('当前内容或 AI 录入草稿尚未保存，确定放弃这些修改吗？'); }
  function selectTab(tab) {
    if (!canDiscard()) return;
    state.editorSequence++; clearAi(); state.editor = null; $('editor-dialog').close(); state.tab = tab; state.page = 1; state.keyword = ''; $('keyword').value = '';
    document.querySelectorAll('[data-tab]').forEach(node => { const selected = node.dataset.tab === tab; node.classList.toggle('active', selected); if (selected) node.setAttribute('aria-current', 'page'); else node.removeAttribute('aria-current'); });
    $('page-title').textContent = names[tab]; $('page-description').textContent = descriptions[tab]; $('new-item').hidden = !singular[tab]; $('new-item').textContent = '＋ 新建' + (singular[tab] || '');
    $('ai-import').hidden = $('ai-assistant').hidden = !aiCollections.includes(tab);
    $('search-form').hidden = tab === 'stats'; $('keyword').closest('label').hidden = tab === 'audit'; $('keyword').disabled = tab === 'audit'; $('search-form').querySelector('[type=submit]').hidden = tab === 'audit'; $('search-form').querySelector('[type=submit]').disabled = tab === 'audit';
    $('resource-navigation').hidden = tab !== 'resources';
    $('list-content').replaceChildren(el('div', 'empty-state', '正在读取内容…')); loadList();
  }
  $('navigation').addEventListener('click', event => { const item = event.target.closest('[data-tab]'); if (item) selectTab(item.dataset.tab); });
  $('search-form').addEventListener('submit', event => { event.preventDefault(); state.keyword = $('keyword').value.trim(); state.page = 1; loadList(); });
  $('refresh').addEventListener('click', () => loadList());
  $('previous-page').addEventListener('click', () => { if (state.page > 1) { state.page--; loadList(); } });
  $('next-page').addEventListener('click', () => { if (state.page * state.pageSize < state.total) { state.page++; loadList(); } });
  $('new-item').addEventListener('click', () => openEditor(state.tab));
  async function loadList() {
    const sequence = ++state.listSequence, tab = state.tab;
    message($('page-message'), ''); $('list-content').setAttribute('aria-busy', 'true'); $('refresh').disabled = true;
    try {
      if (tab === 'resources') { await ensureCatalogs(); if (sequence !== state.listSequence) return; renderResourceNavigation(); }
      if (tab === 'stats') { const data = await api('stats'); if (sequence === state.listSequence) renderStats(data); return; }
      const data = await api(tab + '?' + listQuery(tab, state));
      if (sequence !== state.listSequence) return;
      state.total = data.total; state.page = data.page; renderTable(tab, data.items || []); $('result-count').textContent = `共 ${data.total} 条`; $('pagination').hidden = data.total === 0;
      $('page-label').textContent = `第 ${data.page} / ${Math.max(1, Math.ceil(data.total / state.pageSize))} 页`; $('previous-page').disabled = data.page <= 1; $('next-page').disabled = data.page * state.pageSize >= data.total;
    } catch (error) {
      if (sequence === state.listSequence) {
        message($('page-message'), error.message + ' 可点击刷新重试。');
        const failure = el('div', 'empty-state');
        failure.append(el('strong', '', '内容加载失败'), button('刷新重试', 'secondary-button', () => loadList()));
        $('list-content').replaceChildren(failure);
        state.total = null; $('result-count').textContent = ''; $('page-label').textContent = ''; $('pagination').hidden = true;
      }
    }
    finally { if (sequence === state.listSequence) { $('list-content').setAttribute('aria-busy', 'false'); $('refresh').disabled = false; } }
  }

  function renderResourceNavigation() {
    const host=$('resource-navigation'); host.replaceChildren(); host.hidden=state.tab !== 'resources';
    const roles=el('div','resource-role-tabs'); roles.setAttribute('aria-label','资源角色模块');
    const choose=(audience,category) => {state.resourceAudience=audience; state.resourceCategory=category; state.page=1; loadList();};
    for(const [key,label] of resourceRoles) {
      const tab=button(label,'role-button',()=>choose(key, state.catalogs[key].categories.some(c=>c.value === 'all') ? 'all' : resourceCategories(state.catalogs,key)[0].value));
      tab.classList.toggle('active',state.resourceAudience === key);tab.setAttribute('aria-pressed',String(state.resourceAudience === key));roles.append(tab);
    }
    const all=button('查看全部资源（含首页及项目池）','text-button',()=>choose('',''));
    all.setAttribute('aria-pressed',String(!state.resourceAudience));host.append(roles,all);
    if(!state.resourceAudience)return;
    const categories=el('div','resource-category-tabs');categories.setAttribute('aria-label','资源二级分类');
    for(const category of state.catalogs[state.resourceAudience].categories) {
      const tab=button(category.label+(category.pending?'（暂未开放）':''),'category-button',()=>choose(state.resourceAudience,category.value));
      tab.disabled=!!category.pending;tab.classList.toggle('active',state.resourceCategory === category.value);tab.setAttribute('aria-pressed',String(state.resourceCategory === category.value));categories.append(tab);
    }
    host.append(categories);
  }

  function renderTable(tab, items) {
    const host = $('list-content'); host.replaceChildren();
    if (!items.length) { const empty = el('div', 'empty-state'); empty.append(el('strong', '', state.keyword ? '没有找到匹配内容' : '这里还没有内容'), el('span', '', state.keyword ? '试试其他关键词，或清空搜索条件。' : tab === 'audit' ? '后续的内容操作会显示在这里。' : '点击右上角新建，开始维护平台内容。')); host.append(empty); return; }
    const scroller = el('div', 'table-scroll'), table = el('table'), head = el('thead'), hr = el('tr'), body = el('tbody');
    for (const heading of tab === 'audit' ? ['操作时间', '操作人', '操作', '内容', '内容版本'] : ['内容', '发布状态', '排序', '操作']) { const th = el('th', '', heading); th.scope = 'col'; hr.append(th); } head.append(hr); table.append(head, body);
    for (const item of items) {
      const row = el('tr');
      if (tab === 'audit') {
        const labels = { CREATE: '新建', UPDATE: '修改', PUBLISH: '发布', OFFLINE: '下架', PUBLISHED: '发布', UNPUBLISH: '下架', SAVE: '保存' };
        [formatDate(item.createdAt), item.actor || item.username || '—', labels[item.operation] || item.operation, `${names[item.entityType] || item.entityType || ''} · ${item.entityId || ''}`, `${item.beforeVersion ?? '—'} → ${item.afterVersion ?? '—'}`].forEach(value => row.append(el('td', '', value)));
      } else {
        const title = el('td', 'title-cell'); title.append(el('div', 'item-title', item.title || item.name || item.resourceTitle || '主推资源'), el('div', 'item-meta', [tab === 'resources' ? resourceTypes.find(pair => pair[0] === item.resourceType)?.[1] : '', item.region || item.city, item.sourceName, item.id].filter(Boolean).join(' · ')));
        const status = el('td'); status.append(badge(item.publicationStatus)); const actions = el('td'), actionBar = el('div', 'row-actions');
        actionBar.append(button('编辑', 'text-button', () => openEditor(tab, item.id)), button('预览', 'text-button', () => previewSaved(tab, item.id)));
        const publish = button(item.publicationStatus === 'PUBLISHED' ? '下架' : '发布', item.publicationStatus === 'PUBLISHED' ? 'danger-button' : 'secondary-button', () => changePublication(tab, item, publish)); actionBar.append(publish); actions.append(actionBar);
        row.append(title, status, el('td', '', item.viewSortOrder ?? item.sortOrder ?? 0), actions);
      }
      body.append(row);
    }
    scroller.append(table); host.append(scroller);
  }
  async function changePublication(tab, item, trigger) {
    const status = item.publicationStatus === 'PUBLISHED' ? 'OFFLINE' : 'PUBLISHED';
    trigger.disabled = true;
    try { await api(`${tab}/${encodeURIComponent(item.id)}/publication`, { method: 'PUT', body: JSON.stringify({ status, version: item.version }) }); await loadList(); message($('page-message'), status === 'PUBLISHED' ? '内容已发布。' : '内容已下架。', 'success'); }
    catch (error) { message($('page-message'), error.status === 409 ? '这条内容已被其他操作更新。请刷新列表后重新操作。' : error.message); } finally { trigger.disabled = false; }
  }
  function renderStats(data) {
    $('pagination').hidden = true; $('result-count').textContent = ''; const grid = el('div', 'stats-grid');
    for (const [key, label] of [['technicalDemands', '技术需求'], ['achievements', '科技成果'], ['experts', '行业专家'], ['technologyManagers', '技术经理人'], ['universities', '高校院所'], ['patentResources', '技术专利']]) {
      const card = el('div', 'stat-card'); card.append(el('p', 'stat-label', label), el('strong', '', Number.isFinite(Number(data[key])) && data[key] != null ? Number(data[key]).toLocaleString('zh-CN') : '—'), el('small', '', '条')); grid.append(card);
    }
    const notes = el('div', 'stats-explanation'); notes.append(el('h3', '', '统计口径'), el('p', '', '按已发布且业务状态为“开放”的资源主记录去重统计。同一资源出现在多个专区或推荐位，只计一次。')); const list = el('ul');
    ['技术需求：有效的需求资源。科技成果：项目、技术类供给或“技术成果”类型的成果。', '行业专家：人才成熟度为“资深专家”的人才资源，不代表已认证人数。', '技术经理人、高校院所：当前缺少对应档案来源，返回 0，不使用其他资源代替。', '技术专利：专利资源（排除软著）与成果中的专利包、纯专利权利。一份资源计 1 条。', '迁入内容按实际发布状态参与统计；条目计数不表示资质或真实性认证。'].forEach(text => list.append(el('li', '', text)));
    notes.append(list, button('刷新统计', 'secondary-button', () => loadList())); $('list-content').replaceChildren(grid, notes);
  }
  function field(parent, label, name, current = '', options = {}) {
    const wrapper = el('label', 'field' + (options.full ? ' full' : '')), input = el(options.type === 'textarea' ? 'textarea' : options.choices ? 'select' : 'input');
    input.name = name; input.id = 'field-' + (++controlId); wrapper.htmlFor = input.id;
    if (options.choices) {
      if (options.multiple) input.multiple = true;
      const choices = state.editor?.aiDraft && !options.multiple && !options.choices.some(choice => (Array.isArray(choice) ? choice[0] : choice.value) === '') ? [['', '请人工选择（未识别）'], ...options.choices] : options.choices;
      for (const choice of choices) { const pair = Array.isArray(choice) ? choice : [choice.value, choice.label || choice.value], option = el('option', '', pair[1]); option.value = pair[0]; option.selected = Array.isArray(current) ? current.includes(pair[0]) : String(current ?? '') === String(pair[0]); input.append(option); }
    } else { if (options.type !== 'textarea') input.type = options.type || 'text'; input.value = Array.isArray(current) ? current.join('，') : current ?? ''; }
    if (options.type === 'textarea') input.rows = options.rows || 3;
    for (const prop of ['required', 'maxLength', 'min', 'max', 'step', 'placeholder']) if (options[prop] !== undefined) input[prop] = options[prop];
    wrapper.append(el('span', '', label + (options.required ? ' *' : '')), input); if (options.hint) wrapper.append(el('small', '', options.hint)); parent.append(wrapper); return input;
  }
  function group(parent, title) { const node = el('fieldset', 'form-group'), grid = el('div', 'form-grid'); node.append(el('legend', '', title), grid); parent.append(node); return grid; }
  function check(parent, label, name, current) { const wrapper = el('label', 'check-field'), input = el('input'); input.type = 'checkbox'; input.name = name; input.checked = !!current; wrapper.append(input, el('span', '', label)); parent.append(wrapper); return input; }
  function value(name) { return $('editor-form').elements.namedItem(name)?.value?.trim() || ''; }
  function numberValue(name, empty = 0) { const text = value(name); return text === '' ? empty : Number(text); }
  function editorError(error) { message($('editor-error'), error.status === 409 ? '内容已被其他操作更新。你的输入已保留；请先复制需要保留的修改，再重新载入服务器版本。' : error.message); $('reload-editor').hidden = error.status !== 409; }
  async function ensureCatalogs() { await Promise.all(audiences.map(async ([audience]) => { if (!state.catalogs[audience]) state.catalogs[audience] = await api('catalogs/' + audience); })); }
  function aiHasWork() { return !!(state.ai && (state.ai.text.trim() || state.ai.file || state.ai.result)); }
  function cancelAiRequest(note = '') {
    const assistant = state.ai; if (!assistant) return;
    assistant.sequence++; assistant.controller?.abort(); assistant.controller = null; assistant.busy = false;
    renderAiPanel(); if (note) message($('ai-error'), note, 'notice');
  }
  function clearAi() { cancelAiRequest(); state.ai = null; $('ai-dialog').close(); $('ai-text').value = ''; $('ai-file').value = ''; }
  function captureAiReview(updateContext = true) {
    if (!state.ai || !state.editor?.aiDraft || state.editor.item.id) return;
    state.ai.previousDraft = readEditor(false);
    if (updateContext && state.editor.collection === 'resources') state.ai.context = { audience:value('resourceAudience'), resourceType:value('resourceType') };
  }
  function aiFieldLabel(name) {
    const key = String(name).replace(/^attributes\./, '');
    const labels = { title:'标题',name:'机构名称',summary:'摘要',description:'描述',resourceType:'二级分类',audience:'一级模块',views:'展示专区',kind:'供需类型',publisherRole:'发布方身份',issuer:'发布方名称',region:'地区',city:'城市',amountWan:'金额',amountLabel:'金额文案',industries:'行业',tags:'标签',cooperationModes:'合作标签',status:'业务状态',tone:'色彩模板',sourceKind:'来源类型',sourceName:'来源名称',sourceUrl:'原文链接',category:'资讯分类',date:'展示日期',buttonText:'按钮文案',eyebrow:'广告角标',action:'点击动作','action.type':'点击动作','action.targetId':'关联内容',sections:'分节正文',serviceTags:'服务标签',imageUrl:'图片',images:'机构图片' };
    if (labels[key]) return labels[key];
    for (const catalog of Object.values(state.catalogs)) for (const filters of Object.values(catalog.filtersByCategory || {})) { const field=filters.find(filter=>(filter.field || filter.key) === key); if(field)return field.label; }
    return '待补充信息';
  }
  function renderAiContext() {
    const assistant=state.ai;if(!assistant || assistant.collection !== 'resources')return;
    const fill=(select,choices,current)=>{select.replaceChildren();for(const [value,label]of [['','由材料识别，无法确认则留空'],...choices]){const option=el('option','',label);option.value=value;option.selected=value===current;select.append(option);}};
    fill($('ai-audience'),resourceRoles,assistant.context.audience);
    const categories=assistant.context.audience ? resourceCategories(state.catalogs,assistant.context.audience) : [...new Map(resourceRoles.flatMap(([role])=>resourceCategories(state.catalogs,role)).map(category=>[category.value,category])).values()];
    if(!categories.some(category=>category.value===assistant.context.resourceType))assistant.context.resourceType='';
    fill($('ai-category'),categories.map(category=>[category.value,category.label]),assistant.context.resourceType);
  }
  function renderAiPanel() {
    const assistant=state.ai;if(!assistant)return;
    const fileMode=assistant.mode==='file',status=assistant.status;
    $('ai-title').textContent=(fileMode?'导入文件':'AI录入助手')+' · '+singular[assistant.collection];
    $('ai-file-mode').setAttribute('aria-pressed',String(fileMode));$('ai-text-mode').setAttribute('aria-pressed',String(!fileMode));
    $('ai-file-row').hidden=!fileMode;$('ai-context').hidden=assistant.collection!=='resources';$('ai-text').value=assistant.text;
    $('ai-input-label').textContent=fileMode?'补充说明（选填）':'描述材料或补充要求';
    $('ai-text').maxLength=status?.maxTextChars || 60000;
    const formats=(status?.supportedFormats || []).map(format=>String(format).replace(/^\./,'').toLowerCase());
    $('ai-file').accept=formats.map(format=>'.'+format).join(',');
    $('ai-file-label').textContent=(assistant.file?'已选择：'+assistant.file.name+'。':'')+'单个文件不超过 '+Math.floor((status?.maxFileBytes || 5242880)/1024/1024)+' MB'+(formats.length?'；支持 '+formats.map(format=>format.toUpperCase()).join(' / '):'')+'。';
    $('ai-status').textContent=!status?'正在检查服务配置…':!status.configured?'服务器尚未配置DEEPSEEK_API_KEY':assistant.busy?'正在提取材料，最长等待约 90 秒…':'服务已就绪；未识别的信息会留空。';
    $('ai-submit').disabled=assistant.busy || !status?.configured;$('ai-submit').textContent=fileMode?'识别并打开审核表单':'生成待审核表单';
    $('ai-cancel').hidden=!assistant.busy;$('ai-resume').hidden=!state.editor?.aiDraft || !!state.editor.item.id;
    const history=$('ai-history');history.replaceChildren();
    for(const entry of assistant.messages)history.append(el('p','ai-message '+(entry.role==='user'?'ai-user':''),(entry.role==='user'?'你：':'助手：')+entry.text));
  }
  function renderAiExamples() {
    const templates={
      resources:'资源标题：\n资源类型：\n供给或需求：\n发布方身份与名称：\n所在地区：\n金额（如原文明确）：\n技术 / 产品 / 服务内容：\n已知资质、阶段、合作方式：',
      policies:'资讯标题：\n来源类型（原创 / 转载 / 官方）：\n来源名称：\n原文链接：\n原文日期：\n所属地区：\n政策原文或要点：',
      promos:'广告标题：\n广告角标：\n描述：\n按钮文案：\n点击动作（专题 / 资源 / 资讯 / 不跳转）：\n专题正文：',
      institutions:'机构名称：\n所在地区：\n行业：\n机构简介：\n服务内容与资质：\n需要补充的正文：'
    };
    const host=$('ai-examples');host.replaceChildren();
    const insert=text=>{cancelAiRequest();const assistant=state.ai;assistant.text=[$('ai-text').value.trim(),text].filter(Boolean).join('\n\n');$('ai-text').value=assistant.text;$('ai-text').focus();};
    host.append(button('填入结构化模板','text-button',()=>insert(templates[state.ai.collection])),button('填入提问示例','text-button',()=>insert('请从以下真实材料提取表单字段，保留原意；金额、日期、来源和分类没有依据就留空。\n材料：［请在这里粘贴真实材料］')),el('span','field-hint','只填写已知信息；模板和示例不会自动发送。'));
  }
  async function openAi(mode) {
    if(!aiCollections.includes(state.tab))return;
    if(state.editor && !state.editor.aiDraft && !canDiscard())return;
    if(state.editor?.aiDraft && !state.editor.item.id){if($('editor-dialog').open)captureAiReview();}else state.editor=null;
    state.editorSequence++;
    $('editor-dialog').close();
    if(!state.ai)state.ai={collection:state.tab,mode,text:'',file:null,context:{audience:'',resourceType:''},previousDraft:{},messages:[],result:null,status:null,busy:false,sequence:0};
    const assistant=state.ai;cancelAiRequest();assistant.mode=mode;message($('ai-error'),'');renderAiPanel();renderAiExamples();renderAiContext();
    if(!$('ai-dialog').open)$('ai-dialog').showModal();
    try {
      const [status]=await Promise.all([assistant.status?Promise.resolve(assistant.status):api('ai/status'),assistant.collection==='resources'?ensureCatalogs():Promise.resolve()]);
      if(state.ai!==assistant)return;assistant.status=status;renderAiContext();renderAiPanel();
    } catch(error){if(state.ai===assistant){message($('ai-error'),error.message);$('ai-status').textContent='服务状态读取失败。输入已保留，可关闭后重新打开重试。';}}
  }
  async function submitAi(event) {
    event.preventDefault();const assistant=state.ai;if(!assistant || assistant.busy)return;
    assistant.text=$('ai-text').value;const text=assistant.text.trim(),file=assistant.mode==='file'?assistant.file:null;
    try {
      if(state.uploads)throw new Error('图片仍在上传，请等待上传完成后再补充识别。');
      if(!assistant.status?.configured)throw new Error('服务器尚未配置DEEPSEEK_API_KEY');
      if(assistant.mode==='file'&&!file)throw new Error('请先选择一个材料文件。');
      if(assistant.mode==='text'&&!text)throw new Error('请填写真实材料或需要补充的信息。');
      if(text.length>assistant.status.maxTextChars)throw new Error('输入文字超过限制，请缩短后重试。');
      if(file){const extension=file.name.split('.').pop().toLowerCase();if(file.size>assistant.status.maxFileBytes)throw new Error('文件超过大小限制，请选择 5 MB 以内的文件。');if(!assistant.status.supportedFormats.some(format=>String(format).replace(/^\./,'').toLowerCase()===extension))throw new Error('暂不支持此文件格式，请按页面提示选择。');}
      if(state.editor?.aiDraft && state.editor.dirty && !window.confirm('新的识别结果将替换当前未保存表单。当前已填写的信息会一并提交，确定继续吗？'))return;
      captureAiReview(false);
    } catch(error){message($('ai-error'),error.message);return;}
    const request={collection:assistant.collection,text,context:{...assistant.context},previousDraft:assistant.previousDraft};
    const body=file?new FormData():JSON.stringify(request);if(file){body.append('file',file);body.append('request',JSON.stringify(request));}
    const sequence=++assistant.sequence;assistant.controller=new AbortController();assistant.busy=true;
    assistant.messages.push({role:'user',text:(file?'文件：'+file.name+(text?'；':''):'')+text.slice(0,180)});assistant.messages=assistant.messages.slice(-6);
    message($('ai-error'),'');renderAiPanel();
    const timer=setTimeout(()=>{if(state.ai===assistant && assistant.sequence===sequence)cancelAiRequest('识别等待超时，材料和输入已保留。请稍后手动重试。');},90000);
    try {
      const result=await api('ai/draft',{method:'POST',body,signal:assistant.controller.signal});
      if(state.ai!==assistant || assistant.sequence!==sequence)return;
      if(await showAiReview(result,sequence)){
        assistant.messages.push({role:'assistant',text:String(result.message || '已生成待审核表单，请核对后手动保存。').slice(0,220)});assistant.messages=assistant.messages.slice(-6);assistant.text='';
      }
    } catch(error){if(state.ai===assistant && assistant.sequence===sequence)message($('ai-error'),error.name==='AbortError'?'识别已取消，输入已保留。':error.message);}
    finally{clearTimeout(timer);if(state.ai===assistant && assistant.sequence===sequence){assistant.busy=false;assistant.controller=null;renderAiPanel();}}
  }
  async function openEditor(collection, id) {
    if (!canDiscard()) return; const sequence = ++state.editorSequence; message($('page-message'), '');
    try {
      const [item] = await Promise.all([id ? api(`${collection}/${encodeURIComponent(id)}`) : Promise.resolve({ publicationStatus: 'DRAFT', sortOrder: 0, ...(collection === 'resources' && state.tab === 'resources' && state.resourceAudience && state.resourceCategory !== 'all' ? {resourceType:state.resourceCategory} : {}) }), collection === 'resources' ? ensureCatalogs() : Promise.resolve()]);
      if (sequence !== state.editorSequence) return;
      clearAi(); state.editor = { collection, item, dirty: false, busy: false }; renderEditor(); state.editor.dirty = false;
      if (!$('editor-dialog').open) $('editor-dialog').showModal();
    } catch (error) { if (sequence === state.editorSequence) message($('page-message'), error.message); }
  }
  async function showAiReview(result, sequence = state.ai?.sequence) {
    const assistant = state.ai;
    if (!assistant || result.collection !== assistant.collection) throw new Error('识别结果与当前内容类型不一致，请重试。');
    if (result.collection === 'resources') await ensureCatalogs();
    if (state.ai !== assistant || assistant.sequence !== sequence) return false;
    const item = aiDraftItem(result.collection, result.draft, assistant.previousDraft);
    state.editorSequence++;
    state.editor = { collection:result.collection, item, aiDraft:true, aiResult:result, dirty:true, busy:false };
    assistant.result = result; assistant.previousDraft = item;
    renderEditor(); state.editor.dirty = true;
    $('ai-dialog').close();
    if (!$('editor-dialog').open) $('editor-dialog').showModal();
    return true;
  }
  function renderEditor() {
    const { collection, item } = state.editor;
    $('editor-title').textContent = (item.id ? '编辑' : '新建') + singular[collection]; $('editor-fields').replaceChildren(); message($('editor-error'), ''); $('reload-editor').hidden = true;
    message($('publication-notice'), item.publicationStatus === 'PUBLISHED' ? '这条内容已发布。保存修改将立即更新线上内容；如需暂停展示，请先在列表中下架。' : '', 'notice');
    $('editor-status').textContent = item.id ? `${{ DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '已下架' }[item.publicationStatus] || '草稿'} · 版本 ${item.version ?? 0}` : '新内容保存为草稿';
    const host = $('editor-fields');
    if (item.sourceNote && !state.editor.aiDraft) host.append(el('p', 'message notice', '来源说明：' + item.sourceNote));
    if (collection === 'featured') {
      const grid = group(host, '关联资源'), picker = relatedPicker(grid, '推荐资源', 'resourceId', 'resources', item.resourceId); picker.classList.add('full');
      host.append(button('编辑所选资源的标题、金额与正文', 'text-button', () => { const id = value('resourceId'); if (id) openEditor('resources', id); else editorError(new Error('请先选择一条资源。')); }), el('p', 'field-hint', '主推引用资源内容。关联资源须已发布且处于开放状态，发布主推后才会展示。'));
    } else if (collection === 'resources') renderResource(host, item);
    else if (collection === 'promos') renderPromo(host, item);
    else if (collection === 'policies') renderPolicy(host, item);
    else renderInstitution(host, item);
    if (state.editor.aiDraft) {
      const source = group(host, '资料来源');
      if (collection !== 'policies') field(source, '来源类型', 'sourceKind', item.sourceKind, { choices:[['original','原创资料'],['reprint','转载资料'],['official','官方来源'],['legacy_sample','迁入示例资料']], required:true });
      field(source, '来源备注', 'sourceNote', item.sourceNote, {type:'textarea',maxLength:1000,full:true});
    }
    const order = group(host, '展示设置'); field(order, '排序值', 'sortOrder', item.sortOrder ?? 0, { type: 'number', min: 0, step: 1, required: true, hint: '数值越小越靠前；相同数值按固定顺序排列。' });
    if (collection !== 'featured') renderSections(host, item.sections || []);
    $('return-ai').hidden = !state.editor.aiDraft || !!item.id;
    const review = state.editor.aiResult;
    const missing=[...new Set((review?.missingFields || []).map(aiFieldLabel))];
    message($('ai-review-notice'), state.editor.aiDraft ? ['待人工复核：识别结果尚未保存。请核对所有字段；空白表示材料未能确认，需要时请人工补齐。',missing.length?'尚未确认：'+missing.join('、')+'。':'', ...(review?.warnings || [])].filter(Boolean).join('\n') : '', 'notice');
  }
  function renderResource(host, item) {
    const classification = el('div'); host.append(classification);
    const grid = group(host, '资源信息');
    field(grid, '资源标题', 'title', item.title, { required: true, maxLength: 120, full: true }); field(grid, '摘要', 'summary', item.summary, { type: 'textarea', maxLength: 500, full: true });
    field(grid, '供需类型', 'kind', item.kind || (state.editor.aiDraft ? '' : 'supply'), { choices: [['supply', '供给'], ['demand', '需求']], required:true });
    field(grid, '发布方身份', 'publisherRole', item.publisherRole || (state.editor.aiDraft ? '' : 'platform'), { choices: [['platform', '平台'], ['enterprise', '企业'], ['investor', '投资人']], required:true }); field(grid, '发布方名称', 'issuer', item.issuer, { maxLength: 120 });
    field(grid, '地区', 'region', item.region, { maxLength: 50 }); field(grid, '城市', 'city', item.city, { maxLength: 50 });
    field(grid, '金额（万元）', 'amountWan', item.amountWan, { type: 'number', min: 0, step: '0.01', hint: '留空表示面议。' }); field(grid, '金额展示文案', 'amountLabel', item.amountLabel, { maxLength: 30, hint: '例如“合作面议”；不填写则使用金额。' });
    field(grid, '行业', 'industries', item.industries, { hint: '多个值用逗号分隔，每项最多 30 字，最多 12 项。' }); field(grid, '标签', 'tags', item.tags, { hint: '多个值用逗号分隔，每项最多 30 字，最多 12 项。' });
    field(grid, '合作标签', 'cooperationModes', item.cooperationModes, { hint: '多个值用逗号分隔；分类筛选请填写上方对应选项。' }); field(grid, '业务状态', 'status', item.status || (state.editor.aiDraft ? '' : 'open'), { choices: [['open', '开放'], ['closed', '已结束'], ['withdrawn', '已撤回']], required:true });
    renderImageField(grid, '封面图片', 'imageUrl', item.imageUrl); renderTone(grid, item.tone); renderViews(classification, item);
  }
  function renderTone(parent, current) { const choices = [...tones]; if (current && !choices.some(([value]) => value === current)) choices.push([current, '保留当前色彩']); field(parent, '色彩模板', 'tone', current || (state.editor.aiDraft ? '' : 'medical'), { choices, required:true }); }
  function renderPromo(host, item) {
    const grid = group(host, '广告信息');
    field(grid, '标题', 'title', item.title, { required: true, maxLength: 120, full: true }); field(grid, '广告角标', 'eyebrow', item.eyebrow, { maxLength: 20 }); field(grid, '按钮文案', 'buttonText', item.buttonText || (state.editor.aiDraft ? '' : '查看详情'), { required: true, maxLength: 20 });
    field(grid, '副标题 / 描述', 'description', item.description, { type: 'textarea', maxLength: 500, full: true }); renderImageField(grid, '广告图片', 'imageUrl', item.imageUrl); renderTone(grid, item.tone);
    const action = group(host, '点击动作'), type = field(action, '点击后打开', 'actionType', item.action?.type || (state.editor.aiDraft ? '' : 'article'), { choices: [['article', '当前广告专题'], ['resource', '已存在的资源'], ['policy', '已存在的政策资讯'], ['none', '不跳转']], required:true }), target = el('div', 'full'); action.append(target);
    const updateTarget = () => { target.replaceChildren(); if (['resource', 'policy'].includes(type.value)) relatedPicker(target, '选择跳转内容', 'targetId', type.value === 'resource' ? 'resources' : 'policies', type.value === item.action?.type ? item.action.targetId : ''); };
    type.addEventListener('change', updateTarget); updateTarget();
  }
  function renderPolicy(host, item) {
    const grid = group(host, '资讯内容');
    field(grid, '标题', 'title', item.title, { required: true, maxLength: 120, full: true }); field(grid, '摘要', 'summary', item.summary, { type: 'textarea', maxLength: 500, full: true });
    field(grid, '分类', 'category', item.category || (state.editor.aiDraft ? '' : policyCategories[0]), { required: true, choices: policyCategories.map(category => [category, category]) }); field(grid, '地区', 'region', item.region, { maxLength: 50 }); field(grid, '展示日期', 'date', item.date?.slice(0, 10), { type: 'date', required: true });
    const sourceKind = field(grid, '来源类型', 'sourceKind', item.sourceKind || (state.editor.aiDraft ? '' : 'original'), { choices: [['original', '原创文章'], ['reprint', '转载文章'], ['official', '官方来源'], ['legacy_sample', '迁入示例资料']], required:true }); field(grid, '来源名称', 'sourceName', item.sourceName, { maxLength: 120, required: true });
    const sourceUrl = field(grid, '原文链接（HTTPS）', 'sourceUrl', item.sourceUrl, { type: 'url', maxLength: 2048, hint: '官方来源及转载文章请填写可核对的原文链接。' }); check(host, '推荐到首页政策资讯', 'homeRecommended', item.homeRecommended);
    const updateSourceRequired = () => { sourceUrl.required = ['official', 'reprint'].includes(sourceKind.value); };
    sourceKind.addEventListener('change', updateSourceRequired); updateSourceRequired();
  }
  function renderInstitution(host, item) {
    const grid = group(host, '机构资料'); field(grid, '机构名称', 'name', item.name, { required: true, maxLength: 120, full: true }); field(grid, '机构简介', 'summary', item.summary, { type: 'textarea', maxLength: 500, full: true });
    field(grid, '地区', 'region', item.region, { maxLength: 50 }); field(grid, '行业', 'industries', item.industries, { hint: '多个值用逗号分隔。' }); field(grid, '服务标签', 'serviceTags', item.serviceTags, { hint: '多个值用逗号分隔。', full: true });
    const gallery = el('section'), rows = el('div'), header = el('div', 'subheading'); header.append(el('h3', '', '机构图片'), button('＋ 添加图片', 'secondary-button', () => addImage(''))); gallery.append(header, rows); host.append(gallery);
    function addImage(url) { const row = el('div', 'repeat-row'); renderImageField(row, '图片地址', 'institutionImage', typeof url === 'string' ? url : url.url); row.append(button('移除图片', 'text-button', () => { row.remove(); state.editor.dirty = true; })); rows.append(row); state.editor.dirty = true; }
    (item.images || []).forEach(addImage);
  }
  function renderImageField(parent, label, name, url) {
    const input = field(parent, label, name, url, { maxLength: 2048, placeholder: '上传图片后自动填写，或填写 HTTPS 地址', hint: '支持 JPEG / PNG，最大 5 MB。图片缺失时使用色彩模板。', full: true }), line = el('div', 'upload-line'), file = el('input'), uploadMessage = el('p', 'upload-message');
    file.type = 'file'; file.accept = '.png,.jpg,.jpeg'; file.setAttribute('aria-label', '上传' + label); uploadMessage.setAttribute('role', 'status');
    file.addEventListener('change', async () => {
      const selected = file.files[0]; if (!selected) return;
      if (!['image/jpeg', 'image/png'].includes(selected.type) || selected.size > 5 * 1024 * 1024) { uploadMessage.textContent = '请选择 5 MB 以内的 JPEG 或 PNG 图片。'; file.value = ''; return; }
      file.disabled = true; state.uploads++; uploadMessage.textContent = '正在上传图片…';
      try { const body = new FormData(); body.append('file', selected); const data = await api('media', { method: 'POST', body }); if (!safeImageUrl(data.url)) throw new Error('上传返回的图片地址无效，请联系管理员。'); input.value = data.url; state.editor && (state.editor.dirty = true); uploadMessage.textContent = '图片已上传。请保存内容以完成引用。'; }
      catch (error) { uploadMessage.textContent = error.status === 503 ? '图片存储尚未配置或暂不可用。请联系管理员检查存储配置，原图片地址已保留。' : error.message; }
      finally { state.uploads--; file.disabled = false; file.value = ''; }
    });
    line.append(file); input.parentElement.append(line, uploadMessage); return input;
  }
  function relatedPicker(parent, label, name, collection, currentId) {
    const wrapper = el('fieldset', 'picker'); wrapper.append(el('legend', '', label));
    const search = el('div', 'picker-search'), query = el('input'), results = el('select'), note = el('p', 'upload-message'), footer = el('div', 'picker-footer'), buttons = el('div');
    query.type = 'search'; query.placeholder = '输入标题关键词查找'; query.maxLength = 100; query.setAttribute('aria-label', '搜索' + label); results.name = name; results.required = true; results.setAttribute('aria-label', label);
    const blank = el('option', '', '请选择内容'); blank.value = ''; results.append(blank);
    let page = 1, sequence = 0, selectedItem = null, total = 0; const picked = new Map();
    if (currentId) { const option = el('option', '', '正在读取当前关联内容…'); option.value = currentId; option.selected = true; results.append(option); }
    const previous = button('上一页', 'text-button', () => { if (page > 1) { page--; searchItems(); } }), next = button('下一页', 'text-button', () => { if (page * 20 < total) { page++; searchItems(); } }), count = el('span', '', '正在读取…'); buttons.append(previous, next); footer.append(count, buttons);
    const searchButton = button('查找', 'secondary-button', () => { page = 1; searchItems(); }); search.append(query, searchButton);
    query.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); page = 1; searchItems(); } }); results.addEventListener('change', () => { selectedItem = picked.get(results.value) || null; state.editor && (state.editor.dirty = true); });
    wrapper.append(search, results, footer, note); parent.append(wrapper);
    function appendOption(item) { const option = el('option', '', `${item.title || item.name || '未命名内容'} · ${{ PUBLISHED: '已发布', OFFLINE: '已下架', DRAFT: '草稿' }[item.publicationStatus] || '草稿'}`); option.value = item.id; picked.set(item.id, item); results.append(option); }
    async function searchItems() {
      const request = ++sequence; searchButton.disabled = true; note.textContent = '';
      try {
        const data = await api(`${collection}?${new URLSearchParams({ page, pageSize: 20, keyword: query.value.trim() })}`);
        if (request !== sequence || !wrapper.isConnected) return;
        const selected = results.value; results.replaceChildren(blank); picked.clear();
        if (selectedItem && !data.items.some(item => item.id === selectedItem.id)) appendOption(selectedItem);
        data.items.forEach(appendOption);
        if (selected && !picked.has(selected)) { const option = el('option', '', '已关联的内容（使用当前选择）'); option.value = selected; results.append(option); }
        results.value = selected; total = data.total; count.textContent = `共 ${total} 条 · 第 ${page} 页`; previous.disabled = page <= 1; next.disabled = page * 20 >= total;
      } catch (error) { if (request === sequence) note.textContent = error.message; } finally { if (request === sequence) searchButton.disabled = false; }
    }
    if (currentId) api(`${collection}/${encodeURIComponent(currentId)}`).then(item => { if (!wrapper.isConnected || results.value !== currentId) return; selectedItem = item; const option = [...results.options].find(option => option.value === currentId); if (option) option.textContent = `${item.title || item.name || item.id} · 当前关联`; }).catch(error => { note.textContent = '当前关联内容读取失败：' + error.message; });
    searchItems(); return wrapper;
  }
  function renderSections(host, sections) {
    const section = el('section'), header = el('div', 'subheading'), rows = el('div'); rows.id = 'section-rows';
    header.append(el('h3', '', '分节正文'), button('＋ 添加章节', 'secondary-button', () => { if (rows.children.length >= 20) { editorError(new Error('正文最多 20 节。')); return; } addSection({}); state.editor.dirty = true; }));
    section.append(header, el('p', 'field-hint', '每行作为一个段落；每节最多 10 段，每段最多 2000 字。'), rows); host.append(section);
    function addSection(data) { const row = el('div', 'repeat-row'), rowHeader = el('div', 'repeat-row-header'); rowHeader.append(el('span', '', '正文章节'), button('移除章节', 'text-button', () => { row.remove(); state.editor.dirty = true; })); row.append(rowHeader); field(row, '章节标题', 'sectionHeading', data.heading, { maxLength: 100 }); field(row, '段落内容', 'sectionParagraphs', (data.paragraphs || []).join('\n'), { type: 'textarea', rows: 4 }); rows.append(row); }
    sections.forEach(addSection);
  }

  function renderViews(host, item) {
    const context=state.editor.aiDraft ? state.ai?.context || {} : {};
    const selected=resourceSelection(state.catalogs,{...item,resourceType:item.resourceType || context.resourceType},state.editor.aiDraft ? context.audience : state.resourceAudience,!!state.editor.aiDraft), grid=group(host,'资源分类');
    const roleChoices=[...resourceRoles];if(item.views?.some(view=>view.audience === 'pool'))roleChoices.push(['pool','项目池（已有展示）']);
    const audience=field(grid,'一级模块','resourceAudience',selected.audience,{choices:roleChoices,required:true});
    // Existing achievements retain their exact type; new service records cannot select the retired category.
    const editorCategories=role=>!role && state.editor.aiDraft ? [...new Map(resourceRoles.flatMap(([key])=>resourceCategories(state.catalogs,key)).map(category=>[category.value,category])).values()] : editableResourceCategories(state.catalogs,role,item);
    const category=field(grid,'二级分类','resourceType',selected.category,{choices:editorCategories(audience.value).map(c=>[c.value,c.label]),required:true});
    if(item.resourceType === 'achievement' && item.id)grid.append(el('p','field-hint full','这是原技术经理人的历史成果，保留原资料，可在全部资源中维护，不进入新的服务机构专栏。'));
    const note=el('p','field-hint full','交易意向沿用资源的供需类型（产出=供给、诉求=需求）；其余三级分类选填。不填写也可发布并出现在本专栏，具体筛选仅匹配已填写的属性。');grid.append(note);
    const attrs=el('div');attrs.id='attribute-fields';host.append(attrs);
    const additional=el('details','resource-extra');additional.append(el('summary','','其他展示设置'));const extraBody=el('div');additional.append(extraBody);host.append(additional);
    const featuredOnly=check(extraBody,'仅用于首页主推，不在角色专栏展示','featuredOnly',!!item.id && !(item.views || []).length);
    const extraRows=el('div');extraBody.append(el('p','field-hint','可将同一资源同时展示在支持该二级分类的其他栏目。属性共用，已有其他栏目属性会保留。'),extraRows);
    const cache=new Map();let previousType=category.value, previousAudience=audience.value;
    let primaryFromAssociation=!!item.views?.some(view=>view.audience === audience.value);
    let retained=resourcePayload(state.catalogs,item,previousType,{});
    let extras=(item.views || []).filter(view=>view.audience !== audience.value).map(view=>({...view}));
    const primaryOrder=field(extraBody,'当前专栏排序','primaryViewOrder',item.views?.find(view=>view.audience === audience.value)?.sortOrder || 0,{type:'number',min:0,step:1,required:true,hint:'数值越小越靠前，仅影响当前角色专栏。'});
    function capture() {
      for(const input of attrs.querySelectorAll('[data-resource-field]')) {
        const next=input.multiple ? [...input.selectedOptions].map(option=>option.value) : input.value;
        if(input.dataset.storage === 'attributes')retained[input.dataset.resourceField]=next;
        else { const rootInput=$('editor-form').elements.namedItem(input.dataset.resourceField);rootInput.value=Array.isArray(next)?next.join('，'):next; }
      }
      extras=[...extraRows.querySelectorAll('[name=extraAudience]:checked')].map(input=>({audience:input.value,category:previousType,sortOrder:Number(input.closest('.extra-view-row').querySelector('[name=extraViewOrder]').value)}));
      cache.set(previousType,{attributes:{...retained},primaryAudience:previousAudience,retainPrimary:primaryFromAssociation,views:[{audience:previousAudience,category:previousType,sortOrder:Number(primaryOrder.value)},...extras.map(view=>({...view}))]});
    }
    function renderAttributes() {
      attrs.replaceChildren();
      for(const name of ['industries','cooperationModes','kind']){const input=$('editor-form').elements.namedItem(name);input.closest('label').hidden=false;input.required=name==='kind';}
      const controls=group(attrs,'三级分类（选填）');
      for(const definition of resourceFilters(state.catalogs,audience.value,category.value)) {
        const rootInput=definition.storage === 'root' ? $('editor-form').elements.namedItem(definition.field) : null;
        const stored=rootInput ? (definition.multiple?splitList(rootInput.value):rootInput.value) : retained[definition.field] ?? (definition.multiple?[]:'');
        if(rootInput){rootInput.closest('label').hidden=true;rootInput.required=false;}
        const choices=definition.multiple || definition.field === 'kind' ? [...definition.options] : [{value:'',label:'暂不选择'},...definition.options];
        for(const v of Array.isArray(stored)?stored:[stored])if((audience.value === 'pool'||(state.catalogs[audience.value]?.legacyCategories || []).some(c=>c.value === category.value))&&v&&!choices.some(option=>option.value === v))choices.push({value:v,label:v+'（保留已有值）'});
        const input=field(controls,definition.label,'filter-'+definition.field,stored,{choices,multiple:definition.multiple,required:definition.field==='kind',hint:definition.multiple?'可多选，按住 Ctrl / Command 选择；点击下方按钮可清空。':undefined});
        input.dataset.resourceField=definition.field;input.dataset.storage=definition.storage;
        if(rootInput)input.addEventListener('change',()=>{rootInput.value=input.multiple?[...input.selectedOptions].map(option=>option.value).join('，'):input.value;});
        if(definition.multiple)input.parentElement.append(button('清空选择','text-button',()=>{for(const option of input.options)option.selected=false;state.editor.dirty=true;}));
      }
      if(!controls.children.length)controls.append(el('p','field-hint','当前分类没有额外筛选项。'));
    }
    function renderExtras() {
      extraRows.replaceChildren();
      for(const [key,label]of audiences)if(key !== audience.value && allowedViewCategories(state.catalogs,key,category.value).length) {
        const previous=extras.find(view=>view.audience === key),row=el('div','extra-view-row'),input=check(row,label,'extraAudience',!!previous);input.value=key;
        const order=field(row,label+'排序','extraViewOrder',previous?.sortOrder || 0,{type:'number',min:0,step:1,required:true});order.disabled=!input.checked;input.addEventListener('change',()=>{order.disabled=!input.checked;});extraRows.append(row);
      }
      extraRows.hidden=featuredOnly.checked;primaryOrder.disabled=featuredOnly.checked;
    }
    function changeSelection(roleChanged) {
      capture();
      if(roleChanged) {
        const options=editorCategories(audience.value);category.replaceChildren();
        if(state.editor.aiDraft){const blank=el('option','','请人工选择（未识别）');blank.value='';category.append(blank);}
        for(const c of options){const option=el('option','',c.label);option.value=c.value;category.append(option);}
        if(options.some(c=>c.value === previousType))category.value=previousType;
      }
      const typeChanged=previousType !== category.value, cached=cache.get(category.value);
      if(typeChanged)retained=cached?.attributes || resourcePayload(state.catalogs,item,category.value,{});
      const known=cached?.views || [];
      const selectedViews=resourceViewSelection(known,audience.value,category.value,typeChanged ? cached?.primaryAudience : previousAudience,typeChanged ? !!cached?.retainPrimary : primaryFromAssociation);
      primaryFromAssociation=cached?.primaryAudience === audience.value ? cached.retainPrimary : known.some(view=>view.audience === audience.value);
      primaryOrder.value=selectedViews.find(view=>view.audience === audience.value)?.sortOrder ?? 0;
      extras=selectedViews.filter(view=>view.audience !== audience.value && allowedViewCategories(state.catalogs,view.audience,category.value).length);
      previousType=category.value;previousAudience=audience.value;renderAttributes();renderExtras();state.editor.dirty=true;
    }
    audience.addEventListener('change',()=>changeSelection(true));category.addEventListener('change',()=>changeSelection(false));
    featuredOnly.addEventListener('change',()=>{extraRows.hidden=featuredOnly.checked;primaryOrder.disabled=featuredOnly.checked;});
    state.editor.readResourceClassification=()=>{
      capture();
      return {attributes:resourcePayload(state.catalogs,item,category.value,retained),views:featuredOnly.checked || !audience.value || !category.value?[]:[{audience:audience.value,category:category.value,sortOrder:Number(primaryOrder.value)},...extras]};
    };
    renderAttributes();renderExtras();
  }
  function readEditor(validate = true) {
    const { collection, item } = state.editor, result = { sortOrder: numberValue('sortOrder') }; if (item.id) result.version = item.version;
    if (state.editor.aiDraft) { result.sourceKind = value('sourceKind'); result.sourceNote = value('sourceNote'); }
    if (collection === 'featured') result.resourceId = value('resourceId');
    else {
      result.sections = [...($('section-rows')?.children || [])].map(row => ({ heading: row.querySelector('[name=sectionHeading]').value.trim(), paragraphs: sectionParagraphs(row.querySelector('[name=sectionParagraphs]').value) })).filter(section => section.heading || section.paragraphs.length);
      if (validate && (result.sections.some(section => section.paragraphs.length > 10 || section.paragraphs.some(paragraph => paragraph.length > 2000)) || result.sections.reduce((sum, section) => sum + section.heading.length + section.paragraphs.join('').length, 0) > 30000)) throw new Error('正文超出限制：每节最多 10 段，每段 2000 字，正文总计 30000 字。');
      const fields = { resources: ['title', 'summary', 'resourceType', 'kind', 'publisherRole', 'issuer', 'city', 'region', 'amountLabel', 'imageUrl', 'tone', 'status'], promos: ['title', 'eyebrow', 'description', 'imageUrl', 'tone', 'buttonText'], policies: ['title', 'summary', 'category', 'region', 'date', 'sourceKind', 'sourceName', 'sourceUrl'], institutions: ['name', 'summary', 'region'] }[collection]; fields.forEach(name => { result[name] = value(name); });
      for (const name of collection === 'resources' ? ['industries', 'tags', 'cooperationModes'] : collection === 'institutions' ? ['industries', 'serviceTags'] : []) { result[name] = splitList(value(name)); if (validate && (result[name].length > 12 || result[name].some(item => item.length > 30))) throw new Error('行业、标签和合作模式每组最多 12 项，每项最多 30 字。'); }
      if (collection === 'resources') {
        result.amountWan = numberValue('amountWan', null);
        Object.assign(result,state.editor.readResourceClassification());
        // Optional role filters for industries/cooperation share the existing top-level fields.
        for(const name of ['industries','cooperationModes'])result[name]=splitList(value(name));
        result.kind=value('kind');
      } else if (collection === 'promos') result.action = { type: value('actionType'), targetId: ['resource', 'policy'].includes(value('actionType')) ? value('targetId') : null };
      else if (collection === 'policies') { result.homeRecommended = $('editor-form').elements.namedItem('homeRecommended').checked; if (validate && result.sourceUrl && !/^https:\/\//i.test(result.sourceUrl)) throw new Error('来源链接必须使用 HTTPS。'); }
      else result.images = [...$('editor-form').querySelectorAll('[name=institutionImage]')].map(input => input.value.trim()).filter(Boolean);
      if (validate && [result.imageUrl, ...(result.images || [])].some(url => url && !safeImageUrl(url))) throw new Error('图片地址必须是有效 HTTPS 地址或已有机构图片路径。');
      if (validate && state.editor.aiDraft) {
        const required = collection==='resources'?['sourceKind','resourceType','kind','publisherRole','status','tone']:collection==='promos'?['sourceKind','tone','buttonText']:collection==='policies'?['sourceKind','category','date','sourceName']:['sourceKind'];
        const missing=required.filter(field=>!result[field]);if(collection==='promos'&&!result.action.type)missing.push('action');
        if(missing.length)throw new Error('请人工确认并填写：'+missing.map(aiFieldLabel).join('、')+'。');
      }
    }
    return result;
  }
  $('editor-form').addEventListener('input', () => { if (state.editor) state.editor.dirty = true; }); $('editor-form').addEventListener('change', () => { if (state.editor) state.editor.dirty = true; });
  $('editor-form').addEventListener('submit', async event => {
    event.preventDefault(); if (!state.editor || state.editor.busy) return; const editor = state.editor;
    try {
      if (state.uploads) throw new Error('图片仍在上传，请等待上传完成后保存。'); const data = readEditor();
      editor.busy = true; $('editor-fields').disabled = true; $('save-editor').disabled = true; message($('editor-error'), '');
      const saved = await api(editor.collection + (editor.item.id ? '/' + encodeURIComponent(editor.item.id) : ''), { method: editor.item.id ? 'PUT' : 'POST', body: JSON.stringify(data) });
      if (state.editor !== editor) return; editor.item = saved; editor.aiDraft = false; editor.aiResult = null; clearAi(); renderEditor(); editor.dirty = false;
      message($('editor-error'), saved.publicationStatus === 'PUBLISHED' ? '保存成功，已更新线上内容。' : '保存成功，可返回列表发布。', 'success'); loadList();
    } catch (error) { editorError(error); } finally { editor.busy = false; $('editor-fields').disabled = false; $('save-editor').disabled = false; }
  });
  function closeEditor() { if (state.editor?.busy) return; if(state.editor?.aiDraft && !state.editor.item.id){captureAiReview();$('editor-dialog').close();message($('page-message'),'AI 草稿已在本页保留，可通过“AI录入助手”继续审核或补充信息。','notice');return;} if (canDiscard()) { state.editor = null; state.editorSequence++; $('editor-dialog').close(); } }
  $('close-editor').addEventListener('click', closeEditor); $('editor-dialog').addEventListener('cancel', event => { event.preventDefault(); closeEditor(); });
  $('reload-editor').addEventListener('click', () => { const editor = state.editor; if (editor?.item.id) openEditor(editor.collection, editor.item.id); });
  window.addEventListener('beforeunload', event => { if (state.editor?.dirty || aiHasWork()) { event.preventDefault(); event.returnValue = ''; } });
  async function previewSaved(collection, id) { try { const item = await api(`${collection}/${encodeURIComponent(id)}`); await showPreview(collection, item); } catch (error) { message($('page-message'), error.message); } }
  $('preview-editor').addEventListener('click', async () => { try { await showPreview(state.editor.collection, { ...state.editor.item, ...readEditor(false) }); } catch (error) { editorError(error); } });
  $('close-preview').addEventListener('click', () => $('preview-dialog').close());
  $('ai-import').addEventListener('click',()=>openAi('file'));$('ai-assistant').addEventListener('click',()=>openAi('text'));
  $('ai-file-mode').addEventListener('click',()=>openAi('file'));$('ai-text-mode').addEventListener('click',()=>openAi('text'));
  $('ai-form').addEventListener('submit',submitAi);
  $('ai-text').addEventListener('input',()=>{if(!state.ai)return;state.ai.text=$('ai-text').value;cancelAiRequest();message($('ai-error'),'');});
  $('ai-file').addEventListener('change',()=>{if(!state.ai)return;state.ai.file=$('ai-file').files[0] || null;cancelAiRequest();message($('ai-error'),'');});
  $('ai-audience').addEventListener('change',()=>{if(!state.ai)return;state.ai.context.audience=$('ai-audience').value;cancelAiRequest();renderAiContext();});
  $('ai-category').addEventListener('change',()=>{if(!state.ai)return;state.ai.context.resourceType=$('ai-category').value;cancelAiRequest();});
  $('ai-cancel').addEventListener('click',()=>cancelAiRequest('识别已取消，材料和输入已保留。'));
  function closeAi(){if(state.ai)state.ai.text=$('ai-text').value;cancelAiRequest();$('ai-dialog').close();}
  $('close-ai').addEventListener('click',closeAi);$('ai-dialog').addEventListener('cancel',event=>{event.preventDefault();closeAi();});
  $('return-ai').addEventListener('click',()=>openAi('text'));
  $('ai-resume').addEventListener('click',()=>{if(state.editor?.aiDraft && !state.editor.item.id){cancelAiRequest();$('ai-dialog').close();$('editor-dialog').showModal();}});
  async function showPreview(collection, item) {
    if (collection === 'featured') { if (!item.resourceId) throw new Error('请先选择主推资源。'); item = await api('resources/' + encodeURIComponent(item.resourceId)); }
    const host = $('preview-content'); host.replaceChildren(); host.append(el('p', 'field-hint', '内容预览 · 正式页面会使用对应的小程序排版。'));
    const images = item.images || (item.imageUrl ? [item.imageUrl] : []);
    if (images.length) for (const image of images) { const src = safeImageUrl(typeof image === 'string' ? image : image.url); if (src) { const img = el('img', 'preview-cover'); img.src = src; img.alt = item.title || item.name || '内容图片'; img.loading = 'lazy'; img.addEventListener('error', () => img.replaceWith(el('div', 'preview-banner', '骊珠科创'))); host.append(img); } }
    else if (['featured', 'resources', 'promos'].includes(collection)) host.append(el('div', 'preview-banner', item.eyebrow || '骊珠科创'));
    if (item.eyebrow) host.append(el('p', 'eyebrow', item.eyebrow)); host.append(el('h1', '', item.title || item.name || '未填写标题'));
    const meta = [item.date, item.region || item.city, item.issuer].filter(Boolean).join(' · '); if (meta) host.append(el('p', 'item-meta', meta)); if (item.amountLabel || item.amountWan != null) host.append(el('p', '', item.amountLabel || `${item.amountWan} 万元`));
    const tags = [...(item.tags || []), ...(item.industries || []), ...(item.serviceTags || [])]; if (tags.length) { const row = el('div', 'preview-tags'); [...new Set(tags)].forEach(tag => row.append(el('span', 'preview-tag', tag))); host.append(row); }
    if (item.summary || item.description) host.append(el('p', '', item.summary || item.description));
    for (const data of item.sections || []) { const section = el('section'); if (data.heading) section.append(el('h3', '', data.heading)); (data.paragraphs || []).forEach(paragraph => section.append(el('p', '', paragraph))); host.append(section); }
    if (item.sourceName || item.sourceKind || item.sourceUrl) { const source = el('div', 'preview-source'); source.append(el('p', 'item-meta', '来源：' + (item.sourceName || { original: '原创文章', reprint: '转载文章', official: '官方来源', legacy_sample: '迁入示例资料' }[item.sourceKind] || '未填写'))); if (item.sourceUrl && safeImageUrl(item.sourceUrl)) { const link = el('a', '', '查看来源原文'); link.href = item.sourceUrl; link.target = '_blank'; link.rel = 'noopener noreferrer'; source.append(link); } host.append(source); }
    if (item.sourceNote) host.append(el('p', 'field-hint', '来源说明：' + item.sourceNote));
    for (const banner of host.querySelectorAll('.preview-banner')) if (tones.some(([tone]) => tone === item.tone)) banner.classList.add('tone-' + item.tone);
    if (item.action) host.append(el('p', 'field-hint', '点击动作：' + ({ article: '打开当前广告专题', resource: '打开关联资源', policy: '打开关联资讯', none: '不跳转' }[item.action.type] || '未设置')));
    if (!$('preview-dialog').open) $('preview-dialog').showModal();
  }
  refreshSession().then(session => { if (session.authenticated) loadList(); else showLogin(''); }).catch(error => showLogin(error.message));
}
