'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const exporter = path.join(__dirname, 'export-phase2.cjs');
assert.ok(fs.existsSync(exporter), 'phase2 source exporter must exist');
const { buildManifest, renderSchema, renderSeed, sqlString } = require(exporter);
const source = require('../migration/phase2-source.json');
const manifest = buildManifest(source);
assert.equal(manifest.canonicalResources.length, 93);
assert.equal(new Set(manifest.canonicalResources.map(x => x.id)).size, 93);
assert.equal(manifest.resourceViews.length, 119);
assert.equal(manifest.featured.length, 4);
assert.equal(manifest.promos.length, 5);
assert.equal(manifest.policies.length, 6);
assert.equal(manifest.institutions.length, 8);
assert.equal(Object.keys(manifest.aliases).length, 119);
assert.notEqual(manifest.aliases['scientist:scientist-data-other'], manifest.aliases['enterprise:enterprise-data-other']);
const resources = new Map(manifest.canonicalResources.map(x => [x.id,x]));
for (const p of source.phase1.projects) {
  assert.equal(manifest.aliases['pool:'+p.id],manifest.aliases['investor:'+p.id]);
  assert.deepEqual(resources.get(p.id).attributes.indications,source.investor.investorResources.find(x=>x.id===p.id).indications);
  assert.equal(resources.get(p.id).attributes.researchStage,'');
}
const reused = source.scientist.scientistResources.filter(x => ['talent','patent','service'].includes(x.category));
assert.equal(reused.length,18);
for (const p of reused) assert.equal(manifest.aliases['scientist:'+p.id],p.id.replace(/^scientist-/,'enterprise-'));
assert.equal(resources.get('p-ai-vision-supply').publicationStatus,'OFFLINE');
assert.equal(resources.get('p-ai-vision-supply').businessStatus,'withdrawn');
assert.equal(resources.get('p-energy-investor-demand').businessStatus,'closed');
assert.equal(resources.get('p-energy-investor-demand').publicationStatus,'PUBLISHED');
assert.equal(manifest.expectedStats.demands,13);
assert.equal(manifest.expectedStats.achievements,29);
assert.equal(manifest.expectedStats.experts,4);
assert.equal(manifest.expectedStats.managers,0);
assert.equal(manifest.expectedStats.institutions,0);
assert.equal(manifest.expectedStats.patents,12);
for (const [i,home] of source.home.tradeProjects.entries()) {
  const actual=resources.get('home-featured-'+home.id);
  assert.equal(actual.title,home.title);
  assert.deepEqual(actual.tags,home.tags);
  assert.equal(actual.city,home.city);
  assert.equal(actual.price,home.price);
  assert.equal(actual.kind,i===2?'supply':'demand');
  assert.equal(manifest.featured[i].resourceId,actual.id);
}
for (const audience of ['investor','enterprise','scientist','manager']) {
  assert.deepEqual(manifest.catalogSchema[audience].categories, source[audience][audience+'Categories']);
  assert.deepEqual(manifest.catalogSchema[audience].filtersByCategory,source[audience][audience+'FiltersByCategory']);
}
assert.equal(manifest.catalogSchema.investor.filtersByCategory.project[3].field,'indications');
assert.equal(manifest.catalogSchema.scientist.categories.find(x=>x.value==='finance').pending,true);
assert.deepEqual(manifest.catalogSchema.pool.filtersByCategory.project,source.home.poolFilters);
for (const [i,p] of manifest.policies.entries()) {
  assert.equal(p.sourceKind,'legacy_sample');
  assert.equal(p.sourceName,source.phase1.policies[i].sourceName);
  assert.equal(p.date,source.phase1.policies[i].date);
  assert.deepEqual(p.sections,source.phase1.policies[i].sections);
}
for (const [i,p] of manifest.promos.entries()) {
  assert.equal(p.description,source.phase1.promos[i].desc);
  assert.equal(p.imageUrl,'');
  assert.deepEqual(p.action,{type:'article',targetId:p.id});
}
assert.equal(manifest.institutions.flatMap(x=>x.images).length,24);
assert.equal(new Set(manifest.institutions.flatMap(x=>x.images)).size,3);
for (const item of [...manifest.canonicalResources,...manifest.promos,...manifest.policies,...manifest.institutions]) {
  assert.equal(item.sourceKind,'legacy_sample');
  assert.ok(item.sourceNote.length>0);
  assert.ok(!Object.hasOwn(item,'isDemo'));
}
const wrongAlias=structuredClone(source);
wrongAlias.scientist.scientistResources.find(x=>x.id==='scientist-patent-api').title='Changed independently';
assert.throws(()=>buildManifest(wrongAlias),/alias.*changed/i);
const unsupported=structuredClone(source);
unsupported.investor.investorResources[0].unexpectedAttribute='must not silently import';
assert.throws(()=>buildManifest(unsupported),/unsupported field/i);
assert.deepEqual(buildManifest(source),manifest,'same frozen input gives same manifest and batch hash');
const text="中文's \\ path\nnew line";
const literal=sqlString(text);
assert.match(literal,/^CONVERT\(0x[0-9a-f]+ USING utf8mb4\)$/);
assert.equal(Buffer.from(literal.match(/0x([0-9a-f]+)/)[1],'hex').toString('utf8'),text);
const schema=renderSchema();
assert.doesNotMatch(schema,/\b(?:DROP|TRUNCATE|ALTER)\b/i);
assert.doesNotMatch(schema,/\b(?:app_user|user_card)\b/);
assert.match(schema,/content_resource_view/);
const seed=renderSeed(manifest);
assert.doesNotMatch(seed,/ON DUPLICATE KEY UPDATE|REPLACE INTO|\bSOURCE\s/i);
assert.match(seed,/START TRANSACTION/);
assert.match(seed,/'COMMIT', 'ROLLBACK'/);
assert.match(seed,/@phase2_dry_run = 1/);
console.log('phase2 exporter checks passed: 93 resources, 119 views, explicit 18 aliases, source fields, stats, and SQL generation');

