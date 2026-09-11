'use strict';
const assert = require('node:assert/strict');
const { splitList, sectionParagraphs, safeImageUrl, attributeDefinitions, policyCategories, allowedViewCategories, listQuery } = require('../../src/main/resources/static/admin/admin.js');
assert.deepEqual(splitList(' 医药，技术,医药\n 合作；服务 '), ['医药', '技术', '合作', '服务']);
assert.deepEqual(sectionParagraphs('第一段\n\n 第二段 \r\n第三段'), ['第一段', '第二段', '第三段']);
assert.equal(safeImageUrl('javascript:alert(1)'), '');
assert.equal(safeImageUrl('//evil.example/a.png'), '');
assert.equal(safeImageUrl('https://media.example/a.png'), 'https://media.example/a.png');
for (const name of ['service-lab', 'service-equipment', 'service-material']) assert.equal(safeImageUrl(`/assets/phase1/${name}.png`), `/assets/phase1/${name}.png`);
assert.equal(safeImageUrl('/assets/phase1/nonexistent.png'), '');
assert.equal(safeImageUrl('/admin/api/session'), '');
// The native policy select must offer exactly the backend's four permitted categories.
assert.deepEqual(policyCategories, ['科技创新', '成果转化', '知识产权', '产业扶持']);
const catalogs = {
  investor: { categories: [{ value: 'all' }, { value: 'project', label: '项目' }], filtersByCategory: { project: [
    { key: 'sort', options: [{ value: 'default' }] },
    { key: 'indication', field: 'indications', label: '适应症', options: [{ value: 'all' }, { value: '肿瘤疾病', label: '肿瘤疾病' }] },
    { key: 'industry', field: 'industries', options: [{ value: '生物医药' }] }
  ] } },
  scientist: { categories: [{ value: 'all' }, { value: 'patent', label: '专利' }, { value: 'finance', label: '投融资', pending: true }], filtersByCategory: { project: [
    { key: 'researchStage', label: '阶段', options: [{ value: 'all' }, { value: '1期临床', label: '1期临床' }] }
  ] } }
};
// Attribute fields are a union by resource type, so changing views cannot erase other source fields.
const definitions = attributeDefinitions(catalogs, 'project');
assert.equal(definitions.length, 2);
assert.equal(definitions[0].field, 'indications');
assert.equal(definitions[0].multiple, true);
assert.deepEqual(definitions[0].options, [{ value: '肿瘤疾病', label: '肿瘤疾病' }]);
assert.equal(definitions[1].field, 'researchStage');
assert.deepEqual(attributeDefinitions(catalogs, 'scene'), []);
assert.deepEqual(attributeDefinitions({ pool: { filtersByCategory: { project: [
  { key: 'industry', options: [{ value: '生物医药' }] },
  { key: 'cooperation', options: [{ value: '技术转让' }] }
] } } }, 'project'), []);
assert.deepEqual(allowedViewCategories(catalogs, 'investor', 'project'), [['project', '项目']]);
assert.deepEqual(allowedViewCategories(catalogs, 'investor', 'patent'), []);
assert.deepEqual(allowedViewCategories(catalogs, 'scientist', 'finance'), []);



// Every imported field and view remains representable using the live schema.
const schema = require('../../src/main/resources/catalog-schema.json');
const manifest = require('../../migration/phase2-manifest.json');
for (const resource of manifest.canonicalResources) {
  const fields = new Map(attributeDefinitions(schema, resource.resourceType).map(field => [field.field, field]));
  for (const [key, value] of Object.entries(resource.attributes)) {
    assert.ok(fields.has(key), `${resource.id}: missing editor field ${key}`);
    for (const selected of Array.isArray(value) ? value : [value]) if (selected) assert.ok(fields.get(key).options.some(option => option.value === selected), `${resource.id}: missing option ${key}`);
  }
}
for (const view of manifest.resourceViews) assert.ok(allowedViewCategories(schema, view.audience, view.category).some(([category]) => category === view.category), `${view.resourceId}: missing view category`);
assert.equal(typeof listQuery, 'function', 'list query builder handles the audit API contract');
assert.equal(listQuery('audit', { page: 2, pageSize: 20, keyword: '旧搜索词' }), 'page=2&pageSize=20');
const resourceQuery = new URLSearchParams(listQuery('resources', { page: 3, pageSize: 20, keyword: '技术 & 转化' }));
assert.equal(resourceQuery.get('keyword'), '技术 & 转化');
assert.equal(resourceQuery.get('page'), '3');
assert.equal(resourceQuery.get('pageSize'), '20');
console.log('admin form checks passed');