// Optional real-MySQL regression uses disposable, explicitly prefixed tables only.
// Set PHASE2_MYSQL_EXE and PHASE2_MYSQL_DEFAULTS; connection is pinned to the local test database.
if (process.argv.includes('--mysql')) {
  const cp=require('node:child_process');
  const executable=process.env.PHASE2_MYSQL_EXE;
  const defaults=process.env.PHASE2_MYSQL_DEFAULTS;
  assert.ok(executable&&defaults,'Set PHASE2_MYSQL_EXE and PHASE2_MYSQL_DEFAULTS without exposing credentials');
  const args=['--defaults-extra-file='+defaults,'--protocol=TCP','--host=127.0.0.1','--port=13316','--database=wx_yiyao_test','--batch','--raw','--skip-column-names','--default-character-set=utf8mb4'];
  const run=(sql,force=false)=>{
    const result=cp.spawnSync(executable,[...args,...force?['--force']:[]],{input:sql,encoding:'utf8',maxBuffer:5*1024*1024});
    if (!force) assert.equal(result.status,0,result.stderr||result.error?.message);
    return result;
  };
  const preflight=run('SELECT DATABASE(),@@port;');
  assert.equal(preflight.stdout.trim(),'wx_yiyao_test\t13316','refuse any database other than the dedicated local test target');
  const prefix='phase2check_'+process.pid+'_';
  const names=['home_featured','content_resource_view','home_promo','policy_article','service_institution','content_resource','content_audit','content_media','content_import_batch'];
  const isolated=sql=>sql.replace(new RegExp('\\b('+names.join('|')+')\\b','g'),name=>prefix+name).replace(/CONSTRAINT (fk_[a-z_]+)/g,(_,name)=>'CONSTRAINT '+prefix+name);
  const apply=sql=>isolated(sql).replace('SET @phase2_dry_run = 1;','SET @phase2_dry_run = 0;');
  const counts=()=>run('SELECT '+['content_resource','home_promo','content_resource_view','content_import_batch','content_audit'].map(name=>'(SELECT COUNT(*) FROM '+prefix+name+')').join(',')+';').stdout.trim();
  try {
    run(isolated(renderSchema()));
    const dry=run(isolated(renderSeed(manifest)));
    assert.match(dry.stdout,/DRY_RUN_READY/);
    assert.equal(counts(),'0\t0\t0\t0\t0');
    for (const table of ['home_promo','content_audit']) {
      const statement=table==='home_promo'?'(id,':'(actor,';
      const injected=apply(renderSeed(manifest)).replace('INSERT INTO '+prefix+table+' '+statement,'INSERT INTO '+prefix+table+' (phase2_injected_missing_column,');
      const failure=run(injected,true);
      assert.match(failure.stderr,/Unknown column 'phase2_injected_missing_column'/);
      assert.match(failure.stdout,/NOT_IMPORTED_CHECK_CONFLICTS_AND_ERRORS/);
      assert.equal(counts(),'0\t0\t0\t0\t0','failure in '+table+' must roll back content, batch and audit');
    }
    const first=run("SET SESSION sql_mode=CONCAT(@@sql_mode,',NO_BACKSLASH_ESCAPES');\n"+apply(renderSeed(manifest)));
    assert.match(first.stdout,/\bIMPORTED\b/);
    assert.equal(counts(),'93\t5\t119\t1\t1');
    run('UPDATE '+prefix+"home_promo SET payload=JSON_SET(payload,'$.title',"+sqlString('后台编辑保留')+"),version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id='promo-scene';");
    const again=run(apply(renderSeed(manifest)));
    assert.match(again.stdout,/SKIPPED_ALREADY_IMPORTED/);
    const adminTitle=()=>run('SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,\'$.title\')),version FROM '+prefix+"home_promo WHERE id='promo-scene';").stdout.trim();
    assert.equal(adminTitle(),'后台编辑保留\t2','same-manifest retry must preserve administrator edits');
    const changed=structuredClone(source);
    changed.home.tradeProjects[0].title+=' updated seed';
    const conflict=run(apply(renderSeed(buildManifest(changed))));
    assert.match(conflict.stdout,/NOT_IMPORTED_CHECK_CONFLICTS_AND_ERRORS/);
    assert.equal(counts(),'93\t5\t119\t1\t1','conflicting manifest creates no partial new batch');
    assert.equal(adminTitle(),'后台编辑保留\t2');
    console.log('MySQL checks passed: dry run, failure rollback including audit, first import, NO_BACKSLASH_ESCAPES, repeat preserving admin edits, conflicting batch blocked.');
  } finally {
    // These names are created only by this test; never drop application tables.
    assert.ok(names.every(name=>(prefix+name).startsWith('phase2check_')));
    run(names.map(name=>'DROP TABLE IF EXISTS '+prefix+name+';').join('\n'));
  }
}