const { resourceCategories, resourceFilters, resourceSelection, resourcePayload } = require('../../src/main/resources/static/admin/admin.js');
assert.equal(typeof resourceCategories, 'function', 'resource editing starts with a role and its categories');
assert.deepEqual(resourceCategories(schema, 'investor').map(c => c.value), ['project', 'mah', 'scene']);
assert.deepEqual(resourceCategories(schema, 'enterprise').map(c => c.label), ['技术', '专利', 'MAH', '数据', '人才', '服务机构']);
assert.ok(!resourceCategories(schema, 'scientist').some(c => c.value === 'finance'));
assert.deepEqual(resourceCategories(schema, 'manager').map(c => c.value), ['cro','cdmo','solution','ip_service','financing_service']);
assert.deepEqual(resourceFilters(schema, 'investor', 'project').map(f => f.field), ['kind', 'investorProjectType', 'investorResearchStage', 'investorIndications']);
assert.ok(resourceFilters(schema, 'enterprise', 'mah').find(f => f.field === 'enterpriseApprovalStatus').options.some(o => o.value === '已获批'));
assert.ok(!resourceFilters(schema, 'investor', 'mah').find(f => f.field === 'investorApprovalStatus').options.some(o => o.value === '可委托生产'));
assert.ok(!resourceFilters(schema, 'scientist', 'talent').find(f => f.field === 'scientistTalentType').options.some(o => o.value === '首席科学家'));
assert.deepEqual(resourceFilters(schema, 'enterprise', 'technology').find(f => f.field === 'enterpriseTechnologyField').storage, 'attributes');
assert.deepEqual(resourceSelection(schema, { resourceType:'patent', views:[{audience:'scientist',category:'patent'}] }, 'investor'), { audience:'scientist', category:'patent' });
assert.deepEqual(resourceSelection(schema, { resourceType:'project', views:[{audience:'pool',category:'project'}] }, 'investor'), { audience:'pool', category:'project' });
const oldResource = {resourceType:'technology', attributes:{technologyField:'药物化学',technologyMaturity:'小试',cooperationMode:'技术转让'}};
const saved = resourcePayload(schema, oldResource, 'technology', { technologyMaturity:'', cooperationMode:'' });
assert.equal(saved.technologyField, '药物化学', 'editing one role preserves attributes only exposed by another role');
assert.equal(saved.technologyMaturity, '', 'clearing an optional field is intentional and is persisted');
assert.ok(!('technologyField' in resourcePayload(schema, oldResource, 'patent', {patentType:''})), 'switching category cannot leak old attributes');
const scopedQuery = new URLSearchParams(listQuery('resources', {page:2,pageSize:20,keyword:'ABC',resourceAudience:'investor',resourceCategory:'mah'}));
assert.equal(scopedQuery.get('audience'), 'investor'); assert.equal(scopedQuery.get('category'), 'mah');
assert.ok(!listQuery('policies', {page:1,pageSize:20,resourceAudience:'investor'}).includes('audience'));
console.log('role cascade checks passed');

const { resourceViewSelection } = require('../../src/main/resources/static/admin/admin.js');
assert.equal(typeof resourceViewSelection, 'function');
const existingViews=[{audience:'enterprise',category:'technology',sortOrder:7},{audience:'scientist',category:'technology',sortOrder:19}];
const asScientist=resourceViewSelection(existingViews,'scientist','technology','enterprise',true);
const backToEnterprise=resourceViewSelection(asScientist,'enterprise','technology','scientist',true);
assert.deepEqual(backToEnterprise,existingViews,'changing the editing role preserves all existing associations and their order');
assert.deepEqual(resourceViewSelection([{audience:'investor',category:'mah',sortOrder:0}],'enterprise','mah','investor',false),[{audience:'enterprise',category:'mah',sortOrder:0}],'new form role choice does not cross-publish accidentally');
assert.deepEqual(resourceViewSelection(existingViews,'enterprise','talent','enterprise',true),[{audience:'enterprise',category:'talent',sortOrder:0}]);
assert.deepEqual(resourceViewSelection(existingViews,'enterprise','technology','enterprise',true),existingViews,'cached category selection restores original order');
